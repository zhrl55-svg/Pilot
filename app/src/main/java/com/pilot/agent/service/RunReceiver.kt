package com.pilot.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Process
import com.pilot.agent.Dbg
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import com.pilot.agent.agent.AgentSettings
import com.pilot.agent.assistant.AssistantFastPath
import com.pilot.agent.assistant.exec.FastPathTrace
import com.pilot.agent.assistant.exec.ResultFormatter
import com.pilot.agent.runtime.AgentRuntime

/**
 * 后台触发入口：不开界面，直接让 agent 在当前前台应用上干活。
 *
 * 为什么需要它：agent 自己的界面在前台时，无障碍抓到的屏幕就是 agent 自己，
 * 什么也操作不了。系统级助理的正确形态是「常驻后台 + 被唤起」，而不是「开着 App 再去操作别人」。
 *
 * 语音、系统键、通知栏按钮、外部程序都通过这里触发。
 *
 * 只接受 root / shell / 自身 UID 的广播——避免任意第三方 App 能遥控用户的手机。
 * 产品化时建议改成 signature 级自定义权限。
 *
 * 用法：
 *   adb shell am broadcast -a com.pilot.agent.RUN -p com.pilot.agent.debug --es goal "打开设置"
 */
class RunReceiver : BroadcastReceiver() {

    /**
     * 发送方 UID。API 34+ 用 `sentFromUid`（可靠）；低版本 `getCallingUid()` 在
     * onReceive 里已脱离发送方 Binder 事务，只能作为兜底，真正防线是 manifest 的
     * signature 权限 `com.pilot.agent.permission.TRIGGER`。
     */
    private fun senderUid(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) sentFromUid
        else Binder.getCallingUid()

