package com.pilot.agent.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.pilot.agent.Dbg
import com.pilot.agent.runtime.AgentRuntime

/**
 * 触发入口（用 `am start` 而不是广播）。
 *
 * ## 为什么要有这个
 * 华为/EMUI 的**广播队列会卡死**：实测 `am broadcast` 显示
 * `dispatchClockTime=1970-01-01`——广播入队了但永远不派发，
 * logcat 里还有 `HwBroadcastQueue: process has died, drop BroadcastRecord`。
 * 这时候 RunReceiver 收不到任何东西（goal / sandbox_test / llm_test 全哑）。
 *
 * `am start` 走的是 Activity 启动路径，不经过广播队列，稳定得多。
 *
 * ## 用法
 * ```
 * adb shell am start -n com.pilot.agent.debug/com.pilot.agent.ui.TriggerActivity \
 *     --es goal "导航去张掖西站"
 * adb shell am start -n com.pilot.agent.debug/com.pilot.agent.ui.TriggerActivity \
 *     --es sandbox_test "df -h"
 * ```
 *
 * 走完立刻 finish()，不留界面。
 */
class TriggerActivity : Activity() {

    companion object {
        private const val TAG = "PilotTrigger"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            handle(intent)
        } catch (t: Throwable) {
            Dbg.e(TAG, "触发失败", t)
        } finally {
            finish()
        }
    }

    private fun handle(intent: Intent?) {
        val i = intent ?: return

        // 开始一次语音识别（导航界面点了麦克风）
        if (i.getBooleanExtra("start_voice", false)) {
            Dbg.i(TAG, "请求开始语音识别")
            runCatching { com.pilot.agent.runtime.AgentRuntime.voiceInput?.startListening() }
            return
        }

        // 让主进程播一句话（导航界面的候选播报）
        val speakText = i.getStringExtra("speak_text")
        if (!speakText.isNullOrBlank()) {
            Dbg.i(TAG, "请求播报：$speakText")
            runCatching { com.pilot.agent.runtime.AgentRuntime.speaker.speakNav(speakText) }
            return
        }

        // 停止播报（导航界面点「停止播报」时通知过来）
        if (i.getBooleanExtra("stop_speaking", false)) {
            Dbg.i(TAG, "收到停止播报指令")
            runCatching { com.pilot.agent.runtime.AgentRuntime.speaker.stop() }
            return
        }

        // 语音链路自检（不用说话就能测打断/活跃期/休眠）
        val voiceTest = i.getStringExtra("voice_test")
        if (!voiceTest.isNullOrBlank()) {
            val v = com.pilot.agent.runtime.AgentRuntime.voiceInput
            Thread {
                val vv = v ?: com.pilot.agent.runtime.AgentRuntime.ensureVoiceInput()
                when (voiceTest) {
                    "wake" -> vv.debugHit("小派")
                    "end" -> vv.debugEndTurn()
                    "status" -> Dbg.i(TAG, "自检状态：${vv.debugStatus()}")
                    else -> vv.debugHit(voiceTest)
                }
                Dbg.i(TAG, "自检后：${vv.debugStatus()}")
            }.apply { name = "trigger-voice"; isDaemon = true }.start()
            return
        }

        // 沙箱自检
        val sandboxTest = i.getStringExtra("sandbox_test")?.trim()
        if (!sandboxTest.isNullOrEmpty()) {
            Thread {
                val ctx = applicationContext
                com.pilot.agent.sandbox.SandboxTerminal.init(ctx)
                val r = kotlinx.coroutines.runBlocking {
                    com.pilot.agent.sandbox.SandboxTerminal.run(ctx, sandboxTest, 420_000)
                }
                Dbg.i(TAG, "沙箱自检 exit=${r.exitCode} ${r.durationMs}ms out=${r.output.take(300)}")
            }.apply { name = "trigger-sandbox"; isDaemon = true }.start()
            return
        }

        // LLM 自检
        val llmTest = i.getStringExtra("llm_test")
        if (!llmTest.isNullOrBlank()) {
            Thread {
                val llm = AgentRuntime.localLlm
                if (!llm.ensureReady()) {
                    Dbg.w(TAG, "LLM 自检：加载失败 ${llm.lastError}")
                    return@Thread
                }
                val noThink = if (llm.wantsNoThink) "/no_think" else ""
                val out = llm.generate(
                    "system\n你是手机助手 Pilot。用一句中文介绍你自己。$noThink\n" +
                        "user\n用一句中文介绍你自己\nassistant\n",
                )
                Dbg.i(TAG, "LLM 自检结果: ${out ?: "null"}")
            }.apply { name = "trigger-llm"; isDaemon = true }.start()
            return
        }

        // 正式指令
        val goal = i.getStringExtra("goal")?.trim().orEmpty()
        if (goal.isEmpty()) {
            Dbg.w(TAG, "触发请求缺少 goal / sandbox_test / llm_test")
            return
        }
        val loop = AgentRuntime.loop
        if (loop == null) {
            Dbg.e(TAG, "无障碍服务未连接，无法执行：$goal")
            return
        }
        Dbg.i(TAG, "trigger run goal=$goal")
        loop.start(AgentRuntime.scope, goal, ui = false)
    }
}
