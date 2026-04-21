package com.github.djaler.jvmheapdumpmcp.tools

import com.github.djaler.jvmheapdumpmcp.mat.MatFacade
import com.github.djaler.jvmheapdumpmcp.mat.SnapshotManager
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerCollectionTools() {
    registerTool(
        buildTool(
            name = "get_collection_fill_rates",
            description = """
                Analyzes fill rates of collection instances (HashMap, ArrayList, etc.).
                Shows distribution of how full the collections are, helping identify
                over-provisioned or empty collections that waste memory.
                Works by reading internal size and capacity fields of each collection instance.
            """.trimIndent(),
            schema = toolSchema(
                "id" to stringProp("Session ID returned by open_heap_dump"),
                "className" to stringProp("Fully qualified collection class name (e.g. java.util.HashMap)"),
                "limit" to intProp("Maximum number of collection instances to analyze", default = 1000),
                required = listOf("id", "className"),
            ),
        )
    ) { _, request ->
        runCatching {
            val args = request.arguments
            val id = args.getString("id")
                ?: return@runCatching errorResult("Missing required parameter: id")
            val className = args.getString("className")
                ?: return@runCatching errorResult("Missing required parameter: className")
            val limit = args.getIntOrDefault("limit", 1000)

            val session = SnapshotManager.getSession(id)
            val result = MatFacade.getCollectionFillRates(session.snapshot, className, limit)

            if (result.totalCount == 0) {
                return@runCatching textResult("No instances of `$className` found or could not determine capacity.")
            }

            textResult(buildString {
                appendLine("## Collection Fill Rates for `$className` — session `$id`")
                appendLine("Total: ${"%,d".format(result.totalCount)} instances analyzed")
                appendLine()
                appendLine("| Fill Range | Count | % | Shallow Heap | Wasted Heap |")
                appendLine("|------------|-------|---|--------------|-------------|")
                result.buckets.forEach { b ->
                    val pct = if (result.totalCount > 0) "%.1f%%".format(b.count * 100.0 / result.totalCount) else "0%"
                    appendLine(
                        "| ${b.rangeLabel} | ${"%,d".format(b.count)} | $pct " +
                            "| ${formatSize(b.totalShallowHeap)} | ${formatSize(b.totalWastedHeap)} |"
                    )
                }
            })
        }.getOrElse { errorResult(it) }
    }

    registerTool(
        buildTool(
            name = "get_map_contents",
            description = """
                Inspects the contents of a Map object (HashMap, ConcurrentHashMap, LinkedHashMap).
                Shows key/value type distribution and the top entries by retained size.
                Use this to understand what a specific large map is holding.
            """.trimIndent(),
            schema = toolSchema(
                "id" to stringProp("Session ID returned by open_heap_dump"),
                "objectId" to intProp("Object ID of the Map instance"),
                "limit" to intProp("Maximum number of top entries to show", default = 20),
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
            val limit = args.getIntOrDefault("limit", 20)

            val session = SnapshotManager.getSession(id)
            val result = MatFacade.getMapContents(session.snapshot, objectId, limit)

            if (result.entryCount == 0) {
                return@runCatching textResult("Map is empty or could not read entries (${result.className}).")
            }

            textResult(buildString {
                appendLine("## Map Contents of `${result.className}` (objectId: $objectId) — session `$id`")
                appendLine("Entries: ${"%,d".format(result.entryCount)} | Total retained: ${formatSize(result.totalRetainedHeap)}")
                appendLine()

                appendLine("### Key Types")
                appendLine("| Type | Count | Retained |")
                appendLine("|------|-------|----------|")
                result.keyTypeSummary.forEach { ts ->
                    appendLine("| `${ts.className}` | ${"%,d".format(ts.count)} | ${formatSize(ts.totalRetainedHeap)} |")
                }
                appendLine()

                appendLine("### Value Types")
                appendLine("| Type | Count | Retained |")
                appendLine("|------|-------|----------|")
                result.valueTypeSummary.forEach { ts ->
                    appendLine("| `${ts.className}` | ${"%,d".format(ts.count)} | ${formatSize(ts.totalRetainedHeap)} |")
                }
                appendLine()

                appendLine("### Top Entries by Retained Size")
                appendLine("| # | Key | Value Type | Retained |")
                appendLine("|---|-----|------------|----------|")
                result.topEntries.forEachIndexed { i, e ->
                    val keyDisplay = e.keyLabel?.let { "\"$it\"" } ?: "`${e.keyClassName}` [${e.keyObjectId}]"
                    appendLine(
                        "| ${i + 1} | $keyDisplay | `${e.valueClassName}` | ${formatSize(e.valueRetainedHeap)} |"
                    )
                }
            })
        }.getOrElse { errorResult(it) }
    }
}
