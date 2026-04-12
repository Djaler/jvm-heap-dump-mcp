package com.github.djaler.jvmheapdumpmcp

import com.github.djaler.jvmheapdumpmcp.server.McpServerFactory
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream

fun main(): Unit = runBlocking {
    // MCP stdio transport uses stdout exclusively for JSON-RPC.
    // Grab the raw fd=1 stream before anything touches System.out, then
    // redirect System.out → stderr so library init messages (e.g. kotlin-logging's
    // "initializing..." line) never corrupt the JSON-RPC stream.
    val rawStdout = FileOutputStream(FileDescriptor.out)
    System.setOut(PrintStream(System.err, true))

    val transport = StdioServerTransport(
        inputStream = System.`in`.asSource().buffered(),
        outputStream = rawStdout.asSink().buffered(),
    )

    val server = McpServerFactory.create()
    val session = server.createSession(transport)

    val done = CompletableDeferred<Unit>()
    session.onClose { done.complete(Unit) }
    done.await()
}
