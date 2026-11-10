package com.pilot.agent.agent

import com.pilot.agent.Dbg
import com.pilot.agent.action.DeviceActions
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

    @Volatile
    private var hasUi = true

    val isRunning: Boolean get() = job?.isActive == true

    /**
     * @param ui 是否有界面在等结果。后台触发（语音、广播）时传 false——
     *           没人能点确认按钮，遇到确认请求应该立刻放弃，而不是干等 3 分钟把队列堵死。
     */
    fun start(scope: CoroutineScope, goal: String, ui: Boolean = true) {
        if (goal.isBlank()) return
        // 新任务顶掉旧任务：旧任务可能正卡在等用户确认，不顶掉的话后面所有请求都会被 isRunning 挡回去
        if (isRunning) {
            Dbg.i(TAG, "supersede running task, new goal=$goal")
            cancel()
        }
        hasUi = ui
        // LAZY 启动：否则 Main.immediate 下 launch 的 lambda 会在 job 赋值之前就跑起来，
        // 循环里的 job?.isActive 读到的是上一轮的旧 Job，直接判定"已取消"静默退出。
        val newJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                run(goal.trim())
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

    private suspend fun run(goal: String) {
        val settings = settingsProvider()
        Dbg.i(TAG, "run start goal=$goal maxTurns=${settings.maxTurns} cloudReady=${settings.cloudReady}")

        // 记下任务开始前用户在看什么，结束时要切回去。
        // 这是「任务栈隔离」的轻量版：纯 App 层建不了可交互虚拟屏（需要 CAPTURE_VIDEO_OUTPUT），
        // 但可以让用户感觉不到 App 被顶掉——操作完自动归位。
        val self = com.pilot.agent.PilotApp.instance.packageName
        val originPkg = (reader.capture()?.pkg ?: reader.last()?.pkg)
            ?.takeIf { it.isNotEmpty() && it != self }
        try {
            runInner(goal, settings)
        } finally {
            restoreOrigin(originPkg)
        }
    }

    /** 把前台切回任务开始前的 App */
    private suspend fun restoreOrigin(originPkg: String?) {
        if (originPkg.isNullOrEmpty()) return
        val now = com.pilot.agent.runtime.AgentRuntime.foreground.value
        if (now == originPkg) return
        Dbg.i(TAG, "任务结束，切回 $originPkg")
        runCatching { actions.openApp(originPkg) }
    }

    private suspend fun runInner(goal: String, settings: AgentSettings) {

        // 意图分流：操作 → 进循环；查询 → 直接出答案；闲聊 → 不碰屏幕
        when (IntentGate.classify(goal)) {
            IntentGate.Kind.QUERY -> {
                val answer = com.pilot.agent.query.QueryEngine.handle(goal)
                if (answer != null) {
                    Dbg.i(TAG, "查询完成：${answer.take(60)}")
                    emit(AgentEvent.Finished(answer))
                } else {
                    emit(AgentEvent.Finished("没查到「$goal」的结果"))
                }
                return
            }

            IntentGate.Kind.CHAT -> {
                Dbg.i(TAG, "intent gate: 闲聊，跳过 goal=$goal")
                emit(AgentEvent.Observe("「$goal」不是操作指令，没有动屏幕。"))
                emit(AgentEvent.Finished("这条听起来是交流，没有执行设备操作"))
                return
            }

            IntentGate.Kind.TASK -> Unit
        }

        val trace = mutableListOf<SkillStep>()
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

        for (step in 1..settings.maxTurns) {
            Dbg.i(TAG, "loop step=$step enter")
            emit(AgentEvent.Progress(step, settings.maxTurns))

            var snap = reader.capture()
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
            Dbg.i(TAG, "step=$step/${settings.maxTurns} planner=$plannerId action=${action.describe()} thought=${decision.thought}")

            when (action.action) {
                "done" -> {
                    if (trace.isNotEmpty()) skills.recordSuccess(goal, snap.pkg, trace)
                    Dbg.i(TAG, "done: ${action.summary} steps=$step skills=${trace.size}")
                    emit(AgentEvent.Finished(action.summary ?: "已完成"))
                    return
                }

                // 直达动作（Intent / 组件）成功过一次就算完成。
                // 不判的话会重复发明同一个 Intent：实测「打开电池」连发 3 次直到死循环检测中止。
                "shortcut", "component" -> {
                    if (history.any { it.startsWith("直达") && it.contains("ok") }) {
                        Dbg.i(TAG, "直达已执行过，判定完成：${action.summary}")
                        if (trace.isNotEmpty()) skills.recordSuccess(goal, snap.pkg, trace)
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

            // 2. 护栏在动作落地之前
            val risk = guard.riskOf(action, snap)
            if (risk != Risk.NONE && settings.confirmMoneyActions) {
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
                    emit(AgentEvent.Finished(action.summary?.let { "$it 已完成" } ?: "已完成"))
                } else {
                    emit(AgentEvent.Failed("${action.summary ?: "系统操作"}没能完成，看上面的原因"))
                }
                return
            }
            toSkillStep(action, snap)?.let { if (ok) trace += it }

            // 死循环判定要带上屏幕状态：同一动作但界面变了，那是正常推进，不是卡住
            val fingerprint = "${action.action}:$ok:${snap.pkg}:${snap.nodes.size}"
            if (fingerprint == lastFingerprint) repeats++ else {
                repeats = 0
                lastFingerprint = fingerprint
            }
            history += "${action.describe()} -> ${if (ok) "ok" else "fail"}"

            if (repeats >= 3) throw IllegalStateException("同一动作连续失败 3 次，已中止，避免把界面点乱。")

            // 3. 等界面稳定再抓下一帧
            val settle = when (action.action) {
                "wait" -> action.ms ?: 500
                "open" -> 1200
                "input", "key" -> 500
                else -> 350
            }
            delay(settle.toLong())
        }

        emit(AgentEvent.Failed("超过 ${settings.maxTurns} 步仍未完成，已停在当前界面。"))
    }

    private suspend fun execute(a: AgentAction): Boolean {
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
                "app_func" -> false // 见 AppFunctionBridge，待 ROM 权限到位后接通
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
        val op = a.target ?: return false
        val arg = a.text.orEmpty()
        return when (op) {
            "volume" -> {
                val r = actions.systemOps.adjustVolume(arg.toIntOrNull() ?: 0)
                if (r == null) false else { emit(AgentEvent.Observe(r)); true }
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
                // 一句话放歌：搜 → 逐个候选取播放地址 → 直接拉流，全程不开任何 App
                val s = AgentSettings.load()
                val songs = com.pilot.agent.music.NeteaseApi.search(s.musicBaseUrl, arg, 5, s.neteaseCookie)
                if (songs.isEmpty()) {
                    emit(AgentEvent.Observe("没搜到「$arg」，检查音乐服务或网络"))
                    return false
                }

                // ★ 必须遍历候选。搜出来的第一首经常是翻唱/已下架，url 为 null，
                //   只试第一首会误判成「没登录」或「版权限制」。
                var picked: com.pilot.agent.music.NeteaseApi.Song? = null
                var playUrl: String? = null
                for (song in songs) {
                    val u = com.pilot.agent.music.NeteaseApi.playUrl(
                        s.musicBaseUrl, song.id, s.neteaseCookie
                    )
                    if (u != null) {
                        picked = song
                        playUrl = u
                        break
                    }
                    Dbg.i(TAG, "候选不可播，换下一个：${song.display}")
                }

                if (picked == null || playUrl == null) {
                    emit(AgentEvent.Observe("试了 ${songs.size} 首都没有可播地址，可能都没版权"))
                    return false
                }
                val ctx = com.pilot.agent.PilotApp.instance
                val started = com.pilot.agent.music.MusicPlayer.get(ctx).play(playUrl, picked.display)
                if (started) emit(AgentEvent.Observe("正在播放 ${picked.display}"))
                started
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
            Dbg.i(TAG, "no skill matched goal=$goal pkg=$pkg")
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
        return "按已学技能完成：${skill.goal}"
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
        sink.emit(e)
    }
}
