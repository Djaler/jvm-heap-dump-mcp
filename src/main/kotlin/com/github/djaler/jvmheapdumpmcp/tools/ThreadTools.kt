package com.github.djaler.jvmheapdumpmcp.tools

import com.github.djaler.jvmheapdumpmcp.mat.MatFacade
import com.github.djaler.jvmheapdumpmcp.mat.SnapshotManager
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerThreadTools() {
    registerTool(
        buildTool(
            name = "get_threads",
            description = """
                Returns all threads found in the heap dump with their retained heap sizes and stack frames.
                Useful for identifying threads holding onto large amounts of memory.
                Filter by thread name regex to focus on specific threads.
            """.trimIndent(),
            schema = toolSchema(
                "id" to stringProp("Session ID returned by open_heap_dump"),
                "sortBy" to enumProp(
                    "Sort order for results",
                    listOf("RETAINED_HEAP", "NAME"),
                    default = "RETAINED_HEAP",
                ),
                "filter" to stringProp("Optional regex to filter thread names"),
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

            val session = SnapshotManager.getSession(id)
            val threads = MatFacade.getThreads(session.snapshot, sortBy, filter)

            if (threads.isEmpty()) {
                return@runCatching textResult("No threads found matching the given filter.")
            }

            textResult(buildString {
                appendLine("## Threads — session `$id`")
                if (filter != null) appendLine("*Filter: `$filter`*")
                appendLine("*Sorted by: $sortBy*")
                appendLine()
                threads.forEachIndexed { i, t ->
                    appendLine("### ${i + 1}. `${t.name}` — objectId=${t.objectId}")
                    appendLine("**Retained heap:** ${formatSize(t.retainedHeap)}  |  **Shallow heap:** ${formatSize(t.shallowHeap)}")
                    if (t.stackFrames.isNotEmpty()) {
                        appendLine()
                        appendLine("**Stack frames:**")
                        t.stackFrames.forEach { frame -> appendLine("  - $frame") }
                    }
                    appendLine()
                }
            })
        }.getOrElse { errorResult(it) }
    }
}
