package com.pilot.agent.agent

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
    val appPkg: String = "",
    val keywords: List<String> = emptyList(),
    val steps: List<SkillStep> = emptyList(),
    val successes: Int = 1,
    val updatedAt: Long = 0L,
)

/**
 * 技能库：把成功轨迹沉淀成可复用脚本。
 *
 * 这是把「通用 GUI agent 66% 成功率」拉到 90%+ 的工程关键——
 * 第二次做同一件事不用再让模型重新推理，直接回放确定性步骤。
 * 回放失败就退回模型规划，不会卡死。
 */
class SkillStore(private val dir: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    private val file: File get() = File(dir, "skills.json")

    fun all(): List<Skill> = runCatching {
        if (!file.exists()) emptyList()
        else json.decodeFromString<List<Skill>>(file.readText())
    }.getOrDefault(emptyList())

    private fun persist(list: List<Skill>) {
        runCatching {
            dir.mkdirs()
            file.writeText(json.encodeToString(list))
        }
    }

    fun match(goal: String, pkg: String): Skill? {
        val g = grams(goal)
        return all()
            .filter { it.steps.isNotEmpty() }
            .map { it to score(g, it, pkg) }
            .filter { it.second >= 0.55 }
            .maxByOrNull { it.second }
            ?.first
    }

    fun recordSuccess(goal: String, pkg: String, steps: List<SkillStep>) {
        if (steps.isEmpty()) return
        val list = all().toMutableList()
        val idx = list.indexOfFirst { it.goal == goal && it.appPkg == pkg }
        if (idx >= 0) {
            val old = list[idx]
            list[idx] = old.copy(
                steps = steps,
                successes = old.successes + 1,
                updatedAt = System.currentTimeMillis(),
            )
        } else {
            list += Skill(
                id = "sk_" + System.currentTimeMillis(),
                goal = goal,
                appPkg = pkg,
                keywords = grams(goal).take(12).toList(),
                steps = steps,
                updatedAt = System.currentTimeMillis(),
            )
        }
        // 只留最近 200 条，避免无限膨胀
        persist(list.sortedByDescending { it.updatedAt }.take(200))
    }

    fun recordHit(id: String) {
        val list = all().toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = list[idx].copy(successes = list[idx].successes + 1)
            persist(list)
        }
    }

    fun delete(id: String) {
        persist(all().filterNot { it.id == id })
    }

    fun clear() {
        runCatching { file.delete() }
    }

    private fun score(goalGrams: Set<String>, skill: Skill, pkg: String): Double {
        if (goalGrams.isEmpty()) return 0.0
        val skillGrams = if (skill.keywords.isNotEmpty()) skill.keywords.toSet() else grams(skill.goal)
        val inter = goalGrams.intersect(skillGrams).size
        val union = goalGrams.union(skillGrams).size
        if (union == 0) return 0.0
        val jaccard = inter.toDouble() / union
        val sameApp = skill.appPkg.isNotEmpty() && skill.appPkg == pkg
        return jaccard * if (sameApp) 1.0 else 0.82
    }

    /** 中文没有空格，用字符 bigram 当伪分词，够用且不需要词典。 */
    private fun grams(s: String): Set<String> {
        val clean = s.filter { it.isLetterOrDigit() }
        if (clean.length < 2) return setOf(clean)
        return (0..clean.length - 2).mapTo(HashSet()) { clean.substring(it, it + 2) }
    }
}
