package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.appserver.session.deleteRuntimeSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSessionDeletionOrderTest {
    @Test
    fun activeRunIsJoinedBeforeEventLogRootSessionAndArtifactsAreDeleted() = runBlocking {
        val allowJoin = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        val deletion = launch {
            deleteRuntimeSession(
                sessionId = "session-delete",
                deleteActiveSession = {
                    order += "cancel"
                    allowJoin.await()
                    order += "joined"
                    true
                },
                dropTrajectory = { order += "event-log"; true },
                deletePersistedSession = { order += "session" },
                deleteArtifacts = { order += "artifacts"; true },
            )
        }

        yield()
        assertEquals(listOf("cancel"), order)
        allowJoin.complete(Unit)
        deletion.join()

        assertEquals(listOf("cancel", "joined", "event-log", "session", "artifacts"), order)
    }

    @Test
    fun artifactCleanupFailureIsReturnedInsteadOfReportedAsFullSuccess() = runBlocking {
        val result = deleteRuntimeSession(
            sessionId = "session-delete",
            deleteActiveSession = { true },
            dropTrajectory = { true },
            deletePersistedSession = {},
            deleteArtifacts = { false },
        )

        assertFalse(result.ok)
        assertTrue(result.eventLogDeleted)
        assertEquals(listOf("artifacts"), result.failedTargets)
    }

    @Test
    fun eventLogCleanupFailureIsReportedWithBoundedTargetName() = runBlocking {
        val result = deleteRuntimeSession(
            sessionId = "session-delete",
            deleteActiveSession = { true },
            dropTrajectory = { false },
            deletePersistedSession = {},
            deleteArtifacts = { true },
        )

        assertFalse(result.ok)
        assertEquals(listOf("trajectory event log"), result.failedTargets)
    }

    @Test
    fun eventLogRootIsNotDeletedWhenTheWriterDidNotConfirmCancellation() = runBlocking {
        var dropped = false

        val result = deleteRuntimeSession(
            sessionId = "session-delete",
            deleteActiveSession = { false },
            dropTrajectory = { dropped = true; true },
            deletePersistedSession = {},
            deleteArtifacts = { true },
        )

        // A writer that ignored cancellation can still append to or recreate the tree.
        assertFalse(dropped)
        assertFalse(result.ok)
        assertEquals(listOf("run did not stop", "trajectory event log"), result.failedTargets)
    }
}
