package com.pilot.agent.agent

import com.pilot.agent.Dbg
import com.pilot.agent.action.DeviceActions
import com.pilot.agent.browser.BrowserSessionManager
import com.pilot.agent.extension.UserScript
import com.pilot.agent.extension.UserScriptStep
import com.pilot.agent.perception.ScreenReader
import com.pilot.agent.perception.ScreenSnapshot
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "Pilot"

/** 会自己跳的文本：时钟、倒计时、电量百分比。判界面变化时要忽略它们。 */
private val clockLike = Regex("""^(\d{1,2}[:：]\d{2}([:：]\d{2})?|\d{1,3}%|\d{1,2}/\d{1,2})$""")

private val agentOnlyActions = setOf(
    "browser_open", "browser_read", "browser_click", "browser_type",
    "browser_scroll", "browser_back", "browser_close",
    "mcp_list", "mcp_tools", "mcp_call", "script_list", "script_run",
)

/**
 * Agent 主循环：观察 → 决策 → 护栏 → 执行 → 记录 → 再观察。
 *
 * 三处刻意的设计：
 * 1. 每轮都重抓屏幕，不信任上一轮的状态（界面可能被用户或其他 App 改掉）。
 * 2. 死循环有硬检测（同一动作连续 3 次失败直接中止），不靠模型自觉。
 * 3. 高风险动作在**执行前**挂起等用户确认，确认结果写审计日志。
 */
