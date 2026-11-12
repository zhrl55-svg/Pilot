package com.pilot.agent.tool

import android.content.Context
import com.pilot.agent.Dbg
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Content-free tool audit. Inputs are represented by a short action summary;
 * file bodies, memory text, passwords, and chat messages are never appended.
 */
class ToolAuditLog(private val context: Context) {
    fun record(
        tool: String,
        group: ToolGroup,
        decision: String,
        status: ToolStatus,
        summary: String,
    ) {
        runCatching {
            val timestamp = System.currentTimeMillis()
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestamp))
            val line = "$time\t$tool\t${group.name}\t$decision\t${status.name}\t$summary\n"
            File(context.filesDir, FILE).appendText(line)
            trimIfNeeded()
        }.onFailure {
            Dbg.w(TAG, "write failed: ${it.javaClass.simpleName}")
        }
    }

    fun recent(limit: Int = 50): List<ToolAuditEntry> {
        val file = File(context.filesDir, FILE)
        if (!file.isFile) return emptyList()
        return runCatching {
            file.readLines().takeLast(limit.coerceIn(1, 500)).reversed().mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 6) return@mapNotNull null
                val ts = runCatching {
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(parts[0])?.time
                }.getOrNull() ?: 0L
                ToolAuditEntry(
                    timestamp = ts,
                    tool = parts[1],
                    group = runCatching { ToolGroup.valueOf(parts[2]) }.getOrDefault(ToolGroup.READ),
                    decision = parts[3],
                    result = runCatching { ToolStatus.valueOf(parts[4]) }.getOrDefault(ToolStatus.ERROR),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun trimIfNeeded() {
        val file = File(context.filesDir, FILE)
        if (file.length() <= MAX_BYTES) return
        val tail = file.readLines().takeLast(300)
        file.writeText(tail.joinToString(separator = "\n", postfix = "\n"))
    }

    companion object {
        private const val TAG = "PilotToolAudit"
        private const val FILE = "tool-audit.log"
        private const val MAX_BYTES = 512L * 1024L
    }
}
