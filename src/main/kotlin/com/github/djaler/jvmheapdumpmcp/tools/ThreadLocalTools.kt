package com.github.djaler.jvmheapdumpmcp.tools

import com.github.djaler.jvmheapdumpmcp.mat.MatFacade
import com.github.djaler.jvmheapdumpmcp.mat.SnapshotManager
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerThreadLocalTools() {
    registerTool(
        buildTool(
            name = "get_thread_local_variables",
            description = """
                Lists all ThreadLocal variables held by a specific thread.
                Shows the ThreadLocal key class, value class, retained heap, and value label.
                Stale entries (where the ThreadLocal key has been GC'd) are marked as "(stale)".
                Use get_threads first to find thread object IDs.
            """.trimIndent(),
            schema = toolSchema(
                "id" to stringProp("Session ID returned by open_heap_dump"),
                "objectId" to intProp("Object ID of the Thread instance (from get_threads)"),
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

            val session = SnapshotManager.getSession(id)
            val threadObj = session.snapshot.getObject(objectId)
            val threadName = threadObj.classSpecificName ?: threadObj.displayName
            val entries = MatFacade.getThreadLocalVariables(session.snapshot, objectId)

            if (entries.isEmpty()) {
                return@runCatching textResult("No ThreadLocal entries found for thread `$threadName` (objectId: $objectId).")
            }

            textResult(buildString {
                appendLine("## ThreadLocal Variables for `$threadName` (objectId: $objectId) — session `$id`")
                appendLine("Found ${entries.size} ThreadLocal entries")
                appendLine()
                appendLine("| # | ThreadLocal Class | Value Class | Retained | Value |")
                appendLine("|---|-------------------|-------------|----------|-------|")
                entries.forEachIndexed { i, e ->
                    val tlClass = e.threadLocalClassName ?: "(stale)"
                    val valueDisplay = e.valueLabel ?: "-"
                    appendLine(
                        "| ${i + 1} | `$tlClass` | `${e.valueClassName}` " +
                            "| ${formatSize(e.valueRetainedHeap)} | $valueDisplay |"
                    )
                }
            })
        }.getOrElse { errorResult(it) }
    }
}
