package com.github.djaler.jvmheapdumpmcp.tools

import com.github.djaler.jvmheapdumpmcp.mat.MatFacade
import com.github.djaler.jvmheapdumpmcp.mat.SnapshotManager
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerOverviewTools() {
    registerTool(
        buildTool(
            name = "get_heap_summary",
            description = """
                Returns comprehensive statistics about the heap dump:
                total/used heap size, object count, class count, and GC root count.
                Use this as a quick overview before diving deeper.
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

            val session = SnapshotManager.getSession(id)
            val s = MatFacade.getHeapSummary(session.snapshot)

            textResult(buildString {
                appendLine("## Heap Summary — session `$id`")
                appendLine()
                appendLine("| Metric | Value |")
                appendLine("|--------|-------|")
                appendLine("| Used heap size | ${formatSize(s.usedHeapSize)} |")
                appendLine("| Used heap size  | ${formatSize(s.usedHeapSize)} |")
                appendLine("| Objects         | ${"%,d".format(s.objectCount)} |")
                appendLine("| Classes         | ${"%,d".format(s.classCount)} |")
                appendLine("| GC roots        | ${"%,d".format(s.gcRootCount)} |")
                s.jvmInfo?.let { appendLine("| JVM info        | $it |") }
                s.snapshotDate?.let { appendLine("| Snapshot date   | $it |") }
            })
        }.getOrElse { errorResult(it) }
    }

    registerTool(
        buildTool(
            name = "get_leak_suspects",
            description = """
                Runs Eclipse MAT's automatic leak detection and returns the top leak suspects.
                Each suspect includes a description, retained heap size, and probability rating.
                Use this for a quick first look at what might be causing an OOM.
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

            val session = SnapshotManager.getSession(id)
            val suspects = MatFacade.getLeakSuspects(session.snapshot)

            if (suspects.isEmpty()) {
                return@runCatching textResult("No leak suspects found.")
            }

            textResult(buildString {
                appendLine("## Leak Suspects — session `$id`")
                appendLine()
                suspects.forEachIndexed { i, s ->
                    appendLine("### Suspect ${i + 1} — ${s.probability}")
                    appendLine("**Retained heap:** ${formatSize(s.retainedHeap)}")
                    appendLine()
                    appendLine(s.description)
                    s.detail?.let {
                        appendLine()
                        appendLine(it)
                    }
                    appendLine()
                }
            })
        }.getOrElse { errorResult(it) }
    }
}
