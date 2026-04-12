package com.github.djaler.jvmheapdumpmcp.tools

import com.github.djaler.jvmheapdumpmcp.mat.MatFacade
import com.github.djaler.jvmheapdumpmcp.mat.SnapshotManager
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerQueryTools() {
    registerTool(
        buildTool(
            name = "execute_oql",
            description = """
                Executes an OQL (Object Query Language) query against the heap dump.
                OQL is similar to SQL but operates on heap objects.
                Example: SELECT * FROM java.util.HashMap WHERE size > 1000
                Example: SELECT s.value.toString() FROM java.lang.String s
                Results are returned as a table. Use limit to avoid overwhelming output.
            """.trimIndent(),
            schema = toolSchema(
                "id" to stringProp("Session ID returned by open_heap_dump"),
                "query" to stringProp("OQL query string to execute"),
                "limit" to intProp("Maximum number of result rows to return", default = 100),
                required = listOf("id", "query"),
            ),
        )
    ) { _, request ->
        runCatching {
            val args = request.arguments
            val id = args.getString("id")
                ?: return@runCatching errorResult("Missing required parameter: id")
            val query = args.getString("query")
                ?: return@runCatching errorResult("Missing required parameter: query")
            val limit = args.getIntOrDefault("limit", 100)

            val session = SnapshotManager.getSession(id)
            val result = MatFacade.executeOql(session.snapshot, query, limit)

            textResult(buildString {
                appendLine("## OQL Result — session `$id`")
                appendLine()
                appendLine("**Query:** `${result.query}`")
                appendLine("**Rows:** ${result.rows.size}${if (result.truncated) " (truncated)" else ""}")
                appendLine()
                if (result.rows.isEmpty()) {
                    appendLine("*No results.*")
                } else {
                    val header = result.columns.joinToString(" | ")
                    val separator = result.columns.joinToString("-|-") { "-".repeat(it.length.coerceAtLeast(3)) }
                    appendLine("| $header |")
                    appendLine("|-$separator-|")
                    for (row in result.rows) {
                        appendLine("| ${row.joinToString(" | ")} |")
                    }
                }
                if (result.truncated) {
                    appendLine()
                    appendLine("*Results truncated at $limit rows. Increase `limit` to see more.*")
                }
            })
        }.getOrElse { errorResult(it) }
    }

    registerTool(
        buildTool(
            name = "find_strings",
            description = """
                Finds String objects in the heap whose value matches the given regex pattern.
                Useful for locating specific data (URLs, passwords, config values) that may be leaked.
                Returns objectId, value, and retained heap for each match.
            """.trimIndent(),
            schema = toolSchema(
                "id" to stringProp("Session ID returned by open_heap_dump"),
                "pattern" to stringProp("Regex pattern to match against string values"),
                "limit" to intProp("Maximum number of results to return", default = 50),
                required = listOf("id", "pattern"),
            ),
        )
    ) { _, request ->
        runCatching {
            val args = request.arguments
            val id = args.getString("id")
                ?: return@runCatching errorResult("Missing required parameter: id")
            val pattern = args.getString("pattern")
                ?: return@runCatching errorResult("Missing required parameter: pattern")
            val limit = args.getIntOrDefault("limit", 50)

            val session = SnapshotManager.getSession(id)
            val strings = MatFacade.findStrings(session.snapshot, pattern, limit)

            if (strings.isEmpty()) {
                return@runCatching textResult("No strings found matching pattern `$pattern`.")
            }

            textResult(buildString {
                appendLine("## String Search — pattern=`$pattern` — session `$id`")
                appendLine("*${strings.size} result(s)*")
                appendLine()
                appendLine("| Object ID | Value | Retained Heap |")
                appendLine("|-----------|-------|---------------|")
                for (s in strings) {
                    val truncated = if (s.value.length > 120) s.value.take(120) + "…" else s.value
                    appendLine("| ${s.objectId} | `${truncated.replace("|", "\\|")}` | ${formatSize(s.retainedHeap)} |")
                }
            })
        }.getOrElse { errorResult(it) }
    }
}
