package com.github.djaler.jvmheapdumpmcp.tools

import com.github.djaler.jvmheapdumpmcp.mat.MatFacade
import com.github.djaler.jvmheapdumpmcp.mat.SnapshotManager
import io.modelcontextprotocol.kotlin.sdk.server.Server

fun Server.registerObjectTools() {
    registerTool(
        buildTool(
            name = "get_object_info",
            description = """
                Returns detailed information about a specific object: class, heap sizes,
                GC root type (if applicable), and all field values with their objectIds.
                Use objectId from the dominator tree, histogram, or reference results.
            """.trimIndent(),
            schema = toolSchema(
                "id" to stringProp("Session ID returned by open_heap_dump"),
                "objectId" to intProp("Numeric object ID of the heap object to inspect"),
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
            val obj = MatFacade.getObjectInfo(session.snapshot, objectId)

            textResult(buildString {
                appendLine("## Object Info — objectId=$objectId — session `$id`")
                appendLine()
                appendLine("**Class:** `${obj.className}`")
                appendLine("**Shallow heap:** ${formatSize(obj.shallowHeap)}")
                appendLine("**Retained heap:** ${formatSize(obj.retainedHeap)}")
                obj.gcRootType?.let { appendLine("**GC root type:** $it") }
                appendLine()
                if (obj.fields.isNotEmpty()) {
                    appendLine("### Fields")
                    appendLine("| Field | Type | Value | Object ID |")
                    appendLine("|-------|------|-------|-----------|")
                    for (f in obj.fields) {
                        appendLine("| `${f.name}` | `${f.type}` | ${f.value} | ${f.objectId ?: "-"} |")
                    }
                } else {
                    appendLine("*No fields.*")
                }
            })
        }.getOrElse { errorResult(it) }
    }

    registerTool(
        buildTool(
            name = "get_outbound_references",
            description = """
                Returns objects that this object references (outbound/outgoing edges in the object graph).
                Useful for exploring what a large object holds onto.
                Each result includes the objectId for further drilling down.
            """.trimIndent(),
            schema = toolSchema(
                "id" to stringProp("Session ID returned by open_heap_dump"),
                "objectId" to intProp("Numeric object ID of the source object"),
                "limit" to intProp("Maximum number of references to return", default = 50),
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
            val refs = MatFacade.getOutboundReferences(session.snapshot, objectId, limit)

            if (refs.isEmpty()) {
                return@runCatching textResult("objectId=$objectId has no outbound references.")
            }

            textResult(buildString {
                appendLine("## Outbound References of objectId=$objectId — session `$id`")
                appendLine()
                appendLine("| Object ID | Class | Field | Shallow | Retained |")
                appendLine("|-----------|-------|-------|---------|----------|")
                for (r in refs) {
                    appendLine(
                        "| ${r.objectId} | `${r.className}` | ${r.fieldName ?: "-"} " +
                            "| ${formatSize(r.shallowHeap)} | ${formatSize(r.retainedHeap)} |"
                    )
                }
            })
        }.getOrElse { errorResult(it) }
    }

    registerTool(
        buildTool(
            name = "get_inbound_references",
            description = """
                Returns objects that reference this object (inbound/incoming edges).
                Useful for understanding who holds onto a specific object and preventing GC.
                Each result includes the objectId for further drilling down.
            """.trimIndent(),
            schema = toolSchema(
                "id" to stringProp("Session ID returned by open_heap_dump"),
                "objectId" to intProp("Numeric object ID of the target object"),
                "limit" to intProp("Maximum number of references to return", default = 50),
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
            val refs = MatFacade.getInboundReferences(session.snapshot, objectId, limit)

            if (refs.isEmpty()) {
                return@runCatching textResult("objectId=$objectId has no inbound references.")
            }

            textResult(buildString {
                appendLine("## Inbound References of objectId=$objectId — session `$id`")
                appendLine()
                appendLine("| Object ID | Class | Field | Shallow | Retained |")
                appendLine("|-----------|-------|-------|---------|----------|")
                for (r in refs) {
                    appendLine(
                        "| ${r.objectId} | `${r.className}` | ${r.fieldName ?: "-"} " +
                            "| ${formatSize(r.shallowHeap)} | ${formatSize(r.retainedHeap)} |"
                    )
                }
            })
        }.getOrElse { errorResult(it) }
    }

    registerTool(
        buildTool(
            name = "get_path_to_gc_roots",
            description = """
                Returns the shortest reference paths from an object to GC roots.
                Shows exactly why an object cannot be garbage collected.
                Each path is a chain of objectIds and field names leading to a GC root.
                Use this to confirm a leak and identify the holding reference.
            """.trimIndent(),
            schema = toolSchema(
                "id" to stringProp("Session ID returned by open_heap_dump"),
                "objectId" to intProp("Numeric object ID to trace from"),
                "limit" to intProp("Maximum number of paths to return", default = 10),
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
            val limit = args.getIntOrDefault("limit", 10)

            val session = SnapshotManager.getSession(id)
            val paths = MatFacade.getPathToGcRoots(session.snapshot, objectId, limit)

            if (paths.isEmpty()) {
                return@runCatching textResult("No paths to GC roots found for objectId=$objectId.")
            }

            textResult(buildString {
                appendLine("## Path to GC Roots — objectId=$objectId — session `$id`")
                appendLine()
                paths.forEachIndexed { i, path ->
                    appendLine("### Path ${i + 1}")
                    path.steps.forEachIndexed { step, s ->
                        val indent = "  ".repeat(step)
                        val field = if (s.fieldName != null) ".${s.fieldName}" else ""
                        val root = if (s.gcRootType != null) " **[GC ROOT: ${s.gcRootType}]**" else ""
                        appendLine("$indent- [${s.objectId}] `${s.className}`$field${root}")
                    }
                    appendLine()
                }
            })
        }.getOrElse { errorResult(it) }
    }

    registerTool(
        buildTool(
            name = "inspect_array",
            description = """
                Inspects the contents of an array object (byte[], char[], int[], long[], Object[], etc.).
                For byte[]: shows hex dump with ASCII. For char[]: shows string. For Object[]: shows element types.
                Use offset and limit to page through large arrays.
                Note: on obfuscated production dumps, byte[] and char[] will be zeroed out.
            """.trimIndent(),
            schema = toolSchema(
                "id" to stringProp("Session ID returned by open_heap_dump"),
                "objectId" to intProp("Numeric object ID of the array to inspect"),
                "offset" to intProp("Start index (default 0)", default = 0),
                "limit" to intProp("Number of elements to show (default 100)", default = 100),
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
            val offset = args.getIntOrDefault("offset", 0)
            val limit = args.getIntOrDefault("limit", 100)

            val session = SnapshotManager.getSession(id)
            val arr = MatFacade.inspectArray(session.snapshot, objectId, offset, limit)

            textResult(buildString {
                appendLine("## Array Inspection — objectId=$objectId — session `$id`")
                appendLine()
                appendLine("**Class:** `${arr.className}`")
                appendLine("**Length:** ${arr.length}")
                appendLine("**Element type:** ${arr.elementType}")
                appendLine("**Showing:** [${arr.offset}..${arr.offset + arr.elements.size})")
                if (arr.truncated) appendLine("*(truncated — use offset to see more)*")
                appendLine()
                if (arr.elementType == "byte") {
                    appendLine("```")
                    arr.elements.chunked(16).forEachIndexed { i, chunk ->
                        val hexPart = chunk.joinToString(" ") { it.substringBefore(" ") }
                        val asciiPart = chunk.joinToString("") { it.substringAfter("(").removeSuffix(")") }
                        appendLine("%06x  %-48s  %s".format(arr.offset + i * 16, hexPart, asciiPart))
                    }
                    appendLine("```")
                } else {
                    arr.elements.forEachIndexed { i, el ->
                        appendLine("  [${arr.offset + i}] $el")
                    }
                }
            })
        }.getOrElse { errorResult(it) }
    }
}
