@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.trajectory
import cc.noharry.agent.appserver.runtime.runRequest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.model.FixtureModelClient
import cc.noharry.agent.spi.SessionEventLogDurability
import cc.noharry.agent.spi.SessionEventLogHealthStatus
import cc.noharry.agent.spi.SessionEventQuery
import cc.noharry.agent.runtime.AgentRuntimeBuilder
import cc.noharry.agent.runtime.eventlog.openFileBackedSessionEventLog
import cc.noharry.agent.appserver.console.productionBuildRuntime
import cc.noharry.agent.appserver.console.productionRequest
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TrajectoryStorageTest {

    @Test
    fun perSessionRootsAreNoBackupAndStayInsideTheBaseRoot() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val base = sessionEventLogBaseRoot(context)

        // The root is canonicalized so writer and reader always agree on one directory.
        assertEquals(
            File(context.noBackupFilesDir, DEBUG_SESSION_EVENT_LOG_ROOT_DIR).canonicalFile,
            base,
        )
        assertFalse(base.canonicalPath.startsWith(context.filesDir.canonicalPath))

        // Traversal-shaped ids must still land under the base root after encoding.
        listOf("plain", "../escape", "a/b/c", "很长的会话 id", "").forEach { sessionId ->
            val root = appSessionEventLogRoot(context, sessionId)
            assertTrue(
                "root for '$sessionId' escaped the base root: ${root.canonicalPath}",
                root.canonicalPath.startsWith(base.canonicalPath + File.separator),
            )
            assertEquals(base, root.parentFile)
        }
    }

    @Test
    fun leftoverV1TreeIsClearedWhenTheSessionRootIsResolved() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = appSessionEventLogRoot(context, "legacy-v1-session")
        val leftover = File(root, "session-events/v1/streams/old")
        leftover.mkdirs()
        File(leftover, "events.jsonl").writeText("{}\n")

        val resolved = appSessionEventLogRoot(context, "legacy-v1-session")

        assertEquals(root, resolved)
        assertFalse(File(root, "session-events/v1").exists())
    }

    @Test
    fun differentSessionsGetIsolatedRoots() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(
            appSessionEventLogRoot(context, "session-a") ==
                appSessionEventLogRoot(context, "session-b"),
        )
    }

    @Test
    fun coldReaderReplaysTheSameEventsAfterTheWriterRuntimeIsClosed() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionId = "cold-replay-session"
        val runId = "cold-replay-run"
        val root = appSessionEventLogRoot(context, sessionId)
        root.deleteRecursively()

        val written = writeOneRun(context, sessionId, runId)
        assertTrue(written.isNotEmpty())

        val gateway = gatewayFor(context)
        val replayed = gateway.replay(sessionId, runId)

        assertEquals(written.map { it.eventId }, replayed.map { it.eventId })
        assertEquals(written.map { it.sequence }, replayed.map { it.sequence })
        assertEquals(replayed.map { it.sequence }.sorted(), replayed.map { it.sequence })
    }

    @Test
    fun coldReaderReportsDurableHealthForTheConfiguredRoot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionId = "durable-health-session"
        val runId = "durable-health-run"
        appSessionEventLogRoot(context, sessionId).deleteRecursively()
        writeOneRun(context, sessionId, runId)

        val health = gatewayFor(context).health(sessionId, runId)

        assertEquals(SessionEventLogDurability.DURABLE, health.backendDurability)
        assertEquals(SessionEventLogHealthStatus.COMPLETE, health.status)
    }

    @Test
    fun readerBuildsWithoutModelProviderOrWorkspaceAndOnlyServesReads() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionId = "no-model-reader-session"
        val runId = "no-model-reader-run"
        appSessionEventLogRoot(context, sessionId).deleteRecursively()
        writeOneRun(context, sessionId, runId)

        val reader = buildTrajectoryEventLogReader(appSessionEventLogRoot(context, sessionId))
        try {
            assertTrue(reader.diagnostics.querySessionEvents(SessionEventQuery(sessionId, requestId = runId)).isNotEmpty())
            assertTrue(reader.diagnostics.replaySessionEvents(sessionId, runId).isNotEmpty())
            assertNotNull(reader.diagnostics.getSessionEventLogHealth(sessionId, runId))
        } finally {
            reader.close()
        }
    }

    @Test
    fun queriesAreScopedToTheRequestedSessionRoot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf("scoped-a", "scoped-b").forEach { appSessionEventLogRoot(context, it).deleteRecursively() }
        writeOneRun(context, "scoped-a", "run-a")
        writeOneRun(context, "scoped-b", "run-b")

        val gateway = gatewayFor(context)

        assertTrue(gateway.query(SessionEventQuery("scoped-a", requestId = "run-a")).isNotEmpty())
        assertTrue(gateway.replay("scoped-b", "run-b").isNotEmpty())
        // Reading session B's run through session A's root must not surface anything.
        assertTrue(gateway.replay("scoped-a", "run-b").isEmpty())
    }

    @Test
    fun deletingOneSessionRootLeavesOtherSessionsReadable() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf("delete-a", "delete-b").forEach { appSessionEventLogRoot(context, it).deleteRecursively() }
        writeOneRun(context, "delete-a", "run-a")
        writeOneRun(context, "delete-b", "run-b")
        val gateway = gatewayFor(context)

        assertTrue(gateway.deleteSessionRoot("delete-a"))

        assertFalse(appSessionEventLogRoot(context, "delete-a").exists())
        assertTrue(gateway.replay("delete-a", "run-a").isEmpty())
        assertTrue(gateway.replay("delete-b", "run-b").isNotEmpty())
    }

    @Test
    fun deleteAllRootsRemovesTheWholeBaseRoot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        writeOneRun(context, "clear-a", "run-a")
        writeOneRun(context, "clear-b", "run-b")
        val gateway = gatewayFor(context)

        assertTrue(gateway.deleteAllRoots())

        assertFalse(sessionEventLogBaseRoot(context).exists())
    }

    @Test
    fun deleteWaitsForAnInFlightReadOnTheSameSession() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val sessionId = "race-session"
        appSessionEventLogRoot(context, sessionId).deleteRecursively()
        writeOneRun(context, sessionId, "race-run")

        val readerEntered = CompletableDeferred<Unit>()
        val releaseReader = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val gateway = TrajectoryEventLogGateway(
            baseRoot = { sessionEventLogBaseRoot(context) },
            rootForSession = { appSessionEventLogRoot(context, it) },
            readerFactory = { root ->
                readerEntered.complete(Unit)
                runBlocking { releaseReader.await() }
                order += "read"
                buildTrajectoryEventLogReader(root)
            },
            ioDispatcher = Dispatchers.Unconfined,
        )

        val read = async { gateway.replay(sessionId, "race-run") }
        readerEntered.await()
        val delete = launch { gateway.deleteSessionRoot(sessionId).also { order += "delete" } }
        yield()

        assertEquals(emptyList<String>(), order)
        releaseReader.complete(Unit)
        withTimeout(TEST_TIMEOUT_MS) {
            read.await()
            delete.join()
        }

        assertEquals(listOf("read", "delete"), order)
        assertFalse(appSessionEventLogRoot(context, sessionId).exists())
    }

    @Test
    fun readFailuresSurfaceAsBoundedReasonCodes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val gateway = TrajectoryEventLogGateway(
            baseRoot = { sessionEventLogBaseRoot(context) },
            rootForSession = { appSessionEventLogRoot(context, it) },
            readerFactory = { error("boom at /secret/path") },
            ioDispatcher = Dispatchers.Unconfined,
        )

        val failure = runCatching { gateway.replay("any", "any") }.exceptionOrNull()

        assertTrue(failure is TrajectoryEventLogFailure)
        assertEquals(
            TrajectoryEventLogReason.READER_BUILD_FAILED,
            (failure as TrajectoryEventLogFailure).reasonCode,
        )
        assertFalse(failure.reasonCode.contains("/secret/path"))
    }

    /**
     * Precedence guard: `sessionEventLogRoot` is the lowest-priority source. If the sample ever
     * installs a packaged or custom SESSION_EVENT_LOG capability, the configured root goes
     * silently unused and the Trajectory reader sees nothing.
     */
    @Test
    fun packagedEventLogPluginSilentlyWinsOverTheConfiguredRoot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pluginRoot = File(context.noBackupFilesDir, "precedence-plugin-root").also { it.deleteRecursively() }
        val configuredRoot = File(context.noBackupFilesDir, "precedence-configured-root").also { it.deleteRecursively() }

        val runtime = AgentRuntimeBuilder.create()
            .model(FixtureModelClient())
            .sessionEventLog(openFileBackedSessionEventLog(pluginRoot))
            .sessionEventLogRoot(configuredRoot)
            .build()
        try {
            runtime.runRequest(
                productionRequest("finish with done precedence", "precedence-run", "precedence-session"),
            ).toList()
        } finally {
            runtime.close()
        }

        assertTrue(pluginRoot.walkTopDown().any { it.name.endsWith(".jsonl") && it.path.contains("session-events") })
        assertFalse(configuredRoot.walkTopDown().any { it.name.endsWith(".jsonl") && it.path.contains("session-events") })
    }

    private fun gatewayFor(context: Context) = TrajectoryEventLogGateway(
        baseRoot = { sessionEventLogBaseRoot(context) },
        rootForSession = { appSessionEventLogRoot(context, it) },
        ioDispatcher = Dispatchers.Unconfined,
    )

    private suspend fun writeOneRun(context: Context, sessionId: String, runId: String) =
        productionBuildRuntime(context, "finish with done $runId", sessionId).let { runtime ->
            runtime.runRequest(productionRequest("finish with done $runId", runId, sessionId)).toList()
            val events = runtime.diagnostics.replaySessionEvents(sessionId, runId)
            runtime.close()
            events
        }
}

private const val TEST_TIMEOUT_MS = 10_000L
