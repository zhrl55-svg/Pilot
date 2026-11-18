package com.pilot.agent.assistant.exec

import com.pilot.agent.assistant.routing.RouteDecision
import com.pilot.agent.tool.ToolResult
import com.pilot.agent.tool.ToolStatus
import org.json.JSONObject

/**
 * 快路径的结果格式化。
 *
 * 第一版不做模型总结（那会多一次往返，违背「直达」的初衷），
 * 所以这里要**自己把裸结果说人话**：成功就报做了什么 + 输出，
 * 失败就报真实错误，不伪装成功。
 *
 * 移植自 Minis 的 `AssistantFastPath.formatResult` / `LocalResultFormatter`。
 */
object ResultFormatter {

    /** 一次快路径回复。 */
    data class Reply(
        val text: String,
        val spoken: String,
    )

    /**
     * 给用户看的一句话摘要：**动作 + 关键参数**，不是用户原话的前 N 个字。
     *
     * 「打开设置」「读取 /sdcard/a.txt」「音量调到 60%」—— 用户一眼能看懂
     * 这次到底要干什么；「shell echo O…」这种截断看不出动作。
     */
    fun title(capability: String, action: String, args: JSONObject): String {
        val actionText = when (capability) {
            "app_launch" -> "打开"
            "volume" -> when (action) {
                "set" -> "音量设为"
                "adjust" -> "音量调整"
                else -> "音量"
            }
            "device" -> when (action) {
                "battery" -> "查询电量"
                "storage" -> "查询存储"
                else -> "查看设备"
            }
            "media" -> when (action) {
                "play" -> "播放"
                "pause" -> "暂停播放"
                "resume" -> "继续播放"
                "next" -> "下一首"
                "previous" -> "上一首"
                "stop" -> "停止播放"
                "status" -> "查看播放状态"
                else -> "播放列表"
            }
            "reminder" -> when (action) {
                "schedule" -> "创建提醒"
                "list" -> "查看提醒"
                else -> "取消提醒"
            }
            "scheduled_task" -> when (action) {
                "create" -> "创建定时任务"
                "list" -> "查看定时任务"
                else -> "取消定时任务"
            }
            "memory_write" -> "记住"
            "memory_get" -> "查记忆"
            "file_read" -> "读取"
            "file_write" -> "写入"
            "screenshot" -> "截屏"
            else -> "执行"
        }
        val key = args.optString("path").ifBlank {
            args.optString("app").ifBlank {
                args.optString("title").ifBlank {
                    args.optString("content").ifBlank {
                        args.optString("query").ifBlank {
                            args.optString("keywords").ifBlank {
                                args.optString("percent").ifBlank { "" }
                            }
                        }
                    }
                }
            }
        }
        val brief = key.trim().replace(Regex("\\s+"), " ").take(24)
        return if (brief.isEmpty()) actionText else "$actionText $brief"
    }

    /**
     * 把工具结果格式化成给用户看的一句话。
     *
     * 长了就截断 —— 快路径是「快速看一眼结果」，不是「拉一份报告」。
     */
    fun format(
        capability: String,
        action: String,
        args: JSONObject,
        result: ToolResult,
    ): Reply {
        val summary = title(capability, action, args)
        val header = if (result.status == ToolStatus.SUCCESS) "✅ $summary" else "⚠️ $summary 失败"
        val body = result.message.trim()
        val text = when {
            body.isEmpty() -> header
            body.length > MAX_RESULT_CHARS -> "$header\n\n${body.take(MAX_RESULT_CHARS)}\n…（已截断）"
            else -> "$header\n\n$body"
        }
        val spoken = when {
            result.spoken.isNotBlank() -> result.spoken.take(MAX_SPOKEN_CHARS)
            result.status == ToolStatus.SUCCESS -> summary
            else -> "$summary 失败：${result.message.take(80)}"
        }
        return Reply(text = text, spoken = spoken)
    }

    /** 快路径自己拒绝执行时的回复（例如安全闸拦下）。 */
    fun refusal(reason: String): Reply = Reply(
        text = "⚠️ 这条指令没有执行：$reason",
        spoken = "这条指令没有执行。",
    )

    /** 路由到本地能力时的内部描述，用于日志。 */
    fun describe(decision: RouteDecision.Local): String =
        "${decision.capability}/${decision.action}"

    private const val MAX_RESULT_CHARS = 1200
    private const val MAX_SPOKEN_CHARS = 600
}
