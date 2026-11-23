package com.pilot.agent.assistant.routing

/**
 * 一个能力的「执行权」归属。
 *
 * 快路径不是一次性替换遗留匹配器 —— 两边对同一句话给出不同处理时，
 * 用户会看到行为漂移。所以每个能力单独走三步：
 *
 * ```
 * LEGACY  → 今天的行为：遗留匹配器执行，快路径只旁观（不参与）
 * SHADOW  → 快路径决策并记轨迹，但**不执行**；遗留匹配器执行。
 *           轨迹里同时记 route 与 legacy 两侧，分歧 = 回归信号。
 * ROUTE   → 快路径执行；该能力的遗留匹配器停用。
 * ```
 *
 * 默认全部 [LEGACY]：打开总开关不改变任何行为，只有显式提升的能力才接管。
 */
enum class FastPathOwner {
    LEGACY,
    SHADOW,
    ROUTE,
}

/**
 * 默认归属。
 *
 * 提升顺序（风险从低到高）：device → memory_get → memory_write → screenshot →
 * file_* → scheduled_task → volume → media → reminder → app_launch。
 * 每提升一个都要先用 [FastPathOwner.SHADOW] 在真机对照 10 句语料
 * （5 正 + 5 反），分歧为 0 再切 [FastPathOwner.ROUTE]。
 *
 * 现状（2026-11-18 真机影子对照后）：v1 的 11 个能力**全部**已提升为 [ROUTE]。
 * 最后一批（app_launch / volume / media / reminder）在遗留链路里匹配器不接、
 * 由下游规划器处理，提升等于换执行路径 —— 评估要点见文档 §17。
 *
 * 以后新增能力默认仍是 [LEGACY]：新能力没做过影子对照，不该自动接管。
 *
 * 注意：这份默认值只决定**新装**的归属。已经装过的机器以
 * `files/assistant/routes.json` 里存的为准（设置页或广播改过就保留），
 * 所以升级后老机器不会自动接管。
 */
object DefaultOwners {
    /** 已经过真机影子对照、默认由快路径接管的能力。 */
    val promoted: Set<String> = setOf(
        "device",
        "memory_get",
        "memory_write",
        "screenshot",
        "file_read",
        "file_write",
        "scheduled_task",
        "volume",
        "media",
        "reminder",
        "app_launch",
    )

    val all: Map<String, FastPathOwner> =
        CapabilityRegistry.all.associate {
            it.id to if (it.id in promoted) FastPathOwner.ROUTE else FastPathOwner.LEGACY
        }

    /** 解析存储值；未知值按 LEGACY（宁可不动行为）。 */
    fun parse(raw: String?): FastPathOwner = when (raw?.trim()?.uppercase()) {
        "ROUTE" -> FastPathOwner.ROUTE
        "SHADOW" -> FastPathOwner.SHADOW
        else -> FastPathOwner.LEGACY
    }
}
