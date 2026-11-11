package cc.noharry.agent.appserver.session
import android.app.Application
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.appserver.protocol.ConfirmationStatus
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.ToolCallStatus
import cc.noharry.agent.appserver.protocol.TranscriptRole
import cc.noharry.agent.appserver.protocol.foldedByDefault
import cc.noharry.agent.*
import cc.noharry.agent.spi.ToolSource
import cc.noharry.agent.appserver.runtime.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs under Robolectric for the console's labels; the default (English) resources give the strings asserted here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TranscriptTest {
    private val resources: Resources = ApplicationProvider.getApplicationContext<Application>().resources

    @Test
    fun trimForDisplayBoundsLargeTranscriptBodies() {
        val output = "x".repeat(LONG_BODY_CHARS).trimForDisplay()

        assertEquals(MAX_EXPECTED_UI_BODY_CHARS, output.length)
        assertTrue(output.endsWith("[truncated in debug UI]"))
    }

    @Test
    fun confirmationCardUpdatesInPlaceAfterDecision() {
        val store = TranscriptStore()
        store.openTurn("fetch example")

        store.appendConfirmationRequired(
            pendingId = "pending-1",
            domain = "example.com",
            mode = ConfirmationMode.ALLOW_CONFIRMED_DOMAINS,
        )
        store.updateConfirmation("pending-1", ConfirmationStatus.APPROVED)

        val confirmations = store.snapshot().filterIsInstance<ConversationItem.ConfirmationItem>()
        assertEquals(1, confirmations.size)
        assertEquals("example.com", confirmations.single().domain)
        assertEquals(ConfirmationStatus.APPROVED, confirmations.single().status)
        assertTrue(confirmations.single().resolvedAt != null)
    }

    @Test
    fun thinkingDefaultsToCollapsed() {
        val thinking = ConversationItem.MessageItem(
            id = 5,
            role = TranscriptRole.THINKING,
            content = "reasoning",
        )
        assertTrue(thinking.foldedByDefault)
    }

    @Test
    fun parsesToolResultStatusFromOkLine() {
        assertEquals(ToolCallStatus.DONE, parseToolResultStatus("tool=x\nok=true\nresult={}"))
        assertEquals(ToolCallStatus.FAILED, parseToolResultStatus("tool=x\nok=false\nresult={}"))
        assertEquals(ToolCallStatus.UNKNOWN, parseToolResultStatus("tool=x\nresult={}"))
    }

    @Test
    fun runtimeResultBodyWarnsWhenToolsHaveIssues() {
        val decorated = decorateRuntimeResultBody(
            body = "final\\nanswer",
            issues = ToolIssueSummary(failed = 1, unknown = 2),
        )

        assertTrue(decorated.contains("Tool execution did not fully pass."))
        assertTrue(decorated.contains("Failed tool calls: 1"))
        assertTrue(decorated.contains("Tool calls without captured raw result: 2"))
        assertTrue(decorated.endsWith("final\nanswer"))
    }

    @Test fun toolItemsPairByIdAcrossOutOfOrderCompletion() {
        val store = TranscriptStore()
        store.openTurn("go")
        val first = ThreadItem.ToolCall("one", "run", "track", ToolSource.BACKEND, arguments = "{\"id\":1}")
        val second = first.copy(id = "two", arguments = "{\"id\":2}")
        store.recordThreadEvent(ThreadEvent.ItemStarted(first), ConfirmationMode.ASK_EVERY_TIME)
        store.recordThreadEvent(ThreadEvent.ItemStarted(second), ConfirmationMode.ASK_EVERY_TIME)
        store.recordThreadEvent(ThreadEvent.ItemCompleted(second.copy(output = "failed", status = ItemStatus.FAILED)), ConfirmationMode.ASK_EVERY_TIME)
        store.recordThreadEvent(ThreadEvent.ItemCompleted(first.copy(output = "done", status = ItemStatus.COMPLETED)), ConfirmationMode.ASK_EVERY_TIME)
        store.markPendingToolCallsUnknown()
        val tools = store.snapshot().filterIsInstance<ConversationItem.ToolCallItem>()
        assertEquals(2, tools.size)
        assertEquals("done", tools.single { it.toolCallId == "one" }.resultJson)
        assertEquals(ToolCallStatus.FAILED, tools.single { it.toolCallId == "two" }.status)
        assertEquals(1, store.toolIssuesForCurrentTurn().failed)
    }

    @Test fun completedMessageReplacesDeltasWithoutDuplicateOrLostWhitespace() {
        val store = TranscriptStore()
        store.openTurn("question")
        val item = ThreadItem.AgentMessage("message", "run", "")
        val mode = ConfirmationMode.ASK_EVERY_TIME
        store.recordThreadEvent(ThreadEvent.ItemStarted(item), mode)
        store.recordThreadEvent(ThreadEvent.ItemDelta(item.id, "hello "), mode)
        store.recordThreadEvent(ThreadEvent.ItemDelta(item.id, "world"), mode)
        store.recordThreadEvent(ThreadEvent.ItemCompleted(item.copy(text = "hello world", phase = MessagePhase.FINAL, status = ItemStatus.COMPLETED)), mode)
        val message = store.snapshot().filterIsInstance<ConversationItem.MessageItem>().single()
        assertEquals("hello world", message.content)
        assertTrue(message.final)
    }

    @Test fun hostedSearchKeepsStructuredQueryAndResultCount() {
        val store = TranscriptStore()
        store.openTurn("search")
        store.recordThreadEvent(ThreadEvent.ItemCompleted(ThreadItem.WebSearch("search", "run", "provider", "station parking", 4)), ConfirmationMode.ASK_EVERY_TIME)
        val tool = store.snapshot().filterIsInstance<ConversationItem.ToolCallItem>().single()
        assertEquals("{\"query\":\"station parking\"}", tool.argumentsJson)
        assertTrue(tool.resultJson.orEmpty().contains("\"resultCount\":4"))
    }

    @Test fun approvalCardCompletesInPlace() {
        val store = TranscriptStore()
        store.recordThreadEvent(ThreadEvent.ItemStarted(TestApproval()), ConfirmationMode.ASK_EVERY_TIME)
        val id = store.snapshot().single().id
        store.recordThreadEvent(ThreadEvent.ItemCompleted(TestApproval(ApprovalState.APPROVED)), ConfirmationMode.ASK_EVERY_TIME)
        val item = store.snapshot().single() as ConversationItem.ConfirmationItem
        assertEquals(id, item.id)
        assertEquals(ConfirmationStatus.APPROVED, item.status)
    }

    @Test fun consecutiveTurnsRemainDistinct() {
        val store = TranscriptStore()
        store.openTurn("hot")
        store.openTurn("still hot")
        val turns = store.snapshot().filterIsInstance<ConversationItem.TurnHeader>()
        assertEquals(listOf("hot", "still hot"), turns.map { it.fullText })
        assertTrue(turns[0].id < turns[1].id)
    }

    private companion object {
        const val LONG_BODY_CHARS = 20000
        const val MAX_EXPECTED_UI_BODY_CHARS = 12024
    }
}
