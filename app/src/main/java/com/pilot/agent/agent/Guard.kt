package com.pilot.agent.agent

import android.content.Context
import com.pilot.agent.perception.ScreenSnapshot
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 安全护栏。
 *
 * 系统级 agent 最大的风险不是"做不成"，而是"做成了不该做的事"——
 * 误点付款、群发消息、把相册删了。所以风险判定必须在**执行前**、在**独立的组件里**做，
 * 不能指望模型自己守规矩。
 */
class Guard(private val ctx: Context) {

    private val moneyWords = listOf(
        "支付", "付款", "立即购买", "确认购买", "转账", "充值", "打赏", "红包",
        "提交订单", "确认下单", "结算", "免密", "扣款", "buy now", "pay",
    )
    private val irreversibleWords = listOf(
        "删除", "清空", "卸载", "格式化", "注销", "退出登录", "解散", "移除", "回收站",
        "delete", "clear", "uninstall", "reset",
    )
    private val externalWords = listOf(
        "发送", "发布", "分享", "群发", "评论", "拨号", "呼叫", "邀请", "转发",
        "send", "post", "share",
    )

    fun riskOf(action: AgentAction, snap: ScreenSnapshot): Risk {
        if (action.action == "done" || action.action == "wait" || action.action == "ask") return Risk.NONE
        if (action.action == "key" || action.action == "scroll") return Risk.NONE
        if (action.action == "open") return Risk.NONE

        val targetText = buildString {
            action.summary?.let { append(it).append(' ') }
            action.text?.let { append(it).append(' ') }
            action.target?.let { append(it).append(' ') }
            action.id?.let { id ->
                snap.nodes.firstOrNull { it.id == id }?.let { append(it.text).append(' ').append(it.viewId) }
            }
        }

        // 只在"坐标点击"这种看不清目标的情况下才扫全屏文本。
        // 否则微信首页有个"支付"入口，每次点任何东西都会误报，用户会被确认弹窗逼疯。
        // ★ 注意条件必须是 action=="click"，不能只判 id==null：
        //   systemop/shortcut（系统直调、直达）同样没有 id，但它们的意图完全明确
        //   （summary 里就写着"音量""蓝牙"），扫全屏会让桌面一个「支付宝」图标
        //   把「声音大一点」误判成资金变动（2026-11-10 回归实测踩到）。
        val screenText = if (action.action == "click" && action.id == null)
            snap.nodes.joinToString(" ") { it.text } else ""
        val haystack = (targetText + " " + screenText).lowercase()

        return when {
            moneyWords.any { haystack.contains(it) } -> Risk.MONEY
            irreversibleWords.any { haystack.contains(it) } -> Risk.IRREVERSIBLE
            externalWords.any { haystack.contains(it) } -> Risk.EXTERNAL
            else -> Risk.NONE
        }
    }

    fun riskLabel(risk: Risk): String = when (risk) {
        Risk.MONEY -> "资金变动"
        Risk.IRREVERSIBLE -> "不可恢复的删除/退出"
        Risk.EXTERNAL -> "对外发送内容"
        Risk.NONE -> "无"
    }

    /** 审计日志：每一次被确认/拒绝的高风险动作都留痕，出问题能回溯。 */
    fun audit(goal: String, action: AgentAction, risk: Risk, approved: Boolean) {
        runCatching {
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val line = "$ts\trisk=${risk.name}\tapproved=$approved\tgoal=$goal\taction=${action.describe()}\n"
            File(ctx.filesDir, "audit.log").appendText(line)
        }
    }

    fun recentAudit(limit: Int = 50): List<String> {
        val f = File(ctx.filesDir, "audit.log")
        if (!f.exists()) return emptyList()
        return f.readLines().takeLast(limit).reversed()
    }
}
