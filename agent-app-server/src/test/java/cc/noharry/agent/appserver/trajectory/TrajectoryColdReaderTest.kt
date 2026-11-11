@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.trajectory

import cc.noharry.agent.AgentRuntime
import cc.noharry.agent.ThreadOptions
import cc.noharry.agent.TurnOptions
import cc.noharry.agent.TurnStatus
import cc.noharry.agent.builder
import cc.noharry.agent.appserver.protocol.SessionTurn
import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.SessionEventLogHealthStatus
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelRequest
import java.io.File
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrajectoryColdReaderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun defaultGatewayReadsTheClosedWritersAttemptAndLeavesItsFilesUnchanged() = runBlocking {
        val base = temporary.newFolder()
        val root = File(base, SESSION)
        val written = AgentRuntime.builder().model(FinishModel()).storage(root).sessionEventLogRoot(root).build().use { writer ->
            val result = writer.startThread(ThreadOptions(threadId = SESSION)).run("hello", TurnOptions(turnId = TURN))
            assertEquals(TurnStatus.COMPLETED, result.status)
            writer.diagnostics.replaySessionEvents(SESSION, TURN)
        }
        assertTrue(written.isNotEmpty())
        val execution = written.first().runId
        assertNotEquals(TURN, execution)
        val before = files(root)
        // No readerFactory override: this exercises buildTrajectoryEventLogReader with its real storage adapters.
        val gateway = TrajectoryEventLogGateway({ base }, { File(base, it) })
        val replay = gateway.replay(SESSION, TURN)
        assertEquals(written.map { it.eventId }, replay.map { it.eventId })
        assertTrue(replay.all { it.runId == execution && it.requestId == TURN })
        assertEquals(SessionEventLogHealthStatus.COMPLETE, gateway.health(SESSION, TURN).status)
        val turn = SessionTurn(TURN, TURN, execution, SESSION, "app", "hello", 1, 2, "COMPLETED", "done", emptyList())
        val repository = DefaultTrajectoryRepository(gateway, readTurns = { listOf(turn) }, ioScope = this)
        repository.refreshSession(SESSION)
        val group = repository.snapshot(SESSION).runGroups.single()
        assertEquals(TURN, group.runId)
        assertEquals(replay.map { it.eventId }, group.records.map { it.eventId })
        assertFalse(group.loadFailed)
        assertEquals(before, files(root))
    }

    private fun files(root: File): Map<String, List<Byte>?> = root.walkTopDown().associate { file ->
        file.relativeTo(root).path to if (file.isFile) file.readBytes().toList() else null
    }
}

private class FinishModel : ModelClient {
    override val providerConcurrency = ProviderConcurrencyDeclaration.serialized()
    override fun complete(request: ModelRequest) = flowOf(
        ModelEvent.ToolCall("finish", "finish", """{"response":"done","outcome":"success"}"""), ModelEvent.Completed,
    )
}

private const val SESSION = "cold-session"
private const val TURN = "public-turn"
