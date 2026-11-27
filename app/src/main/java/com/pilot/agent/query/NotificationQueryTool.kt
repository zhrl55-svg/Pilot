package com.pilot.agent.query

import com.pilot.agent.assistant.PrivacyGate
import com.pilot.agent.service.NotificationInbox
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotificationQueryTool : QueryTool {
    override val name = "notifications"

    override fun matches(goal: String): Boolean = MATCHES.any(goal::contains)

    override suspend fun execute(goal: String): String = when (val r = executeResult(goal)) {
        is QueryResult.Answer -> r.text
        is QueryResult.NeedMoreInfo -> r.prompt
        is QueryResult.NoResult -> r.why
        is QueryResult.Unavailable -> r.why
    }

    /**
     * 通知是个人数据：锁屏时不读（审核 产品 T5），结果标记为只走本机 TTS（审核 V-10）。
     * 权限缺失/暂无通知属于“如实告知”的答复，不交给浏览器去找。
     */
    override suspend fun executeResult(goal: String): QueryResult {
        val context = com.pilot.agent.PilotApp.instance
        if (PrivacyGate.isDeviceLocked(context)) {
            return QueryResult.NeedMoreInfo(PrivacyGate.LOCKED_PERSONAL_REPLY)
        }
        if (!NotificationInbox.isPermissionEnabled(context)) {
            return QueryResult.NeedMoreInfo(
                "还没有通知读取权限。请在设置中的“通知读取”打开 Pilot；权限只用于读取你请求的通知摘要。",
            )
        }
        val items = NotificationInbox.recent()
        if (items.isEmpty()) {
            return QueryResult.Answer("暂时没有可读的新通知。通知读取仅缓存开启服务后的通知，重启 Pilot 后会清空缓存。")
        }
        val date = SimpleDateFormat("HH:mm", Locale.getDefault())
        val text = buildString {
            append("最近的通知（最多 ").append(items.size).append(" 条）：")
            items.forEachIndexed { index, item ->
                append("\n").append(index + 1).append(". ").append(item.app)
                if (item.title.isNotBlank()) append(" · ").append(item.title)
                if (item.text.isNotBlank()) append("：").append(item.text)
                append("（").append(date.format(Date(item.at))).append("）")
            }
        }
        return QueryResult.Answer(text, privateData = true)
    }

    companion object {
        val MATCHES = listOf(
            "读通知", "读一下通知", "念通知", "最新通知", "通知摘要", "有什么通知", "刚才谁发消息", "读一下消息通知",
            "念一下通知", "看看通知", "有没有新通知",
        )
    }
}
