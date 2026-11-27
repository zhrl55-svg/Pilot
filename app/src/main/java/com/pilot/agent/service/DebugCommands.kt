package com.pilot.agent.service

import android.content.Context
import android.content.Intent
import com.pilot.agent.BuildConfig
import com.pilot.agent.Dbg
import com.pilot.agent.LogRedactor
import com.pilot.agent.agent.AgentSettings
import com.pilot.agent.assistant.AssistantFastPath
import com.pilot.agent.runtime.AgentRuntime
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * adb 调试指令（审核报告 code.md S1 / M3）。
 *
 * 以前这些指令（沙箱跑 shell、改网易云 Cookie、删记忆、取消提醒、改快路径归属……）
 * 直接挂在导出的 RunReceiver / TriggerActivity 上，release 包里也编译进去了，debug 包还去掉了权限，
 * 任何 App 发个显式 Intent 就能用。现在：
 *
 * 1. **只在 debug 包**生效（`BuildConfig.DEBUG`，release 里整段是死代码，入口别名也被禁用）；
 * 2. **只从调试别名进入**：`com.pilot.agent.ui.DebugTriggerActivity`（activity-alias → TriggerActivity，
 *    要求 `android.permission.DUMP`；普通 App 拿不到该权限，adb shell 天然持有）。
 *    正式入口 RunReceiver / TriggerActivity 收到调试 extra 会直接忽略；
 * 3. **必须显式打开开关**：应用私有 `no_backup/pilot_debug_commands` 文件存在才执行。
 *    该目录不在文件工具可访问范围（filesDir）内，只能经 `run-as`（仅 debuggable 包可用）创建：
 *
 * ```
 * adb shell run-as com.pilot.agent sh -c 'mkdir -p no_backup && touch no_backup/pilot_debug_commands'
 * adb shell am start -n com.pilot.agent/.ui.DebugTriggerActivity --es sandbox_test "uname -a"
 * adb shell am start -n com.pilot.agent/.ui.DebugTriggerActivity --es goal "打开设置"
 * # 用完关掉：
 * adb shell run-as com.pilot.agent rm no_backup/pilot_debug_commands
 * ```
 *
 * 日志一律经 [LogRedactor] 脱敏；需要看原文时（仅本机排查）先发 `--ez log_verbose true`。
 */
internal object DebugCommands {
    private const val TAG = "PilotDebug"

    /** 显式开关文件名（位于 Context.noBackupFilesDir）。 */
    const val SWITCH_FILE = "pilot_debug_commands"

    /** debug manifest 里启用的调试别名（main manifest 中默认 enabled=false）。 */
    const val ALIAS_CLASS = "com.pilot.agent.ui.DebugTriggerActivity"

    private val STRING_KEYS = listOf(
        "fastpath_owner", "fastpath_compare", "reminder_cancel", "memory_delete_id",
        "memory_forget", "fastpath_fill_test", "set_netease_cookie", "tts_test", "tts_engine",
        "asr_test", "llm_test", "sandbox_test", "sandbox_timeout_ms", "voice_test", "simulate_nav",
    )
    private val BOOL_KEYS = listOf("kws_test", "import_models", "log_verbose")

    /** 是否携带了任何调试指令（正式入口据此拒绝并记日志）。 */
    fun hasDebugExtra(intent: Intent): Boolean =
        STRING_KEYS.any { intent.hasExtra(it) } || BOOL_KEYS.any { intent.hasExtra(it) }

    fun switchOn(ctx: Context): Boolean =
        BuildConfig.DEBUG && runCatching { File(ctx.noBackupFilesDir, SWITCH_FILE).isFile }.getOrDefault(false)

