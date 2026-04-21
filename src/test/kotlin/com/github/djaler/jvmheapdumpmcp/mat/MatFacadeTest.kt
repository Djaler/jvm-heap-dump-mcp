package com.github.djaler.jvmheapdumpmcp.mat

import org.eclipse.mat.snapshot.ISnapshot
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.assertDoesNotThrow
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Tag("integration")
class MatFacadeTest {

    companion object {
        private lateinit var snapshot: ISnapshot
        private lateinit var leakedSnapshot: ISnapshot
        private const val SESSION_ID = "mat-facade-test-session"
        private const val LEAKED_SESSION_ID = "mat-facade-test-leaked-session"

        @JvmStatic
        @BeforeAll
        fun setUpAll() {
            MatBootstrap.initialize()
            val heapDumpPath: Path = Paths.get(
                checkNotNull(MatFacadeTest::class.java.classLoader.getResource("test-heap-dump.hprof")) {
                    "test-heap-dump.hprof not found in test resources"
                }.toURI()
            )
            val session = SnapshotManager.openSnapshot(heapDumpPath, SESSION_ID)
            snapshot = session.snapshot

            val leakedDumpPath: Path = Paths.get(
                checkNotNull(MatFacadeTest::class.java.classLoader.getResource("test-heap-dump-leaked.hprof")) {
                    "test-heap-dump-leaked.hprof not found in test resources"
                }.toURI()
            )
            val leakedSession = SnapshotManager.openSnapshot(leakedDumpPath, LEAKED_SESSION_ID)
            leakedSnapshot = leakedSession.snapshot
        }
    }

    @Test
    fun `getHeapSummary should return non-zero heap size and counts`() {
        val summary = MatFacade.getHeapSummary(snapshot)
        assertNotNull(summary)
        assertTrue(summary.usedHeapSize > 0, "Used heap size should be greater than 0")
        assertTrue(summary.objectCount > 0, "Object count should be greater than 0")
        assertTrue(summary.classCount > 0, "Class count should be greater than 0")
        assertTrue(summary.gcRootCount > 0, "GC root count should be greater than 0")
    }

    @Test
    fun `getClassHistogram should return entries sorted by retained heap`() {
        val entries = MatFacade.getClassHistogram(snapshot, "RETAINED_HEAP", null, 50)
        assertTrue(entries.isNotEmpty(), "Histogram should have entries")
        for (i in 0 until entries.size - 1) {
            assertTrue(
                entries[i].retainedHeap >= entries[i + 1].retainedHeap,
                "Histogram should be sorted by retained heap descending"
            )
        }
    }

    @Test
    fun `getClassHistogram with filter should return only matching entries`() {
        val entries = MatFacade.getClassHistogram(snapshot, "RETAINED_HEAP", "java\\.lang", 50)
        assertTrue(entries.isNotEmpty(), "Filtered histogram should have entries")
        entries.forEach { entry ->
            assertTrue(
                entry.className.contains("java.lang"),
                "All entries should match filter 'java.lang', got: ${entry.className}"
            )
        }
    }

    @Test
    fun `getClassHistogram with limit should respect the limit`() {
        val limit = 5
        val entries = MatFacade.getClassHistogram(snapshot, "RETAINED_HEAP", null, limit)
        assertTrue(entries.size <= limit, "Result count ${entries.size} should not exceed limit $limit")
    }

    @Test
    fun `getDominatorTree should return entries with valid fields`() {
        val entries = MatFacade.getDominatorTree(snapshot, 30)
        assertTrue(entries.isNotEmpty(), "Dominator tree should have entries")
        entries.forEach { entry ->
            assertNotNull(entry.className, "Entry className should not be null")
            assertTrue(entry.retainedHeap >= 0, "Retained heap should be non-negative")
            assertTrue(entry.retainedPercent in 0.0..100.0, "Percent should be in [0, 100]")
        }
    }

    @Test
    fun `getObjectInfo should return info for a valid object ID`() {
        val dominators = MatFacade.getDominatorTree(snapshot, 1)
        assertTrue(dominators.isNotEmpty())
        val objectId = dominators.first().objectId
        val info = MatFacade.getObjectInfo(snapshot, objectId)
        assertNotNull(info)
        assertNotNull(info.className, "ObjectInfo className should not be null")
        assertTrue(info.shallowHeap >= 0, "Shallow heap should be non-negative")
        assertTrue(info.retainedHeap >= 0, "Retained heap should be non-negative")
    }

