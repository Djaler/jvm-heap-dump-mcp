package com.github.djaler.jvmheapdumpmcp.tools

import com.github.djaler.jvmheapdumpmcp.mat.MatFacade
import com.github.djaler.jvmheapdumpmcp.mat.SnapshotManager
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerCompareTools() {
    registerTool(
        buildTool(
            name = "compare_class_histograms",
            description = """
                Compares class histograms between two heap dump sessions.
                Shows which classes grew or shrank in object count and heap usage.
                Essential for identifying memory leaks by comparing a "before" and "after" dump.
            """.trimIndent(),
            schema = toolSchema(
                "id1" to stringProp("Session ID of the first (baseline) heap dump"),
                "id2" to stringProp("Session ID of the second (comparison) heap dump"),
                "sortBy" to enumProp(
                    "Sort order for results",
                    listOf("SHALLOW_HEAP_DELTA", "OBJECT_COUNT_DELTA"),
                    default = "SHALLOW_HEAP_DELTA",
                ),
                "limit" to intProp("Maximum number of entries to return", default = 50),
                required = listOf("id1", "id2"),
            ),
        )
    ) { _, request ->
        runCatching {
            val args = request.arguments
            val id1 = args.getString("id1")
                ?: return@runCatching errorResult("Missing required parameter: id1")
            val id2 = args.getString("id2")
                ?: return@runCatching errorResult("Missing required parameter: id2")
            val sortBy = args.getStringOrDefault("sortBy", "SHALLOW_HEAP_DELTA")
            val limit = args.getIntOrDefault("limit", 50)

            val session1 = SnapshotManager.getSession(id1)
            val session2 = SnapshotManager.getSession(id2)
            val diffs = MatFacade.compareClassHistograms(session1.snapshot, session2.snapshot, sortBy, limit)

            if (diffs.isEmpty()) {
                return@runCatching textResult("No differences found between the two heap dumps.")
            }

            val growing =
                diffs.filter { it.shallowHeapDelta > 0 || (it.shallowHeapDelta == 0L && it.objectCountDelta > 0) }
            val shrinking =
                diffs.filter { it.shallowHeapDelta < 0 || (it.shallowHeapDelta == 0L && it.objectCountDelta < 0) }

            textResult(buildString {
                appendLine("## Class Histogram Diff — session `$id1` vs `$id2`")
                appendLine("*Sorted by: $sortBy, showing top $limit*")
                appendLine()

                if (growing.isNotEmpty()) {
                    appendLine("### Growing (top by heap increase)")
                    appendLine("| # | Class | Delta Objects | Delta Shallow | Count (before) | Count (after) |")
                    appendLine("|---|-------|-------------|--------------|----------------|---------------|")
                    growing.forEachIndexed { i, e ->
                        appendLine(
                            "| ${i + 1} | `${e.className}` | +${"%,d".format(e.objectCountDelta)} " +
                                "| +${formatSize(e.shallowHeapDelta)} " +
                                "| ${"%,d".format(e.objectCount1)} | ${"%,d".format(e.objectCount2)} |"
                        )
                    }
                    appendLine()
                }

                if (shrinking.isNotEmpty()) {
                    appendLine("### Shrinking")
                    appendLine("| # | Class | Delta Objects | Delta Shallow | Count (before) | Count (after) |")
                    appendLine("|---|-------|-------------|--------------|----------------|---------------|")
                    shrinking.forEachIndexed { i, e ->
                        appendLine(
                            "| ${i + 1} | `${e.className}` | ${"%,d".format(e.objectCountDelta)} " +
                                "| ${formatSize(e.shallowHeapDelta)} " +
                                "| ${"%,d".format(e.objectCount1)} | ${"%,d".format(e.objectCount2)} |"
                        )
                    }
                }
            })
        }.getOrElse { errorResult(it) }
    }
}
