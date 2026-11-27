package com.pilot.agent.agent

import com.pilot.agent.Dbg

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 技能库里的一条步骤。注意存的是**语义定位**（文本 / viewId）而不是节点 id——
 * 节点 id 是快照内的临时索引，下次运行必然失效。
 */
@Serializable
data class SkillStep(
    val action: String,
    val label: String = "",
    val value: String = "",
)

@Serializable
data class Skill(
    val id: String,
    val goal: String,
    /** 落点 App（完成时前台的包）。旧版本只记了这一个。 */
    val appPkg: String = "",
    /** 目标的完整 bigram 集合（仅供展示/调试；匹配时按 goal 重新计算，见审核 S-03）。 */
    val keywords: List<String> = emptyList(),
    val steps: List<SkillStep> = emptyList(),
    val successes: Int = 1,
    val updatedAt: Long = 0L,
    /** 起点 App（任务开始时前台的包）。回放前若前台不是它且首步不是 open，会先补一步 open（审核 S-04）。 */
    val originPkg: String = "",
    /** 完成时界面的关键文字（3~5 个），回放结束后比对，不一致就交回模型（审核 S-06）。 */
    val finalSignature: List<String> = emptyList(),
    /** 连续回放失败次数；达到 [SkillStore.MAX_FAILURES] 即淘汰（审核 S-05）。 */
    val failures: Int = 0,
    val lastFailAt: Long = 0L,
)

/**
 * 技能库：把成功轨迹沉淀成可复用脚本。
 *
 * 第二次做同一件事不用再让模型重新推理，直接回放确定性步骤；回放失败就退回模型规划。
 *
 * ## 2026-11-27 修订（审核 S-01~S-07）
 * - 匹配门槛提高：同 App ≥ 0.8、跨 App ≥ 0.9（规范化后完全一致直接 1.0）；
 *   **槽位不一致直接拒绝**：打开/关闭、调高/调低、播放/暂停、添加/删除、上一/下一等反义动词，以及数字。
 *   「打开深色模式」不会再命中「关闭深色模式」的技能。
 * - 含资金/删除/发送等风险词的目标或步骤**不沉淀**（回放时 AgentLoop 仍逐步过 Guard）。
 * - 失败计数：连续失败 [MAX_FAILURES] 次删除；成功回放清零。
 * - 内存缓存 + 写穿，所有读写加锁（旧版每次 match 都读盘解析 JSON 且不加锁）。
 */
