package com.github.djaler.jvmheapdumpmcp.model

import kotlinx.serialization.Serializable

@Serializable
data class HeapSummary(
    val usedHeapSize: Long,
    val objectCount: Long,
    val classCount: Int,
    val gcRootCount: Int,
    val snapshotDate: String? = null,
    val jvmInfo: String? = null,
)

@Serializable
data class LeakSuspect(
    val description: String,
    val retainedHeap: Long,
    val probability: String,
    val detail: String? = null,
)

@Serializable
data class HistogramEntry(
    val className: String,
    val objectCount: Long,
    val shallowHeap: Long,
    val retainedHeap: Long,
)

@Serializable
data class DominatorEntry(
    val objectId: Int,
    val className: String,
    val shallowHeap: Long,
    val retainedHeap: Long,
    val retainedPercent: Double,
    val label: String? = null,
)

@Serializable
data class ObjectInfo(
    val objectId: Int,
    val className: String,
    val shallowHeap: Long,
    val retainedHeap: Long,
    val gcRootType: String? = null,
    val fields: List<FieldInfo> = emptyList(),
)

@Serializable
data class FieldInfo(
    val name: String,
    val type: String,
    val value: String,
    val objectId: Int? = null,
)

@Serializable
data class ReferenceInfo(
    val objectId: Int,
    val className: String,
    val fieldName: String?,
    val shallowHeap: Long,
    val retainedHeap: Long,
    val label: String? = null,
)

@Serializable
data class GcRootPath(
    val steps: List<GcRootStep>,
)

@Serializable
data class GcRootStep(
    val objectId: Int,
    val className: String,
    val fieldName: String?,
    val shallowHeap: Long,
    val gcRootType: String? = null,
)

@Serializable
data class ThreadInfo(
    val objectId: Int,
    val name: String,
    val retainedHeap: Long,
    val shallowHeap: Long,
    val stackFrames: List<String> = emptyList(),
)

@Serializable
data class OqlResult(
    val query: String,
    val columns: List<String>,
    val rows: List<List<String>>,
    val truncated: Boolean = false,
)

@Serializable
data class StringInfo(
    val objectId: Int,
    val value: String,
    val retainedHeap: Long,
)

@Serializable
data class SessionInfo(
    val id: String,
    val path: String,
    val openedAt: String,
    val heapSizeBytes: Long,
)

@Serializable
data class ArrayInspection(
    val objectId: Int,
    val className: String,
    val length: Int,
    val elementType: String,
    val elements: List<String>,
    val offset: Int = 0,
    val truncated: Boolean = false,
)

@Serializable
data class ClassInstanceEntry(
    val objectId: Int,
    val className: String,
    val shallowHeap: Long,
    val retainedHeap: Long,
    val label: String? = null,
)
