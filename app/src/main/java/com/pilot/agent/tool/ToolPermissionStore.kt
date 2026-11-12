package com.pilot.agent.tool

import android.content.Context

/**
 * Stores only permission choices. Tool inputs and outputs stay out of this store.
 *
 * Defaults intentionally favor useful read-only assistance while requiring a
 * fresh confirmation for mutations and external sharing.
 */
class ToolPermissionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun policyFor(group: ToolGroup): ToolPolicy {
        val raw = prefs.getString(group.name, null) ?: return group.defaultPolicy
        return runCatching { ToolPolicy.valueOf(raw) }.getOrDefault(group.defaultPolicy)
    }

    fun setPolicy(group: ToolGroup, policy: ToolPolicy) {
        prefs.edit().putString(group.name, policy.name).apply()
    }

    fun all(): Map<ToolGroup, ToolPolicy> =
        ToolGroup.entries.associateWith(::policyFor)

    companion object {
        private const val PREFS = "pilot_tool_policy_v1"
    }
}
