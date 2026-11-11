package cc.noharry.agent.appserver.session

import cc.noharry.agent.ItemStatus
import cc.noharry.agent.MessagePhase
import cc.noharry.agent.ThreadEvent
import cc.noharry.agent.ThreadItem
import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.appserver.protocol.ConversationItem
import org.junit.Assert.*
import org.junit.Test

class ThreadItemPresentationTest {
    @Test fun aStreamedReplyBecomesTheFinalMessageOnTheSameItem() {
        val store = TranscriptStore()
        val mode = ConfirmationMode.ASK_EVERY_TIME
        val item = ThreadItem.AgentMessage("answer", "turn", "")
        store.recordThreadEvent(ThreadEvent.ItemStarted(item), mode)
        store.recordThreadEvent(ThreadEvent.ItemDelta(item.id, "Which station?\n"), mode)
        val firstId = store.snapshot().single().id
        store.recordThreadEvent(ThreadEvent.ItemCompleted(item.copy(
            text = "Which station?\n1. North\n2. South", phase = MessagePhase.FINAL, status = ItemStatus.COMPLETED,
        )), mode)
        val shown = store.snapshot().single() as ConversationItem.MessageItem
        assertEquals(firstId, shown.id)
        assertEquals("Which station?\n1. North\n2. South", shown.content)
        assertTrue(shown.final)
    }
}
