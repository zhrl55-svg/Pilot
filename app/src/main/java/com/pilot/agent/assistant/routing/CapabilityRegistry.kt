package com.pilot.agent.assistant.routing

/**
 * 本地能力面：快路径能直接执行的技能集合。
 *
 * 移植自 Minis 的 `LocalCapabilityRegistry`，但按 Pilot 的执行器裁剪：
 * 这里声明的每一个 capability/action，都必须能在 Pilot 里找到对应的执行实现
 * （见 `docs/ASSISTANT-FASTPATH-PORT.md` §2.2）。
 *
 * 与云端工具表（AgentTools）刻意分开：这些能力**只在本地执行**，不进入云端
 * Agent 的工具 schema。
 */
object CapabilityRegistry {

    private fun slot(
        name: String,
        type: SlotType,
        required: Boolean = true,
        enumValues: List<String> = emptyList(),
    ) = SlotSpec(name, type, required, enumValues)

    private fun action(
        name: String,
        readOnly: Boolean,
        risk: CapabilityRisk = if (readOnly) CapabilityRisk.LOW else CapabilityRisk.MEDIUM,
        vararg slots: SlotSpec,
    ) = CapabilityAction(name, readOnly, risk, slots.toList())

    private val capabilities: Map<String, LocalCapability> = listOf(
        LocalCapability(
            "app_launch", "打开应用",
            mapOf(
                "open" to action(
                    "open", false, CapabilityRisk.LOW,
                    slot("app", SlotType.VERBATIM_TEXT),
                ),
            ),
        ),
        LocalCapability(
            "volume", "音量",
            mapOf(
                // 音量是瞬时可撤销的状态变化：LOW 风险，不弹确认。
                "set" to action(
                    "set", false, CapabilityRisk.LOW,
                    slot("percent", SlotType.NUMBER),
                    slot("stream", SlotType.ENUM, required = false),
                ),
                "adjust" to action(
                    "adjust", false, CapabilityRisk.LOW,
                    slot("percent", SlotType.NUMBER),
                    slot("stream", SlotType.ENUM, required = false),
                ),
                "status" to action(
                    "status", true,
                    slots = arrayOf(slot("stream", SlotType.ENUM, required = false)),
                ),
            ),
        ),
        LocalCapability(
            "device", "设备状态",
            mapOf(
                "battery" to action("battery", true),
                "storage" to action("storage", true),
                "info" to action("info", true),
            ),
        ),
        LocalCapability(
            "media", "媒体控制",
            mapOf(
                // kind 是语义维度（歌/歌手/流派/场景/榜单），抽取层判定，执行层只消费。
                "play" to action(
                    "play", false, CapabilityRisk.LOW,
                    slot(
                        "kind", SlotType.ENUM,
                        enumValues = listOf("song", "artist", "genre", "scene", "chart", "none"),
                    ),
                    slot("query", SlotType.QUERY, required = false),
                    slot("artist", SlotType.QUERY, required = false),
                    slot(
                        "chart", SlotType.ENUM, required = false,
                        enumValues = listOf("hot", "new", "rising", "recent", "daily", "liked"),
                    ),
                ),
                "pause" to action("pause", false, CapabilityRisk.LOW),
                "resume" to action("resume", false, CapabilityRisk.LOW),
                "next" to action("next", false, CapabilityRisk.LOW),
                "previous" to action("previous", false, CapabilityRisk.LOW),
                "stop" to action("stop", false, CapabilityRisk.LOW),
                "status" to action("status", true),
                "list" to action("list", true),
            ),
            needsLlm = true,
        ),
        LocalCapability(
            "reminder", "提醒",
            mapOf(
                "schedule" to action(
                    "schedule", false, CapabilityRisk.MEDIUM,
                    slot("title", SlotType.VERBATIM_TEXT),
                    slot("at", SlotType.TIME, required = false),
                    slot("after", SlotType.DURATION, required = false),
                ),
                "list" to action("list", true),
                "cancel" to action(
                    "cancel", false, CapabilityRisk.HIGH,
                    slot("id", SlotType.VERBATIM_TEXT, required = false),
                ),
            ),
        ),
        LocalCapability(
            "scheduled_task", "定时任务",
            mapOf(
                "create" to action(
                    "create", false, CapabilityRisk.MEDIUM,
                    slot("prompt", SlotType.VERBATIM_TEXT),
                    slot("at", SlotType.TIME, required = false),
                    slot("after", SlotType.DURATION, required = false),
                ),
                "list" to action("list", true),
                "cancel" to action(
                    "cancel", false, CapabilityRisk.HIGH,
                    slot("id", SlotType.VERBATIM_TEXT, required = false),
                ),
            ),
        ),
        LocalCapability(
            "memory_write", "保存记忆",
            mapOf(
                "write" to action(
                    "write", false, CapabilityRisk.MEDIUM,
                    slot("content", SlotType.VERBATIM_TEXT),
                ),
            ),
        ),
        LocalCapability(
            "memory_get", "查记忆",
            mapOf(
                "search" to action(
                    "search", true,
                    slots = arrayOf(slot("keywords", SlotType.QUERY, required = false)),
                ),
                // 「你记得什么」问的是「全部」，不是「搜某个词」。
                // 遗留链路有独立的 memory_list 工具；快路径补上同样的动作，
                // 否则这句话会被当成搜索（keywords="什么"），结果和遗留不一致。
                "list" to action("list", true),
            ),
        ),
        LocalCapability(
            "file_read", "读文件",
            mapOf(
                "read" to action("read", true, slots = arrayOf(slot("path", SlotType.PATH))),
            ),
        ),
        LocalCapability(
            // 覆盖已有文件是破坏性操作：HIGH 风险，路由层就要确认。
            "file_write", "写文件",
            mapOf(
                "write" to action(
                    "write", false, CapabilityRisk.HIGH,
                    slot("path", SlotType.PATH), slot("content", SlotType.TEXT),
                ),
            ),
            needsLlm = true,
        ),
        LocalCapability(
            "screenshot", "截屏",
            mapOf("take" to action("take", false, CapabilityRisk.LOW)),
        ),
    ).associateBy { it.id }

    val all: List<LocalCapability> get() = capabilities.values.toList()

    fun get(id: String): LocalCapability? = capabilities[id]

    fun supports(capability: String, action: String): Boolean =
        capabilities[capability]?.action(action) != null

    fun action(capability: String, action: String): CapabilityAction? =
        capabilities[capability]?.action(action)

    fun isReadOnly(capability: String, action: String): Boolean =
        this.action(capability, action)?.readOnly ?: false

    fun isHighRisk(capability: String, action: String): Boolean =
        this.action(capability, action)?.risk == CapabilityRisk.HIGH
}
