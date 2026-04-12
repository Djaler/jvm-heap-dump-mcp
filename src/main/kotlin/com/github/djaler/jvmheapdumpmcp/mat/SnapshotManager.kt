package com.github.djaler.jvmheapdumpmcp.mat

import com.github.djaler.jvmheapdumpmcp.model.HeapSummary
import com.github.djaler.jvmheapdumpmcp.model.SessionInfo
import org.eclipse.mat.snapshot.ISnapshot
import org.eclipse.mat.snapshot.SnapshotFactory
import org.eclipse.mat.util.IProgressListener
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class SessionState(
    val id: String,
    val snapshot: ISnapshot,
    val filePath: Path,
    val openedAt: Instant,
    val metadata: HeapSummary,
)

object SnapshotManager {

    private val sessions = ConcurrentHashMap<String, SessionState>()

    fun openSnapshot(path: Path, id: String? = null): SessionState {
        MatBootstrap.initialize()

        val sessionId = id ?: UUID.randomUUID().toString()
        val snapshot = SnapshotFactory.openSnapshot(path.toFile(), emptyMap(), SilentProgressListener)
        val metadata = MatFacade.getHeapSummary(snapshot)
        val state = SessionState(
            id = sessionId,
            snapshot = snapshot,
            filePath = path,
            openedAt = Instant.now(),
            metadata = metadata,
        )
        sessions[sessionId] = state
        return state
    }

    fun closeSnapshot(id: String) {
        val state = sessions.remove(id)
            ?: throw IllegalArgumentException("Session not found: $id")
        SnapshotFactory.dispose(state.snapshot)
    }

    fun getSession(id: String): SessionState =
        sessions[id] ?: throw IllegalArgumentException("Session not found: $id")

    fun listSessions(): List<SessionInfo> =
        sessions.values.map { it.toSessionInfo() }

    private fun SessionState.toSessionInfo() = SessionInfo(
        id = id,
        path = filePath.toString(),
        openedAt = DateTimeFormatter.ISO_INSTANT.format(openedAt),
        heapSizeBytes = metadata.usedHeapSize,
    )

    private object SilentProgressListener : IProgressListener {
        override fun beginTask(name: String?, totalWork: Int) {}
        override fun done() {}
        override fun isCanceled(): Boolean = false
        override fun setCanceled(value: Boolean) {}
        override fun subTask(name: String?) {}
        override fun worked(work: Int) {}
        override fun sendUserMessage(severity: IProgressListener.Severity?, message: String?, exception: Throwable?) {}
    }
}
