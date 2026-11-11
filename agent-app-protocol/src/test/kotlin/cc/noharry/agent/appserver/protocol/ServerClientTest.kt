package cc.noharry.agent.appserver.protocol

import cc.noharry.agent.appserver.protocol.ApprovalDecision
import cc.noharry.agent.appserver.protocol.ApprovalRequest
import cc.noharry.agent.appserver.protocol.ApprovalResolved
import cc.noharry.agent.appserver.protocol.ApprovalResponse
import cc.noharry.agent.appserver.protocol.ClientCapabilities
import cc.noharry.agent.appserver.protocol.ClientInfo
import cc.noharry.agent.appserver.protocol.ClientRequests
import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.appserver.protocol.ConfirmationStatus
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.InitializeResult
import cc.noharry.agent.appserver.protocol.ItemChanged
import cc.noharry.agent.appserver.protocol.ItemDelta
import cc.noharry.agent.appserver.protocol.ItemRemoved
import cc.noharry.agent.appserver.protocol.PROTOCOL_VERSION
import cc.noharry.agent.appserver.protocol.RunOutcome
import cc.noharry.agent.appserver.protocol.RunResult
import cc.noharry.agent.appserver.protocol.RunSection
import cc.noharry.agent.appserver.protocol.ServerInfo
import cc.noharry.agent.appserver.protocol.ServerNotifications
import cc.noharry.agent.appserver.protocol.ServerRequests
import cc.noharry.agent.appserver.protocol.ServerSnapshot
import cc.noharry.agent.appserver.protocol.SessionSummary
import cc.noharry.agent.appserver.protocol.StateChanged
import cc.noharry.agent.appserver.protocol.ThreadListChanged
import cc.noharry.agent.appserver.protocol.ThreadSnapshot
import cc.noharry.agent.appserver.protocol.TranscriptRole
import cc.noharry.agent.appserver.protocol.TurnStartParams
import cc.noharry.agent.appserver.protocol.client.ServerClient
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcErrorCodes
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcException
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcPeer
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcRequest
import cc.noharry.agent.appserver.protocol.jsonrpc.paramsOf
import cc.noharry.agent.appserver.protocol.jsonrpc.resultOf
import cc.noharry.agent.appserver.protocol.request
import cc.noharry.agent.appserver.protocol.text
import cc.noharry.agent.appserver.protocol.transport.InProcessChannels
import cc.noharry.agent.appserver.protocol.transport.LineMessageChannel
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ServerClientTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun theHandshakeGivesTheSnapshotAndLaterNotificationsChangeIt() = runBlocking {
        val server = FakeServer(scope)
        val client = server.client()
        client.connect()
        assertEquals("t1", client.state.value.activeSessionId)
        assertEquals(listOf(header), client.state.value.items)
        assertTrue(client.state.value.connected)

        server.notify(ServerNotifications.ITEM_STARTED, ItemChanged("t1", answer.copy(content = "路线")))
        server.notify(ServerNotifications.ITEM_DELTA, ItemDelta("t1", answer.id, "大约 42 分钟"))
        server.notify(ServerNotifications.ITEM_STARTED, ItemChanged("other", answer.copy(id = 99)))
        server.notify(ServerNotifications.STATE_CHANGED, StateChanged(RunSection(runtimeStatus = "Done", lastRun = RunResult(RunOutcome.COMPLETED, 3_000))))
        val state = withTimeout(TIMEOUT_MS) { client.state.first { it.runtimeStatus == "Done" } }
        assertEquals(listOf(header, answer.copy(content = "路线大约 42 分钟")), state.items)
        assertEquals(RunResult(RunOutcome.COMPLETED, 3_000), state.lastRun)
    }

    @Test
    fun notificationsSentBeforeTheHandshakesAnswerArrivesAreKeptInOrder() = runBlocking {
        val server = FakeServer(scope, beforeAnswer = { peer ->
            peer.notify(ServerNotifications.ITEM_STARTED, ItemChanged("t1", answer))
            peer.notify(ServerNotifications.ITEM_REMOVED, ItemRemoved("t1", header.id))
        })
        val client = server.client()
        client.connect()
        assertEquals(listOf(answer), client.state.value.items)
    }

    @Test
    fun switchingThreadsReplacesTheItemsAndForgetsWhatWasOpened() = runBlocking {
        val server = FakeServer(scope)
        val client = server.client()
        client.connect()
        client.setFolded(header.id, folded = true)
        assertTrue(client.state.value.isFolded(header))
        server.notify(ServerNotifications.THREAD_LIST_CHANGED, ThreadListChanged(listOf(summary("t1"), summary("t2")), "t2"))
        server.notify(ServerNotifications.THREAD_SELECTED, ThreadSnapshot("t2", listOf(answer)))
        val state = withTimeout(TIMEOUT_MS) { client.state.first { it.activeSessionId == "t2" && it.items.isNotEmpty() } }
        assertEquals(listOf(answer), state.items)
        assertFalse(state.isFolded(header))
        assertEquals(2, state.sessions.size)
    }

    @Test
    fun thinkingAndToolsStartFoldedUntilOpened() = runBlocking {
        val client = FakeServer(scope).client()
        client.connect()
        val thinking = ConversationItem.MessageItem(5, TranscriptRole.THINKING, "先查路线")
        assertTrue(client.state.value.isFolded(thinking))
        client.setFolded(thinking.id, folded = false)
        assertFalse(client.state.value.isFolded(thinking))
        assertFalse(client.state.value.isFolded(answer))
    }

    @Test
    fun aRequestTheServerRefusesThrowsItsError() = runBlocking {
        val client = FakeServer(scope).client()
        client.connect()
        try {
            client.request(ClientRequests.TURN_START, TurnStartParams(""))
            fail("accepted an empty turn")
        } catch (error: JsonRpcException) {
            assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, error.code)
        }
    }

    @Test
    fun anApprovalIsAnsweredByTheClientsHandler() = runBlocking {
        val server = FakeServer(scope)
        val client = server.client(ClientCapabilities(approvals = true))
        client.approvals = { ask -> if (ask.toolName == "car_climate") ApprovalDecision.APPROVE else ApprovalDecision.DENY }
        client.connect()
        val decision = server.ask(ApprovalRequest("t1", 4, "p1", mode = ConfirmationMode.ASK_EVERY_TIME, toolName = "car_climate"))
        assertEquals(ApprovalDecision.APPROVE, decision.decision)
    }

    @Test
    fun anApprovalAnotherClientAnsweredIsCalledOff() = runBlocking {
        val server = FakeServer(scope)
        val client = server.client(ClientCapabilities(approvals = true))
        val asked = CompletableDeferred<Unit>()
        val calledOff = CompletableDeferred<Unit>()
        client.approvals = {
            asked.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                calledOff.complete(Unit)
            }
        }
        client.connect()
        val answer = scope.async { runCatching { server.ask(ApprovalRequest("t1", 4, "p1", mode = ConfirmationMode.ASK_EVERY_TIME)) } }
        withTimeout(TIMEOUT_MS) { asked.await() }
        server.notify(ServerNotifications.APPROVAL_RESOLVED, ApprovalResolved("p1", ConfirmationStatus.APPROVED))
        withTimeout(TIMEOUT_MS) { calledOff.await() }
        // The server hears it was answered elsewhere, and stops waiting on this client.
        val refused = withTimeout(TIMEOUT_MS) { answer.await() }.exceptionOrNull() as JsonRpcException
        assertEquals(JsonRpcErrorCodes.REJECTED, refused.code)
    }

    @Test
    fun followingTheCurrentThreadTakesWhatStreamedOnce() = runBlocking {
        val partial = answer.copy(content = "路线大约 ", final = false)
        val whole = answer.copy(content = "路线大约 42 分钟", final = false)
        // The server published a delta just before it answered the follow: its answer holds that delta too.
        val server = FakeServer(scope, subscribed = { peer, id ->
            peer.notify(ServerNotifications.ITEM_DELTA, ItemDelta(id, answer.id, "42 分钟"))
            ThreadSnapshot(id, listOf(header, whole))
        })
        val client = server.client()
        client.connect()
        server.notify(ServerNotifications.ITEM_STARTED, ItemChanged("t1", partial))
        withTimeout(TIMEOUT_MS) { client.state.first { it.items.size == 2 } }
        client.follow("t1")
        assertEquals(listOf(header, whole), client.state.value.items)
        assertEquals(listOf(header, whole), client.state.value.threads["t1"])
    }

    @Test
    fun theSameConversationRunsOverALineStream() = runBlocking {
        val toServer = PipedOutputStream()
        val serverIn = PipedInputStream(toServer, PIPE_BYTES)
        val toClient = PipedOutputStream()
        val clientIn = PipedInputStream(toClient, PIPE_BYTES)
        val server = FakeServer(scope, channel = LineMessageChannel(serverIn, toClient, scope))
        val client = ServerClient(LineMessageChannel(clientIn, toServer, scope), ClientInfo("test"), scope = scope)
        server.start()
        client.connect()
        server.notify(ServerNotifications.ITEM_STARTED, ItemChanged("t1", answer))
        val state = withTimeout(TIMEOUT_MS) { client.state.first { it.items.size == 2 } }
        assertEquals(listOf(header, answer), state.items)
    }

    private fun summary(id: String) = SessionSummary(id, id, updatedAtEpochMillis = 0, turnCount = 1, compactedSummary = "")

    private val header = ConversationItem.TurnHeader(1, "You", "去世界之窗", "去世界之窗")
    private val answer = ConversationItem.MessageItem(2, TranscriptRole.ASSISTANT, "路线大约 42 分钟", final = true)

    /** A server that answers the handshake with one thread and refuses an empty turn. */
    private inner class FakeServer(
        private val scope: CoroutineScope,
        channel: cc.noharry.agent.appserver.protocol.transport.MessageChannel? = null,
        private val beforeAnswer: suspend (JsonRpcPeer) -> Unit = {},
        private val subscribed: suspend (JsonRpcPeer, String) -> ThreadSnapshot = { _, id -> ThreadSnapshot(id, emptyList()) },
    ) {
        private val ends = if (channel == null) InProcessChannels.pair() else null
        private val clientEnd = ends?.second
        private lateinit var peer: JsonRpcPeer
        private val serverChannel = channel ?: ends!!.first

        fun start() {
            peer = JsonRpcPeer(serverChannel, scope, ::handle, onNotification = {})
            peer.start()
        }

        fun client(capabilities: ClientCapabilities = ClientCapabilities()): ServerClient {
            start()
            return ServerClient(clientEnd!!, ClientInfo("test"), capabilities, scope)
        }

        suspend fun <P> notify(method: cc.noharry.agent.appserver.protocol.jsonrpc.NotificationMethod<P>, params: P) = peer.notify(method, params)

        suspend fun ask(request: ApprovalRequest): ApprovalResponse = peer.request(ServerRequests.APPROVAL_REQUEST, request)

        private suspend fun handle(request: JsonRpcRequest): JsonElement? = when (request.method) {
            ClientRequests.INITIALIZE.name -> {
                beforeAnswer(peer)
                resultOf(
                    ClientRequests.INITIALIZE,
                    InitializeResult(
                        protocolVersion = PROTOCOL_VERSION,
                        serverInfo = ServerInfo("fake"),
                        snapshot = ServerSnapshot(listOf(summary("t1")), "t1", ThreadSnapshot("t1", listOf(header))),
                    ),
                )
            }
            ClientRequests.THREAD_SUBSCRIBE.name ->
                resultOf(ClientRequests.THREAD_SUBSCRIBE, subscribed(peer, request.paramsOf(ClientRequests.THREAD_SUBSCRIBE).threadId))
            ClientRequests.TURN_START.name -> {
                if (request.paramsOf(ClientRequests.TURN_START).text.isBlank()) {
                    throw JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, "empty turn")
                }
                resultOf(ClientRequests.TURN_START, Empty)
            }
            else -> throw JsonRpcException(JsonRpcErrorCodes.METHOD_NOT_FOUND, request.method)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val PIPE_BYTES = 1 shl 16
    }
}
