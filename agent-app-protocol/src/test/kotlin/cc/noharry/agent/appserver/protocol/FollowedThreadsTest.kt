package cc.noharry.agent.appserver.protocol

import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.ItemChanged
import cc.noharry.agent.appserver.protocol.ItemDelta
import cc.noharry.agent.appserver.protocol.ServerNotifications
import cc.noharry.agent.appserver.protocol.TranscriptRole
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.client.after
import cc.noharry.agent.appserver.protocol.client.afterInFollowed
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcNotification
import cc.noharry.agent.appserver.protocol.jsonrpc.NotificationMethod
import cc.noharry.agent.appserver.protocol.jsonrpc.ProtocolJson
import org.junit.Assert.assertEquals
import org.junit.Test

class FollowedThreadsTest {
    private val asked = ConversationItem.TurnHeader(1, "You", "去世界之窗", "去世界之窗")
    private val other = ConversationItem.TurnHeader(9, "You", "开空调", "开空调")
    private val reply = ConversationItem.MessageItem(10, TranscriptRole.ASSISTANT, "开", final = false)
    private val state = ServerState(activeSessionId = "t1", items = listOf(asked), threads = mapOf("t2" to listOf(other)))

    @Test
    fun aFollowedThreadsItemsChangeBesideTheCurrentOnes() {
        val next = state
            .after(notification(ServerNotifications.ITEM_STARTED, ItemChanged("t2", reply)))
            .after(notification(ServerNotifications.ITEM_DELTA, ItemDelta("t2", reply.id, "好了")))
        assertEquals(listOf(asked), next.items)
        assertEquals("开好了", (next.threads.getValue("t2").last() as ConversationItem.MessageItem).content)
    }

    @Test
    fun anUnfollowedThreadIsNotKept() {
        val next = state.after(notification(ServerNotifications.ITEM_STARTED, ItemChanged("t3", reply)))
        assertEquals(state, next)
    }

    @Test
    fun theCurrentThreadFollowedToo() {
        val both = state.copy(threads = mapOf("t1" to listOf(asked)))
        val next = both.after(notification(ServerNotifications.ITEM_STARTED, ItemChanged("t1", reply)))
        assertEquals(listOf(asked, reply), next.items)
        assertEquals(listOf(asked, reply), next.threads["t1"])
    }

    @Test
    fun whatCameWhileAFollowWasAnsweredGoesOverItsAnswer() {
        val missed = notification(ServerNotifications.ITEM_STARTED, ItemChanged("t2", reply))
        val answered = state.copy(threads = mapOf("t2" to listOf(other)))
        assertEquals(listOf(other, reply), answered.afterInFollowed(missed, "t2").threads["t2"])
        assertEquals(answered, answered.afterInFollowed(missed, "t3"))
    }

    private fun <P> notification(method: NotificationMethod<P>, params: P) =
        JsonRpcNotification(method.name, ProtocolJson.encodeToJsonElement(method.params, params))
}
