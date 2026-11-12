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
}

data class ToolResult(
    val status: ToolStatus,
    val message: String,
    val spoken: String = message,
    val activity: Activity? = null,
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
