package com.pilot.agent.tool

import android.app.Activity
import android.content.Context
import com.pilot.agent.agent.Risk

/** User-facing permission groups. Tools inherit the policy of their group. */
enum class ToolGroup(val label: String, val defaultPolicy: ToolPolicy) {
    READ("读取与查询", ToolPolicy.ALLOW),
    WRITE("创建与修改", ToolPolicy.ASK),
    SHARE("打开与外发", ToolPolicy.ASK),
    DELETE("删除", ToolPolicy.ASK),
}

enum class ToolPolicy(val label: String) {
    ALLOW("允许"),
    ASK("每次询问"),
    DENY("禁止"),
}

enum class ToolStatus {
    SUCCESS,
    DENIED,
    UNAVAILABLE,
    ERROR,
    /**
     * 已发出动作，但没能确认它真的生效（例如 startActivity 没抛异常、前台却没切过去）。
     * 文案必须如实说“未确认”，不能当成功播报。
     */
    UNKNOWN,
}

data class ToolResult(
    val status: ToolStatus,
    val message: String,
    val spoken: String = message,
    val activity: Activity? = null,
    /**
     * 结果含个人数据（通知、记忆、提醒、待办、文件内容）：只能用本机 TTS 播报，
     * 不得交给云端 TTS；锁屏时不得读出。
     */
    val privateSpeech: Boolean = false,
) {
    companion object {
        fun success(message: String, spoken: String = message) =
            ToolResult(ToolStatus.SUCCESS, message, spoken)

        fun denied(message: String) =
            ToolResult(ToolStatus.DENIED, message, message)

        fun unavailable(message: String) =
            ToolResult(ToolStatus.UNAVAILABLE, message, message)

        fun error(message: String) =
            ToolResult(ToolStatus.ERROR, message, message)

        fun unknown(message: String, spoken: String = message) =
            ToolResult(ToolStatus.UNKNOWN, message, spoken)
    }
}

data class ToolInvocation(
    val name: String,
    val group: ToolGroup,
    val summary: String,
    val confirmation: String,
    val explicitAuthorized: Boolean = false,
    val execute: suspend (Context, ToolConfirmer) -> ToolResult,
) {
    fun risk(): Risk = when (group) {
        ToolGroup.READ -> Risk.NONE
        ToolGroup.WRITE, ToolGroup.DELETE -> Risk.IRREVERSIBLE
        ToolGroup.SHARE -> Risk.EXTERNAL
    }
}

fun interface ToolConfirmer {
    suspend fun confirm(question: String, risk: Risk): Boolean
}

/** Used by the UI to show a useful, content-free audit trail. */
data class ToolAuditEntry(
    val timestamp: Long,
    val tool: String,
    val group: ToolGroup,
    val decision: String,
    val result: ToolStatus,
)
