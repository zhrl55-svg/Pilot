package com.pilot.agent.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Process
import com.pilot.agent.Dbg
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import com.pilot.agent.agent.AgentSettings
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

    override fun onReceive(context: Context, intent: Intent) {
        val caller = Binder.getCallingUid()
        val self = context.applicationInfo.uid
        if (caller != Process.ROOT_UID && caller != Process.SHELL_UID && caller != self) {
            Dbg.w(TAG, "拒绝来自 uid=$caller 的触发请求")
            return
        }

        val goal = intent.getStringExtra("goal")?.trim().orEmpty()

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

        // Local tools work even when accessibility is disconnected. This path has
        // no UI, so mutating tools that require confirmation are auto-denied.
        if (AgentRuntime.localTools.match(goal) != null) {
            Thread {
                val result = runCatching {
                    runBlocking {
                        AgentRuntime.localTools.execute(goal) { question, _ ->
                            Dbg.w(TAG, "本地工具无人确认，已拒绝：$question")
                            false
                        }
                    }
                }.getOrNull()
                if (result != null) {
                    AgentRuntime.speaker.speak(result.spoken)
                } else {
                    Dbg.e(TAG, "本地工具没有返回结果")
                }
            }.apply { name = "local-tool"; isDaemon = true }.start()
            return
        }

        // ★ 意图门：不是操作指令就别进 agent loop。
        //   「你是谁」这类问题进 loop 会去乱点屏幕（真机实测过一句话触发 3 次乱点）。
        //   走模型回答 —— 老板 11-10：「你是谁这样的问题不要走规则，模型交给模型回答」。
        //   但 QUERY 类（查上证指数）要优先走 QueryEngine：更快、数据更准，
        //   查不到才 fallback 到模型。
        val kind = com.pilot.agent.agent.IntentGate.classify(goal)
        if (kind != com.pilot.agent.agent.IntentGate.Kind.TASK) {
            if (kind == com.pilot.agent.agent.IntentGate.Kind.QUERY) {
                Thread {
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
                }.apply { name = "query-answer"; isDaemon = true }.start()
                return
            }
            Dbg.i(TAG, "非操作指令（$kind），交给模型回答：$goal")
            // onReceive 不能阻塞（10 秒 ANR），扔到后台线程回答
            Thread {
                val reply = runCatching { runBlocking { answerByModel(goal) } }
                    .getOrDefault("我暂时没法回答。")
                AgentRuntime.speaker.speak(reply)
            }.apply { name = "chat-answer"; isDaemon = true }.start()
            return
        }

        val loop = AgentRuntime.loop
        if (loop == null) {
            Dbg.e(TAG, "无障碍服务未连接，无法执行：$goal")
            return
        }

        Dbg.i(TAG, "broadcast run goal=$goal caller=$caller")
        // ui=false：后台触发时没人能点确认按钮，遇到需要确认的动作应快速放弃
        loop.start(AgentRuntime.scope, goal, ui = false)
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
                okhttp3.OkHttpClient().newCall(req).execute().use { resp ->
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
                memoryPrompt +
                "回答简短口语化，不超过 25 个字。$noThink\n" +
                "user\n$text\nassistant\n"
        )?.trim()?.take(200) ?: "我暂时没法回答。"
    }

    private companion object {
        const val TAG = "Pilot"
    }
}
