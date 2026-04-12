package com.github.djaler.jvmheapdumpmcp.mat

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SnapshotManagerTest {

    private val heapDumpPath: Path = Paths.get(
        checkNotNull(javaClass.classLoader.getResource("test-heap-dump.hprof")) {
            "test-heap-dump.hprof not found in test resources"
        }.toURI()
    )

    @AfterEach
    fun tearDown() {
        SnapshotManager.listSessions().forEach { session ->
            runCatching { SnapshotManager.closeSnapshot(session.id) }
        }
    }

    @Tag("integration")
    @Test
    fun `openSnapshot should return valid session for real heap dump`() {
        val session = SnapshotManager.openSnapshot(heapDumpPath)
        assertNotNull(session)
        assertNotNull(session.id)
        assertNotNull(session.snapshot)
    }

    @Tag("integration")
    @Test
    fun `openSnapshot with explicit id should use provided id`() {
        val customId = "my-test-session"
        val session = SnapshotManager.openSnapshot(heapDumpPath, customId)
        assertEquals(customId, session.id)
    }

    @Tag("integration")
    @Test
    fun `session should be cached and retrievable after opening`() {
        val opened = SnapshotManager.openSnapshot(heapDumpPath, "cache-test")
        val retrieved = SnapshotManager.getSession("cache-test")
        assertEquals(opened.id, retrieved.id)
        assertNotNull(retrieved.snapshot)
    }

    @Tag("integration")
    @Test
    fun `closeSnapshot should remove session`() {
        SnapshotManager.openSnapshot(heapDumpPath, "close-test")
        SnapshotManager.closeSnapshot("close-test")
        assertThrows<Exception> {
            SnapshotManager.getSession("close-test")
        }
    }

    @Tag("integration")
    @Test
    fun `listSessions should include all open sessions`() {
        SnapshotManager.openSnapshot(heapDumpPath, "list-session-1")
        SnapshotManager.openSnapshot(heapDumpPath, "list-session-2")
        val sessions = SnapshotManager.listSessions()
        val ids = sessions.map { it.id }
        assertTrue("list-session-1" in ids)
        assertTrue("list-session-2" in ids)
    }

    @Tag("integration")
    @Test
    fun `listSessions should return empty when no sessions open`() {
        val sessions = SnapshotManager.listSessions()
        assertTrue(sessions.isEmpty())
    }

    @Test
    fun `openSnapshot should throw on invalid path`() {
        val invalidPath = Paths.get("/nonexistent/path/dump.hprof")
        assertThrows<Exception> {
            SnapshotManager.openSnapshot(invalidPath)
        }
    }

    @Tag("integration")
    @Test
    fun `getSession should throw on unknown session id`() {
        assertThrows<Exception> {
            SnapshotManager.getSession("unknown-session-id-xyz")
        }
    }

    @Tag("integration")
    @Test
    fun `closeSnapshot should throw on unknown session id`() {
        assertThrows<Exception> {
            SnapshotManager.closeSnapshot("unknown-session-id-xyz")
        }
    }
}
