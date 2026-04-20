package com.github.djaler.jvmheapdumpmcp.generator.model

/**
 * Collections with varying fill rates for testing get_collection_fill_rates.
 *
 * Each holder contains HashMaps and ArrayLists at different fill levels:
 * - empty (0%), sparse (~6%), half (~50%), full (~75% for maps, 100% for lists)
 */
class CollectionHolder(val name: String) {
    var emptyMap: HashMap<String, String> = HashMap()              // size=0, capacity=16
    var sparseMap: HashMap<String, String> = HashMap()             // size=1, capacity=16
    var halfMap: HashMap<String, String> = HashMap()               // size=8, capacity=16
    var fullMap: HashMap<String, String> = HashMap()               // size=12, capacity=16

    var emptyList: ArrayList<String> = ArrayList(100)              // size=0, capacity=100
    var sparseList: ArrayList<String> = ArrayList(100)             // size=1, capacity=100
    var fullList: ArrayList<String> = ArrayList(100)               // size=100, capacity=100
}

fun createCollectionData(): List<CollectionHolder> {
    return List(100) { i ->
        CollectionHolder("holder-$i").apply {
            // sparseMap: 1 entry
            sparseMap["key-0"] = "value-0"

            // halfMap: 8 entries (50% of default capacity 16)
            repeat(8) { j -> halfMap["key-$j"] = "value-$j" }

            // fullMap: 12 entries (75% of capacity 16, just below resize threshold)
            repeat(12) { j -> fullMap["key-$j"] = "value-$j" }

            // sparseList: 1 element, capacity 100
            sparseList.add("element-0")

            // fullList: 100 elements, capacity 100
            repeat(100) { j -> fullList.add("element-$j") }
        }
    }
}