    override fun onReceive(context: Context, intent: Intent) {
        val caller = senderUid()
        val self = context.applicationInfo.uid
        if (caller != Process.ROOT_UID && caller != Process.SHELL_UID &&
            caller != Process.SYSTEM_UID && caller != self
        ) {
            Dbg.w(TAG, "拒绝来自 uid=$caller 的触发请求")
            return
        }

        val goal = intent.getStringExtra("goal")?.trim().orEmpty()

        // P4 调试：设置某个能力的执行权归属（LEGACY / SHADOW / ROUTE）
        //   adb shell "am broadcast -a com.pilot.agent.RUN -p com.pilot.agent \
        //       --es fastpath_owner 'device=SHADOW'"
        val ownerSpec = intent.getStringExtra("fastpath_owner")
        if (!ownerSpec.isNullOrBlank()) {
            val capability = ownerSpec.substringBefore('=').trim()
            val owner = com.pilot.agent.assistant.routing.DefaultOwners
                .parse(ownerSpec.substringAfter('=', ""))
            runCatching {
                com.pilot.agent.assistant.routing.RouteStore.get(context)
                    .setOwner(capability, owner)
                Dbg.i(TAG, "快路径归属：$capability=$owner")
            }.onFailure {
                Dbg.w(TAG, "设置快路径归属失败：${it.message}")
            }
            return
        }

        // 快路径 / 遗留链路两侧对照：同一句话，两边各判一次，**都不执行**。
        //   用来做影子对照 —— 快路径认不认、抽成什么样，遗留匹配器接不接。
        //   adb shell "am broadcast -a com.pilot.agent.RUN -p com.pilot.agent \
        //       --es fastpath_compare '记住我喜欢喝咖啡'"
        val compareText = intent.getStringExtra("fastpath_compare")
        if (!compareText.isNullOrBlank()) {
            Thread {
                val fast = runCatching {
                    val router = com.pilot.agent.assistant.routing.AssistantRouter()
                    val routes = com.pilot.agent.assistant.routing.RouteStore.get(context).snapshot()
                    when (val d = router.route(compareText, routes)) {
                        is com.pilot.agent.assistant.routing.RouteDecision.Local ->
                            "fast=${d.capability}/${d.action} args=${d.args} " +
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
                    "legacy=${it.name} group=${it.group} summary=${it.summary}"
                } ?: "legacy=none"
                Dbg.i(TAG, "对照「$compareText」$fast | $legacyText")
            }.apply { name = "fastpath-compare"; isDaemon = true }.start()
            return
        }

        // 提醒维护：按标题关键词取消提醒。
        //   DELETE 组在无界面场景一律拒绝确认，清理测试提醒只能走这里。
        //   adb shell "am broadcast -a com.pilot.agent.RUN -p com.pilot.agent \
        //       --es reminder_cancel '快路径验收样本'"
        val reminderKeyword = intent.getStringExtra("reminder_cancel")
        if (!reminderKeyword.isNullOrBlank()) {
            Thread {
                val message = runCatching {
                    com.pilot.agent.action.ReminderManager.handle(
                        context,
                        "取消提醒 ${reminderKeyword.trim()}",
                    )
                }.getOrNull()
                Dbg.i(TAG, "提醒维护：取消「$reminderKeyword」→ ${message ?: "失败"}")
            }.apply { name = "reminder-cancel"; isDaemon = true }.start()
            return
        }

        // 记忆维护（按 id 精确删除）：清理测试数据用这个。
        //   adb shell "am broadcast -a com.pilot.agent.RUN -p com.pilot.agent \
        //       --es memory_delete_id 7"
        // ★ 不要用 memory_forget 做清理 —— 它是模糊匹配，
        //   实测「忘掉 shadow-probe-S」把一条无关的中文记忆一起删了。
        val deleteId = intent.getStringExtra("memory_delete_id")
        if (!deleteId.isNullOrBlank()) {
            Thread {
                val id = deleteId.trim().toLongOrNull()
                val ok = id != null && AgentRuntime.localTools.deleteMemoryFromUi(id)
                Dbg.i(TAG, "记忆维护：删除 #$deleteId → ${if (ok) "已删除" else "未找到"}")
            }.apply { name = "memory-delete-id"; isDaemon = true }.start()
            return
        }

        // 记忆维护：按关键词删除（模糊，谨慎使用）。
        //   DELETE 组在无界面场景一律拒绝确认，所以清理测试数据只能走这里。
        //   adb shell "am broadcast -a com.pilot.agent.RUN -p com.pilot.agent \
        //       --es memory_forget '影子对照样本'"
        val forgetKeyword = intent.getStringExtra("memory_forget")
        if (!forgetKeyword.isNullOrBlank()) {
            Thread {
                val removed = runCatching {
                    AgentRuntime.localTools.memory.forget(forgetKeyword)
                }.getOrNull()
                Dbg.i(TAG, "记忆维护：删除「$forgetKeyword」→ ${removed?.size ?: 0} 条")
                removed?.forEach { Dbg.i(TAG, "  已删除 #${it.id} ${it.content}") }
            }.apply { name = "memory-forget"; isDaemon = true }.start()
            return
        }

        // 端侧补值自检：走完整 plan → complete 流程，但**不执行**工具。
        //   adb shell "am broadcast -a com.pilot.agent.RUN -p com.pilot.agent \
        //       --es fastpath_fill_test '保存到 /sdcard/Download/a.txt 内容 hello'"
        val fillTest = intent.getStringExtra("fastpath_fill_test")
        if (!fillTest.isNullOrBlank()) {
            Thread {
                val plan = AssistantFastPath.plan(context, fillTest, "selftest")
                val completed = plan?.let {
                    runCatching { runBlocking { AssistantFastPath.complete(context, it) } }.getOrNull()
                }
                Dbg.i(
                    TAG,
                    "补值自检：plan=${plan?.capability}/${plan?.action} " +
                        "missing=${plan?.decision?.missingSlots} " +
                        "completed=${completed?.invocation?.name} args=${completed?.args}",
                )
            }.apply { name = "fastpath-fill-test"; isDaemon = true }.start()
            return
        }

        // 从服务器侧写入网易云 cookie（扫码/验证码在电脑上完成，不用在 App 里走一遍）
        //   adb shell am broadcast -a com.pilot.agent.RUN -p com.pilot.agent.debug \
        //       --es set_netease_cookie "MUSIC_U=xxx; __csrf=yyy"
        val injectedCookie = intent.getStringExtra("set_netease_cookie")
        if (!injectedCookie.isNullOrBlank()) {
            AgentSettings.save(AgentSettings.load().copy(neteaseCookie = injectedCookie))
            Dbg.i(TAG, "已写入网易云 cookie（${injectedCookie.length} 字符）")
            return
        }

        // KWS 自检：验证唤醒词能否转成模型认识的音素行
        //   adb shell am broadcast -a com.pilot.agent.RUN -p com.pilot.agent.debug --ez kws_test true
        if (intent.getBooleanExtra("kws_test", false)) {
            Thread {
                val words = AgentSettings.load().wakeWordList()
                Dbg.i(TAG, "KWS 自检: 唤醒词=$words")
                words.forEach { w ->
                    Dbg.i(TAG, "KWS 拼音行「$w」→ ${com.pilot.agent.voice.KwsKeywords.line(w)}")
                }
                val d = com.pilot.agent.voice.WakeWordDetector(context)
                val ok = d.load(words)
                Dbg.i(
                    TAG,
                    "KWS 自检: load=$ok labels=${d.labels} err=${d.lastError ?: "无"}",
                )
                // 声纹模型也顺手验一下
                val gate = com.pilot.agent.voice.VoiceprintGate(context)
                val vp = gate.ensureReady()
                Dbg.i(TAG, "声纹自检: ready=$vp 模板=${gate.hasTemplate()} err=${gate.lastError ?: "无"}")
            }.apply { name = "kws-test"; isDaemon = true }.start()
            return
        }

        // 播报自检：直接念一段，验证 TTS 引擎
        //   adb shell am broadcast -a com.pilot.agent.RUN -p com.pilot.agent.debug \
        //       --es tts_test "你好，我是 Pilot"
        val ttsTest = intent.getStringExtra("tts_test")
        if (!ttsTest.isNullOrBlank()) {
            val override = intent.getStringExtra("tts_engine")
            Dbg.i(TAG, "TTS 自检: 引擎=${override ?: AgentSettings.load().ttsEngine} 文本=$ttsTest")
            if (override == AgentSettings.TTS_MINIMAX) {
                // 临时绕过设置，直接验云端合成，不动用户配置
                Thread {
                    val mm = com.pilot.agent.voice.MiniMaxTts(context)
                    mm.speak(ttsTest, onDone = { Dbg.i(TAG, "TTS 自检: MiniMax 播完") }) { err ->
                        Dbg.e(TAG, "TTS 自检: MiniMax 失败 $err")
                    }
                }.apply { name = "tts-test"; isDaemon = true }.start()
            } else {
                AgentRuntime.speaker.speak(ttsTest)
            }
            return
        }

        // 手动触发模型导入（脚本用，避免为了触发 onCreate 去 force-stop 把无障碍踢掉）
        if (intent.getBooleanExtra("import_models", false)) {
            Thread {
                Dbg.i(TAG, "模型导入: 开始")
                val n = com.pilot.agent.voice.ModelStore.ensure(context)
                Dbg.i(TAG, "模型导入: $n 个文件 · ${com.pilot.agent.voice.ModelStore.diagnose(context)}")
            }.apply { name = "model-import"; isDaemon = true }.start()
            return
        }

        // ASR 自检：拿 files 目录下的 wav 喂一遍，验证端侧识别链路
        //   adb shell am broadcast -a com.pilot.agent.RUN -p com.pilot.agent.debug \
        //       --es asr_test test.wav
        val asrTest = intent.getStringExtra("asr_test")
        if (!asrTest.isNullOrBlank()) {
            Thread {
                // 先找私有目录，再找外部私有目录（adb push 只能写后者）
                val f = listOfNotNull(
                    java.io.File(context.filesDir, asrTest),
                    context.getExternalFilesDir(null)?.let { java.io.File(it, asrTest) },
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
                        (text ?: "null（不可用：${AgentRuntime.asr.lastError}）"),
                )
            }.apply { name = "asr-selftest"; isDaemon = true }.start()
            return
        }

        // LLM 自检：验证端侧模型加载与生成
        //   adb shell am broadcast -a com.pilot.agent.RUN -p com.pilot.agent.debug \
        //       --es llm_test "打开设置"
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
                // 模型带思考模式时必须加 /no_think，否则会先写一大段推理再给答案
                // （实测 3765ms → 关掉后 800ms 内）。和正式链路的 buildPrompt 保持一致。
                val noThink = if (llm.wantsNoThink) "/no_think" else ""
                val prompt = "<|im_start|>user\n用一句中文介绍你自己。$noThink<|im_end|>\n" +
                    "<|im_start|>assistant\n"
                val out = llm.generate(prompt)
                Dbg.i(TAG, "LLM 自检结果: ${out ?: "null（${llm.lastError}）"}")
            }.apply { name = "llm-selftest"; isDaemon = true }.start()
            return
        }