class SkillStore(private val dir: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val file: File get() = File(dir, "skills.json")

    /** 内存缓存；null 表示尚未从磁盘加载。 */
    private var cache: List<Skill>? = null

    init {
        migrate()
        // 应用加载技能库时即迁移清理旧版含输入值 / 风险步骤的记录。
        all()
    }

    /**
     * 技能库版本迁移。
     *
     * v1 → v2（2026-11-11）：语义变了。「打开X」以前记的是"打开这一项的设置页"，
     * 现在要求"开关真的拨到位"；而这些旧轨迹会**短路新逻辑**——
     * 回放一步"打开蓝牙设置页"就宣告完成，用户看到的还是没打开。
     * 旧轨迹一律作废（技能重学一次很便宜，回放错一次很贵）。
     */
    private fun migrate() {
        runCatching {
            val marker = File(dir, "skills.v2")
            if (marker.exists()) return
            dir.mkdirs()
            val old = file
            if (old.exists()) {
                Dbg.i("Pilot", "技能库迁移 v1→v2：作废 ${old.length()} 字节旧轨迹")
                old.delete()
            }
            marker.writeText("2")
        }
    }

    @Synchronized
    fun all(): List<Skill> {
        cache?.let { return it }
        val loaded = runCatching {
            if (!file.exists()) {
                emptyList()
            } else {
                val decoded = json.decodeFromString<List<Skill>>(file.readText())
                // 清理旧版本可能保存的密码、验证码或消息正文，以及含风险步骤的轨迹，禁止继续回放或留在磁盘。
                val safe = decoded.filter { isSafeToKeep(it.goal, it.steps) }
                if (safe.size != decoded.size) persist(safe)
                safe
            }
        }.getOrDefault(emptyList())
        cache = loaded
        return loaded
    }

    private fun persist(list: List<Skill>) {
        cache = list
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "skills.json.tmp")
            tmp.writeText(json.encodeToString(list))
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    /**
     * 找可回放的技能。
     * @param pkg 当前前台包；与技能的起点或落点包一致时门槛略低。
     */
    @Synchronized
    fun match(goal: String, pkg: String): Skill? {
        val norm = normalize(goal)
        if (norm.length < 2) return null
        val g = grams(norm)
        val slots = slotsOf(goal)
        return all()
            .asSequence()
            .filter { it.steps.isNotEmpty() && it.failures < MAX_FAILURES }
            .filter { slotsOf(it.goal) == slots }
            .map { it to score(norm, g, it, pkg) }
            .filter { (skill, s) ->
                val sameApp = pkg.isNotEmpty() && (pkg == skill.originPkg || pkg == skill.appPkg)
                s >= if (sameApp) SAME_APP_THRESHOLD else CROSS_APP_THRESHOLD
            }
            .maxByOrNull { it.second }
            ?.first
    }

    /**
     * 沉淀一条成功轨迹。目标或任一步骤含风险词（支付/删除/发送…）时不记录，返回 false。
     *
     * @param originPkg 任务开始时的前台包
     * @param landingPkg 完成时的前台包
     * @param finalSignature 完成界面的关键文字
     */
    @Synchronized
    fun recordSuccess(
        goal: String,
        originPkg: String,
        landingPkg: String,
        steps: List<SkillStep>,
        finalSignature: List<String> = emptyList(),
    ): Boolean {
        if (steps.isEmpty()) return false
        if (!isSafeToKeep(goal, steps)) {
            Dbg.i("Pilot", "技能不沉淀：目标或步骤含资金/删除/对外发送类操作")
            return false
        }
        val list = all().toMutableList()
        val norm = normalize(goal)
        val idx = list.indexOfFirst { normalize(it.goal) == norm && it.appPkg == landingPkg }
        val now = System.currentTimeMillis()
        if (idx >= 0) {
            val old = list[idx]
            list[idx] = old.copy(
                steps = steps,
                successes = old.successes + 1,
                updatedAt = now,
                originPkg = originPkg,
                finalSignature = finalSignature,
                failures = 0,
                keywords = grams(norm).sorted(),
            )
        } else {
            list += Skill(
                id = "sk_$now",
                goal = goal,
                appPkg = landingPkg,
                keywords = grams(norm).sorted(),
                steps = steps,
                updatedAt = now,
                originPkg = originPkg,
                finalSignature = finalSignature,
            )
        }
        // 只留最近 200 条，避免无限膨胀
        persist(list.sortedByDescending { it.updatedAt }.take(200))
        return true
    }

    /** 回放成功：计数 +1，失败计数清零。 */
    @Synchronized
    fun recordHit(id: String) {
        val list = all().toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(successes = list[idx].successes + 1, failures = 0)
            persist(list)
        }
    }

    /** 回放失败：连续失败 [MAX_FAILURES] 次即删除。 */
    @Synchronized
    fun recordFailure(id: String) {
        val list = all().toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return
        val s = list[idx]
        val failures = s.failures + 1
        if (failures >= MAX_FAILURES) {
            Dbg.i("Pilot", "技能「${s.id}」连续回放失败 $failures 次，已淘汰")
            list.removeAt(idx)
        } else {
            list[idx] = s.copy(failures = failures, lastFailAt = System.currentTimeMillis())
        }
        persist(list)
    }

    @Synchronized
    fun delete(id: String) {
        persist(all().filterNot { it.id == id })
    }

    @Synchronized
    fun clear() {
        cache = emptyList()
        runCatching { file.delete() }
    }

    private fun isSafeToKeep(goal: String, steps: List<SkillStep>): Boolean {
        if (steps.any { it.action == "input" }) return false
        if (Guard.textRisk(goal) != Risk.NONE) return false
        return steps.none { Guard.textRisk(it.label) != Risk.NONE }
    }

    private fun score(norm: String, goalGrams: Set<String>, skill: Skill, pkg: String): Double {
        val skillNorm = normalize(skill.goal)
        if (skillNorm == norm) return 1.0
        if (goalGrams.isEmpty()) return 0.0
        val skillGrams = grams(skillNorm)
        val union = goalGrams.union(skillGrams).size
        if (union == 0) return 0.0
        return goalGrams.intersect(skillGrams).size.toDouble() / union
    }

    /** 槽位：反义动词的极性 + 数字。任何一项不同就不能复用同一条轨迹。 */
    private data class Slots(val polarity: Set<String>, val numbers: List<String>)

    private fun slotsOf(goal: String): Slots {
        val g = goal.lowercase()
        val polarity = HashSet<String>()
        for ((name, words) in POLARITY) {
            if (words.any { g.contains(it) }) polarity += name
        }
        val cleaned = FILLERS.fold(g) { acc, w -> acc.replace(w, "") }
        val numbers = NUMBER.findAll(cleaned).map { it.value }.toList()
        return Slots(polarity, numbers)
    }

    /** 去掉客套词、标点、空白，统一小写。 */
    private fun normalize(s: String): String {
        val lower = s.lowercase()
        val noFiller = FILLERS.fold(lower) { acc, w -> acc.replace(w, "") }
        return noFiller.filter { it.isLetterOrDigit() }
    }

    /** 中文没有空格，用字符 bigram 当伪分词，够用且不需要词典。 */
    private fun grams(clean: String): Set<String> {
        if (clean.length < 2) return if (clean.isEmpty()) emptySet() else setOf(clean)
        return (0..clean.length - 2).mapTo(HashSet()) { clean.substring(it, it + 2) }
    }

    companion object {
        const val MAX_FAILURES = 2
        private const val SAME_APP_THRESHOLD = 0.8
        private const val CROSS_APP_THRESHOLD = 0.9

        private val FILLERS = listOf(
            "帮我", "麻烦", "请你", "请", "给我", "一下", "能不能", "可以", "可不可以", "我想", "我要",
            "吧", "呢", "啊", "呀", "哦", "嘛",
        )

        /** 反义动词组：同组内不同极性 = 不同意图。 */
        private val POLARITY: List<Pair<String, List<String>>> = listOf(
            "open" to listOf("打开", "开启", "启用", "打開", "開啟", "turn on", "enable"),
            "close" to listOf("关闭", "关掉", "关上", "停用", "禁用", "关了", "關閉", "turn off", "disable"),
            "up" to listOf("调高", "调大", "增大", "提高", "加大", "升高", "大一点", "高一点", "亮一点", "大点"),
            "down" to listOf("调低", "调小", "降低", "减小", "减少", "小一点", "低一点", "暗一点", "小点"),
            "play" to listOf("播放", "继续播放", "放一首"),
            "pause" to listOf("暂停", "停止", "停播"),
            "add" to listOf("添加", "新增", "新建", "创建", "加入", "收藏"),
            "del" to listOf("删除", "移除", "清空", "取消"),
            "next" to listOf("下一"),
            "prev" to listOf("上一"),
        )

        private val NUMBER = Regex("[0-9]+(?:\\.[0-9]+)?|[零〇一二两三四五六七八九十百千万]+")
    }
}
