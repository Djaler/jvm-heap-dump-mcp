package com.github.djaler.jvmheapdumpmcp.generator.model

import java.util.concurrent.CyclicBarrier

/**
 * ThreadLocal values for testing get_thread_local_variables.
 *
 * Creates named threads with ThreadLocal<ThreadLocalPayload>, kept alive
 * via CyclicBarrier until the heap dump is taken.
 */
class ThreadLocalPayload(val id: Int, val data: ByteArray)

class ThreadLocalSetup(
    val threads: List<Thread>,
    val dumpBarrier: CyclicBarrier,
    val exitBarrier: CyclicBarrier,
)

fun createThreadLocalData(): ThreadLocalSetup {
    val dumpBarrier = CyclicBarrier(3) // 2 threads + main
    val exitBarrier = CyclicBarrier(3)

    val threadLocal = ThreadLocal<ThreadLocalPayload>()

    val threads = List(2) { i ->
        Thread({
            threadLocal.set(ThreadLocalPayload(id = i + 1, data = ByteArray(5_000)))
            dumpBarrier.await() // signal ready, wait for dump
            exitBarrier.await() // wait for permission to exit
        }, "test-thread-${i + 1}")
    }

    threads.forEach { it.start() }

    return ThreadLocalSetup(threads, dumpBarrier, exitBarrier)
}
