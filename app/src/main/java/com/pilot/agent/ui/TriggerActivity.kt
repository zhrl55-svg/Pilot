package com.pilot.agent.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import com.pilot.agent.BuildConfig
import com.pilot.agent.Dbg
import com.pilot.agent.LogRedactor
import com.pilot.agent.runtime.AgentRuntime
import com.pilot.agent.service.DebugCommands
import com.pilot.agent.service.TriggerAuth
import com.pilot.agent.service.dispatchBackgroundGoal

/**
 * 触发入口（用 `am start` 而不是广播）。
 *
 * ## 为什么要有这个
 * 华为/EMUI 的**广播队列会卡死**：实测 `am broadcast` 显示
 * `dispatchClockTime=1970-01-01`——广播入队了但永远不派发。
 * `am start` 走的是 Activity 启动路径，不经过广播队列，稳定得多。
 *
 * ## 安全（审核报告 code.md S1）
 * - 本组件在**所有构建类型**都要求 signature 权限 `com.pilot.agent.permission.TRIGGER`；
 *   `:navigation` 进程与主进程同 UID，不受影响。
 * - 正式入口只接受：`start_voice`、`speak_text`、`stop_speaking`（导航界面用）和 `goal`。
 *   `goal` 与广播/后台语音走同一条 [dispatchBackgroundGoal]（快路径 + 策略判定），不再直接塞给 AgentLoop。
 * - 调试指令只能经 debug 包的别名 `com.pilot.agent.ui.DebugTriggerActivity` 进入
 *   （`android.permission.DUMP` 保护，仅 adb shell 持有），并且要显式打开开关，见 [DebugCommands]。
 * - API 34+ 能拿到 `launchedFromUid` 时做签名比对；API<34 拿不到可信 UID，不再「按自身 UID 放行」，
 *   只依赖系统强制的 signature 权限。
 *
 * ## 用法
 * ```
 * # 同签名组件 / 导航进程
 * am start -n com.pilot.agent/.ui.TriggerActivity --es goal "导航去张江"
 * # adb 调试（debug 包，先打开开关）
 * adb shell am start -n com.pilot.agent/.ui.DebugTriggerActivity --es sandbox_test "df -h"
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
            Dbg.e(TAG, "触发失败：${LogRedactor.error(t)}")
        } finally {
            finish()
        }
    }

    private fun handle(intent: Intent?) {
        val i = intent ?: return

        // 调试别名（仅 debug 包启用；manifest 用 DUMP 权限把关，只有 adb shell 能启动）。
        if (i.component?.className == DebugCommands.ALIAS_CLASS) {
            if (!BuildConfig.DEBUG) {
                Dbg.w(TAG, "release 包不提供调试入口，已忽略")
                return
            }
            DebugCommands.handle(applicationContext, i)
            return
        }

        val caller = callerIdentity()
        if (caller is TriggerAuth.Caller.Rejected) {
            Dbg.w(TAG, "拒绝来自 uid=${caller.uid} 的触发请求")
            return
        }

        if (DebugCommands.hasDebugExtra(i)) {
            Dbg.w(TAG, "正式触发入口不接受调试指令，已忽略（调试请用 debug 包的 DebugTriggerActivity）")
            return
        }

        // 开始一次语音识别（导航界面点了麦克风）
        if (i.getBooleanExtra("start_voice", false)) {
            Dbg.i(TAG, "请求开始语音识别")
            // ★ 不能只取 voiceInput：主界面没创建过语音实例时它是 null，
            //   点击导航界面的麦克风会静默失败（表现为「没录音」）。
            //   ensureVoiceInput 会创建单例；识别结果仍写 VoiceState 给导航搜索框。
            runCatching {
                // ★ 点麦克风 = 用户要说话：先把 TTS 停掉，否则放音闸门会把
                //   这几秒麦克风全静音，用户说完什么也没录到（表现为「聆听」不动）。
                AgentRuntime.speaker.stop()
                AgentRuntime.ensureVoiceInput().startListening()
            }.onFailure { Dbg.e(TAG, "开始语音识别失败：${LogRedactor.error(it)}") }
            return
        }

        // 让主进程播一句话（导航界面的候选播报）
        val speakText = i.getStringExtra("speak_text")
        if (!speakText.isNullOrBlank()) {
            Dbg.i(TAG, "请求播报：${LogRedactor.text("text", speakText)}")
            runCatching { AgentRuntime.speaker.speakNav(speakText) }
                .onFailure { Dbg.e(TAG, "播报失败：${LogRedactor.error(it)}") }
            return
        }

        // 停止播报（导航界面点「停止播报」时通知过来）
        if (i.getBooleanExtra("stop_speaking", false)) {
            Dbg.i(TAG, "收到停止播报指令")
            runCatching { AgentRuntime.speaker.stop() }
            return
        }

        // 正式指令：与广播 / 后台语音走同一条分发链（快路径 → 本地只读工具 → 意图判定 → Agent），
        // 以前这里直接 loop.start(ui=false)，绕过了快路径和策略判定（code.md S1 补充）。
        val goal = i.getStringExtra("goal")?.trim().orEmpty()
        if (goal.isEmpty()) {
            Dbg.w(TAG, "触发请求缺少 goal")
            return
        }
        dispatchBackgroundGoal(applicationContext, goal, "trigger ${TriggerAuth.label(caller)}")
    }

    /**
     * 调用方身份。API 34+ 用 `launchedFromUid`（同 UID 的 :navigation 进程总能拿到；
     * 其他调用方未共享身份时为 INVALID_UID）。API<34 没有可信来源 → [TriggerAuth.Caller.Unknown]，
     * 此时由 manifest 的 signature 权限把关。
     */
    private fun callerIdentity(): TriggerAuth.Caller {
        val uid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            launchedFromUid
        } else {
            Process.INVALID_UID
        }
        return TriggerAuth.classify(this, uid)
    }
}
