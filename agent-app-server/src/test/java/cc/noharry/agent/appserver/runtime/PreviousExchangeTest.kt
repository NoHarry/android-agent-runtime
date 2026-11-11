package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.appserver.protocol.SessionTurn
import cc.noharry.agent.appserver.session.SessionStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreviousExchangeTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun optionSelectionSnapshotUsesTheLatestTurnWithoutReplayingItIntoThePrompt() {
        val store = SessionStore(temporary.newFolder())
        store.appendTurn(turn("one", "COMPLETED", "old offer"))
        val before = store.previousExchange("session")
        store.appendTurn(turn("two", "FAILED", "current reply"))
        assertEquals("one", before?.turnId)
        assertEquals("two", store.previousExchange("session")?.turnId)
        assertEquals("current reply", store.previousExchange("session")?.assistantText)
        assertNull(store.previousExchange("other-session"))
    }
    private fun turn(id: String, status: String, reply: String) = SessionTurn(
        turnId = id, requestId = id, runId = id, sessionId = "session", scene = "app", userText = "input",
        startedAtEpochMillis = 1, completedAtEpochMillis = 2, finalStatus = status, finalResponse = reply,
        tools = emptyList(), completionOutcome = "UNKNOWN", replayStatus = "NOT_RUN", replaySummary = "",
    )
}
