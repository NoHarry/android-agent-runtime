package cc.noharry.agent.kernel.core.state
import cc.noharry.agent.kernel.api.model.ModelMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LoopHistoryStoreTest {
    @Test
    fun fileBackedStoreReadsProviderNeutralMessagesAcrossInstances() {
        val root = Files.createTempDirectory("agent-runtime-loop-history-").toFile()
        val messages = listOf(
            ModelMessage(role = "user", content = "run"),
            ModelMessage(
                role = "assistant",
                content = "",
                toolCallId = "tool-1",
                toolName = "set_temperature",
                toolArgumentsJson = """{"degreesCelsius":22}""",
            ),
        )

        FileBackedLoopHistoryStore(root).save("run-1", messages)
        val restored = FileBackedLoopHistoryStore(root).read("run-1")

        assertEquals(messages, restored?.messages)
        assertEquals(0, restored?.droppedMessages)
    }

    @Test
    fun fileBackedStoreTrimsOldestMessagesToBudget() {
        val root = Files.createTempDirectory("agent-runtime-loop-history-trim-").toFile()
        val messages = (0 until MESSAGE_COUNT).map {
            ModelMessage(role = "user", content = "$it-${"x".repeat(MESSAGE_BYTES)}")
        }

        val history = FileBackedLoopHistoryStore(root).save("run-trim", messages)
        val file = File(File(root, RUNS_DIR), encodeFileName("run-trim")).resolve("loop-history.json")

        assertTrue(history.droppedMessages > 0)
        assertTrue(file.length() <= MAX_LOOP_HISTORY_BYTES)
        assertEquals(messages.last(), history.messages.last())
    }

    @Test
    fun trimmerPreservesFirstUserIntentWhenOverBudget() {
        val firstUser = ModelMessage(role = "user", content = "turn on the AC after you inspect context")
        val messages = listOf(ModelMessage(role = "system", content = "bootstrap")) +
            listOf(firstUser) +
            (0 until MESSAGE_COUNT).map {
                ModelMessage(role = "assistant", content = "$it-${"x".repeat(MESSAGE_BYTES)}")
            }

        val history = LoopHistoryTrimmer.trim("run-preserve-user", messages)

        assertTrue(history.droppedMessages > 0)
        assertEquals(firstUser, history.messages.first { it.role == "user" })
        assertTrue(history.messages.contains(firstUser))
    }
    @Test
    fun tokenWindowHistoryCanPersistBeyondLegacyByteCapWithoutDroppingMessages() {
        val root = Files.createTempDirectory("token-window-history-").toFile()
        val messages = (0 until MESSAGE_COUNT).map { ModelMessage("user", "$it-${"x".repeat(MESSAGE_BYTES)}") }
        val saved = FileBackedLoopHistoryStore(root, budgetBytes = Int.MAX_VALUE).save("context-window", messages)
        assertEquals(0, saved.droppedMessages)
        assertEquals(messages, FileBackedLoopHistoryStore(root, budgetBytes = Int.MAX_VALUE).read("context-window")?.messages)
    }

}

private const val MESSAGE_COUNT = 400
private const val MESSAGE_BYTES = 2048
