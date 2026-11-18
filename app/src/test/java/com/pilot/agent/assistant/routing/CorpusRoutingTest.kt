package com.pilot.agent.assistant.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语料驱动的路由验收。
 *
 * 语料文件：`app/src/test/resources/assistant-fastpath/corpus.tsv`
 *
 * 每行 8 列：id / text / expect / capability / action / args / needs_llm / guard。
 * LOCAL 行断言「本地接管 + 能力 + 动作 + 槽位」，CLOUD 行断言「不接管 + 原因」。
 */
class CorpusRoutingTest {

    private val router = AssistantRouter()

    @Test
    fun `corpus rows are well formed`() {
        val rows = corpus()
        assertTrue("corpus should not be empty", rows.isNotEmpty())
        rows.forEach { row ->
            assertEquals("row ${row[0]} should have 8 columns", 8, row.size)
            assertTrue(
                "row ${row[0]} expect must be LOCAL or CLOUD",
                row[2] == "LOCAL" || row[2] == "CLOUD",
            )
        }
    }

    @Test
    fun `corpus decisions match the frozen expectations`() {
        val failures = mutableListOf<String>()
        corpus().forEach { row ->
            val id = row[0]
            val text = row[1]
            val expect = row[2]
            val capability = row[3]
            val action = row[4]
            val args = row[5]
            val guard = row[7]

            val decision = router.route(text, DefaultRoutes.all)
            if (expect == "LOCAL") {
                if (decision !is RouteDecision.Local) {
                    failures += "$id 「$text」 期望本地接管，实际 ${decision.reason()}"
                    return@forEach
                }
                if (decision.capability != capability || decision.action != action) {
                    failures += "$id 「$text」 期望 $capability/$action，" +
                        "实际 ${decision.capability}/${decision.action}"
                    return@forEach
                }
                args.split(';')
                    .filter { it.isNotBlank() && it != "-" }
                    .forEach { pair ->
                        val key = pair.substringBefore('=')
                        val expected = pair.substringAfter('=')
                        val actual = decision.args.optString(key)
                        if (expected == "*") {
                            if (actual.isBlank()) failures += "$id 「$text」 缺少槽位 $key"
                        } else if (actual != expected) {
                            failures += "$id 「$text」 槽位 $key 期望 $expected，实际 $actual"
                        }
                    }
            } else {
                if (decision is RouteDecision.Local) {
                    failures += "$id 「$text」 期望不接管，" +
                        "实际接管 ${decision.capability}/${decision.action}"
                    return@forEach
                }
                val reason = (decision as RouteDecision.Cloud).reason
                if (reason != guard) {
                    failures += "$id 「$text」 期望原因 $guard，实际 $reason"
                }
            }
        }
        assertTrue(
            "corpus mismatches:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    private fun RouteDecision.reason(): String =
        if (this is RouteDecision.Cloud) reason else "local"

    private fun corpus(): List<List<String>> {
        val stream = javaClass.classLoader?.getResourceAsStream("assistant-fastpath/corpus.tsv")
            ?: error("corpus.tsv not found on the test classpath")
        val text = stream.bufferedReader().use { it.readText() }
        return text.lineSequence()
            .filter { it.isNotBlank() && !it.trimStart().startsWith("#") }
            .map { line -> line.split('\t') }
            .toList()
    }
}