        // 沙箱自检：解包 rootfs + 起 proot 跑一条命令
        //   adb shell am broadcast -a com.pilot.agent.RUN -p com.pilot.agent.debug \
        //       --es sandbox_test "uname -a; echo PILOT_SANDBOX_OK"
        val sandboxTest = intent.getStringExtra("sandbox_test")
        if (!sandboxTest.isNullOrBlank()) {
            Thread {
                val term = com.pilot.agent.sandbox.SandboxTerminal
                term.init(context)
                kotlinx.coroutines.runBlocking {
                    val r = term.run(context, sandboxTest, timeoutMs = 420_000)
                    Dbg.i(TAG, "沙箱自检 exit=${r.exitCode} ${r.durationMs}ms mode=${com.pilot.agent.sandbox.SandboxClient.mode()} out=${r.output.take(400)}")
                }
            }.apply { name = "sandbox-selftest"; isDaemon = true }.start()
            return
        }

        if (goal.isEmpty()) {
            Dbg.w(TAG, "触发请求缺少 goal / asr_test / llm_test")
            return
        }
        dispatchBackgroundGoal(context, goal, "broadcast uid=$caller")
    }
}

/**
 * 非操作类问题的模型回答（云端优先，端侧兜底）。
 *
 * 走 AgentRuntime 里同一套提示词，保证语音路径和广播路径回答一致。
 */
