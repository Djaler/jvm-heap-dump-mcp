package com.github.djaler.jvmheapdumpmcp.tools

import com.github.djaler.jvmheapdumpmcp.mat.MatFacade
import com.github.djaler.jvmheapdumpmcp.mat.SnapshotManager
import io.modelcontextprotocol.kotlin.sdk.server.Server
import java.nio.file.Paths
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE_FMT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

fun Server.registerSessionTools() {
    registerTool(
        buildTool(
            name = "open_heap_dump",
            description = """
                Opens a JVM heap dump (.hprof) file and starts an analysis session.
                Returns a session ID that is required by all other analysis tools.
                Also returns a heap summary so you immediately know the scale of the dump.
                Call this first before using any other tool.
            """.trimIndent(),
            schema = toolSchema(
                "path" to stringProp("Absolute path to the .hprof heap dump file"),
                "id" to stringProp("Optional custom session ID; auto-generated UUID if omitted"),
                required = listOf("path"),
            ),
        )
    ) { _, request ->
        runCatching {
            val args = request.arguments
            val path = args.getString("path")
                ?: return@runCatching errorResult("Missing required parameter: path")
            val id = args.getString("id")

            val session = SnapshotManager.openSnapshot(Paths.get(path), id)
            val summary = MatFacade.getHeapSummary(session.snapshot)

            textResult(buildString {
                appendLine("## Heap Dump Opened")
                appendLine()
                appendLine("**Session ID:** `${session.id}`")
                appendLine("**File:** ${session.filePath}")
                appendLine("**Opened at:** ${DATE_FMT.format(session.openedAt)}")
                appendLine()
                appendLine("### Heap Summary")
                appendLine("| Metric | Value |")
                appendLine("|--------|-------|")
                appendLine("| Used heap size | ${formatSize(summary.usedHeapSize)} |")
                appendLine("| Used heap size  | ${formatSize(summary.usedHeapSize)} |")
                appendLine("| Objects         | ${"%,d".format(summary.objectCount)} |")
                appendLine("| Classes         | ${"%,d".format(summary.classCount)} |")
                appendLine("| GC roots        | ${"%,d".format(summary.gcRootCount)} |")
                summary.jvmInfo?.let { appendLine("| JVM info        | $it |") }
                summary.snapshotDate?.let { appendLine("| Snapshot date   | $it |") }
            })
        }.getOrElse { errorResult(it) }
    }

    registerTool(
        buildTool(
            name = "close_heap_dump",
            description = """
                Closes an open heap dump session and releases all associated memory.
                Always call this when analysis is complete to avoid memory leaks.
                The session ID becomes invalid after closing.
            """.trimIndent(),
            schema = toolSchema(
                "id" to stringProp("Session ID returned by open_heap_dump"),
                required = listOf("id"),
            ),
        )
    ) { _, request ->
        runCatching {
            val id = request.arguments.getString("id")
                ?: return@runCatching errorResult("Missing required parameter: id")

            SnapshotManager.closeSnapshot(id)
            textResult("Session `$id` closed successfully.")
        }.getOrElse { errorResult(it) }
    }

    registerTool(
        buildTool(
            name = "list_sessions",
            description = """
                Lists all currently open heap dump sessions with their IDs, file paths, and sizes.
                Use this to discover available session IDs or check what dumps are loaded.
            """.trimIndent(),
            schema = toolSchema(),
        )
    ) { _, _ ->
        runCatching {
            val sessions = SnapshotManager.listSessions()

            if (sessions.isEmpty()) {
                return@runCatching textResult(
                    "No open sessions. Use `open_heap_dump` to load a heap dump."
                )
            }

            textResult(buildString {
                appendLine("## Open Sessions (${sessions.size})")
                appendLine()
                appendLine("| Session ID | File | Opened At | Heap Size |")
                appendLine("|------------|------|-----------|-----------|")
                for (s in sessions) {
                    appendLine("| `${s.id}` | ${s.path} | ${s.openedAt} | ${formatSize(s.heapSizeBytes)} |")
                }
            })
        }.getOrElse { errorResult(it) }
    }
}
