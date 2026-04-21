package com.github.djaler.jvmheapdumpmcp.tools

import com.github.djaler.jvmheapdumpmcp.mat.MatFacade
import com.github.djaler.jvmheapdumpmcp.mat.SnapshotManager
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerRetainedSetTools() {
    registerTool(
        buildTool(
            name = "get_retained_set",
            description = """
                Returns a class histogram of the retained set of a single object.
                The retained set includes all objects that would be garbage collected
                if this object were removed. Useful for understanding the true memory
                impact of a specific object (e.g., a cache, a session, a collection).
            """.trimIndent(),
            schema = toolSchema(
                "id" to stringProp("Session ID returned by open_heap_dump"),
                "objectId" to intProp("Object ID to compute retained set for"),
                "limit" to intProp("Maximum number of class entries to return", default = 50),
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
            val limit = args.getIntOrDefault("limit", 50)

            val session = SnapshotManager.getSession(id)
            val objInfo = session.snapshot.getObject(objectId)
            val entries = MatFacade.getRetainedSet(session.snapshot, objectId, limit)

            val totalObjects = entries.sumOf { it.objectCount }
            val totalRetained = entries.sumOf { it.retainedHeap }

            textResult(buildString {
                appendLine("## Retained Set of `${objInfo.clazz.name}` (objectId: $objectId) — session `$id`")
                appendLine("Total: ${"%,d".format(totalObjects)} objects, ${formatSize(totalRetained)} retained")
                appendLine()
                appendLine("| # | Class | Objects | Shallow Heap | Retained Heap | % of Total |")
                appendLine("|---|-------|---------|--------------|---------------|------------|")
                entries.forEachIndexed { i, e ->
                    val pct = if (totalRetained > 0) "%.1f%%".format(e.retainedHeap * 100.0 / totalRetained) else "0%"
                    appendLine(
                        "| ${i + 1} | `${e.className}` | ${"%,d".format(e.objectCount)} " +
                            "| ${formatSize(e.shallowHeap)} | ${formatSize(e.retainedHeap)} | $pct |"
                    )
                }
            })
        }.getOrElse { errorResult(it) }
    }
}
