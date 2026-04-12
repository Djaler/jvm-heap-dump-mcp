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
                        try { rec.calculateRetainedSize(snapshot, false, false, NoOpProgress) } catch (_: Exception) {}
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
                    try { it.objectId } catch (_: Exception) { null }
                }
                // Resolve String field values via classSpecificName (survives obfuscation for UTF8-sourced strings)
                val displayValue = if (refId != null) {
                    try {
                        val refObj = snapshot.getObject(refId)
                        if (refObj.clazz.name == "java.lang.String") {
                            refObj.classSpecificName ?: value?.toString() ?: "null"
                        } else value?.toString() ?: "null"
                    } catch (_: Exception) { value?.toString() ?: "null" }
                } else value?.toString() ?: "null"
                FieldInfo(
                    name = f.name,
                    type = f.verboseSignature,
                    value = displayValue,
                    objectId = refId,
                )
            }
            is IObjectArray -> obj.referenceArray.take(20).mapIndexed { i, addr ->
                val refId = if (addr != 0L) try { snapshot.mapAddressToId(addr) } catch (_: Exception) { null } else null
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
            val refId = try { ref.objectId } catch (_: Exception) { return@mapNotNull null }
            val refObj = try { snapshot.getObject(refId) } catch (_: Exception) { return@mapNotNull null }
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
        } catch (_: Exception) { null } ?: return emptyList()

        val threads = result.elements.mapNotNull { row ->
            val ctx = result.getContext(row) ?: return@mapNotNull null
            val id = ctx.objectId
            val obj = snapshot.getObject(id)
            // classSpecificName reads from HPROF_START_THREAD UTF8 records (survives obfuscation)
            val nameVal = obj.classSpecificName

            val stack = try {
                snapshot.getThreadStack(id)?.stackFrames?.map { it.text } ?: emptyList()
            } catch (_: Exception) { emptyList() }

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
                    results.add(StringInfo(
                        objectId = id,
                        value = str,
                        retainedHeap = snapshot.getRetainedHeapSize(id),
                    ))
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
                        val refId = try { snapshot.mapAddressToId(addr) } catch (_: Exception) { null }
                        if (refId != null) {
                            val refObj = try { snapshot.getObject(refId) } catch (_: Exception) { null }
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

    fun getClassInstances(snapshot: ISnapshot, className: String, sortBy: String, limit: Int): List<ClassInstanceEntry> {
        val classes = snapshot.getClassesByName(className, true)
        if (classes.isNullOrEmpty()) {
            val regex = className.toRegex(RegexOption.IGNORE_CASE)
            val allClasses = snapshot.classes.filter { regex.containsMatchIn(it.name) }
            return allClasses.flatMap { cls ->
                cls.objectIds.map { id ->
                    val obj = snapshot.getObject(id)
                    ClassInstanceEntry(id, cls.name, obj.usedHeapSize, snapshot.getRetainedHeapSize(id), obj.classSpecificName)
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
                ClassInstanceEntry(id, cls.name, obj.usedHeapSize, snapshot.getRetainedHeapSize(id), obj.classSpecificName)
            }
        }.let { entries ->
            when (sortBy.uppercase()) {
                "SHALLOW_HEAP" -> entries.sortedByDescending { it.shallowHeap }
                else -> entries.sortedByDescending { it.retainedHeap }
            }
        }.take(limit)
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