private suspend fun answerByModel(text: String): String {
        val s = com.pilot.agent.agent.AgentSettings.load()
        val now = java.text.SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", java.util.Locale.CHINA)
            .format(java.util.Date())
        val memory = AgentRuntime.localTools.memory.promptContext(text, 5)
        val memoryPrompt = if (memory.isBlank()) "" else "\n相关长期记忆：\n$memory"
        // 云端
        if (s.cloudReady) {
            runCatching {
                val body = org.json.JSONObject().apply {
                    put("model", s.model)
                    put("stream", false)
                    put("messages", org.json.JSONArray().apply {
                        put(org.json.JSONObject().apply {
                            put("role", "system")
                            put(
                                "content",
                                "你是手机助手 Pilot，运行在这台安卓手机上。现在是 $now。" +
                                    "用一到两句话回答，简短口语化，不要用列表。" +
                                    "你能开关应用、查天气、股票和火车余票、播音乐、跑 shell 命令、做导航。" +
                                    com.pilot.agent.agent.IntentGate.SELF_KNOWLEDGE +
                                    memoryPrompt,
                            )
                        })
                        put(org.json.JSONObject().apply {
                            put("role", "user")
                            put("content", text)
                        })
                    })
                }.toString()
                val req = okhttp3.Request.Builder()
                    .url(s.baseUrl.trimEnd('/') + "/chat/completions")
                    .addHeader("Authorization", "Bearer ${s.apiKey}")
                    .addHeader("Content-Type", "application/json")
                    .post(okhttp3.RequestBody.create("application/json".toMediaType(), body))
                    .build()
                com.pilot.agent.agent.CloudHttp.client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        org.json.JSONObject(resp.body?.string().orEmpty())
                            .optJSONArray("choices")?.optJSONObject(0)
                            ?.optJSONObject("message")?.optString("content")
                    } else null
                }
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let { return it.trim().take(200) }
        }
        // 端侧兜底
        val llm = AgentRuntime.localLlm
        if (!llm.ensureReady()) return "我暂时没法回答。"
        val noThink = if (llm.wantsNoThink) "/no_think" else ""
        return llm.generate(
            "system\n你是手机助手 Pilot，运行在这台安卓手机上，现在是 $now。" +
                "能开关应用、查天气股票和火车余票、播音乐、跑命令、做导航。" +
                com.pilot.agent.agent.IntentGate.SELF_KNOWLEDGE +
                memoryPrompt +
                "回答简短口语化，不超过 25 个字。$noThink\n" +
                "user\n$text\nassistant\n"
        )?.trim()?.take(200) ?: "我暂时没法回答。"
}

