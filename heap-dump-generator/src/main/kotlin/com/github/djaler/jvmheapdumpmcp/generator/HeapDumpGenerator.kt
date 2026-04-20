package com.github.djaler.jvmheapdumpmcp.generator

import com.github.djaler.jvmheapdumpmcp.generator.model.*
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path

fun main(args: Array<String>) {
    require(args.size == 2) {
        "Usage: HeapDumpGenerator <baseline.hprof> <leaked.hprof>"
    }
    val baselinePath = args[0]
    val leakedPath = args[1]

    println("Creating data structures...")

    // 1. Retained set tree
    val retainedTree = createRetainedSetData()
    println("  RetainedTreeRoot: 1 root → 3 children → 6 grandchildren")

    // 2. Collection fill rates
    val collections = createCollectionData()
    println("  CollectionHolder: ${collections.size} holders × 7 collections = ${collections.size * 7} total")

    // 3. Map contents
    val maps = createMapData()
    println("  KnownMaps: stringToObject=50, intToString=30, concurrentMap=20, linkedMap=10")

    // 4. ThreadLocal data — start threads and wait for them to be ready
    val threadSetup = createThreadLocalData()
    println("  Threads: ${threadSetup.threads.map { it.name }}")
    println("  Waiting for threads to set ThreadLocal values...")
    threadSetup.dumpBarrier.await() // all threads ready, ThreadLocals set
    println("  Threads ready.")

    // 5. Take baseline dump
    deleteIfExists(baselinePath)
    println("Dumping baseline → $baselinePath")
    dumpHeap(baselinePath)
    println("  Baseline dump complete: ${Files.size(Path.of(baselinePath)) / 1024} KB")

    // 6. Create leaked objects (only in second dump)
    @Suppress("UNUSED_VARIABLE")
    val leakedObjects = createLeakData()
    println("  LeakedObject: ${leakedObjects.size} instances created")

    // 7. Take leaked dump
    deleteIfExists(leakedPath)
    println("Dumping leaked → $leakedPath")
    dumpHeap(leakedPath)
    println("  Leaked dump complete: ${Files.size(Path.of(leakedPath)) / 1024} KB")

    // 8. Release threads
    threadSetup.exitBarrier.await()
    threadSetup.threads.forEach { it.join(5_000) }
    println("Threads joined. Done.")

    // Keep references alive until here (prevent GC from collecting before dump)
    keepAlive(retainedTree, collections, maps, leakedObjects)
}

private fun dumpHeap(path: String) {
    val server = ManagementFactory.getPlatformMBeanServer()
    val mxBean = ManagementFactory.newPlatformMXBeanProxy(
        server,
        "com.sun.management:type=HotSpotDiagnostic",
        com.sun.management.HotSpotDiagnosticMXBean::class.java,
    )
    mxBean.dumpHeap(path, true)
}

private fun deleteIfExists(path: String) {
    Files.deleteIfExists(Path.of(path))
}

/**
 * Ensures objects are reachable at the point of call (prevents JIT/GC from collecting them early).
 */
@Suppress("UNUSED_PARAMETER")
private fun keepAlive(vararg refs: Any?) {
    // no-op — the call itself keeps arguments reachable
}
