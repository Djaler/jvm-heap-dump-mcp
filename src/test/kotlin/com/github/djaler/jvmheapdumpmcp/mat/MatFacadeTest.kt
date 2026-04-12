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
        private const val SESSION_ID = "mat-facade-test-session"

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
}
