package com.github.djaler.jvmheapdumpmcp.tools

import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

fun errorResult(message: String) = CallToolResult(
    content = listOf(TextContent(message)),
    isError = true,
)

fun errorResult(e: Throwable): CallToolResult {
    val root = generateSequence(e) { it.cause }.last()
    val msg = buildString {
        append("Error: ").append(e.javaClass.simpleName)
        e.message?.let { append(": ").append(it) }
        if (root !== e) {
            appendLine()
            append("Caused by: ").append(root.javaClass.simpleName)
            root.message?.let { append(": ").append(it) }
        }
    }
    return CallToolResult(content = listOf(TextContent(msg)), isError = true)
}

fun textResult(text: String) = CallToolResult(
    content = listOf(TextContent(text)),
)

fun formatSize(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.2f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.2f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L -> "%.2f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

fun JsonElement.asString(): String? = (this as? JsonPrimitive)?.content

fun JsonElement.asInt(): Int? = (this as? JsonPrimitive)?.content?.toIntOrNull()

fun JsonObject?.getString(key: String): String? = this?.get(key)?.asString()

fun JsonObject?.getInt(key: String): Int? = this?.get(key)?.asInt()

fun JsonObject?.getIntOrDefault(key: String, default: Int): Int = getInt(key) ?: default

fun JsonObject?.getStringOrDefault(key: String, default: String): String = getString(key) ?: default

/**
 * Builds a ToolSchema (input schema) from a map of property-name to JSON schema object.
 */
fun toolSchema(
    vararg properties: Pair<String, JsonObject>,
    required: List<String> = emptyList(),
): ToolSchema {
    val propsMap = JsonObject(properties.toMap())
    return ToolSchema(properties = propsMap, required = required)
}

fun stringProp(description: String): JsonObject = JsonObject(
    mapOf(
        "type" to JsonPrimitive("string"),
        "description" to JsonPrimitive(description),
    )
)

fun intProp(description: String, default: Int? = null): JsonObject {
    val map = mutableMapOf<String, JsonElement>(
        "type" to JsonPrimitive("integer"),
        "description" to JsonPrimitive(description),
    )
    if (default != null) map["default"] = JsonPrimitive(default)
    return JsonObject(map)
}

fun enumProp(description: String, values: List<String>, default: String? = null): JsonObject {
    val map = mutableMapOf<String, JsonElement>(
        "type" to JsonPrimitive("string"),
        "description" to JsonPrimitive(description),
        "enum" to JsonArray(values.map { JsonPrimitive(it) }),
    )
    if (default != null) map["default"] = JsonPrimitive(default)
    return JsonObject(map)
}

fun buildTool(name: String, description: String, schema: ToolSchema): Tool =
    Tool(name = name, description = description, inputSchema = schema)

/**
 * Type-safe wrapper around Server.addTool that correctly infers the suspend handler type.
 */
fun Server.registerTool(
    tool: Tool,
    handler: suspend (ClientConnection, CallToolRequest) -> CallToolResult,
) {
    addTools(listOf(RegisteredTool(tool, handler)))
}
