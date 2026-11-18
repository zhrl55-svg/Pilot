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
 * file_* → scheduled_task。每提升一个都要先用 [FastPathOwner.SHADOW] 在真机
 * 对照 10 句语料（5 正 + 5 反），分歧为 0 再切 [FastPathOwner.ROUTE]。
 *
 * 现状（2026-11-18 真机影子对照后）：device / memory_get / memory_write /
 * screenshot / file_read / file_write / scheduled_task 已提升为 [ROUTE]；
 * app_launch / volume / media / reminder 仍留 [LEGACY] —— 这四类在遗留链路里
 * 匹配器不接、由下游规划器处理，提升等于换执行路径，得单独评估。
 *
 * 注意：这份默认值只决定**新装**的归属。已经装过的机器以
 * `files/assistant/routes.json` 里存的为准（设置页或广播改过就保留），
 * 所以升级后老机器不会自动接管。
 */
object DefaultOwners {
    /** 已经过真机影子对照、默认由快路径接管的能力。 */
    private val PROMOTED = setOf(
        "device",
        "memory_get",
        "memory_write",
        "screenshot",
        "file_read",
        "file_write",
        "scheduled_task",
    )

    val all: Map<String, FastPathOwner> =
        CapabilityRegistry.all.associate {
            it.id to if (it.id in PROMOTED) FastPathOwner.ROUTE else FastPathOwner.LEGACY
        }

    /** 解析存储值；未知值按 LEGACY（宁可不动行为）。 */
    fun parse(raw: String?): FastPathOwner = when (raw?.trim()?.uppercase()) {
        "ROUTE" -> FastPathOwner.ROUTE
        "SHADOW" -> FastPathOwner.SHADOW
        else -> FastPathOwner.LEGACY
    }
}
