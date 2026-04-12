package com.github.djaler.jvmheapdumpmcp.server

import com.github.djaler.jvmheapdumpmcp.tools.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

object McpServerFactory {
    fun create(): Server {
        val server = Server(
            serverInfo = Implementation(
                name = "jvm-heap-dump-mcp",
                version = "0.1.0",
            ),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                ),
            ),
        )

        server.registerSessionTools()
        server.registerOverviewTools()
        server.registerHistogramTools()
        server.registerDominatorTools()
        server.registerObjectTools()
        server.registerThreadTools()
        server.registerQueryTools()

        return server
    }
}