/**
 * 后台执行一条自然语言指令（无人确认）。
 *
 * 广播入口与后台语音共用这一条链，避免两套行为不一致：
 * 快路径 → 只读本地工具 → 意图门（查询/闲聊交给模型）→ Agent 循环。
 *
 * @param callerLabel 仅用于日志/追踪，标明是谁触发的
 */
internal fun dispatchBackgroundGoal(
    context: Context,
    goal: String,
    callerLabel: String = "background",
) {
    // 助理快路径：规则选能力 → 确定性抽参 → 本机执行，不调云端。
    // 接管了就不走下面的遗留匹配器，避免同一个请求被执行两遍。
    // 需要端侧模型补值的请求在无界面场景不处理（保持确定性），照旧走原链路。
    Dbg.i(TAG, "dispatch plan begin goal=$goal caller=$callerLabel")
    val fastPlan = AssistantFastPath.plan(context, goal, callerLabel)
    Dbg.i(
        TAG,
        "dispatch plan result=${fastPlan?.let { "${it.capability}/${it.action}" } ?: "null"}",
    )
    val fastInvocation = fastPlan?.invocation
    if (fastPlan != null && fastInvocation != null) {
        // 破坏性能力（HIGH / 路由强制确认）在后台默认不执行，
        // 但用户原话已明确说出操作对象、执行器又标了 explicitAuthorized 的能力
        // （记住/忘掉/建提醒/建定时）例外 —— 再让用户「去界面说一次」等于
        // 把同一条明确指令说两遍，实测会被当成没听懂。文件覆盖/删除没有这个标记，
        // 仍然保持后台拒绝。
        if (fastPlan.needsConfirmation) {
            if (!fastInvocation.explicitAuthorized) {
                Dbg.w(TAG, "后台无人确认，拒绝破坏性快路径：${fastPlan.capability}/${fastPlan.action}")
                AgentRuntime.speaker.speak("这个操作需要你先确认，请在界面里说一次。")
                return
            }
            Dbg.i(TAG, "后台执行已明确授权的破坏性操作：${fastPlan.capability}/${fastPlan.action}")
        }
        val capability = fastPlan.capability
        val action = fastPlan.action
        val args = fastPlan.args
        Thread {
            val result = runCatching {
                runBlocking {
                    AgentRuntime.localTools.execute(fastInvocation) { question, _ ->
                        Dbg.w(TAG, "快路径无人确认，已拒绝：$question")
                        false
                    }
                }
            }.getOrNull()
            if (result != null) {
                val reply = ResultFormatter.format(capability, action, args, result)
                FastPathTrace.handled(
                    context,
                    capability,
                    action,
                    result.status.name,
                    reply.text.length,
                )
                AgentRuntime.speaker.speak(reply.spoken)
            } else {
                Dbg.e(TAG, "快路径没有返回结果")
            }
        }.apply { name = "fast-path"; isDaemon = true }.start()
        return
    }

    // 本地工具兜底：只读类（网络/定位/蓝牙/使用时长、列目录等）在后台无人确认
    // 也可以安全执行；写/删/分享仍按原链路处理，绝不在后台静默执行。
    val localInvocation = AgentRuntime.localTools.match(goal)
    if (localInvocation != null &&
        AgentRuntime.localTools.policyFor(localInvocation.group) ==
        com.pilot.agent.tool.ToolPolicy.ALLOW
    ) {
        Thread {
            val result = runCatching {
                runBlocking {
                    AgentRuntime.localTools.execute(localInvocation) { question, _ ->
                        Dbg.w(TAG, "本地工具无人确认，已拒绝：$question")
                        false
                    }
                }
            }.getOrNull()
            result?.let { AgentRuntime.speaker.speak(it.spoken) }
        }.apply { name = "local-tool"; isDaemon = true }.start()
        return
    }

    // 自动路由：快路径有规则就本机直执行；没有规则的请求交给 Agent，
    // 由模型决定回答还是执行。遗留匹配器不再插在中间。

    // ★ 意图判定：和界面侧保持一致，先让云端模型判断「回答还是执行」，
    //   关键词 IntentGate 只在云端不可用时兜底。否则「需要执行」的话被关键词
    //   误判成闲聊，就只回一句话、什么都不做。
    //   判定要联网，必须在后台线程跑，不能阻塞广播回调（10 秒 ANR）。
    Thread {
        val kind = com.pilot.agent.agent.IntentGate.classify(goal)
        val settings = com.pilot.agent.agent.AgentSettings.load()
        val decision = if (settings.cloudReady) {
            val now = java.text.SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", java.util.Locale.CHINA)
                .format(java.util.Date())
            val memory = runCatching {
                AgentRuntime.localTools.memory.promptContext(goal, 5)
            }.getOrDefault("")
            runCatching {
                runBlocking {
                    com.pilot.agent.agent.AgentIntentRouter {
                        com.pilot.agent.agent.AgentSettings.load()
                    }.decide(goal, emptyList(), memory, now)
                }
            }.getOrNull()
        } else {
            null
        }

        if (decision != null) {
            if (decision.execute) {
                Dbg.i(TAG, "后台路由 → 执行 thought=${decision.thought}")
                executeBackgroundTask(goal, callerLabel)
                return@Thread
            }
            val answer = decision.answer?.trim().orEmpty()
            if (answer.isNotBlank()) {
                Dbg.i(TAG, "后台路由 → 回答 chars=${answer.length} thought=${decision.thought}")
                AgentRuntime.speaker.speak(answer)
                return@Thread
            }
        }

        Dbg.i(TAG, "后台路由云端不可用或未给出结论（$kind），关键词兜底")
        answerOrExecuteByKeyword(goal, kind, callerLabel)
    }.apply { name = "background-route"; isDaemon = true }.start()
}

