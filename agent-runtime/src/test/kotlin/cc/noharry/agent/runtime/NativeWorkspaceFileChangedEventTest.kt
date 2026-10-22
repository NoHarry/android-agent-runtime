package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class NativeWorkspaceFileChangedEventTest {
    @Test
    fun runEmitsWorkspaceFileChangedEventsForWrites() = runBlocking {
        val workspace = Files.createTempDirectory("workspace-file-events-").toFile()
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(workspace)
            .modelClient(FakeModelClient(eventsByRound = workspaceWriteRounds()))
            .build()

        val events = runtime.run(agentRequest()).toList()
        val changes = events.filterIsInstance<AgentEvent.WorkspaceFileChanged>()
        val result = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result

        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertEquals("hello heater", File(workspace, "memory/note.txt").readText())
        assertEquals(listOf("write_file", "edit_file"), changes.map { it.toolName })
        assertEquals(listOf("/memory/note.txt", "/memory/note.txt"), changes.map { it.path })
        assertEquals(listOf("write-1", "edit-1"), changes.map { it.toolCallId })
        assertEquals(10L, changes[0].bytes)
        assertEquals(null, changes[0].replacements)
        assertEquals(1, changes[0].addedLines)
        assertEquals(0, changes[0].removedLines)
        assertEquals(false, changes[0].diffTimedOut)
        assertEquals(1, changes[1].replacements)
        assertEquals(1, changes[1].addedLines)
        assertEquals(1, changes[1].removedLines)
        assertEquals(false, changes[1].diffTimedOut)
    }

    @Test
    fun diffSummaryFallsBackWhenTimingBudgetIsExceeded() {
        var now = 0L
        val before = (0 until 100).joinToString("\n") { "old-$it" }
        val after = (0 until 100).joinToString("\n") { "new-$it" }
        val summary = diffSummary(
            before = before,
            after = after,
            nowNanos = {
                now += TimeUnit.MILLISECONDS.toNanos(25)
                now
            },
        )

        assertTrue(summary.timedOut)
        assertTrue(summary.addedLines < 100)
        assertTrue(summary.removedLines < 100)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(now) <= 175)
    }

    private fun workspaceWriteRounds(): List<List<ModelEvent>> =
        listOf(
            listOf(
                ModelEvent.ToolCall(
                    "write-1",
                    "write_file",
                    """{"path":"/memory/note.txt","content":"hello lamp","mode":"atomic_replace"}""",
                ),
                ModelEvent.Completed,
            ),
            listOf(
                ModelEvent.ToolCall("read-1", "read_file", """{"path":"/memory/note.txt"}"""),
                ModelEvent.Completed,
            ),
            listOf(
                ModelEvent.ToolCall(
                    "edit-1",
                    "edit_file",
                    """{"path":"/memory/note.txt","old_string":"lamp","new_string":"heater"}""",
                ),
                ModelEvent.Completed,
            ),
            listOf(ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}"""), ModelEvent.Completed),
        )
}
