package com.pilot.agent.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.pilot.agent.runtime.AgentRuntime

/**
 * 系统的常驻入口。自研 ROM 里这个服务由系统在开机后按 ENABLED_ACCESSIBILITY_SERVICES 拉起，
 * 不需要用户手动去设置里勾选（ROM 侧配置见 rom/README）。
 */
class AgentAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        AgentRuntime.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 只做轻量的前台应用记录，重活都在 AgentLoop 里按需抓取，
        // 事件回调里做重计算会拖慢整个系统的 UI 线程。
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            // ★ 输入法窗口也会发这个事件，而且它一弹出来就覆盖"前台应用"。
            //   实测点开设置搜索框后前台被记成 com.huawei.ohos.inputmethod，
            //   于是「任务结束切回原 App」判断成"不在原 App"、白跑一次搜索拉起。
            //   自己的包同样要跳过：那只是我们自己的界面，用户视角没变。
            val ignorable = pkg == null ||
                pkg.contains("inputmethod", ignoreCase = true) ||
                pkg == packageName
            if (!ignorable) AgentRuntime.onForegroundChanged(pkg!!)
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        AgentRuntime.detach()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AgentRuntime.detach()
        super.onDestroy()
    }
}
