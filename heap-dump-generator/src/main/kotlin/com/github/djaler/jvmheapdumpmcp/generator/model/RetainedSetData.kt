package com.github.djaler.jvmheapdumpmcp.generator.model

/**
 * Tree structure with known retained size for testing get_retained_set.
 *
 * Structure: 1 root → 3 children → 2 grandchildren each (6 leaves).
 * Each leaf holds a 10 KB payload → total retained ~60 KB + object overhead.
 */
class RetainedTreeRoot {
    val children: MutableList<RetainedTreeNode> = mutableListOf()
}

class RetainedTreeNode(
    val payload: ByteArray,
    val children: MutableList<RetainedTreeNode> = mutableListOf(),
)

fun createRetainedSetData(): RetainedTreeRoot {
    val root = RetainedTreeRoot()
    repeat(3) { i ->
        val child = RetainedTreeNode(payload = ByteArray(10_000))
        repeat(2) { j ->
            child.children.add(RetainedTreeNode(payload = ByteArray(10_000)))
        }
        root.children.add(child)
    }
    return root
}
