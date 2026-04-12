package com.github.djaler.jvmheapdumpmcp.tools

import com.github.djaler.jvmheapdumpmcp.mat.MatFacade
import com.github.djaler.jvmheapdumpmcp.mat.SnapshotManager
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerHistogramTools() {
    registerTool(
        buildTool(
            name = "get_class_histogram",
            description = """
                Returns a histogram of all classes in the heap, sorted by heap consumption.
                Shows object count, shallow heap, and retained heap per class.
                Use this to identify which classes are consuming the most memory.
                Filter by class name regex to narrow results.
            """.trimIndent(),
            schema = toolSchema(
                "id" to stringProp("Session ID returned by open_heap_dump"),
                "sortBy" to enumProp(
                    "Sort order for results",
                    listOf("RETAINED_HEAP", "SHALLOW_HEAP", "OBJECTS"),
                    default = "RETAINED_HEAP",
                ),
                "filter" to stringProp("Optional regex to filter class names (e.g. 'com\\.example\\.')"),
                "limit" to intProp("Maximum number of entries to return", default = 50),
                required = listOf("id"),
            ),
        )
    ) { _, request ->
        runCatching {
            val args = request.arguments
            val id = args.getString("id")
                ?: return@runCatching errorResult("Missing required parameter: id")
            val sortBy = args.getStringOrDefault("sortBy", "RETAINED_HEAP")
            val filter = args.getString("filter")
            val limit = args.getIntOrDefault("limit", 50)

            val session = SnapshotManager.getSession(id)
            val entries = MatFacade.getClassHistogram(session.snapshot, sortBy, filter, limit)

            if (entries.isEmpty()) {
                return@runCatching textResult("No classes found matching the given filter.")
            }

            textResult(buildString {
                appendLine("## Class Histogram — session `$id`")
                if (filter != null) appendLine("*Filter: `$filter`*")
                appendLine("*Sorted by: $sortBy, showing top $limit*")
                appendLine()
                appendLine("| # | Class | Objects | Shallow Heap | Retained Heap |")
                appendLine("|---|-------|---------|--------------|---------------|")
                entries.forEachIndexed { i, e ->
                    appendLine(
                        "| ${i + 1} | `${e.className}` | ${"%,d".format(e.objectCount)} " +
                            "| ${formatSize(e.shallowHeap)} | ${formatSize(e.retainedHeap)} |"
                    )
                }
            })
        }.getOrElse { errorResult(it) }
    }

    registerTool(
        buildTool(
            name = "get_class_instances",
            description = """
                Returns all instances of a specific class with their objectId, sizes, and labels.
                The className parameter can be a fully qualified name (exact match) or a regex pattern.
                Use this to find specific objects for further inspection with get_object_info.
            """.trimIndent(),
            schema = toolSchema(
                "id" to stringProp("Session ID returned by open_heap_dump"),
                "className" to stringProp("Fully qualified class name or regex pattern"),
                "sortBy" to enumProp("Sort order", listOf("RETAINED_HEAP", "SHALLOW_HEAP"), default = "RETAINED_HEAP"),
                "limit" to intProp("Maximum number of instances to return", default = 50),
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
            val sortBy = args.getStringOrDefault("sortBy", "RETAINED_HEAP")
            val limit = args.getIntOrDefault("limit", 50)

            val session = SnapshotManager.getSession(id)
            val instances = MatFacade.getClassInstances(session.snapshot, className, sortBy, limit)

            if (instances.isEmpty()) {
                return@runCatching textResult("No instances found for class `$className`.")
            }

            textResult(buildString {
                appendLine("## Instances of `$className` — session `$id`")
                appendLine("*Sorted by: $sortBy, showing top $limit*")
                appendLine()
                appendLine("| # | Object ID | Class | Shallow | Retained | Label |")
                appendLine("|---|-----------|-------|---------|----------|-------|")
                instances.forEachIndexed { i, e ->
                    appendLine(
                        "| ${i + 1} | ${e.objectId} | `${e.className}` " +
                            "| ${formatSize(e.shallowHeap)} | ${formatSize(e.retainedHeap)} " +
                            "| ${e.label ?: "-"} |"
                    )
                }
            })
        }.getOrElse { errorResult(it) }
    }
}
