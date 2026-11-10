package com.pilot.agent.agent

import com.pilot.agent.perception.ScreenSnapshot

data class AgentRequest(
    val goal: String,
    val history: List<String>,
    val snapshot: ScreenSnapshot,
    val stepIndex: Int = 0,
    val skillHint: String? = null,
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
