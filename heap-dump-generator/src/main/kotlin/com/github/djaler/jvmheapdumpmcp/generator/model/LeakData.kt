package com.github.djaler.jvmheapdumpmcp.generator.model

/**
 * Objects that appear only in the second dump for testing compare_class_histograms.
 */
class LeakedObject(val id: Int, val payload: ByteArray)

fun createLeakData(): List<LeakedObject> {
    return List(500) { i ->
        LeakedObject(id = i, payload = ByteArray(1_000))
    }
}
