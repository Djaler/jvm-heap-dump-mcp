package com.github.djaler.jvmheapdumpmcp.generator.model

import java.util.concurrent.ConcurrentHashMap

/**
 * Maps with known key/value types and counts for testing get_map_contents.
 */
class MapEntry(val data: ByteArray)

class KnownMaps {
    val stringToObject: HashMap<String, MapEntry> = HashMap()
    val intToString: HashMap<Int, String> = HashMap()
    val concurrentMap: ConcurrentHashMap<String, MapEntry> = ConcurrentHashMap()
    val linkedMap: LinkedHashMap<String, String> = LinkedHashMap()
}

fun createMapData(): KnownMaps {
    return KnownMaps().apply {
        // 50 entries: String → MapEntry
        repeat(50) { i ->
            stringToObject["key-$i"] = MapEntry(ByteArray(100))
        }

        // 30 entries: Int → String
        repeat(30) { i ->
            intToString[i] = "value-$i"
        }

        // 20 entries: String → MapEntry (concurrent)
        repeat(20) { i ->
            concurrentMap["concurrent-$i"] = MapEntry(ByteArray(100))
        }

        // 10 entries: String → String (linked)
        repeat(10) { i ->
            linkedMap["linked-$i"] = "value-$i"
        }
    }
}
