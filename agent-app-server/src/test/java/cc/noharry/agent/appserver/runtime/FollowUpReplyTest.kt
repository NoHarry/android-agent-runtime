@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)
package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.*
import cc.noharry.agent.appserver.session.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FollowUpReplyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun hostPassesTheReplyUnframedToTheSdk() {
        val store = SessionStore(temporary.newFolder())
        for (text in listOf("要", "确认", "new request", "raw\ninput")) {
            assertEquals(text, store.prepareRunInput("session", text).first)
        }
    }

    @Test fun sdkQuestionPersistsAsTheVisibleTurnReply() {
        val store = SessionStore(temporary.newFolder())
        val recorder = SessionTurnRecorder(RuntimeTurnRequest("turn", "session", "question"), "question")
        val question = Question("question-id", "turn", "Which city?")
        recorder.recordEvent(ThreadEvent.TurnCompleted(TurnResult("turn", TurnStatus.WAITING_FOR_ANSWER, question.text, listOf(question), pending = question)))
        recorder.recordCancelled()
        store.appendTurn(recorder.snapshot())
        val saved = store.readTurns("session").single()
        assertEquals("WAITING_USER_INPUT", saved.finalStatus)
        assertEquals("Which city?", saved.finalResponse)
        val (input, previous) = store.prepareRunInput("session", "Suzhou")
        assertEquals("Suzhou", input)
        assertEquals(question.text, previous?.assistantText)
    }
}