    @Test
    fun `getOutboundReferences should not throw for a valid object`() {
        val dominators = MatFacade.getDominatorTree(snapshot, 1)
        assertTrue(dominators.isNotEmpty())
        val objectId = dominators.first().objectId
        assertDoesNotThrow {
            val refs = MatFacade.getOutboundReferences(snapshot, objectId, 50)
            assertNotNull(refs)
        }
    }

    @Test
    fun `getInboundReferences should not throw for a valid object`() {
        val dominators = MatFacade.getDominatorTree(snapshot, 1)
        assertTrue(dominators.isNotEmpty())
        val objectId = dominators.first().objectId
        assertDoesNotThrow {
            val refs = MatFacade.getInboundReferences(snapshot, objectId, 50)
            assertNotNull(refs)
        }
    }

    @Test
    fun `getPathToGcRoots should not throw for a retained object`() {
        val dominators = MatFacade.getDominatorTree(snapshot, 5)
        assertTrue(dominators.isNotEmpty())
        val objectId = dominators.first().objectId
        assertDoesNotThrow {
            val paths = MatFacade.getPathToGcRoots(snapshot, objectId, 10)
            assertNotNull(paths)
        }
    }

    @Test
    fun `getThreads should return at least one thread`() {
        val threads = MatFacade.getThreads(snapshot, "RETAINED_HEAP", null)
        assertTrue(threads.isNotEmpty(), "Should find at least one thread in heap dump")
        threads.forEach { thread ->
            assertNotNull(thread.name, "Thread name should not be null")
        }
    }

    @Test
    fun `executeOql with String query should return a result`() {
        val result = MatFacade.executeOql(snapshot, "SELECT * FROM java.lang.String", 100)
        assertNotNull(result)
        assertNotNull(result.query)
        assertNotNull(result.rows)
        assertTrue(result.rows.size >= 0)
    }

    @Test
    fun `executeOql with nonexistent class should not throw`() {
        assertDoesNotThrow {
            val result = MatFacade.executeOql(snapshot, "SELECT * FROM com.nonexistent.Class12345", 100)
            assertNotNull(result)
        }
    }

    @Test
    fun `getLeakSuspects should return without error`() {
        assertDoesNotThrow {
            val suspects = MatFacade.getLeakSuspects(snapshot)
            assertNotNull(suspects)
        }
    }

    @Test
    fun `findStrings should return string entries`() {
        val strings = MatFacade.findStrings(snapshot, "java", 50)
        assertNotNull(strings)
        strings.forEach { s ->
            assertNotNull(s.value, "String value should not be null")
            assertTrue(s.retainedHeap >= 0, "Retained heap should be non-negative")
        }
    }

    // -------------------------------------------------------------------------
    // get_retained_set
    // -------------------------------------------------------------------------

    @Test
    fun `getRetainedSet should return histogram of retained objects`() {
        // Find RetainedTreeRoot instance
        val instances = MatFacade.getClassInstances(snapshot, "RetainedTreeRoot", "RETAINED_HEAP", 1)
        assertTrue(instances.isNotEmpty(), "Should find RetainedTreeRoot in test dump")
        val rootId = instances.first().objectId

        val entries = MatFacade.getRetainedSet(snapshot, rootId, 50)
        assertTrue(entries.isNotEmpty(), "Retained set should have entries")

        // Should contain RetainedTreeNode (9 nodes in the tree)
        val nodeEntry = entries.find { it.className.contains("RetainedTreeNode") }
        assertNotNull(nodeEntry, "Retained set should include RetainedTreeNode instances")
        assertTrue(nodeEntry.objectCount > 0, "Should have multiple RetainedTreeNode instances")

        // Entries should be sorted by retained heap descending
        for (i in 0 until entries.size - 1) {
            assertTrue(
                entries[i].retainedHeap >= entries[i + 1].retainedHeap,
                "Retained set histogram should be sorted by retained heap descending"
            )
        }
    }

    // -------------------------------------------------------------------------
    // compare_class_histograms
    // -------------------------------------------------------------------------

    @Test
    fun `compareClassHistograms should detect LeakedObject growth`() {
        val diffs = MatFacade.compareClassHistograms(snapshot, leakedSnapshot, "OBJECT_COUNT_DELTA", 100)
        assertTrue(diffs.isNotEmpty(), "Should have differences between baseline and leaked dumps")

        val leakedDiff = diffs.find { it.className.contains("LeakedObject") }
        assertNotNull(leakedDiff, "Should find LeakedObject in histogram diff")
        assertTrue(leakedDiff.objectCountDelta > 0, "LeakedObject count should increase in leaked dump")
        assertTrue(leakedDiff.objectCount1 == 0L, "Baseline dump should have 0 LeakedObject instances")
        assertTrue(leakedDiff.objectCount2 == 500L, "Leaked dump should have 500 LeakedObject instances")
    }

