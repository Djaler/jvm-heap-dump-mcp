package com.github.djaler.jvmheapdumpmcp.tools

import com.github.djaler.jvmheapdumpmcp.mat.MatBootstrap
import com.github.djaler.jvmheapdumpmcp.mat.MatFacade
import com.github.djaler.jvmheapdumpmcp.mat.SnapshotManager
import org.eclipse.mat.snapshot.ISnapshot
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.assertThrows
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end integration tests covering the full analysis scenario:
 * open → summary → histogram → leak suspects → OQL → close.
 *
 * Tests call SnapshotManager and MatFacade directly — the same layer that
 * the MCP tools delegate to — rather than spinning up the MCP server.
 */
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ToolsIntegrationTest {

    companion object {
        private const val SESSION_ID = "tools-integration-test"
        private val heapDumpPath = Paths.get(
            checkNotNull(ToolsIntegrationTest::class.java.classLoader.getResource("test-heap-dump.hprof")) {
                "test-heap-dump.hprof not found in test resources"
            }.toURI()
        )
        private lateinit var snapshot: ISnapshot

        @JvmStatic
        @BeforeAll
        fun setUpAll() {
            MatBootstrap.initialize()
        }

        @JvmStatic
        @AfterAll
        fun tearDownAll() {
            runCatching { SnapshotManager.closeSnapshot(SESSION_ID) }
        }
    }

    @Order(1)
    @Test
    fun `open heap dump should return a valid session`() {
        val session = SnapshotManager.openSnapshot(heapDumpPath, SESSION_ID)
        assertNotNull(session)
        assertTrue(session.id == SESSION_ID)
        assertNotNull(session.snapshot)
        snapshot = session.snapshot
    }

    @Order(2)
    @Test
    fun `get heap summary should return non-zero data`() {
        val session = SnapshotManager.getSession(SESSION_ID)
        val summary = MatFacade.getHeapSummary(session.snapshot)
        assertNotNull(summary)
        assertTrue(summary.usedHeapSize > 0, "Used heap size should be > 0")
        assertTrue(summary.objectCount > 0, "Object count should be > 0")
    }

    @Order(3)
    @Test
    fun `get class histogram should return sorted entries`() {
        val session = SnapshotManager.getSession(SESSION_ID)
        val entries = MatFacade.getClassHistogram(session.snapshot, "RETAINED_HEAP", null, 50)
        assertTrue(entries.isNotEmpty(), "Histogram should not be empty")
        for (i in 0 until entries.size - 1) {
            assertTrue(
                entries[i].retainedHeap >= entries[i + 1].retainedHeap,
                "Histogram should be sorted by retained heap descending"
            )
        }
    }

    @Order(4)
    @Test
    fun `get leak suspects should complete without error`() {
        val session = SnapshotManager.getSession(SESSION_ID)
        val suspects = MatFacade.getLeakSuspects(session.snapshot)
        assertNotNull(suspects)
        // Each suspect should have a description and probability
        suspects.forEach { suspect ->
            assertNotNull(suspect.description, "Suspect description should not be null")
            assertNotNull(suspect.probability, "Suspect probability should not be null")
        }
    }

    @Order(5)
    @Test
    fun `execute OQL should return a result with rows list`() {
        val session = SnapshotManager.getSession(SESSION_ID)
        val result = MatFacade.executeOql(session.snapshot, "SELECT * FROM java.lang.String", 100)
        assertNotNull(result)
        assertNotNull(result.rows, "OQL result rows should not be null")
        assertNotNull(result.columns, "OQL result columns should not be null")
    }

    @Order(6)
    @Test
    fun `list sessions should include the open session`() {
        val sessions = SnapshotManager.listSessions()
        val ids = sessions.map { it.id }
        assertTrue(SESSION_ID in ids, "Session $SESSION_ID should be in the sessions list")
    }

    @Order(7)
    @Test
    fun `close heap dump should remove session`() {
        SnapshotManager.closeSnapshot(SESSION_ID)
        assertThrows<Exception>("Accessing closed session should throw") {
            SnapshotManager.getSession(SESSION_ID)
        }
    }
}
