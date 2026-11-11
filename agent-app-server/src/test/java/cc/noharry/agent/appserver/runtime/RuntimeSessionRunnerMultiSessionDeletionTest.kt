@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.appserver.protocol.SessionTurn
import cc.noharry.agent.appserver.session.SessionStore
import cc.noharry.agent.spi.reliability.RuntimeRunStatus
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSessionRunnerMultiSessionDeletionTest {
    @Test
    fun deleteActiveSessionDoesNotResurrectDirectory() =
        deleteActiveSessionDoesNotResurrectDirectoryCase()

    @Test
    fun deleteThenExplicitRecreateRevivesTombstone() =
        deleteThenExplicitRecreateRevivesTombstoneCase()

    @Test
    fun deleteRemovesFinishedSnapshotWithoutResurrecting() =
        deleteRemovesFinishedSnapshotWithoutResurrectingCase()
}

internal fun deleteActiveSessionDoesNotResurrectDirectoryCase() {
    runBlocking {
        RuntimeMultiSessionHarness().use { harness ->
            val root = Files.createTempDirectory("sample-delete-active-").toFile()
            try {
                val store = SessionStore(root)
                val sessionId = "session-delete"
                store.ensureSession(sessionId)
                harness.coordinator.attachStoreForTest(store)
                val fixture = RuntimeMultiSessionFixture(sessionId, "run-delete")
                harness.startHeld(fixture)
                val dir = File(root, encodeSessionId(sessionId))
                assertTrue(dir.isDirectory)
                harness.runner.deleteActiveSession(sessionId)
                harness.coordinator.deletePersistedSession(sessionId)
                assertTrue(awaitUntil { fixture.runtimeCloses.get() == 1 })
                harness.coordinator.appendTurn(lateDeleteTurn(sessionId))
                assertFalse(dir.exists())
                assertFalse(File(dir, "turns.jsonl").exists())
                assertTrue(store.listSessions().none { it.sessionId == sessionId })
            } finally {
                root.deleteRecursively()
            }
        }
    }
}

internal fun lateDeleteTurn(sessionId: String): SessionTurn =
    SessionTurn(
        turnId = "late",
        requestId = "late",
        runId = "run-delete",
        sessionId = sessionId,
        scene = "debug-console",
        userText = "should not persist",
        startedAtEpochMillis = 1L,
        completedAtEpochMillis = 2L,
        finalStatus = RuntimeRunStatus.COMPLETED.name,
        finalResponse = "late",
        tools = emptyList(),
    )

internal fun deleteThenExplicitRecreateRevivesTombstoneCase() {
    runBlocking {
        RuntimeMultiSessionHarness().use { harness ->
            val root = Files.createTempDirectory("sample-delete-recreate-").toFile()
            try {
                val store = SessionStore(root)
                val sessionId = "session-recreate"
                store.ensureSession(sessionId)
                harness.coordinator.attachStoreForTest(store)
                val fixture = RuntimeMultiSessionFixture(sessionId, "run-recreate")
                harness.startHeld(fixture)
                harness.runner.deleteActiveSession(sessionId)
                harness.coordinator.deletePersistedSession(sessionId)
                assertTrue(awaitUntil { fixture.runtimeCloses.get() == 1 })
                assertTombstoneThenRevive(harness, store, sessionId)
            } finally {
                root.deleteRecursively()
            }
        }
    }
}

internal suspend fun assertTombstoneThenRevive(
    harness: RuntimeMultiSessionHarness,
    store: SessionStore,
    sessionId: String,
) {
    harness.coordinator.appendTurn(multiSessionTurn(sessionId, "late", "should stay suppressed"))
    assertTrue(store.listSessions().none { it.sessionId == sessionId })
    val implicit = RuntimeMultiSessionFixture(sessionId, "run-implicit")
    harness.startHeld(implicit)
    implicit.release.complete(Unit)
    assertTrue(awaitUntil { implicit.runtimeCloses.get() == 1 })
    harness.coordinator.appendTurn(multiSessionTurn(sessionId, "implicit", "still suppressed"))
    assertTrue(store.listSessions().none { it.sessionId == sessionId })
    harness.coordinator.reviveDeletedSession(sessionId)
    store.ensureSession(sessionId)
    harness.coordinator.appendTurn(multiSessionTurn(sessionId, "revived", "host recreate"))
    assertTrue(store.listSessions().any { it.sessionId == sessionId })
    assertEquals(1, store.readTurns(sessionId).size)
    assertEquals("host recreate", store.readTurns(sessionId).single().userText)
}

internal fun deleteRemovesFinishedSnapshotWithoutResurrectingCase() {
    runBlocking {
        RuntimeMultiSessionHarness().use { harness ->
            val fixture = RuntimeMultiSessionFixture("session-finished", "run-finished")
            harness.startHeld(fixture)
            fixture.release.complete(Unit)
            assertTrue(
                awaitUntil {
                    harness.runner.activeRunSnapshots()
                        .any { it.sessionId == "session-finished" && !it.isActive }
                },
            )
            harness.runner.deleteActiveSession("session-finished")
            assertTrue(harness.runner.activeRunSnapshots().none { it.sessionId == "session-finished" })
            harness.runner.discardSession("session-finished")
            assertTrue(harness.runner.activeRunSnapshots().none { it.sessionId == "session-finished" })
            assertTrue(harness.coordinator.isSessionDeleted("session-finished"))
        }
    }
}
