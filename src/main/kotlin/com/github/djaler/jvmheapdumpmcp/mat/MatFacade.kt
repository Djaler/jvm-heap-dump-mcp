package com.github.djaler.jvmheapdumpmcp.mat

import com.github.djaler.jvmheapdumpmcp.model.*
import org.eclipse.mat.query.IContextObject
import org.eclipse.mat.query.IResultTable
import org.eclipse.mat.query.IResultTree
import org.eclipse.mat.query.results.TextResult
import org.eclipse.mat.report.QuerySpec
import org.eclipse.mat.report.SectionSpec
import org.eclipse.mat.snapshot.ISnapshot
import org.eclipse.mat.snapshot.model.IInstance
import org.eclipse.mat.snapshot.model.IObject
import org.eclipse.mat.snapshot.model.IObjectArray
import org.eclipse.mat.snapshot.model.ObjectReference
import org.eclipse.mat.snapshot.query.SnapshotQuery
import org.eclipse.mat.util.IProgressListener
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object MatFacade {

    // -------------------------------------------------------------------------
    // Heap summary
    // -------------------------------------------------------------------------

    fun getHeapSummary(snapshot: ISnapshot): HeapSummary {
        val info = snapshot.snapshotInfo
        val date = info.creationDate?.toInstant()?.let {
            DateTimeFormatter.ISO_INSTANT.format(it)
        }
        return HeapSummary(
            usedHeapSize = info.usedHeapSize,
            objectCount = info.numberOfObjects.toLong(),
            classCount = info.numberOfClasses,
            gcRootCount = info.numberOfGCRoots,
            snapshotDate = date,
            jvmInfo = info.jvmInfo,
        )
    }

    // -------------------------------------------------------------------------
    // Leak suspects (via MAT's "leakhunter" query)
    // -------------------------------------------------------------------------

    fun getLeakSuspects(snapshot: ISnapshot): List<LeakSuspect> {
        val result = try {
            SnapshotQuery.lookup("find_leaks", snapshot)
                ?.execute(NoOpProgress)
        } catch (e: Exception) {
            return listOf(LeakSuspect("Could not run leak hunter: ${e.message}", 0L, "unknown"))
        } ?: return emptyList()

        return when (result) {
            is TextResult -> listOf(LeakSuspect(result.text, 0L, "info"))
            is SectionSpec -> result.children.filterIsInstance<QuerySpec>().mapNotNull { spec ->
                val specResult = spec.result
                if (specResult is TextResult) {
                    LeakSuspect(
                        description = spec.name ?: "Suspect",
                        retainedHeap = 0L,
                        probability = "high",
                        detail = specResult.text,
                    )
                } else null
            }

            else -> emptyList()
        }
    }

    // -------------------------------------------------------------------------
    // Class histogram
    // -------------------------------------------------------------------------

    fun getClassHistogram(snapshot: ISnapshot, sortBy: String, filter: String?, limit: Int): List<HistogramEntry> {
        val histogram = snapshot.getHistogram(NoOpProgress)
        val filterRegex = filter?.let { Regex(it, RegexOption.IGNORE_CASE) }
        val records = histogram.classHistogramRecords.let { all ->
            if (filterRegex != null) all.filter { filterRegex.containsMatchIn(it.label) } else all
        }
        val sorted = when (sortBy.uppercase()) {
            "RETAINED_HEAP", "RETAINED" -> {
                records.onEach { rec ->
                    if (rec.retainedHeapSize == 0L) {
                        try {
                            rec.calculateRetainedSize(snapshot, false, false, NoOpProgress)
                        } catch (_: Exception) {
                        }
                    }
                }.sortedByDescending { it.retainedHeapSize }
            }

            "OBJECTS" -> records.sortedByDescending { it.numberOfObjects }
            else -> records.sortedByDescending { it.usedHeapSize } // default: shallow
        }
        return sorted.take(limit).map { rec ->
            HistogramEntry(
                className = rec.label,
                objectCount = rec.numberOfObjects,
                shallowHeap = rec.usedHeapSize,
                retainedHeap = rec.retainedHeapSize,
            )
        }
    }

    // -------------------------------------------------------------------------
    // Dominator tree — top roots
    // -------------------------------------------------------------------------

    fun getDominatorTree(snapshot: ISnapshot, limit: Int): List<DominatorEntry> {
        val rootIds = snapshot.getImmediateDominatedIds(-1)
        val totalHeap = snapshot.snapshotInfo.usedHeapSize.toDouble().coerceAtLeast(1.0)

        return rootIds.map { id ->
            val obj = snapshot.getObject(id)
            val retained = snapshot.getRetainedHeapSize(id)
            DominatorEntry(
                objectId = id,
                className = obj.clazz.name,
                shallowHeap = obj.usedHeapSize,
                retainedHeap = retained,
                retainedPercent = retained / totalHeap * 100.0,
                label = obj.displayName,
            )
        }.sortedByDescending { it.retainedHeap }.take(limit)
    }

    // -------------------------------------------------------------------------
    // Dominator tree — children of a node
    // -------------------------------------------------------------------------

    fun getDominatorTreeChildren(snapshot: ISnapshot, objectId: Int, limit: Int): List<DominatorEntry> {
        val childIds = snapshot.getImmediateDominatedIds(objectId)
        val totalHeap = snapshot.snapshotInfo.usedHeapSize.toDouble().coerceAtLeast(1.0)

        return childIds.map { id ->
            val obj = snapshot.getObject(id)
            val retained = snapshot.getRetainedHeapSize(id)
            DominatorEntry(
                objectId = id,
                className = obj.clazz.name,
                shallowHeap = obj.usedHeapSize,
                retainedHeap = retained,
                retainedPercent = retained / totalHeap * 100.0,
                label = obj.displayName,
            )
        }.sortedByDescending { it.retainedHeap }.take(limit)
    }

    // -------------------------------------------------------------------------
    // Object info
    // -------------------------------------------------------------------------

    fun getObjectInfo(snapshot: ISnapshot, objectId: Int): ObjectInfo {
        val obj = snapshot.getObject(objectId)
        val gcRootInfos = if (snapshot.isGCRoot(objectId)) snapshot.getGCRootInfo(objectId) else null
        val gcRootType = gcRootInfos?.firstOrNull()?.let {
            org.eclipse.mat.snapshot.model.GCRootInfo.getTypeAsString(it.type)
        }

        val fields = when (obj) {
            is IInstance -> obj.fields.map { f ->
                val value = f.value
                val refId = (value as? ObjectReference)?.let {
                    try {
                        it.objectId
                    } catch (_: Exception) {
                        null
                    }
                }
                // Resolve String field values via classSpecificName (survives obfuscation for UTF8-sourced strings)
                val displayValue = if (refId != null) {
                    try {
                        val refObj = snapshot.getObject(refId)
                        if (refObj.clazz.name == "java.lang.String") {
                            refObj.classSpecificName ?: value?.toString() ?: "null"
                        } else value?.toString() ?: "null"
                    } catch (_: Exception) {
                        value?.toString() ?: "null"
                    }
                } else value?.toString() ?: "null"
                FieldInfo(
                    name = f.name,
                    type = f.verboseSignature,
                    value = displayValue,
                    objectId = refId,
                )
            }

            is IObjectArray -> obj.referenceArray.take(20).mapIndexed { i, addr ->
                val refId = if (addr != 0L) try {
                    snapshot.mapAddressToId(addr)
                } catch (_: Exception) {
                    null
                } else null
                FieldInfo(name = "[$i]", type = "ref", value = "0x${addr.toString(16)}", objectId = refId)
            }

            else -> emptyList()
        }

        return ObjectInfo(
            objectId = objectId,
            className = obj.clazz.name,
            shallowHeap = obj.usedHeapSize,
            retainedHeap = snapshot.getRetainedHeapSize(objectId),
            gcRootType = gcRootType,
            fields = fields,
        )
    }

    // -------------------------------------------------------------------------
    // Outbound references
    // -------------------------------------------------------------------------

    fun getOutboundReferences(snapshot: ISnapshot, objectId: Int, limit: Int): List<ReferenceInfo> {
        val obj = snapshot.getObject(objectId)
        return obj.outboundReferences.take(limit).mapNotNull { ref ->
            val refId = try {
                ref.objectId
            } catch (_: Exception) {
                return@mapNotNull null
            }
            val refObj = try {
                snapshot.getObject(refId)
            } catch (_: Exception) {
                return@mapNotNull null
            }
            ReferenceInfo(
                objectId = refId,
                className = refObj.clazz.name,
                fieldName = ref.name,
                shallowHeap = refObj.usedHeapSize,
                retainedHeap = snapshot.getRetainedHeapSize(refId),
                label = refObj.displayName,
            )
        }
    }

    // -------------------------------------------------------------------------
    // Inbound references
    // -------------------------------------------------------------------------

    fun getInboundReferences(snapshot: ISnapshot, objectId: Int, limit: Int): List<ReferenceInfo> {
        val inboundIds = snapshot.getInboundRefererIds(objectId)
        return inboundIds.take(limit).map { refId ->
            val refObj = snapshot.getObject(refId)
            ReferenceInfo(
                objectId = refId,
                className = refObj.clazz.name,
                fieldName = null,
                shallowHeap = refObj.usedHeapSize,
                retainedHeap = snapshot.getRetainedHeapSize(refId),
                label = refObj.displayName,
            )
        }
    }

    // -------------------------------------------------------------------------
    // Shortest paths to GC roots
    // -------------------------------------------------------------------------

    fun getPathToGcRoots(snapshot: ISnapshot, objectId: Int, limit: Int): List<GcRootPath> {
        val computer = snapshot.getPathsFromGCRoots(objectId, emptyMap())
        val paths = mutableListOf<GcRootPath>()
        repeat(limit) {
            val path = computer.nextShortestPath ?: return@repeat
            val steps = path.map { id ->
                val obj = snapshot.getObject(id)
                val gcRootInfos = if (snapshot.isGCRoot(id)) snapshot.getGCRootInfo(id) else null
                GcRootStep(
                    objectId = id,
                    className = obj.clazz.name,
                    fieldName = null,
                    shallowHeap = obj.usedHeapSize,
                    gcRootType = gcRootInfos?.firstOrNull()?.let {
                        org.eclipse.mat.snapshot.model.GCRootInfo.getTypeAsString(it.type)
                    },
                )
            }
            paths.add(GcRootPath(steps))
        }
        return paths
    }

    // -------------------------------------------------------------------------
    // Threads
    // -------------------------------------------------------------------------

    fun getThreads(snapshot: ISnapshot, sortBy: String, filter: String?): List<ThreadInfo> {
        val result = try {
            SnapshotQuery.lookup("thread_overview", snapshot)
                ?.execute(NoOpProgress) as? IResultTree
        } catch (_: Exception) {
            null
        } ?: return emptyList()

        val threads = result.elements.mapNotNull { row ->
            val ctx = result.getContext(row) ?: return@mapNotNull null
            val id = ctx.objectId
            val obj = snapshot.getObject(id)
            // classSpecificName reads from HPROF_START_THREAD UTF8 records (survives obfuscation)
            val nameVal = obj.classSpecificName

            val stack = try {
                snapshot.getThreadStack(id)?.stackFrames?.map { it.text } ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }

            ThreadInfo(
                objectId = id,
                name = nameVal ?: obj.displayName,
                retainedHeap = snapshot.getRetainedHeapSize(id),
                shallowHeap = obj.usedHeapSize,
                stackFrames = stack,
            )
        }

        val filtered = if (filter != null) threads.filter { it.name.contains(filter, ignoreCase = true) } else threads
        return when (sortBy.uppercase()) {
            "NAME" -> filtered.sortedBy { it.name }
            "SHALLOW_HEAP", "SHALLOW" -> filtered.sortedByDescending { it.shallowHeap }
            else -> filtered.sortedByDescending { it.retainedHeap }
        }
    }

    // -------------------------------------------------------------------------
    // OQL
    // -------------------------------------------------------------------------

    fun executeOql(snapshot: ISnapshot, query: String, limit: Int): OqlResult {
        val result = SnapshotQuery.parse("oql", snapshot)
            .setArgument("queryString", query)
            .execute(NoOpProgress)

        return when (result) {
            is IResultTable -> {
                val cols = result.columns.map { it.label }
                val rows = (0 until minOf(result.rowCount, limit)).map { i ->
                    val row = result.getRow(i)
                    cols.indices.map { c -> result.getColumnValue(row, c)?.toString() ?: "" }
                }
                OqlResult(
                    query = query,
                    columns = cols,
                    rows = rows,
                    truncated = result.rowCount > limit,
                )
            }

            is TextResult -> OqlResult(query, listOf("result"), listOf(listOf(result.text)))
            else -> OqlResult(query, emptyList(), emptyList())
        }
    }

    // -------------------------------------------------------------------------
    // Find strings
    // -------------------------------------------------------------------------

    fun findStrings(snapshot: ISnapshot, pattern: String, limit: Int): List<StringInfo> {
        val classes = snapshot.getClassesByName("java.lang.String", false)
        if (classes.isEmpty()) return emptyList()

        val regex = pattern.toRegex(RegexOption.IGNORE_CASE)
        val results = mutableListOf<StringInfo>()

        for (cls in classes) {
            if (results.size >= limit) break
            for (id in cls.objectIds) {
                if (results.size >= limit) break
                val obj = snapshot.getObject(id)
                val str = obj.classSpecificName ?: continue
                if (regex.containsMatchIn(str)) {
                    results.add(
                        StringInfo(
                            objectId = id,
                            value = str,
                            retainedHeap = snapshot.getRetainedHeapSize(id),
                        )
                    )
                }
            }
        }
        return results
    }

    // -------------------------------------------------------------------------
    // Inspect array contents
    // -------------------------------------------------------------------------

    fun inspectArray(snapshot: ISnapshot, objectId: Int, offset: Int, limit: Int): ArrayInspection {
        val obj = snapshot.getObject(objectId)
        return when (obj) {
            is org.eclipse.mat.snapshot.model.IPrimitiveArray -> {
                val len = obj.length
                val elementType = obj.clazz.name.removeSuffix("[]")
                val end = minOf(offset + limit, len)
                val elements = (offset until end).map { i ->
                    when (elementType) {
                        "byte" -> {
                            val b = (obj.getValueAt(i) as? Byte) ?: 0
                            val hex = "%02x".format(b.toInt() and 0xFF)
                            val ascii = if (b in 32..126) b.toInt().toChar().toString() else "."
                            "$hex ($ascii)"
                        }

                        "char" -> {
                            val c = (obj.getValueAt(i) as? Char) ?: '?'
                            c.toString()
                        }

                        else -> obj.getValueAt(i)?.toString() ?: "null"
                    }
                }
                ArrayInspection(objectId, obj.clazz.name, len, elementType, elements, offset, end < len)
            }

            is IObjectArray -> {
                val len = obj.length
                val end = minOf(offset + limit, len)
                val refs = obj.referenceArray
                val elements = (offset until end).map { i ->
                    val addr = refs[i]
                    if (addr == 0L) "null"
                    else {
                        val refId = try {
                            snapshot.mapAddressToId(addr)
                        } catch (_: Exception) {
                            null
                        }
                        if (refId != null) {
                            val refObj = try {
                                snapshot.getObject(refId)
                            } catch (_: Exception) {
                                null
                            }
                            "[$refId] ${refObj?.clazz?.name ?: "?"}"
                        } else "0x${addr.toString(16)}"
                    }
                }
                ArrayInspection(objectId, obj.clazz.name, len, "object", elements, offset, end < len)
            }

            else -> throw IllegalArgumentException("Object $objectId is not an array (${obj.clazz.name})")
        }
    }

    // -------------------------------------------------------------------------
    // Class instances
    // -------------------------------------------------------------------------

    fun getClassInstances(
        snapshot: ISnapshot,
        className: String,
        sortBy: String,
        limit: Int,
    ): List<ClassInstanceEntry> {
        val classes = snapshot.getClassesByName(className, true)
        if (classes.isNullOrEmpty()) {
            val regex = className.toRegex(RegexOption.IGNORE_CASE)
            val allClasses = snapshot.classes.filter { regex.containsMatchIn(it.name) }
            return allClasses.flatMap { cls ->
                cls.objectIds.map { id ->
                    val obj = snapshot.getObject(id)
                    ClassInstanceEntry(
                        id,
                        cls.name,
                        obj.usedHeapSize,
                        snapshot.getRetainedHeapSize(id),
                        obj.classSpecificName
                    )
                }
            }.let { entries ->
                when (sortBy.uppercase()) {
                    "SHALLOW_HEAP" -> entries.sortedByDescending { it.shallowHeap }
                    else -> entries.sortedByDescending { it.retainedHeap }
                }
            }.take(limit)
        }
        return classes.flatMap { cls ->
            cls.objectIds.map { id ->
                val obj = snapshot.getObject(id)
                ClassInstanceEntry(
                    id,
                    cls.name,
                    obj.usedHeapSize,
                    snapshot.getRetainedHeapSize(id),
                    obj.classSpecificName
                )
            }
        }.let { entries ->
            when (sortBy.uppercase()) {
                "SHALLOW_HEAP" -> entries.sortedByDescending { it.shallowHeap }
                else -> entries.sortedByDescending { it.retainedHeap }
            }
        }.take(limit)
    }

    // -------------------------------------------------------------------------
    // Retained set
    // -------------------------------------------------------------------------

    fun getRetainedSet(snapshot: ISnapshot, objectId: Int, limit: Int): List<HistogramEntry> {
        val retainedIds = snapshot.getRetainedSet(intArrayOf(objectId), NoOpProgress)
        val classMap = mutableMapOf<String, Triple<Long, Long, Long>>() // count, shallow, retained
        for (id in retainedIds) {
            val obj = snapshot.getObject(id)
            val className = obj.clazz.name
            val current = classMap.getOrDefault(className, Triple(0L, 0L, 0L))
            classMap[className] = Triple(
                current.first + 1,
                current.second + obj.usedHeapSize,
                current.third + snapshot.getRetainedHeapSize(id),
            )
        }
        return classMap.entries
            .sortedByDescending { it.value.third }
            .take(limit)
            .map { (className, stats) ->
                HistogramEntry(className, stats.first, stats.second, stats.third)
            }
    }

    // -------------------------------------------------------------------------
    // Compare class histograms
    // -------------------------------------------------------------------------

    fun compareClassHistograms(
        snapshot1: ISnapshot,
        snapshot2: ISnapshot,
        sortBy: String,
        limit: Int,
    ): List<HistogramDiffEntry> {
        val histogram1 = snapshot1.getHistogram(NoOpProgress)
        val histogram2 = snapshot2.getHistogram(NoOpProgress)

        val map1 = mutableMapOf<String, Pair<Long, Long>>() // count, shallow
        for (rec in histogram1.classHistogramRecords) {
            map1[rec.label] = Pair(rec.numberOfObjects, rec.usedHeapSize)
        }

        val map2 = mutableMapOf<String, Pair<Long, Long>>()
        for (rec in histogram2.classHistogramRecords) {
            map2[rec.label] = Pair(rec.numberOfObjects, rec.usedHeapSize)
        }

        val allClasses = map1.keys + map2.keys
        val diffs = allClasses.map { className ->
            val (count1, shallow1) = map1.getOrDefault(className, Pair(0L, 0L))
            val (count2, shallow2) = map2.getOrDefault(className, Pair(0L, 0L))
            HistogramDiffEntry(
                className = className,
                objectCountDelta = count2 - count1,
                shallowHeapDelta = shallow2 - shallow1,
                objectCount1 = count1,
                objectCount2 = count2,
            )
        }.filter { it.objectCountDelta != 0L || it.shallowHeapDelta != 0L }

        val sorted = when (sortBy.uppercase()) {
            "OBJECT_COUNT_DELTA" -> diffs.sortedByDescending { kotlin.math.abs(it.objectCountDelta) }
            else -> diffs.sortedByDescending { kotlin.math.abs(it.shallowHeapDelta) }
        }
        return sorted.take(limit)
    }

    // -------------------------------------------------------------------------
    // Collection fill rates
    // -------------------------------------------------------------------------

    fun getCollectionFillRates(snapshot: ISnapshot, className: String, limit: Int): CollectionFillRateResult {
        val classes = snapshot.getClassesByName(className, true)
        if (classes.isNullOrEmpty()) {
            return CollectionFillRateResult(className, 0, emptyList())
        }

        data class FillInfo(val fillRatio: Double, val shallowHeap: Long, val wastedHeap: Long)

        val fills = mutableListOf<FillInfo>()
        for (cls in classes) {
            if (fills.size >= limit) break
            for (id in cls.objectIds) {
                if (fills.size >= limit) break
                val obj = snapshot.getObject(id)
                if (obj !is IInstance) continue

                val size = obj.fields.firstOrNull { it.name == "size" }
                    ?.value?.toString()?.toIntOrNull() ?: 0

                // Try to get capacity from different collection types
                val capacity = getCollectionCapacity(obj)
                if (capacity <= 0) continue

                val ratio = if (capacity > 0) size.toDouble() / capacity else 0.0
                val shallow = obj.usedHeapSize
                val wasted = if (capacity > size) ((capacity - size).toDouble() / capacity * shallow).toLong() else 0L
                fills.add(FillInfo(ratio, shallow, wasted))
            }
        }

        data class BucketDef(val label: String, val predicate: (Double) -> Boolean)

        val bucketDefs = listOf(
            BucketDef("0% (empty)") { it == 0.0 },
            BucketDef("1-25%") { it > 0.0 && it <= 0.25 },
            BucketDef("26-50%") { it > 0.25 && it <= 0.50 },
            BucketDef("51-75%") { it > 0.50 && it <= 0.75 },
            BucketDef("76-100%") { it > 0.75 },
        )
        val buckets = bucketDefs.map { (label, predicate) ->
            val matching = fills.filter { predicate(it.fillRatio) }
            FillRateBucket(
                rangeLabel = label,
                count = matching.size,
                totalShallowHeap = matching.sumOf { it.shallowHeap },
                totalWastedHeap = matching.sumOf { it.wastedHeap },
            )
        }

        return CollectionFillRateResult(className, fills.size, buckets)
    }

    private fun getCollectionCapacity(instance: IInstance): Int {
        // HashMap/LinkedHashMap: table array length
        val tableField = instance.fields.firstOrNull { it.name == "table" }
        if (tableField != null) {
            val tableRef = tableField.value
            if (tableRef is ObjectReference) {
                return try {
                    val tableObj = tableRef.`object`
                    if (tableObj is IObjectArray) tableObj.length else 0
                } catch (_: Exception) {
                    0
                }
            }
        }
        // ArrayList: elementData array length
        val elementDataField = instance.fields.firstOrNull { it.name == "elementData" }
        if (elementDataField != null) {
            val ref = elementDataField.value
            if (ref is ObjectReference) {
                return try {
                    val arrObj = ref.`object`
                    if (arrObj is IObjectArray) arrObj.length else 0
                } catch (_: Exception) {
                    0
                }
            }
        }
        return 0
    }

    // -------------------------------------------------------------------------
    // Map contents
    // -------------------------------------------------------------------------

    fun getMapContents(snapshot: ISnapshot, objectId: Int, limit: Int): MapContentsResult {
        val obj = snapshot.getObject(objectId)
        val className = obj.clazz.name

        val entries = mutableListOf<MapEntryInfo>()
        val keyTypeMap = mutableMapOf<String, Pair<Int, Long>>() // className -> (count, retainedSum)
        val valueTypeMap = mutableMapOf<String, Pair<Int, Long>>()

        // Walk through the map's internal structure to find key-value entries
        val entryNodes = collectMapEntries(snapshot, obj)

        for ((keyId, valueId) in entryNodes) {
            val keyObj = try {
                snapshot.getObject(keyId)
            } catch (_: Exception) {
                continue
            }
            val valueObj = try {
                snapshot.getObject(valueId)
            } catch (_: Exception) {
                continue
            }
            val valueRetained = snapshot.getRetainedHeapSize(valueId)

            val keyClassName = keyObj.clazz.name
            val valueClassName = valueObj.clazz.name

            val kc = keyTypeMap.getOrDefault(keyClassName, Pair(0, 0L))
            keyTypeMap[keyClassName] = Pair(kc.first + 1, kc.second + snapshot.getRetainedHeapSize(keyId))
            val vc = valueTypeMap.getOrDefault(valueClassName, Pair(0, 0L))
            valueTypeMap[valueClassName] = Pair(vc.first + 1, vc.second + valueRetained)

            if (entries.size < limit) {
                entries.add(
                    MapEntryInfo(
                        keyObjectId = keyId,
                        keyClassName = keyClassName,
                        keyLabel = keyObj.classSpecificName,
                        valueObjectId = valueId,
                        valueClassName = valueClassName,
                        valueRetainedHeap = valueRetained,
                    )
                )
            }
        }

        entries.sortByDescending { it.valueRetainedHeap }

        return MapContentsResult(
            objectId = objectId,
            className = className,
            entryCount = entryNodes.size,
            totalRetainedHeap = snapshot.getRetainedHeapSize(objectId),
            keyTypeSummary = keyTypeMap.entries
                .sortedByDescending { it.value.second }
                .map { (cn, pair) -> TypeSummary(cn, pair.first, pair.second) },
            valueTypeSummary = valueTypeMap.entries
                .sortedByDescending { it.value.second }
                .map { (cn, pair) -> TypeSummary(cn, pair.first, pair.second) },
            topEntries = entries.take(limit),
        )
    }

    /**
     * Collects key-value pairs from a Map by walking its internal node structure.
     * Supports HashMap, LinkedHashMap, ConcurrentHashMap.
     */
    private fun collectMapEntries(snapshot: ISnapshot, mapObj: IObject): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        if (mapObj !is IInstance) return result

        // Get the table/buckets array
        val tableField = mapObj.fields.firstOrNull { it.name == "table" }
            ?: return result
        val tableRef = tableField.value as? ObjectReference ?: return result
        val tableArray = try {
            tableRef.`object` as? IObjectArray
        } catch (_: Exception) {
            null
        }
            ?: return result

        val refs = tableArray.referenceArray
        for (addr in refs) {
            if (addr == 0L) continue
            val nodeId = try {
                snapshot.mapAddressToId(addr)
            } catch (_: Exception) {
                continue
            }
            walkNode(snapshot, nodeId, result)
        }
        return result
    }

    private fun walkNode(snapshot: ISnapshot, startNodeId: Int, result: MutableList<Pair<Int, Int>>) {
        val visited = mutableSetOf<Int>()
        var currentId: Int? = startNodeId

        while (currentId != null && visited.add(currentId)) {
            val node = try {
                snapshot.getObject(currentId) as? IInstance
            } catch (_: Exception) {
                return
            } ?: return

            val className = node.clazz.name

            // ConcurrentHashMap$TreeBin wraps a TreeNode — descend into first
            if (className.contains("TreeBin")) {
                val firstField = node.fields.firstOrNull { it.name == "first" }
                val firstRef = firstField?.value as? ObjectReference ?: return
                currentId = try {
                    firstRef.objectId
                } catch (_: Exception) {
                    return
                }
                continue
            }

            // Extract key and value
            val keyField = node.fields.firstOrNull { it.name == "key" }
            val valueField = node.fields.firstOrNull { it.name == "val" }
                ?: node.fields.firstOrNull { it.name == "value" }

            val keyRef = keyField?.value as? ObjectReference
            val valueRef = valueField?.value as? ObjectReference
            if (keyRef != null && valueRef != null) {
                val keyId = try {
                    keyRef.objectId
                } catch (_: Exception) {
                    -1
                }
                val valueId = try {
                    valueRef.objectId
                } catch (_: Exception) {
                    -1
                }
                if (keyId >= 0 && valueId >= 0) {
                    result.add(Pair(keyId, valueId))
                }
            }

            // Follow next pointer for chaining
            val nextField = node.fields.firstOrNull { it.name == "next" }
            val nextRef = nextField?.value as? ObjectReference
            currentId = if (nextRef != null) {
                try {
                    nextRef.objectId
                } catch (_: Exception) {
                    null
                }
            } else null
        }
    }

    // -------------------------------------------------------------------------
    // ThreadLocal variables
    // -------------------------------------------------------------------------

    fun getThreadLocalVariables(snapshot: ISnapshot, objectId: Int): List<ThreadLocalInfo> {
        val threadObj = snapshot.getObject(objectId) as? IInstance
            ?: throw IllegalArgumentException("Object $objectId is not a Thread instance")

        val threadLocalsField = threadObj.fields.firstOrNull { it.name == "threadLocals" }
            ?: return emptyList()
        val tlMapRef = threadLocalsField.value as? ObjectReference ?: return emptyList()
        val tlMapObj = try {
            tlMapRef.`object` as? IInstance
        } catch (_: Exception) {
            return emptyList()
        }
            ?: return emptyList()

        val tableField = tlMapObj.fields.firstOrNull { it.name == "table" }
            ?: return emptyList()
        val tableRef = tableField.value as? ObjectReference ?: return emptyList()
        val tableArray = try {
            tableRef.`object` as? IObjectArray
        } catch (_: Exception) {
            return emptyList()
        }
            ?: return emptyList()

        val results = mutableListOf<ThreadLocalInfo>()
        val refs = tableArray.referenceArray
        for (addr in refs) {
            if (addr == 0L) continue
            val entryId = try {
                snapshot.mapAddressToId(addr)
            } catch (_: Exception) {
                continue
            }
            val entry = try {
                snapshot.getObject(entryId) as? IInstance
            } catch (_: Exception) {
                continue
            }
                ?: continue

            // Entry extends WeakReference<ThreadLocal> — referent is the ThreadLocal key
            val referentField = entry.fields.firstOrNull { it.name == "referent" }
            val referentRef = referentField?.value as? ObjectReference
            val referentId = referentRef?.let {
                try {
                    it.objectId
                } catch (_: Exception) {
                    null
                }
            }
            val referentObj = referentId?.let {
                try {
                    snapshot.getObject(it)
                } catch (_: Exception) {
                    null
                }
            }

            // value field holds the stored value
            val valueField = entry.fields.firstOrNull { it.name == "value" }
            val valueRef = valueField?.value as? ObjectReference ?: continue
            val valueId = try {
                valueRef.objectId
            } catch (_: Exception) {
                continue
            }
            val valueObj = try {
                snapshot.getObject(valueId)
            } catch (_: Exception) {
                continue
            }

            results.add(
                ThreadLocalInfo(
                    threadLocalObjectId = referentId,
                    threadLocalClassName = referentObj?.clazz?.name,
                    valueObjectId = valueId,
                    valueClassName = valueObj.clazz.name,
                    valueRetainedHeap = snapshot.getRetainedHeapSize(valueId),
                    valueLabel = valueObj.classSpecificName,
                )
            )
        }
        return results.sortedByDescending { it.valueRetainedHeap }
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private object NoOpProgress : IProgressListener {
        override fun beginTask(name: String?, totalWork: Int) {}
        override fun done() {}
        override fun isCanceled(): Boolean = false
        override fun setCanceled(value: Boolean) {}
        override fun subTask(name: String?) {}
        override fun worked(work: Int) {}
        override fun sendUserMessage(severity: IProgressListener.Severity?, message: String?, exception: Throwable?) {}
    }
}
