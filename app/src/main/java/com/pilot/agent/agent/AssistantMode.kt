package com.pilot.agent.agent

/**
 * User-selected operating mode.
 *
 * The modes describe who owns a request:
 * - ASSISTANT: local rules, local tools and chat, selected automatically.
 * - AGENT: multi-step execution with browser, MCP and script extensions.
 */
enum class AssistantMode(
    val storageValue: String,
    val label: String,
    val description: String,
) {
    ASSISTANT("assistant", "助理", "自动区分规则、本机工具与聊天"),
    AGENT("agent", "Agent", "多步执行、浏览器、脚本与 MCP"),
    ;

    companion object {
        fun fromStorage(value: String?): AssistantMode = when (value) {
            // Migrate the temporary four-mode build and the earlier explicit
            // "Agent" setting without exposing internal legacy modes again.
            "agent", "cloud_agent" -> AGENT
            else -> ASSISTANT
        }
    }
}
