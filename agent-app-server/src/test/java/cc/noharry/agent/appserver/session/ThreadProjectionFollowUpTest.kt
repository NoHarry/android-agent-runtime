package cc.noharry.agent.appserver.session

import cc.noharry.agent.*
import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.SessionTurn
import cc.noharry.agent.appserver.protocol.USER_STEERING_LABEL
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.RunAddOns
import cc.noharry.agent.appserver.runtime.approvalSite
import cc.noharry.agent.spi.ToolSource
import org.junit.Assert.*
import org.junit.Test

/** How SDK items reach the console, phone and A2A after the move to the Thread API. */
class ThreadProjectionFollowUpTest {
    @Test fun steeringShowsThePersonsWordsAndHidesTheAppsOwnInput() {
        val store = TranscriptStore()
        store.expectSteering("mine", "开慢点")
        store.expectSteering("reminder", null)
        store.recordThreadEvent(ThreadEvent.ItemCompleted(steer("s1", "mine", "Driver says: 开慢点")), MODE)
        store.recordThreadEvent(ThreadEvent.ItemCompleted(steer("s2", "reminder", "【计划提醒】把它原样告诉用户")), MODE)
        store.recordThreadEvent(ThreadEvent.ItemCompleted(steer("s3", null, "我选第二个")), MODE)

        // Each expectation is used once: a later item with the same request id is not the app's input any more.
        store.recordThreadEvent(ThreadEvent.ItemCompleted(steer("s4", "reminder", "再说一遍")), MODE)
        val bubbles = store.snapshot().filterIsInstance<ConversationItem.TurnHeader>()
        assertEquals(listOf("开慢点", "我选第二个", "再说一遍"), bubbles.map { it.fullText })
        assertTrue(bubbles.all { it.label == USER_STEERING_LABEL })
    }

    @Test fun anApprovalCardNamesTheSiteOfAPageAndNothingForOtherTools() {
        assertEquals("example.com", approvalSite("web_fetch:example.com"))
        assertEquals("", approvalSite("mcp:cabin:unlock_door"))
        assertEquals("", approvalSite("tool:write_file"))
    }

    @Test fun aTurnWhoseToolFailedEndsWithTheResultLine() {
        val store = TranscriptStore()
        store.recordThreadEvent(ThreadEvent.ItemCompleted(ThreadItem.ToolCall("t1", "turn", "navigate", ToolSource.BACKEND,
            output = """{"ok":false}""", status = ItemStatus.FAILED)), MODE)
        store.recordThreadEvent(ThreadEvent.TurnCompleted(TurnResult("turn", TurnStatus.COMPLETED, "导航没有打开。", emptyList())), MODE)

        val result = store.snapshot().filterIsInstance<ConversationItem.RuntimePhaseItem>().single()
        assertEquals("Runtime result COMPLETED", result.title)
    }


    @Test fun aQuestionShowsOnceAsTheTurnsReply() {
        val store = TranscriptStore()
        val text = "明天去上海，你打算几点出发？"
        store.recordThreadEvent(ThreadEvent.ItemCompleted(ThreadItem.AgentMessage("m1", "turn", text, status = ItemStatus.COMPLETED)), MODE)
        val question = Question("q1", "turn", text)
        store.recordThreadEvent(ThreadEvent.ItemCompleted(question), MODE)
        store.recordThreadEvent(ThreadEvent.TurnCompleted(TurnResult("turn", TurnStatus.WAITING_FOR_ANSWER, text, emptyList(), pending = question)), MODE)

        val replies = store.snapshot().filterIsInstance<ConversationItem.MessageItem>().filter { it.content == text }
        assertEquals(1, replies.size)
        assertTrue(replies.single().final)
    }

    private fun steer(id: String, requestId: String?, text: String) =
        ThreadItem.UserMessage(id, "turn", listOf(InputPart.Text(text)), steer = true, requestId = requestId)

    private companion object {
        val MODE = ConfirmationMode.ASK_EVERY_TIME
    }
}
