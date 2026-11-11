package com.pilot.agent.action

import com.pilot.agent.query.SecretVault
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Small private todo list for quick voice capture; encrypted at rest and kept on-device. */
object PersonalTodoStore {
    private const val KEY = "personal_todo_items"
    data class Todo(val id: String, val text: String, val created: Long, val done: Boolean)

    fun handles(text: String): Boolean = listOf("我的待办", "添加待办", "新增待办", "记一条待办", "完成待办", "删除待办", "清空已完成").any(text::contains)

    fun handle(text: String): String {
        when {
            text.contains("我的待办") -> {
                val active = all().filterNot { it.done }.sortedByDescending { it.created }
                return if (active.isEmpty()) "待办清单是空的。" else buildString {
                    append("你的待办：")
                    active.take(20).forEachIndexed { i, todo -> append("\n${i + 1}. ").append(todo.text) }
                }
            }
            text.contains("清空已完成") -> {
                val all = all(); save(all.filterNot { it.done }); return "已清理 ${all.count { it.done }} 条已完成待办。"
            }
            text.contains("添加待办") || text.contains("新增待办") || text.contains("记一条待办") -> {
                val title = text.substringAfter("待办").trim(' ', '：', ':', '，', ',', '。', '.', '！', '!')
                if (title.isBlank()) return "要添加什么待办？例如“添加待办买牛奶”。"
                if (title.length > 160) return "待办内容太长，请缩短到 160 字以内。"
                val list = all()
                if (list.any { !it.done && it.text.equals(title, true) }) return "这条待办已经在清单里了：$title"
                save(list + Todo(UUID.randomUUID().toString(), title, System.currentTimeMillis(), false))
                return "已添加待办：$title"
            }
            text.contains("完成待办") -> return updateByText(text.substringAfter("完成待办"), true)
            text.contains("删除待办") -> return deleteByText(text.substringAfter("删除待办"))
            else -> return ""
        }
    }

    private fun updateByText(raw: String, done: Boolean): String {
        val key = raw.trim(' ', '：', ':', '，', ',', '。', '.', '！', '!')
        val list = all().toMutableList()
        val index = list.indexOfFirst { key.isBlank() || it.text.contains(key, true) }
        if (index < 0) return "没找到匹配的待办。"
        list[index] = list[index].copy(done = done)
        save(list)
        return "已标记完成：${list[index].text}"
    }

    private fun deleteByText(raw: String): String {
        val key = raw.trim(' ', '：', ':', '，', ',', '。', '.', '！', '!')
        val list = all()
        val item = list.firstOrNull { key.isNotBlank() && it.text.contains(key, true) }
            ?: return "请说出要删除的待办内容；说“我的待办”可以查看清单。"
        save(list.filterNot { it.id == item.id })
        return "已删除待办：${item.text}"
    }

    private fun all(): List<Todo> = runCatching {
        val json = JSONArray(SecretVault.get(KEY).ifBlank { "[]" })
        (0 until json.length()).mapNotNull { i ->
            val o = json.optJSONObject(i) ?: return@mapNotNull null
            Todo(o.optString("id"), o.optString("text"), o.optLong("created"), o.optBoolean("done"))
        }
    }.getOrDefault(emptyList())

    private fun save(items: List<Todo>) {
        val json = JSONArray()
        items.takeLast(500).forEach { json.put(JSONObject().put("id", it.id).put("text", it.text).put("created", it.created).put("done", it.done)) }
        SecretVault.put(KEY, json.toString())
    }
}