    // -------------------------------------------------------------------------
    // get_collection_fill_rates
    // -------------------------------------------------------------------------

    @Test
    fun `getCollectionFillRates should analyze HashMap instances`() {
        val result = MatFacade.getCollectionFillRates(snapshot, "java.util.HashMap", 1000)
        assertTrue(result.totalCount > 0, "Should find HashMap instances in test dump")
        assertTrue(result.buckets.isNotEmpty(), "Should have fill rate buckets")

        // CollectionHolder creates maps with varying fill rates, including empty ones
        val emptyBucket = result.buckets.find { it.rangeLabel.contains("empty") }
        assertNotNull(emptyBucket, "Should have an empty bucket")
        assertTrue(emptyBucket.count > 0, "Should have some empty HashMaps")
    }

    // -------------------------------------------------------------------------
    // get_map_contents
    // -------------------------------------------------------------------------

    @Test
    fun `getMapContents should read map entries`() {
        // Find KnownMaps instance and its stringToObject HashMap
        val knownMaps = MatFacade.getClassInstances(snapshot, "KnownMaps", "RETAINED_HEAP", 1)
        assertTrue(knownMaps.isNotEmpty(), "Should find KnownMaps in test dump")

        val objInfo = MatFacade.getObjectInfo(snapshot, knownMaps.first().objectId)
        val stringToObjectField = objInfo.fields.find { it.name == "stringToObject" }
        assertNotNull(stringToObjectField, "KnownMaps should have stringToObject field")
        val mapObjectId = stringToObjectField.objectId
        assertNotNull(mapObjectId, "stringToObject field should have objectId")

        val result = MatFacade.getMapContents(snapshot, mapObjectId, 20)
        assertTrue(result.entryCount > 0, "Map should have entries")
        assertTrue(result.keyTypeSummary.isNotEmpty(), "Should have key type summary")
        assertTrue(result.valueTypeSummary.isNotEmpty(), "Should have value type summary")

        // Keys should be String type
        val stringKeyType = result.keyTypeSummary.find { it.className == "java.lang.String" }
        assertNotNull(stringKeyType, "Keys should be java.lang.String")
    }

    @Test
    fun `getMapContents should read ConcurrentHashMap entries`() {
        val knownMaps = MatFacade.getClassInstances(snapshot, "KnownMaps", "RETAINED_HEAP", 1)
        assertTrue(knownMaps.isNotEmpty(), "Should find KnownMaps in test dump")

        val objInfo = MatFacade.getObjectInfo(snapshot, knownMaps.first().objectId)
        val concurrentMapField = objInfo.fields.find { it.name == "concurrentMap" }
        assertNotNull(concurrentMapField, "KnownMaps should have concurrentMap field")
        assertNotNull(concurrentMapField.objectId, "concurrentMap field should have objectId")

        val result = MatFacade.getMapContents(snapshot, concurrentMapField.objectId!!, 20)
        assertTrue(result.entryCount > 0, "ConcurrentHashMap should have entries")
        assertTrue(result.keyTypeSummary.isNotEmpty(), "Should have key type summary")
    }

    // -------------------------------------------------------------------------
    // get_thread_local_variables
    // -------------------------------------------------------------------------

    @Test
    fun `getThreadLocalVariables should find ThreadLocal values in named threads`() {
        // Find all Thread instances and look for one with ThreadLocals containing ThreadLocalPayload
        val threadInstances = MatFacade.getClassInstances(snapshot, "java.lang.Thread", "RETAINED_HEAP", 200)
        assertTrue(threadInstances.isNotEmpty(), "Should find Thread instances in heap dump")

        // Try each thread to find one with ThreadLocalPayload
        var foundPayload = false
        for (threadInstance in threadInstances) {
            val entries = try {
                MatFacade.getThreadLocalVariables(snapshot, threadInstance.objectId)
            } catch (_: Exception) {
                continue
            }

            val payloadEntry = entries.find { it.valueClassName.contains("ThreadLocalPayload") }
            if (payloadEntry != null) {
                foundPayload = true
                assertTrue(payloadEntry.valueRetainedHeap > 0, "ThreadLocalPayload should have positive retained heap")
                assertNotNull(payloadEntry.threadLocalClassName, "ThreadLocal key should not be stale")
                break
            }
        }
        assertTrue(foundPayload, "Should find ThreadLocalPayload in at least one thread's ThreadLocals")
    }
}