    /**
     * 处理一条调试指令。只应由调试别名调用。
     * @return true = 已作为调试请求处理（包括因开关未开而拒绝）
     */
    fun handle(context: Context, intent: Intent): Boolean {
        if (!BuildConfig.DEBUG) return false
        val app = context.applicationContext
        if (!switchOn(app)) {
            Dbg.w(
                TAG,
                "调试入口未开启。需要时执行：adb shell run-as ${app.packageName} sh -c " +
                    "'mkdir -p no_backup && touch no_backup/$SWITCH_FILE'",
            )
            return true
        }

        if (intent.hasExtra("log_verbose")) {
            LogRedactor.verboseContent = intent.getBooleanExtra("log_verbose", false)
            Dbg.i(TAG, "日志原文输出：${if (LogRedactor.verboseContent) "开（仅本进程，重启恢复关闭）" else "关"}")
            return true
        }

        // P4 调试：设置某个能力的执行权归属（LEGACY / SHADOW / ROUTE）
        //   --es fastpath_owner 'device=SHADOW'
        val ownerSpec = intent.getStringExtra("fastpath_owner")
        if (!ownerSpec.isNullOrBlank()) {
            val capability = ownerSpec.substringBefore('=').trim()
            val owner = com.pilot.agent.assistant.routing.DefaultOwners
                .parse(ownerSpec.substringAfter('=', ""))
            runCatching {
                com.pilot.agent.assistant.routing.RouteStore.get(app).setOwner(capability, owner)
                Dbg.i(TAG, "快路径归属：$capability=$owner")
            }.onFailure {
                Dbg.w(TAG, "设置快路径归属失败：${LogRedactor.error(it)}")
            }
            return true
        }

        // 快路径 / 遗留链路两侧对照：同一句话两边各判一次，**都不执行**。
        //   --es fastpath_compare '记住我喜欢喝咖啡'
        val compareText = intent.getStringExtra("fastpath_compare")
        if (!compareText.isNullOrBlank()) {
            Thread {
                val fast = runCatching {
                    val router = com.pilot.agent.assistant.routing.AssistantRouter()
                    val routes = com.pilot.agent.assistant.routing.RouteStore.get(app).snapshot()
                    when (val d = router.route(compareText, routes)) {
                        is com.pilot.agent.assistant.routing.RouteDecision.Local ->
                            "fast=${d.capability}/${d.action} ${LogRedactor.text("args", d.args.toString())} " +
                                "missing=${d.missingSlots} needsLlm=${d.needsModelExtraction} " +
                                "confirm=${d.needsConfirmation}"
                        is com.pilot.agent.assistant.routing.RouteDecision.Cloud ->
                            "fast=cloud(${d.reason})"
                    }
                }.getOrElse { "fast=error(${it.javaClass.simpleName})" }
                val legacy = runCatching {
                    AgentRuntime.localTools.match(compareText)
                }.getOrNull()
                val legacyText = legacy?.let {
                    "legacy=${it.name} group=${it.group} ${LogRedactor.text("summary", it.summary)}"
                } ?: "legacy=none"
                Dbg.i(TAG, "对照 ${LogRedactor.text("text", compareText)} $fast | $legacyText")
            }.apply { name = "fastpath-compare"; isDaemon = true }.start()
            return true
        }

        // 提醒维护：按标题关键词取消提醒（DELETE 组在无界面场景一律拒绝确认，清理测试提醒只能走这里）。
        //   --es reminder_cancel '快路径验收样本'
        val reminderKeyword = intent.getStringExtra("reminder_cancel")
        if (!reminderKeyword.isNullOrBlank()) {
            Thread {
                val message = runCatching {
                    com.pilot.agent.action.ReminderManager.handle(app, "取消提醒 ${reminderKeyword.trim()}")
                }.getOrNull()
                Dbg.i(
                    TAG,
                    "提醒维护：取消 ${LogRedactor.text("keyword", reminderKeyword)} → " +
                        (message?.let { LogRedactor.text("result", it) } ?: "失败"),
                )
            }.apply { name = "reminder-cancel"; isDaemon = true }.start()
            return true
        }

        // 记忆维护（按 id 精确删除）：清理测试数据用这个。
        //   --es memory_delete_id 7
        // ★ 不要用 memory_forget 做清理 —— 它是模糊匹配，实测会误删无关记忆。
        val deleteId = intent.getStringExtra("memory_delete_id")
        if (!deleteId.isNullOrBlank()) {
            Thread {
                val id = deleteId.trim().toLongOrNull()
                val ok = id != null && AgentRuntime.localTools.deleteMemoryFromUi(id)
                Dbg.i(TAG, "记忆维护：删除 #${id ?: "无效id"} → ${if (ok) "已删除" else "未找到"}")
            }.apply { name = "memory-delete-id"; isDaemon = true }.start()
            return true
        }

        // 记忆维护：按关键词删除（模糊，谨慎使用）。
        //   --es memory_forget '影子对照样本'
        val forgetKeyword = intent.getStringExtra("memory_forget")
        if (!forgetKeyword.isNullOrBlank()) {
            Thread {
                val removed = runCatching {
                    AgentRuntime.localTools.memory.forget(forgetKeyword)
                }.getOrNull()
                Dbg.i(
                    TAG,
                    "记忆维护：删除 ${LogRedactor.text("keyword", forgetKeyword)} → ${removed?.size ?: 0} 条" +
                        (removed?.takeIf { it.isNotEmpty() }?.joinToString(prefix = " ids=") { "#${it.id}" } ?: ""),
                )
            }.apply { name = "memory-forget"; isDaemon = true }.start()
            return true
        }

        // 端侧补值自检：走完整 plan → complete 流程，但**不执行**工具。
        //   --es fastpath_fill_test '保存到 /sdcard/Download/a.txt 内容 hello'
        val fillTest = intent.getStringExtra("fastpath_fill_test")
        if (!fillTest.isNullOrBlank()) {
            Thread {
                val plan = AssistantFastPath.plan(app, fillTest, "selftest")
                val completed = plan?.let {
                    runCatching { runBlocking { AssistantFastPath.complete(app, it) } }.getOrNull()
                }
                Dbg.i(
                    TAG,
                    "补值自检：plan=${plan?.capability}/${plan?.action} " +
                        "missing=${plan?.decision?.missingSlots} " +
                        "completed=${completed?.invocation?.name} " +
                        LogRedactor.text("args", completed?.args?.toString()),
                )
            }.apply { name = "fastpath-fill-test"; isDaemon = true }.start()
            return true
        }

        // 从电脑侧写入网易云 cookie（扫码/验证码在电脑上完成）
        //   --es set_netease_cookie "MUSIC_U=xxx; __csrf=yyy"
        val injectedCookie = intent.getStringExtra("set_netease_cookie")
        if (!injectedCookie.isNullOrBlank()) {
            val problem = AgentSettings.saveChecked(AgentSettings.load().copy(neteaseCookie = injectedCookie))
            if (problem == null) {
                Dbg.i(TAG, "已写入网易云 cookie（${injectedCookie.length} 字符）")
            } else {
                Dbg.w(TAG, "网易云 cookie 未写入：$problem")
            }
            return true
        }

        // KWS 自检：验证唤醒词能否转成模型认识的音素行
        //   --ez kws_test true
        if (intent.getBooleanExtra("kws_test", false)) {
            Thread {
                val words = AgentSettings.load().wakeWordList()
                Dbg.i(TAG, "KWS 自检: 唤醒词=$words")
                words.forEach { w ->
                    Dbg.i(TAG, "KWS 拼音行「$w」→ ${com.pilot.agent.voice.KwsKeywords.line(w)}")
                }
                val d = com.pilot.agent.voice.WakeWordDetector(app)
                val ok = d.load(words)
                Dbg.i(TAG, "KWS 自检: load=$ok labels=${d.labels} err=${d.lastError ?: "无"}")
                val gate = com.pilot.agent.voice.VoiceprintGate(app)
                val vp = gate.ensureReady()
                Dbg.i(TAG, "声纹自检: ready=$vp 模板=${gate.hasTemplate()} err=${gate.lastError ?: "无"}")
            }.apply { name = "kws-test"; isDaemon = true }.start()
            return true
        }

        // 播报自检：直接念一段，验证 TTS 引擎
        //   --es tts_test "你好，我是 Pilot" [--es tts_engine minimax]
        val ttsTest = intent.getStringExtra("tts_test")
        if (!ttsTest.isNullOrBlank()) {
            val override = intent.getStringExtra("tts_engine")
            Dbg.i(
                TAG,
                "TTS 自检: 引擎=${override ?: AgentSettings.load().ttsEngine} ${LogRedactor.text("text", ttsTest)}",
            )
            if (override == AgentSettings.TTS_MINIMAX) {
                // 临时绕过设置，直接验云端合成，不动用户配置
                Thread {
                    val mm = com.pilot.agent.voice.MiniMaxTts(app)
                    mm.speak(ttsTest, onDone = { Dbg.i(TAG, "TTS 自检: MiniMax 播完") }) { err ->
                        Dbg.e(TAG, "TTS 自检: MiniMax 失败 $err")
                    }
                }.apply { name = "tts-test"; isDaemon = true }.start()
            } else {
                AgentRuntime.speaker.speak(ttsTest)
            }
            return true
        }

        // 手动触发模型导入（脚本用，避免为了触发 onCreate 去 force-stop 把无障碍踢掉）
        //   --ez import_models true
        if (intent.getBooleanExtra("import_models", false)) {
            Thread {
                Dbg.i(TAG, "模型导入: 开始")
                val n = com.pilot.agent.voice.ModelStore.ensure(app)
                Dbg.i(TAG, "模型导入: $n 个文件 · ${com.pilot.agent.voice.ModelStore.diagnose(app)}")
            }.apply { name = "model-import"; isDaemon = true }.start()
            return true
        }

        // ASR 自检：拿 files 目录下的 wav 喂一遍，验证端侧识别链路
        //   --es asr_test test.wav
        val asrTest = intent.getStringExtra("asr_test")
        if (!asrTest.isNullOrBlank()) {
            Thread {
                // 先找私有目录，再找外部私有目录（adb push 只能写后者）
                val f = listOfNotNull(
                    File(app.filesDir, asrTest),
                    app.getExternalFilesDir(null)?.let { File(it, asrTest) },
                ).firstOrNull { it.isFile }
                val pcm = f?.let { com.pilot.agent.voice.WavReader.read(it) }
                if (pcm == null) {
                    Dbg.e(TAG, "ASR 自检: 读不到 wav（$asrTest），已试 private + external files 目录")
                    return@Thread
                }
                Dbg.i(TAG, "ASR 自检: ${pcm.samples.size} 采样 ${pcm.sampleRate}Hz ${pcm.channels}ch")
                val t0 = System.currentTimeMillis()
                val text = AgentRuntime.asr.transcribe(pcm.samples, pcm.sampleRate)
                Dbg.i(
                    TAG,
                    "ASR 自检结果(${System.currentTimeMillis() - t0}ms): " +
                        (text?.let { LogRedactor.text("text", it) }
                            ?: "null（不可用：${AgentRuntime.asr.lastError}）"),
                )
            }.apply { name = "asr-selftest"; isDaemon = true }.start()
            return true
        }

        // LLM 自检：验证端侧模型加载与生成（固定提示词，不含用户内容）
        //   --es llm_test start
        val llmTest = intent.getStringExtra("llm_test")
        if (!llmTest.isNullOrBlank()) {
            Thread {
                val llm = AgentRuntime.localLlm
                Dbg.i(TAG, "LLM 自检: 开始加载，模型目录=${llm.modelDir().absolutePath}")
                val t0 = System.currentTimeMillis()
                if (!llm.ensureReady()) {
                    Dbg.e(TAG, "LLM 自检: 加载失败 ${llm.lastError}")
                    return@Thread
                }
                Dbg.i(TAG, "LLM 自检: 加载 ${System.currentTimeMillis() - t0}ms，开始生成")
                // 模型带思考模式时必须加 /no_think，否则会先写一大段推理再给答案。
                val noThink = if (llm.wantsNoThink) "/no_think" else ""
                val prompt = "<|im_start|>user\n用一句中文介绍你自己。$noThink<|im_end|>\n" +
                    "<|im_start|>assistant\n"
                val out = llm.generate(prompt)
                Dbg.i(TAG, "LLM 自检结果: ${out?.take(200) ?: "null（${llm.lastError}）"}")
            }.apply { name = "llm-selftest"; isDaemon = true }.start()
            return true
        }

        // 沙箱自检：解包 rootfs + 起 proot 跑一条命令（命令与输出都不写原文日志）
        //   --es sandbox_test "uname -a; echo PILOT_SANDBOX_OK"
        val sandboxTest = intent.getStringExtra("sandbox_test")?.trim()
        if (!sandboxTest.isNullOrEmpty()) {
            // 可选：--es sandbox_timeout_ms 60000 —— 验证「超时返回结果未知」用；默认 420s。
            val timeoutMs = intent.getStringExtra("sandbox_timeout_ms")?.toLongOrNull() ?: 420_000L
            Thread {
                val term = com.pilot.agent.sandbox.SandboxTerminal
                term.init(app)
                runBlocking {
                    val r = term.run(app, sandboxTest, timeoutMs = timeoutMs)
                    Dbg.i(
                        TAG,
                        "沙箱自检 status=${r.status} exit=${r.exitCode} ${r.durationMs}ms " +
                            "mode=${com.pilot.agent.sandbox.SandboxClient.mode()} " +
                            LogRedactor.text("out", r.output.take(400)),
                    )
                }
            }.apply { name = "sandbox-selftest"; isDaemon = true }.start()
            return true
        }

        // 语音链路自检（不用说话就能测打断/活跃期/休眠）
        //   --es voice_test wake|end|status|enroll|say|dnd [--es say_text "..."]
        val voiceTest = intent.getStringExtra("voice_test")
        if (!voiceTest.isNullOrBlank()) {
            val v = AgentRuntime.voiceInput
            val sayText = intent.getStringExtra("say_text").orEmpty()
            Thread {
                val vv = v ?: AgentRuntime.ensureVoiceInput()
                when (voiceTest) {
                    "wake" -> vv.debugHit("小派")
                    "end" -> vv.debugEndTurn()
                    "status" -> Dbg.i(TAG, "自检状态：${vv.debugStatus()}")
                    "enroll" -> {
                        val ok = vv.startEnroll()
                        Dbg.i(TAG, "自检：声纹注册 startEnroll=$ok")
                    }
                    // 自检：模拟识别到一句话（测意图分流用）
                    "say" -> AgentRuntime.voiceInput?.debugSay(sayText)
                    // 自检：直接进免打扰
                    "dnd" -> {
                        vv.enterDoNotDisturb()
                        Dbg.i(TAG, "自检：进入免打扰")
                    }
                    else -> vv.debugHit(voiceTest)
                }
                Dbg.i(TAG, "自检后：${vv.debugStatus()}")
            }.apply { name = "trigger-voice"; isDaemon = true }.start()
            return true
        }

        // 设备端导航模拟测试入口。标记文件位于 App 私有目录，主/导航进程共享。
        //   --es simulate_nav "张江"
        val simulateNav = intent.getStringExtra("simulate_nav")?.trim().orEmpty()
        if (simulateNav.isNotBlank()) {
            Dbg.i(TAG, "启动模拟导航测试：${LogRedactor.text("dest", simulateNav)}")
            val loop = AgentRuntime.loop
            if (loop != null) {
                val marker = File(app.filesDir, "nav_simulate_next")
                if (!runCatching { marker.writeText("1"); true }.getOrDefault(false)) {
                    Dbg.e(TAG, "写入模拟导航标记失败")
                    return true
                }
                loop.start(AgentRuntime.scope, "导航去$simulateNav", ui = false)
                Dbg.i(TAG, "模拟导航请求已交给 AgentLoop")
                return true
            }
            Thread {
                val jobId = runBlocking {
                    com.pilot.agent.navigation.NavigationClient.startSimulatedNavi(app, simulateNav)
                }
                Dbg.i(TAG, "模拟导航服务已返回 jobId=$jobId")
            }.apply { name = "simulate-navigation-test"; isDaemon = true }.start()
            return true
        }

        // 调试别名上的自然语言指令（regress.sh 等脚本用），与正式入口走同一条后台分发链。
        val goal = intent.getStringExtra("goal")?.trim().orEmpty()
        if (goal.isNotEmpty()) {
            dispatchBackgroundGoal(app, goal, "debug-shell")
            return true
        }

        Dbg.w(TAG, "调试入口：没有可识别的指令")
        return true
    }
}