class AgentLoop(
    private val reader: ScreenReader,
    private val actions: DeviceActions,
    private val router: PlannerRouter,
    private val skills: SkillStore,
    private val guard: Guard,
    private val settingsProvider: () -> AgentSettings,
    private val sink: MutableSharedFlow<AgentEvent>,
) {

    private val pendingConfirms = ConcurrentHashMap<Long, CompletableDeferred<Boolean>>()
    private val confirmSeq = AtomicLong(0)
    private var job: Job? = null

    /** 本次任务最后一个「把用户带到别的 App」的动作落在了哪个包，用于决定收尾要不要归位。 */
    @Volatile
    private var landedPkg: String? = null

    @Volatile
    private var hasUi = true

    /**
     * 系统直调最近一次的结果话术（「音量 80% 调到 93%（+13%）」这种）。
     * 直调成功要播报的是**实际结果**，不是「音量」两个字——
     * 之前用 action.summary 播，用户只听到"音量"，等于没告诉你调成多少。
     */
    @Volatile
    private var lastSystemMessage: String? = null

    /** Result text from browser/MCP so the next planner turn can use it. */
    @Volatile
    private var lastActionResult: String? = null

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * @param ui 是否有界面在等结果。后台触发（语音、广播）时传 false——
     *           没人能点确认按钮，遇到确认请求应该立刻放弃，而不是干等 3 分钟把队列堵死。
     */
    fun start(
        scope: CoroutineScope,
        goal: String,
        ui: Boolean = true,
        executionMode: AssistantMode? = null,
    ) {
        if (goal.isBlank()) return
        // 新任务顶掉旧任务：旧任务可能正卡在等用户确认，不顶掉的话后面所有请求都会被 isRunning 挡回去
        if (isRunning) {
            Dbg.i(TAG, "supersede running task")
            cancel()
        }
        hasUi = ui
        // LAZY 启动：否则 Main.immediate 下 launch 的 lambda 会在 job 赋值之前就跑起来，
        // 循环里的 job?.isActive 读到的是上一轮的旧 Job，直接判定"已取消"静默退出。
        val newJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                run(goal.trim(), executionMode)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Dbg.e(TAG, "run failed: ${t.message}", t)
                emit(AgentEvent.Failed(t.message ?: "执行异常"))
            }
        }
        job = newJob
        newJob.start()
    }

    fun cancel() {
        job?.cancel()
        job = null
        pendingConfirms.values.forEach { it.complete(false) }
        pendingConfirms.clear()
    }

    fun confirm(requestId: Long, approved: Boolean) {
        pendingConfirms.remove(requestId)?.complete(approved)
    }

    private suspend fun run(goal: String, executionMode: AssistantMode?) {
        val configured = settingsProvider()
        val settings = if (executionMode != null) configured.copy(mode = executionMode) else configured
        Dbg.i(
            TAG,
            "run start goalChars=${goal.length} mode=${settings.mode.storageValue} " +
                "maxTurns=${settings.maxTurns} reasoning=${settings.reasoningEffort} " +
                "cloudReady=${settings.cloudReady}",
        )

        // 记下任务开始前用户在看什么，结束时要切回去。
        // 这是「任务栈隔离」的轻量版：纯 App 层建不了可交互虚拟屏（需要 CAPTURE_VIDEO_OUTPUT），
        // 但可以让用户感觉不到 App 被顶掉——操作完自动归位。
        val self = com.pilot.agent.PilotApp.instance.packageName
        val originPkg = (reader.capture()?.pkg ?: reader.last()?.pkg)
            ?.takeIf { it.isNotEmpty() && it != self }
        landedPkg = null
        try {
            runInner(goal, settings)
        } finally {
            restoreOrigin(originPkg)
        }
    }

    /**
     * 把前台切回任务开始前的 App。
     *
     * ★ 只给「借道别的 App 办完事」的任务用。如果任务本身就是「打开X」，
     *   收尾时把用户拽回原 App 等于把刚打开的结果又关掉——所以只要当前前台
     *   正是本任务最后一步主动跳过去的 App，就不归位。
     */
    private suspend fun restoreOrigin(originPkg: String?) {
        if (originPkg.isNullOrEmpty()) return
        val now = com.pilot.agent.runtime.AgentRuntime.foreground.value
        if (now == originPkg) return
        if (landedPkg != null && now == landedPkg) {
            Dbg.i(TAG, "收尾不归位：$now 就是本任务的目标 App")
            return
        }
        Dbg.i(TAG, "任务结束，切回 $originPkg")
        runCatching { actions.openApp(originPkg) }
    }

    private suspend fun runInner(goal: String, settings: AgentSettings) {

        if (com.pilot.agent.action.CalendarTool.handles(goal)) {
            emit(AgentEvent.Finished(com.pilot.agent.action.CalendarTool.create(
                com.pilot.agent.PilotApp.instance, goal,
            )))
            return
        }

        if (com.pilot.agent.action.PersonalTodoStore.handles(goal)) {
            emit(AgentEvent.Finished(com.pilot.agent.action.PersonalTodoStore.handle(goal)))
            return
        }

        // 私人提醒为本地系统工具，不进入模型规划或上云。
        if (com.pilot.agent.action.ReminderManager.handles(goal)) {
            val result = com.pilot.agent.action.ReminderManager.handle(
                com.pilot.agent.PilotApp.instance, goal,
            )
            emit(AgentEvent.Finished(result))
            return
        }

        // User scripts are explicit local extensions. Their narrow DSL can only
        // call the browser and MCP layers, never arbitrary JavaScript or shell.
        if (settings.mode == AssistantMode.AGENT) {
            com.pilot.agent.runtime.AgentRuntime.userScripts.match(goal)?.let { script ->
                val completed = runUserScript(script)
                if (completed) {
                    emit(AgentEvent.Finished("已运行用户脚本「${script.name}」"))
                    return
                }
                emit(AgentEvent.Observe("用户脚本「${script.name}」未完成，已回到 Agent 重新规划"))
            }
        }

        // 意图分流：操作 → 进循环；查询 → 直接出答案；闲聊 → 不碰屏幕
        when (IntentGate.classify(goal)) {
            IntentGate.Kind.QUERY -> {
                val explicitAgentExtension =
                    com.pilot.agent.runtime.AgentRuntime.localTools.prefersAgentExtension(goal)
                if (!explicitAgentExtension) {
                    val answer = com.pilot.agent.query.QueryEngine.handle(goal)
                    if (answer != null) {
                        Dbg.i(TAG, "查询完成：${answer.take(60)}")
                        emit(AgentEvent.Finished(answer))
                        return
                    }
                }
                if (settings.mode != AssistantMode.AGENT) {
                    emit(AgentEvent.Finished("没查到「$goal」的结果"))
                    return
                }
                emit(AgentEvent.Observe("内置查询没有结果，Agent 将使用浏览器或 MCP 继续查找"))
            }

            IntentGate.Kind.CHAT -> {
                Dbg.i(TAG, "intent gate: 闲聊，跳过本次输入")
                emit(AgentEvent.Observe("「$goal」不是操作指令，没有动屏幕。"))
                emit(AgentEvent.Finished("这条听起来是交流，没有执行设备操作"))
                return
            }

            IntentGate.Kind.TASK -> Unit
        }

        val trace = mutableListOf<SkillStep>()
        // 输入步骤目前没有安全的参数槽位/密钥托管机制，任何含输入的任务都不沉淀为可回放技能。
        var skillEligible = true
        val history = mutableListOf<String>()

        // 1. 先看技能库，能回放就不惊动模型
        if (settings.allowSkillReplay) {
            replaySkill(goal)?.let {
                emit(AgentEvent.Finished(it))
                return
            }
        }

        var lastFingerprint = ""
        var repeats = 0
        // 连续「执行成功但界面没动」的次数。这是「每步都成功、任务原地踏步」的唯一可观测信号。
        var invalidStreak = 0
        // 上一步动作后settle完的屏幕。既做无效动作判定，也直接给下一轮决策用。
        var nextSnap: ScreenSnapshot? = null

        for (step in 1..settings.maxTurns) {
            Dbg.i(TAG, "loop step=$step enter")
            emit(AgentEvent.Progress(step, settings.maxTurns))

            var snap = nextSnap ?: reader.capture()
            nextSnap = null
            if (snap == null) {
                // 上一步点击触发了页面跳转，切换瞬间 rootInActiveWindow 会是 null。
                // 这里必须重试，否则「点了之后读不到屏幕」会把好好的任务判死。
                delay(500)
                snap = reader.capture()
            }
            if (snap == null) {
                throw IllegalStateException("读不到屏幕。请确认 Pilot 的无障碍服务已开启，且当前没有锁屏。")
            }
            Dbg.i(TAG, "step=$step captured pkg=${snap.pkg} nodes=${snap.nodes.size} truncated=${snap.truncated}")

            val request = AgentRequest(
                goal = goal, history = history, snapshot = snap, stepIndex = step - 1
            )
            // 按成本从低到高逐级尝试：规则 → 端侧小模型 → 云端。
            // 某个规划器抛异常（没配 key、模型没装）就跳到下一个，不打断整个任务。
            var used: Planner? = null
            var decision: AgentDecision? = null
            for (candidate in router.chain(goal, step - 1)) {
                val d = try {
                    candidate.decide(request)
                } catch (t: Throwable) {
                    Dbg.w(TAG, "planner ${candidate.id} unavailable: ${t.message}")
                    continue
                }
                if (d.action.action != "unable") {
                    decision = d
                    used = candidate
                    break
                }
                decision = d
            }
            if (decision == null) {
                decision = AgentDecision(
                    thought = "没有可用的规划器",
                    action = AgentAction(
                        action = "ask",
                        summary = "本地规则覆盖不了这条指令，端侧模型和云端模型都不可用。请检查模型文件或 API Key。",
                    ),
                )
            }
            val plannerId = used?.id ?: "none"

            emit(AgentEvent.Thinking("[$plannerId] ${decision.thought}"))
            val action = decision.action
            Dbg.i(
                TAG,
                "step=$step/${settings.maxTurns} planner=$plannerId " +
                    "actionId=${action.action} action=${action.describe()} thought=${decision.thought}",
            )

            when (action.action) {
                "done" -> {
                    if (skillEligible && trace.isNotEmpty()) skills.recordSuccess(goal, snap.pkg, trace)
                    Dbg.i(TAG, "done: ${action.summary} steps=$step skills=${trace.size}")
                    emit(AgentEvent.Finished(action.summary ?: "已完成"))
                    return
                }

                // 直达动作（Intent / 组件）成功过一次就算完成。
                // 不判的话会重复发明同一个 Intent：实测「打开电池」连发 3 次直到死循环检测中止。
                "shortcut", "component" -> {
                    if (history.any { it.startsWith("直达") && it.contains("ok") }) {
                        Dbg.i(TAG, "直达已执行过，判定完成：${action.summary}")
                        if (skillEligible && trace.isNotEmpty()) skills.recordSuccess(goal, snap.pkg, trace)
                        emit(AgentEvent.Finished("已打开 ${action.summary ?: ""}"))
                        return
                    }
                }

                "ask" -> {
                    val ok = askUser(action.summary ?: "需要你确认后继续")
                    if (!ok) {
                        emit(AgentEvent.Failed("用户取消"))
                        return
                    }
                    continue
                }

                "unable" -> {
                    emit(AgentEvent.Failed("这个请求当前无法处理：${action.summary}"))
                    return
                }
            }

            if (settings.mode != AssistantMode.AGENT && action.action in agentOnlyActions) {
                emit(AgentEvent.Finished("浏览器会话、MCP 和用户脚本需要切换到 Agent 模式"))
                return
            }

            // ★ 未知动作名：执行层只会默默返回 false，不拦的话会一路"失败"到 maxTurns。
            //   实测「音量调到百分之五十」被端侧模型接成 set_volume，烧了 23 步。
            if (action.action !in KNOWN_ACTIONS) {
                invalidStreak++
                history += "${action.action}（未知动作，不可执行）"
                Dbg.w(TAG, "step=$step 未知动作=${action.action} streak=$invalidStreak planner=$plannerId")
                emit(AgentEvent.Observe("这一步的动作执行不了，换个做法"))
                if (invalidStreak >= 2) {
                    throw IllegalStateException("规划器连续给出无法执行的动作（${action.action}），已中止。")
                }
                delay(300)
                continue
            }

            // 2. 护栏在动作落地之前
            val risk = guard.riskOf(action, snap)
            // 高风险确认是策略约束，不受普通设置开关影响。
            if (risk != Risk.NONE) {
                val go = askUser("${action.describe()} —— 可能涉及${guard.riskLabel(risk)}，确认执行？", risk)
                guard.audit(goal, action, risk, go)
                if (!go) {
                    emit(AgentEvent.Failed("已按你的意思取消该操作"))
                    return
                }
            }

            emit(AgentEvent.Acting(action.describe()))
            val ok = execute(action)
            Dbg.i(TAG, "step=$step result=${if (ok) "ok" else "fail"} nodes=${snap.nodes.size} pkg=${snap.pkg}")

            // 系统直调是一步到位的原子操作：成功即完成，失败即终止。
            // 失败还重试没有意义（缺权限就是缺权限），只会把同一句话重复问 5 遍。
            if (action.action == "systemop") {
                if (ok) {
                    emit(AgentEvent.Finished(lastSystemMessage ?: action.summary ?: "完成了"))
                } else {
                    emit(AgentEvent.Failed("${action.summary ?: "系统操作"}没能完成，看上面的原因"))
                }
                return
            }
            if (action.action == "input") skillEligible = false
            toSkillStep(action, snap)?.let { if (ok) trace += it }

            // 3. 等界面稳定再抓下一帧。这一帧有两个用途：给下一轮决策，以及判断这一步到底有没有生效。
            val settle = when (action.action) {
                "wait" -> action.ms ?: 500
                "open" -> 1200
                "input", "key" -> 500
                else -> 350
            }
            delay(settle.toLong())

            // ★ 动作后校验：无障碍返回 true 只代表「事件发出去了」，不代表界面动了。
            //   点了不可点区域、输入没落进输入框、Intent 被系统后台拦下——三种都会一路「成功」、
            //   一路重规划，直到 maxTurns 才报超时，用户看到的是「它一直在瞎点」。
            //   所以这里必须回抓一帧比对：界面没变 = 无效动作，写进历史让规划器换策略。
            val after = reader.capture()
            if (after != null) nextSnap = after
            val changed = after != null && screenKey(after) != screenKey(snap)
            val countsAsProgress = !uiAffecting(action) || changed

            if (countsAsProgress) {
                if (invalidStreak > 0) Dbg.i(TAG, "step=$step 界面有变化，无效动作计数清零")
                invalidStreak = 0
                appendHistory(history, action, ok)
            } else {
                invalidStreak++
                appendHistory(
                    history,
                    action,
                    ok,
                    suffix = "界面没有变化（无效动作），换一种方式",
                )
                Dbg.w(
                    TAG,
                    "step=$step 无效动作 streak=$invalidStreak action=${action.action} pkg=${snap.pkg} nodes=${snap.nodes.size}",
                )
                emit(AgentEvent.Observe("这一步没有引起界面变化，换个方式再试"))
                // 返回键在顶层界面按下去本来就不会有变化——这不是"无效动作"，
                // 是"已经没得退了"。再重试两遍只会让用户听三遍同样的提示。
                if (action.action == "key" && action.target == "back") {
                    emit(AgentEvent.Finished("已经是最外层界面，没有可返回的地方"))
                    return
                }
                if (invalidStreak >= 3) {
                    throw IllegalStateException(
                        "连续 3 步动作没有引起界面变化，已中止，避免空转。请确认当前界面是否还能操作（可能被弹窗、锁屏或权限提示挡住）。",
                    )
                }
            }

            // 跳转类动作记下落点，收尾时据此决定要不要把用户切回原 App
            if (action.action == "open" || action.action == "shortcut" || action.action == "component") {
                val p = after?.pkg.orEmpty()
                if (p.isNotEmpty() && p != snap.pkg) landedPkg = p
            }

            // 死循环判定要带上界面状态：同一动作、同样的结果、界面也没变，才算卡住
            val fingerprint = "${action.action}:$ok:$changed:${after?.pkg}:${after?.nodes?.size}"
            if (fingerprint == lastFingerprint) repeats++ else {
                repeats = 0
                lastFingerprint = fingerprint
            }

            if (repeats >= 3) throw IllegalStateException("同一动作连续 3 次没有进展，已中止，避免把界面点乱。")
        }

        emit(AgentEvent.Failed("超过 ${settings.maxTurns} 步仍未完成，已停在当前界面。"))
    }

    private fun appendHistory(
        history: MutableList<String>,
        action: AgentAction,
        ok: Boolean,
        suffix: String? = null,
    ) {
        val builder = StringBuilder(action.describe())
            .append(" -> ")
            .append(if (ok) "ok" else "fail")
        if (!suffix.isNullOrBlank()) builder.append("：").append(suffix)
        lastActionResult?.takeIf { it.isNotBlank() }?.let {
            builder.append("\n工具结果：").append(it.take(16_000))
        }
        history += builder.toString()
    }

    private fun mcpMessage(label: String, result: com.pilot.agent.extension.McpExecution): String {
        val body = result.output.ifBlank { "(无输出)" }.take(10_000)
        return buildString {
            append(label)
            append(if (result.ok) "：\n" else "失败（exit=${result.exitCode}）：\n")
            append(body)
            append("\n耗时 ").append(result.durationMs).append("ms")
        }
    }

    private suspend fun runUserScript(script: UserScript): Boolean {
        if (script.hasSideEffects) {
            val approved = askUser(
                "运行用户脚本「${script.name}」？其中点击网页、填写内容或调用 MCP 可能改变外部状态。",
                Risk.EXTERNAL,
            )
            if (!approved) {
                emit(AgentEvent.Observe("已取消用户脚本「${script.name}」"))
                return false
            }
        }
        emit(AgentEvent.Observe("运行用户脚本「${script.name}」，共 ${script.steps.size} 步"))
        for ((index, step) in script.steps.withIndex()) {
            val action = scriptStepToAction(step)
            if (action == null) {
                lastActionResult = "用户脚本第 ${index + 1} 步不受支持"
                emit(AgentEvent.Observe(lastActionResult.orEmpty()))
                return false
            }
            emit(AgentEvent.Acting("脚本 ${index + 1}/${script.steps.size}：${action.describe()}"))
            val ok = execute(action)
            if (!ok) {
                emit(
                    AgentEvent.Observe(
                        "用户脚本第 ${index + 1} 步失败" +
                            lastActionResult?.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty(),
                    ),
                )
                return false
            }
            delay(180)
        }
        return true
    }

    private fun scriptStepToAction(step: UserScriptStep): AgentAction? = when (step.action) {
        "browser_open" -> AgentAction(
            action = "browser_open",
            url = step.url.ifBlank { step.target },
        )
        "browser_read" -> AgentAction(action = "browser_read", maxChars = step.max)
        "browser_click" -> AgentAction(
            action = "browser_click",
            target = step.target.ifBlank { step.text },
        )
        "browser_type" -> AgentAction(
            action = "browser_type",
            target = step.target,
            text = step.text,
        )
        "browser_scroll" -> AgentAction(
            action = "browser_scroll",
            target = step.target.ifBlank { "forward" },
        )
        "browser_back" -> AgentAction(action = "browser_back")
        "mcp_list" -> AgentAction(action = "mcp_list")
        "mcp_tools" -> AgentAction(action = "mcp_tools", server = step.server)
        "mcp_call" -> AgentAction(
            action = "mcp_call",
            server = step.server,
            tool = step.tool,
            arguments = step.arguments.ifBlank { "{}" },
        )
        else -> null
    }

    private suspend fun execute(a: AgentAction): Boolean {
        lastActionResult = null
        val x = a.x
        val y = a.y
        val x2 = a.x2
        val y2 = a.y2
        val id = a.id
        val text = a.text
        val target = a.target
        return try {
            when (a.action) {
                "tap" -> x != null && y != null && actions.tap(x, y)
                "tap_node" -> id != null && actions.tapNode(id)
                "long_press" -> id != null && actions.longPressNode(id)
                "input" -> id != null && text != null && actions.input(id, text)
                "swipe" -> x != null && y != null && x2 != null && y2 != null &&
                    actions.swipe(x, y, x2, y2, (a.ms ?: 300).toLong())
                "scroll" -> id != null && actions.scroll(id, target != "backward")
                "open" -> target != null && actions.openApp(target)
                "shortcut" -> target != null && actions.openShortcut(target)
                "component" -> {
                    if (target != null && target.contains('/')) {
                        actions.launchComponent(target.substringBefore('/'), target.substringAfter('/'))
                    } else {
                        false
                    }
                }
                "key" -> target != null && actions.key(target)
                "systemop" -> runSystemOp(a)
                "app_func" -> {
                    val dest = target ?: return false
                    val pkg = dest.substringBefore('/')
                    val functionId = dest.substringAfter('/', "")
                    if (pkg.isBlank() || functionId.isBlank()) return false
                    val result = com.pilot.agent.appfunction.AppFunctionBridge.invoke(
                        com.pilot.agent.PilotApp.instance, pkg, functionId, text.orEmpty(),
                    ) ?: return false
                    emit(AgentEvent.Observe(result))
                    true
                }
                "browser_open", "browser_read", "browser_click", "browser_type",
                "browser_scroll", "browser_back", "browser_close" -> {
                    val result = BrowserSessionManager.execute(
                        context = com.pilot.agent.PilotApp.instance,
                        action = a.action,
                        target = a.url ?: a.target,
                        text = a.text,
                        maxChars = a.maxChars,
                    )
                    lastActionResult = buildString {
                        append(result.message)
                        if (result.title.isNotBlank()) append("\n标题：").append(result.title)
                        if (result.url.isNotBlank()) append("\n网址：").append(result.url)
                        if (result.text.isNotBlank()) append("\n正文：").append(result.text.take(16_000))
                    }
                    emit(AgentEvent.Observe(lastActionResult.orEmpty()))
                    result.ok
                }
                "mcp_list" -> {
                    val result = com.pilot.agent.runtime.AgentRuntime.mcp.listServers()
                    lastActionResult = mcpMessage("MCP 服务器", result)
                    emit(AgentEvent.Observe(lastActionResult.orEmpty()))
                    result.ok
                }
                "mcp_tools" -> {
                    val server = a.server ?: target ?: run {
                        lastActionResult = "缺少 MCP server"
                        emit(AgentEvent.Observe(lastActionResult.orEmpty()))
                        return false
                    }
                    val result = com.pilot.agent.runtime.AgentRuntime.mcp.listTools(server)
                    lastActionResult = mcpMessage("MCP「$server」工具", result)
                    emit(AgentEvent.Observe(lastActionResult.orEmpty()))
                    result.ok
                }
                "mcp_call" -> {
                    val server = a.server ?: run {
                        lastActionResult = "缺少 MCP server"
                        emit(AgentEvent.Observe(lastActionResult.orEmpty()))
                        return false
                    }
                    val tool = a.tool ?: run {
                        lastActionResult = "缺少 MCP tool"
                        emit(AgentEvent.Observe(lastActionResult.orEmpty()))
                        return false
                    }
                    val result = com.pilot.agent.runtime.AgentRuntime.mcp.call(
                        server,
                        tool,
                        a.arguments ?: "{}",
                    )
                    lastActionResult = mcpMessage("MCP $server/$tool", result)
                    emit(AgentEvent.Observe(lastActionResult.orEmpty()))
                    result.ok
                }
                "script_list" -> {
                    val scripts = com.pilot.agent.runtime.AgentRuntime.userScripts.all()
                    lastActionResult = if (scripts.isEmpty()) {
                        "还没有安装用户脚本。"
                    } else {
                        scripts.joinToString("\n") { script ->
                            "${script.id} · ${script.name} · ${script.triggers.joinToString(" / ")}"
                        }
                    }
                    emit(AgentEvent.Observe(lastActionResult.orEmpty()))
                    true
                }
                "script_run" -> {
                    val query = target.orEmpty().ifBlank { text.orEmpty() }
                    val script = com.pilot.agent.runtime.AgentRuntime.userScripts.all()
                        .firstOrNull {
                            it.id.equals(query, ignoreCase = true) ||
                                it.name.equals(query, ignoreCase = true)
                        }
                    if (script == null) {
                        lastActionResult = "没有找到用户脚本「$query」"
                        emit(AgentEvent.Observe(lastActionResult.orEmpty()))
                        false
                    } else {
                        runUserScript(script)
                    }
                }
                else -> false
            }
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 系统能力直调。
     *
     * 返回值只有成功/失败，但**过程必须说出来**——用户要知道"音量从 30% 调到了 50%"，
     * 而不是只听到一句"完成了"。所以这里把 SystemOps 的返回文本用 Observe 事件发出去。
     */
    private suspend fun runSystemOp(a: AgentAction): Boolean {
        lastSystemMessage = null
        val op = a.target ?: return false
        val arg = a.text.orEmpty()
        return when (op) {
            "volume" -> {
                val r = actions.systemOps.adjustVolume(arg.toIntOrNull() ?: 0)
                if (r == null) false else { emit(AgentEvent.Observe(r)); true }
            }

            "volume_set" -> {
                val r = actions.systemOps.setVolume(arg.toIntOrNull() ?: return false)
                if (r == null) false else { emit(AgentEvent.Observe(r)); true }
            }

            "brightness_set" -> {
                val r = actions.systemOps.setBrightness(arg.toIntOrNull() ?: return false)
                when (r) {
                    null -> false
                    com.pilot.agent.action.SystemOps.NEED_WRITE_SETTINGS -> {
                        emit(AgentEvent.Observe("调亮度需要「修改系统设置」权限，正在打开授权页"))
                        actions.systemOps.openWriteSettingsPage()
                        false
                    }

                    else -> {
                        emit(AgentEvent.Observe(r))
                        true
                    }
                }
            }

            "brightness" -> {
                val r = actions.systemOps.adjustBrightness(arg.toIntOrNull() ?: 0)
                when (r) {
                    null -> false
                    com.pilot.agent.action.SystemOps.NEED_WRITE_SETTINGS -> {
                        emit(AgentEvent.Observe("调亮度需要「修改系统设置」权限，正在打开授权页"))
                        actions.systemOps.openWriteSettingsPage()
                        false
                    }

                    else -> {
                        emit(AgentEvent.Observe(r))
                        true
                    }
                }
            }

            "call" -> {
                val ctx = com.pilot.agent.PilotApp.instance
                // 是号码就直接拨，是名字就查通讯录
                val number = if (arg.any { it.isDigit() } && arg.length >= 3) arg
                else com.pilot.agent.action.Contacts.findNumber(ctx, arg)
                if (number == null) {
                    emit(AgentEvent.Observe("通讯录里找不到「$arg」"))
                    false
                } else {
                    // ★ 坑 #18：HarmonyOS 后台拦截 ACTION_CALL 是静默失败
                    //   （startActivity 不抛异常）。判据=起前后前台包有没有动过：
                    //   没动 = 被拦，报失败；动了 = 进了通话/拨号界面（含华为变体）。
                    val before = actions.foregroundPackage()
                    val r = actions.systemOps.call(number)
                    if (r == null) false
                    else {
                        kotlinx.coroutines.delay(800)
                        val fg = actions.foregroundPackage()
                        if (fg != null && fg != before) {
                            emit(AgentEvent.Observe(r)); true
                        } else {
                            emit(AgentEvent.Observe("拨号被系统后台拦截（前台未切换），未拨出"))
                            false
                        }
                    }
                }
            }

            "music" -> {
                val ctx = com.pilot.agent.PilotApp.instance
                val s = AgentSettings.load()
                val query = arg.replace("网易云音乐", "").replace("网易云", "").trim()

                suspend fun startOnline(
                    songs: List<com.pilot.agent.music.NeteaseApi.Song>,
                    label: String,
                ): Boolean {
                    if (songs.isEmpty()) {
                        emit(AgentEvent.Observe("${label}为空，检查登录、网络或音乐服务"))
                        return false
                    }
                    com.pilot.agent.music.MusicHistoryStore.remember(songs)
                    val tracks = songs.map { song ->
                        com.pilot.agent.music.MusicPlayer.Track(
                            title = song.name,
                            artist = song.artist,
                            resolver = {
                                com.pilot.agent.music.NeteaseApi.playUrl(
                                    s.musicBaseUrl, song.id, s.neteaseCookie,
                                )?.let { com.pilot.agent.music.MusicPlayer.Source.Remote(it) }
                            },
                            lyricProvider = {
                                com.pilot.agent.music.NeteaseApi.lyrics(
                                    s.musicBaseUrl, song.id, s.neteaseCookie,
                                )
                            },
                        )
                    }
                    val started = withContext(Dispatchers.Main.immediate) {
                        com.pilot.agent.music.MusicPlayer.get(ctx).playQueue(tracks)
                    }
                    if (started) {
                        emit(AgentEvent.Observe("正在播放${label}：${tracks.first().display}，共 ${tracks.size} 首"))
                    }
                    return started
                }

                when {
                    query.contains("本地") -> {
                        if (!com.pilot.agent.music.LocalMusicLibrary.hasPermission(ctx)) {
                            emit(AgentEvent.Observe("播放本地音乐需要读取音频权限，请在系统应用中授权 Pilot"))
                            return false
                        }
                        val songs = withContext(Dispatchers.IO) {
                            com.pilot.agent.music.LocalMusicLibrary.list(ctx, 30)
                        }
                        if (songs.isEmpty()) {
                            emit(AgentEvent.Observe("手机音乐库里没有找到本地歌曲"))
                            return false
                        }
                        val tracks = songs.map { local ->
                            com.pilot.agent.music.MusicPlayer.Track(
                                title = local.title,
                                artist = local.artist,
                                source = com.pilot.agent.music.MusicPlayer.Source.Local(local.uri),
                            )
                        }
                        val started = withContext(Dispatchers.Main.immediate) {
                            com.pilot.agent.music.MusicPlayer.get(ctx).playQueue(tracks)
                        }
                        if (started) emit(AgentEvent.Observe("正在播放本地音乐：${tracks.first().display}，共 ${tracks.size} 首"))
                        return started
                    }

                    query.contains("最近") -> {
                        val remote = withContext(Dispatchers.IO) {
                            com.pilot.agent.music.NeteaseApi.recentSongs(
                                s.musicBaseUrl, s.neteaseCookie, 20,
                            )
                        }
                        val songs = (remote + com.pilot.agent.music.MusicHistoryStore.recent(20))
                            .distinctBy { it.id }
                            .take(20)
                        return startOnline(songs, "最近播放")
                    }

                    query.contains("每日推荐") || query == "推荐" -> {
                        val songs = withContext(Dispatchers.IO) {
                            com.pilot.agent.music.NeteaseApi.everydayRecommendations(
                                s.musicBaseUrl, s.neteaseCookie, 20,
                            )
                        }
                        return startOnline(songs, "每日推荐")
                    }

                    query.contains("我喜欢") || query.contains("收藏") -> {
                        val songs = withContext(Dispatchers.IO) {
                            com.pilot.agent.music.NeteaseApi.likedSongs(
                                s.musicBaseUrl, s.neteaseCookie, 20,
                            )
                        }
                        return startOnline(songs, "我喜欢的音乐")
                    }

                    query.contains("新歌") -> {
                        val songs = withContext(Dispatchers.IO) {
                            com.pilot.agent.music.NeteaseApi.chartSongs(
                                s.musicBaseUrl, 3779629L, s.neteaseCookie, 20,
                            )
                        }
                        return startOnline(songs, "网易云新歌榜")
                    }

                    query.contains("飙升") -> {
                        val songs = withContext(Dispatchers.IO) {
                            com.pilot.agent.music.NeteaseApi.chartSongs(
                                s.musicBaseUrl, 19723756L, s.neteaseCookie, 20,
                            )
                        }
                        return startOnline(songs, "网易云飙升榜")
                    }

                    query.isBlank() || query.contains("热门") || query.contains("热歌") -> {
                        val songs = withContext(Dispatchers.IO) {
                            com.pilot.agent.music.NeteaseApi.chartSongs(
                                s.musicBaseUrl, 3778678L, s.neteaseCookie, 20,
                            )
                        }
                        return startOnline(songs, "网易云热歌榜")
                    }
                }

                // 普通关键词：本地优先，再从网易云搜索。
                val local = withContext(Dispatchers.IO) {
                    com.pilot.agent.music.LocalMusicLibrary.find(ctx, arg)
                }
                if (local != null) {
                    val track = com.pilot.agent.music.MusicPlayer.Track(
                        title = local.title,
                        artist = local.artist,
                        source = com.pilot.agent.music.MusicPlayer.Source.Local(local.uri),
                    )
                    val started = withContext(Dispatchers.Main.immediate) {
                        com.pilot.agent.music.MusicPlayer.get(ctx).playQueue(listOf(track))
                    }
                    if (started) emit(AgentEvent.Observe("正在播放本地音乐 ${local.display}"))
                    return started
                }

                val songs = withContext(Dispatchers.IO) {
                    com.pilot.agent.music.NeteaseApi.search(
                        s.musicBaseUrl, arg, 5, s.neteaseCookie,
                    )
                }
                if (songs.isEmpty()) emit(AgentEvent.Observe("没搜到「$arg」，检查音乐服务或网络"))
                startOnline(songs, "搜索「$arg」")
            }

            "terminal" -> {
                // 沙箱终端（minis 移植的 proot + Alpine rootfs）。
                // 首条命令含 rootfs 解包（3.7MB，约 30 秒），后续是热壳。
                val ctx = com.pilot.agent.PilotApp.instance
                com.pilot.agent.sandbox.SandboxTerminal.init(ctx)
                val r = withContext(Dispatchers.IO) {
                    com.pilot.agent.sandbox.SandboxTerminal.run(ctx, arg)
                }
                // 非零退出但 stdout 有内容也算可用（实测 `df -h` 在华为上
                // 个别挂载点 Permission denied → exit=1，但数据是好的）。
                val usable = r.ok || r.output.isNotBlank()
                emit(
                    AgentEvent.Observe(
                        "终端 ${arg.take(40)} → ${r.brief(500)}" +
                            if (!r.ok) "\n(exit=${r.exitCode})" else "",
                    ),
                )
                if (!usable) Dbg.w(TAG, "终端无输出 exit=${r.exitCode}: $arg")
                usable
            }

            "navigate" -> {
                // 导航：高德 SDK 在 :navigation 进程，主进程只发指令 + 收播报。
                val ctx = com.pilot.agent.PilotApp.instance
                val jobId = com.pilot.agent.navigation.NavigationClient.startNavi(ctx, arg)
                if (jobId == 0L) {
                    emit(AgentEvent.Observe("导航服务没连上"))
                    false
                } else {
                    // ★ 导航播报要**抢占**普通播报：「前方 200 米右转」不能等
                    //   前面那句说完。onAnnounce 里用 speakPriority。
                    com.pilot.agent.navigation.NavigationClient.onAnnounce = { text, priority ->
                        com.pilot.agent.runtime.AgentRuntime.speaker.speakPriority(
                            text, priority >= 1,
                        )
                    }
                    emit(AgentEvent.Observe("开始导航去 $arg"))
                    true
                }
            }

            "navigate_stop" -> {
                val ok = com.pilot.agent.navigation.NavigationClient.stopNavi(0)
                emit(AgentEvent.Observe(if (ok) "已退出导航" else "当前没有在导航"))
                true
            }

            "navigate_status" -> {
                // 导航进程回的是 JSON，这里取关键字段拼成人话
                val json = com.pilot.agent.navigation.NavigationClient.stateJson()
                val st = Regex("\"state\":\"([^\"]*)\"").find(json)?.groupValues?.get(1) ?: "IDLE"
                val dest = Regex("\"destination\":\"([^\"]*)\"").find(json)?.groupValues?.get(1).orEmpty()
                val remain = Regex("\"remainMeters\":(\\d+)").find(json)?.groupValues?.get(1)
                val text = when (st) {
                    "IDLE" -> "当前没有在导航"
                    "NAVIGATING" -> if (remain != null) "距$dest 还有 ${remain}米" else "正在导航去$dest"
                    "ROUTING" -> "正在规划去$dest 的路线"
                    "ARRIVED" -> "已经到达$dest"
                    else -> "导航状态：$st"
                }
                emit(AgentEvent.Observe(text))
                true
            }

            "settings_switch_on", "settings_switch_off" -> {
                // 拨开关：不是打开设置页就完事，要开关真的到位（内部回读校验过）。
                val msg = actions.setSettingsItem(arg, op == "settings_switch_on")
                if (msg != null) {
                    val text = if (msg.startsWith("已经")) "「$arg」$msg" else "$msg「$arg」"
                    emit(AgentEvent.Observe(text))
                    true
                } else {
                    // 有些项本来就没有独立开关（是一堆列表/子菜单），退化成"打开这一项"，
                    // 但要如实说明，不能播成"已开启"。
                    val opened = actions.searchSettings(arg)
                    emit(
                        AgentEvent.Observe(
                            if (opened) "「$arg」没有独立开关，已打开它的设置页" else "没能打开「$arg」",
                        ),
                    )
                    opened
                }
            }

            "settings_search" -> {
                // 设置项搜索：静态表和 App 索引都没命中时才走这里。
                // 比逐级点菜单稳——设置改版、菜单挪位置都不影响。
                val ok = actions.searchSettings(arg)
                emit(AgentEvent.Observe(if (ok) "已在设置里搜索并打开「$arg」" else "设置里没搜到「$arg」"))
                ok
            }

            else -> false
        }
    }

    private suspend fun askUser(question: String, risk: Risk = Risk.NONE): Boolean {
        if (!hasUi) {
            Dbg.w(TAG, "no ui attached, auto-deny: $question")
            emit(AgentEvent.Observe("无人可确认，已放弃：$question"))
            return false
        }
        val id = confirmSeq.incrementAndGet()
        val deferred = CompletableDeferred<Boolean>()
        pendingConfirms[id] = deferred
        emit(AgentEvent.NeedConfirm(id, question, risk))
        val result = withTimeoutOrNull(60_000L) { deferred.await() } ?: false
        pendingConfirms.remove(id)
        return result
    }

    private suspend fun replaySkill(goal: String): String? {
        val pkg = reader.last()?.pkg.orEmpty()
        val skill = skills.match(goal, pkg)
        if (skill == null) {
            Dbg.i(TAG, "no skill matched pkg=$pkg")
            return null
        }
        Dbg.i(TAG, "skill hit goal=${skill.goal} steps=${skill.steps.size} successes=${skill.successes}")
        emit(AgentEvent.Observe("命中技能「${skill.goal}」（成功 ${skill.successes} 次），开始回放 ${skill.steps.size} 步"))
        for (step in skill.steps) {
            val snap = reader.capture() ?: return null
            val ok = replayStep(step, snap)
            Dbg.i(TAG, "replay step=${step.action} label=${step.label} ok=$ok")
            if (!ok) {
                emit(AgentEvent.Observe("回放中断，转交模型重新规划"))
                return null
            }
            delay(400)
        }
        skills.recordHit(skill.id)
        Dbg.i(TAG, "replay done goal=${skill.goal} steps=${skill.steps.size}")
        return "${skill.goal.trim().trimEnd('。', '！', '!', '；', ';')}已完成"
    }

    private suspend fun replayStep(step: SkillStep, snap: ScreenSnapshot): Boolean = when (step.action) {
        "tap_text" -> {
            val node = snap.nodes.firstOrNull {
                it.clickable && (it.text == step.label || it.viewId.endsWith(step.label, ignoreCase = true))
            } ?: snap.nodes.firstOrNull { it.clickable && it.text.contains(step.label, ignoreCase = true) }
            node != null && actions.tapNode(node.id)
        }

        "input" -> {
            val node = snap.nodes.firstOrNull { it.editable } ?: return false
            actions.input(node.id, step.value)
        }

        "key" -> actions.key(step.label)
        "open" -> actions.openApp(step.label)
        "shortcut" -> actions.openShortcut(step.label)
        "component" -> {
            val t = step.label
            t.contains('/') && actions.launchComponent(t.substringBefore('/'), t.substringAfter('/'))
        }
        "scroll" -> {
            val node = snap.nodes.firstOrNull { it.scrollable } ?: return false
            actions.scroll(node.id, step.label != "backward")
        }

        "wait" -> {
            delay(step.value.toLongOrNull() ?: 500L)
            true
        }

        else -> false
    }

    /** 这一步动作是否「本来就应该让界面变化」。不改变界面的动作（wait 等）不参与无效动作判定。 */
    private fun uiAffecting(a: AgentAction): Boolean = when (a.action) {
        "tap", "tap_node", "long_press", "input", "swipe", "scroll", "key", "open", "shortcut", "component" -> true
        else -> false
    }

    /**
     * 界面指纹：只回答「这两帧是不是同一个界面」。
     *
     * ★ 必须剔掉时间类文本（状态栏 11:59、倒计时 00:12、电量 87%）。时钟每秒跳一下，
     *   每一帧都会被判成「有变化」，无效动作就永远检测不出来——这是这个功能最容易写废的地方。
     *   宁可漏判（少报一次无效动作），不可误判（把正常推进当成无效，逼规划器换策略）。
     */
    private fun screenKey(s: ScreenSnapshot): String {
        val sb = StringBuilder(s.pkg).append('|').append(s.nodes.size)
        for (n in s.nodes) {
            val t = n.text.take(24)
            if (t.isNotEmpty() && !clockLike.matches(t)) {
                sb.append('|').append(n.cls).append(':').append(t)
            }
            if (n.checked) sb.append('v')
            if (!n.enabled) sb.append('d')
        }
        return sb.toString()
    }

    private fun toSkillStep(action: AgentAction, snap: ScreenSnapshot): SkillStep? = when (action.action) {
        "tap_node", "long_press" -> {
            val node = action.id?.let { id -> snap.nodes.firstOrNull { it.id == id } }
            if (node == null || node.text.isBlank()) null
            else SkillStep(action = "tap_text", label = node.text.take(40))
        }

        "input" -> {
            val node = action.id?.let { id -> snap.nodes.firstOrNull { it.id == id } }
            SkillStep(action = "input", label = node?.text.orEmpty(), value = action.text.orEmpty())
        }

        "key" -> action.target?.let { SkillStep(action = "key", label = it) }
        "open" -> action.target?.let { SkillStep(action = "open", label = it) }
        "shortcut", "component" -> action.target?.let { SkillStep(action = action.action, label = it) }
        "scroll" -> SkillStep(action = "scroll", label = action.target ?: "forward")
        else -> null
    }

    private suspend fun emit(e: AgentEvent) {
        // 直调的结果话术是通过 Observe 发出去的（「音量 80% 调到 93%」）。
        // 在这里顺手记一份，完成时就能播"实际结果"而不是干巴巴的"音量"。
        if (e is AgentEvent.Observe) lastSystemMessage = e.text
        sink.emit(e)
    }
}
