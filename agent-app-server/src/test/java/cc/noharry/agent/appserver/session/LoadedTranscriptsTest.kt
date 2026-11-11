package cc.noharry.agent.appserver.session

import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.TranscriptRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LoadedTranscriptsTest {
    private val focus = TranscriptStore()
    private val loaded = LoadedTranscripts(focus)
    private val running = mutableSetOf<String>()

    init {
        loaded.keepLoaded = { it in running }
    }

    @Test
    fun aRunGoingOnKeepsItsLiveItemsAcrossSelectingAwayAndBack() {
        focus.openTurn("去世界之窗")
        focus.appendAssistantDelta("路线")
        running += "a"
        loaded.select(previous = "a", next = "b", keepPrevious = loaded.keepLoaded("a")) { stored("b") }
        assertEquals(stored("b"), focus.snapshot())
        val background = loaded.of("a", activeSessionId = "b")!!
        background.appendAssistantDelta("大约 42 分钟")
        loaded.select(previous = "b", next = "a", keepPrevious = loaded.keepLoaded("b")) { error("a is live") }
        val reply = focus.snapshot().filterIsInstance<ConversationItem.MessageItem>().single { it.role == TranscriptRole.ASSISTANT }
        assertEquals("路线大约 42 分钟", reply.content)
        assertNull(loaded.of("b", activeSessionId = "a"))
    }

    @Test
    fun aSessionLoadedBesideIsLetGoOnceNothingKeepsIt() {
        val store = loaded.load("c") { stored("c") }
        assertSame(store, loaded.of("c", activeSessionId = "a"))
        running += "c"
        loaded.release("c")
        assertSame(store, loaded.of("c", activeSessionId = "a"))
        running -= "c"
        loaded.release("c")
        assertNull(loaded.of("c", activeSessionId = "a"))
    }

    @Test
    fun anApprovalIsFoundInTheSessionWhereItWaits() {
        loaded.load("c") { emptyList() }.apply {
            openTurn("开空调")
            appendConfirmationRequired(pendingId = "p1", domain = "", mode = ConfirmationMode.ASK_EVERY_TIME)
        }
        assertEquals("c", loaded.sessionWaitingFor("p1", activeSessionId = "a"))
        assertNull(loaded.sessionWaitingFor("p2", activeSessionId = "a"))
    }

    private fun stored(sessionId: String) = listOf(ConversationItem.TurnHeader(1, "You", sessionId, sessionId))
}