/**
 * 关键词兜底：QUERY/CHAT 交给模型回答，TASK 交给 Agent 执行。
 *
 * 「你是谁」这类问题进 loop 会去乱点屏幕（真机实测过一句话触发 3 次乱点），
 * 所以闲聊必须先在这里拦下。QUERY 类（查上证指数）优先走 QueryEngine：更快、数据更准。
 * 注意：本函数运行在后台线程，内部可以放心使用 runBlocking。
 */
private fun answerOrExecuteByKeyword(
    goal: String,
    kind: com.pilot.agent.agent.IntentGate.Kind,
    callerLabel: String,
) {
    if (kind == com.pilot.agent.agent.IntentGate.Kind.QUERY) {
        val answer = runCatching {
            runBlocking { com.pilot.agent.query.QueryEngine.handle(goal) }
        }.getOrNull()
        if (!answer.isNullOrBlank()) {
            val spokenSummary = com.pilot.agent.query.QueryEngine.speechSummary(answer)
            if (spokenSummary != null) AgentRuntime.speaker.speakExpanded(spokenSummary)
            else AgentRuntime.speaker.speak(answer)
        } else {
            AgentRuntime.speaker.speak(
                runCatching { runBlocking { answerByModel(goal) } }
                    .getOrDefault("我暂时没法回答。"),
            )
        }
        return
    }
    if (kind != com.pilot.agent.agent.IntentGate.Kind.TASK) {
        Dbg.i(TAG, "非操作指令（$kind），交给模型回答：$goal")
        val reply = runCatching { runBlocking { answerByModel(goal) } }
            .getOrDefault("我暂时没法回答。")
        AgentRuntime.speaker.speak(reply)
        return
    }
    executeBackgroundTask(goal, callerLabel)
}

/** 交给 Agent 循环执行；后台无人确认，ui=false 时遇到确认动作会快速放弃。 */
private fun executeBackgroundTask(goal: String, callerLabel: String) {
    val loop = AgentRuntime.loop
    if (loop == null) {
        Dbg.e(TAG, "无障碍服务未连接，无法执行：$goal")
        return
    }
    Dbg.i(TAG, "background run goal=$goal caller=$callerLabel")
    loop.start(
        AgentRuntime.scope,
        goal,
        ui = false,
        executionMode = com.pilot.agent.agent.AssistantMode.AGENT,
    )
}

/** 供后台语音（界面已销毁、没有 ViewModel）复用同一套执行链。 */
fun runBackgroundGoal(context: Context, goal: String, callerLabel: String = "voice") =
    dispatchBackgroundGoal(context, goal, callerLabel)

private const val TAG = "Pilot"
