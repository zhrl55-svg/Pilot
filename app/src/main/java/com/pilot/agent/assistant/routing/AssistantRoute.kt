package com.pilot.agent.assistant.routing

import org.json.JSONArray
import org.json.JSONObject

/**
 * 助理快路径的声明式模型。
 *
 * 移植自 Minis（`com.openminis.app.assistant.routing.AssistantRoute`），语义保持一致：
 * 路由是**可编辑的小对象**——能在设置里改、能导出成 JSON、能在不重新打包的情况下
 * 改变行为。
 */
enum class ConfirmMode { NEVER, WRITE, ALWAYS }

/**
 * 一条用户可配置的指令路由。
 */
data class AssistantRoute(
    val id: String,
    val label: String,
    val enabled: Boolean = true,
    val priority: Int = 100,
    val category: String = "custom",
    val capability: String,
    val action: String,
    val keywords: List<String> = emptyList(),
    val allKeywords: List<String> = emptyList(),
    val patterns: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
    val examples: List<String> = emptyList(),
    val confirm: ConfirmMode = ConfirmMode.WRITE,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("label", label)
        put("enabled", enabled)
        put("priority", priority)
        put("category", category)
        put("capability", capability)
        put("action", action)
        put("keywords", JSONArray(keywords))
        put("allKeywords", JSONArray(allKeywords))
        put("patterns", JSONArray(patterns))
        put("exclude", JSONArray(exclude))
        put("examples", JSONArray(examples))
        put("confirm", confirm.name)
    }

    companion object {
        fun fromJson(o: JSONObject): AssistantRoute = AssistantRoute(
            id = o.optString("id").trim(),
            label = o.optString("label", o.optString("id")).trim(),
            enabled = o.optBoolean("enabled", true),
            priority = o.optInt("priority", 100),
            category = o.optString("category", "custom").ifBlank { "custom" },
            capability = o.optString("capability").trim(),
            action = o.optString("action").trim(),
            keywords = o.optJSONArray("keywords").stringList(),
            allKeywords = o.optJSONArray("allKeywords").stringList(),
            patterns = o.optJSONArray("patterns").stringList(),
            exclude = o.optJSONArray("exclude").stringList(),
            examples = o.optJSONArray("examples").stringList(),
            confirm = runCatching {
                ConfirmMode.valueOf(o.optString("confirm", ConfirmMode.WRITE.name).uppercase())
            }.getOrDefault(ConfirmMode.WRITE),
        )

        private fun JSONArray?.stringList(): List<String> {
            if (this == null) return emptyList()
            return buildList {
                for (i in 0 until length()) {
                    val s = optString(i).trim()
                    if (s.isNotEmpty()) add(s)
                }
            }
        }
    }
}

/** 槽位类型：决定抽取方式与校验方式。 */
enum class SlotType {
    TEXT,
    VERBATIM_TEXT,
    PATH,
    URL,
    TIME,
    DURATION,
    ENUM,
    NUMBER,
    QUERY,
}

data class SlotSpec(
    val name: String,
    val type: SlotType,
    val required: Boolean = true,
    val enumValues: List<String> = emptyList(),
)

/**
 * 破坏性分级。
 *
 * - [LOW]：瞬时可撤销的状态变化（调音量、暂停播放）。
 * - [MEDIUM]：普通写操作（新建提醒、记一条记忆）。
 * - [HIGH]：破坏性操作（覆盖文件、删除、跑命令、改系统设置）。
 */
enum class CapabilityRisk { LOW, MEDIUM, HIGH }

data class CapabilityAction(
    val name: String,
    val readOnly: Boolean,
    val risk: CapabilityRisk = if (readOnly) CapabilityRisk.LOW else CapabilityRisk.MEDIUM,
    val slots: List<SlotSpec> = emptyList(),
) {
    val requiredSlots: Set<String>
        get() = slots.filter { it.required }.mapTo(linkedSetOf()) { it.name }
}

data class LocalCapability(
    val id: String,
    val label: String,
    val actions: Map<String, CapabilityAction>,
    /** True when free-text slots may use the optional on-device model. */
    val needsLlm: Boolean = false,
) {
    fun action(name: String): CapabilityAction? = actions[name]
}
