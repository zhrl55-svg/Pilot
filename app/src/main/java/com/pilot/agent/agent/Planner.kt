package com.pilot.agent.agent

import com.pilot.agent.perception.ScreenSnapshot

data class AgentRequest(
    val goal: String,
    val history: List<String>,
    val snapshot: ScreenSnapshot,
    val stepIndex: Int = 0,
    val skillHint: String? = null,
    /**
     * 本轮之前的对话（问题→回答）。
     *
     * 执行路径原来只拿到 goal，模型判「执行」后「刚才那个」「换成第二个」
     * 这类指代全部断裂；由 AgentLoop.start 透传，云端规划器据此理解指代。
     */
    val chatHistory: List<Pair<String, String>> = emptyList(),
)

data class AgentDecision(
    val thought: String,
    val action: AgentAction,
)

interface Planner {
    val id: String

    suspend fun decide(req: AgentRequest): AgentDecision

    class Unavailable(message: String) : IllegalStateException(message)
}

sealed interface AgentEvent {
    data class Thinking(val text: String) : AgentEvent
    data class Acting(val text: String) : AgentEvent
    data class Observe(val text: String) : AgentEvent
    data class NeedConfirm(val requestId: Long, val question: String, val risk: Risk) : AgentEvent
    data class Finished(val summary: String) : AgentEvent
    data class Failed(val reason: String) : AgentEvent
    data class Progress(val step: Int, val maxSteps: Int) : AgentEvent
}
