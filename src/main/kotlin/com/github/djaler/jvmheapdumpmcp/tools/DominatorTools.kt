package com.github.djaler.jvmheapdumpmcp.tools

import com.github.djaler.jvmheapdumpmcp.mat.MatFacade
import com.github.djaler.jvmheapdumpmcp.mat.SnapshotManager
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerDominatorTools() {
    registerTool(
        buildTool(
            name = "get_dominator_tree",
            description = """
                Returns the top entries of the dominator tree — objects that retain the most heap.
                Each entry shows objectId, class, shallow heap, retained heap, and retained %.
                Use objectId with get_dominator_tree_children to drill into a subtree.
                This is the primary tool for finding the root cause of high memory usage.
            """.trimIndent(),
            schema = toolSchema(
                "id" to stringProp("Session ID returned by open_heap_dump"),
                "limit" to intProp("Maximum number of top-level entries to return", default = 30),
                required = listOf("id"),
            ),
        )
    ) { _, request ->
        runCatching {
            val args = request.arguments
            val id = args.getString("id")
                ?: return@runCatching errorResult("Missing required parameter: id")
            val limit = args.getIntOrDefault("limit", 30)

            val session = SnapshotManager.getSession(id)
            val entries = MatFacade.getDominatorTree(session.snapshot, limit)

            textResult(buildString {
                appendLine("## Dominator Tree — session `$id` (top $limit)")
                appendLine()
                appendLine("| # | Object ID | Class | Shallow | Retained | % |")
                appendLine("|---|-----------|-------|---------|----------|---|")
                entries.forEachIndexed { i, e ->
                    val label = if (e.label != null) " `${e.label}`" else ""
                    appendLine(
                        "| ${i + 1} | ${e.objectId} | `${e.className}`$label " +
                            "| ${formatSize(e.shallowHeap)} | ${formatSize(e.retainedHeap)} " +
                            "| ${"%.1f".format(e.retainedPercent)}% |"
                    )
                }
                appendLine()
                appendLine("*Use `get_dominator_tree_children` with an objectId to drill down.*")
            })
        }.getOrElse { errorResult(it) }
    }

    registerTool(
        buildTool(
            name = "get_dominator_tree_children",
            description = """
                Returns the children of a specific node in the dominator tree.
                Use objectId from get_dominator_tree or a previous call to this tool.
                Allows recursive drill-down into the object graph to find the exact leak source.
            """.trimIndent(),
            schema = toolSchema(
                "id" to stringProp("Session ID returned by open_heap_dump"),
                "objectId" to intProp("Object ID of the parent node (from dominator tree)"),
                "limit" to intProp("Maximum number of children to return", default = 30),
                required = listOf("id", "objectId"),
            ),
        )
    ) { _, request ->
        runCatching {
            val args = request.arguments
            val id = args.getString("id")
                ?: return@runCatching errorResult("Missing required parameter: id")
            val objectId = args.getInt("objectId")
                ?: return@runCatching errorResult("Missing required parameter: objectId")
            val limit = args.getIntOrDefault("limit", 30)

            val session = SnapshotManager.getSession(id)
            val entries = MatFacade.getDominatorTreeChildren(session.snapshot, objectId, limit)

            if (entries.isEmpty()) {
                return@runCatching textResult("No children found for objectId $objectId.")
            }

            textResult(buildString {
                appendLine("## Dominator Tree Children of objectId=$objectId — session `$id`")
                appendLine()
                appendLine("| # | Object ID | Class | Shallow | Retained | % |")
                appendLine("|---|-----------|-------|---------|----------|---|")
                entries.forEachIndexed { i, e ->
                    val label = if (e.label != null) " `${e.label}`" else ""
                    appendLine(
                        "| ${i + 1} | ${e.objectId} | `${e.className}`$label " +
                            "| ${formatSize(e.shallowHeap)} | ${formatSize(e.retainedHeap)} " +
                            "| ${"%.1f".format(e.retainedPercent)}% |"
                    )
                }
            })
        }.getOrElse { errorResult(it) }
    }
}
