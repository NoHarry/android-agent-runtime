package cc.noharry.agent.appserver.server

import cc.noharry.agent.appserver.protocol.ApprovalDecision
import cc.noharry.agent.appserver.protocol.CapabilityRequests
import cc.noharry.agent.appserver.protocol.ClientCapabilities
import cc.noharry.agent.appserver.protocol.ClientInfo
import cc.noharry.agent.appserver.protocol.ClientRequests
import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.appserver.protocol.ConfirmationStatus
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.FeatureRequests
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.FindingCode
import cc.noharry.agent.appserver.protocol.FlagParams
import cc.noharry.agent.appserver.protocol.IdParams
import cc.noharry.agent.appserver.protocol.SubagentSettingsView
import cc.noharry.agent.appserver.protocol.McpServerDraft
import cc.noharry.agent.appserver.protocol.McpServerKind
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.protocol.ModelSelectParams
import cc.noharry.agent.appserver.protocol.OverlayCommand
import cc.noharry.agent.appserver.protocol.PlanDraft
import cc.noharry.agent.appserver.protocol.PlanTrigger
import cc.noharry.agent.appserver.protocol.PresentCardParams
import cc.noharry.agent.appserver.protocol.PresentResult
import cc.noharry.agent.appserver.protocol.PresentStartParams
import cc.noharry.agent.appserver.protocol.PresentationReports
import cc.noharry.agent.appserver.protocol.PresentationRequests
import cc.noharry.agent.appserver.protocol.PresentationState
import cc.noharry.agent.appserver.protocol.ProviderCreateParams
import cc.noharry.agent.appserver.protocol.ProviderEditState
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.appserver.protocol.ProviderFetchParams
import cc.noharry.agent.appserver.protocol.ProviderModelsParams
import cc.noharry.agent.appserver.protocol.ProviderSaveParams
import cc.noharry.agent.appserver.protocol.RunOutcome
import cc.noharry.agent.appserver.protocol.RunResult
import cc.noharry.agent.appserver.protocol.RunSettingsParams
import cc.noharry.agent.appserver.protocol.ServerFeatures
import cc.noharry.agent.appserver.protocol.ServerInfo
import cc.noharry.agent.appserver.protocol.ServerNotifications
import cc.noharry.agent.appserver.protocol.SessionPolicy
import cc.noharry.agent.appserver.protocol.SessionSummary
import cc.noharry.agent.appserver.protocol.SettingsRequests
import cc.noharry.agent.appserver.protocol.ThreadIdParams
import cc.noharry.agent.appserver.protocol.ToolCallStatus
import cc.noharry.agent.appserver.protocol.TrajectorySnapshot
import cc.noharry.agent.appserver.protocol.TranscriptRole
import cc.noharry.agent.appserver.protocol.TurnInterruptParams
import cc.noharry.agent.appserver.protocol.TurnStartParams
import cc.noharry.agent.appserver.protocol.UserQuestion
import cc.noharry.agent.appserver.protocol.USER_STEERING_LABEL
import cc.noharry.agent.appserver.protocol.client.ServerClient
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.client.threadOf
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcErrorCodes
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcException
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcMessage
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcNotification
import cc.noharry.agent.appserver.protocol.text
import cc.noharry.agent.appserver.protocol.transport.InProcessChannels
import cc.noharry.agent.appserver.protocol.transport.MessageChannel
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.BuiltInFeatures
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ServerHubTest {
    private val mainThread = Executors.newSingleThreadExecutor()
    private val main = mainThread.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val backend = FakeBackend()
    private val settings = FakeSettings()
    private val hub = ServerHub(backend, settings, main, scope, ServerInfo("test"))

    @After
    fun tearDown() {
        scope.cancel()
        mainThread.shutdownNow()
    }

    @Test
    fun aClientFollowsEveryChangeAndEndsWhereTheServerIs() = runBlocking {
        backend.publish(start)
        val client = connect()
        val notes = record(client)
        backend.publish(start.copy(items = start.items + answer.copy(content = "路线"), isRunning = true, runtimeStatus = "Running"))
        backend.publish(start.copy(items = start.items + answer.copy(content = "路线大约 42 分钟"), isRunning = true, runtimeStatus = "Running"))
        val tool = ConversationItem.ToolCallItem(3, "c1", "navigation_route", "{}", null, ToolCallStatus.RUNNING, startedAt = 1)
        backend.publish(backend.state.copy(items = backend.state.items + tool))
        backend.publish(backend.state.copy(items = backend.state.items.map { if (it.id == 3L) tool.copy(status = ToolCallStatus.DONE, resultJson = "{}") else it }))
        val last = backend.state.copy(items = backend.state.items.filterNot { it.id == 1L }, isRunning = false, runtimeStatus = "Idle", lastRun = RunResult(RunOutcome.COMPLETED, 3_000))
        backend.publish(last)
        val state = withTimeout(TIMEOUT_MS) { client.state.first { it.lastRun != null } }
        assertEquals(last.forClient(), state.forClient())
        assertEquals(1, notes.count { it.method == ServerNotifications.ITEM_DELTA.name })
        assertTrue(notes.any { it.method == ServerNotifications.TURN_STARTED.name })
        assertTrue(notes.any { it.method == ServerNotifications.TURN_COMPLETED.name })
    }

    @Test
    fun switchingThreadsSendsTheNewThreadWhole() = runBlocking {
        backend.publish(start)
        val client = connect()
        client.request(ClientRequests.THREAD_SELECT, ThreadIdParams("t2"))
        val state = withTimeout(TIMEOUT_MS) { client.state.first { it.activeSessionId == "t2" } }
        assertEquals(listOf(other), state.items)
    }

    @Test
    fun requestsReachTheBackendAndBadOnesAreRefused() = runBlocking {
        backend.publish(start)
        val client = connect()
        client.request(ClientRequests.TURN_START, TurnStartParams("去世界之窗"))
        client.request(ClientRequests.TURN_INTERRUPT, TurnInterruptParams())
        assertEquals(listOf("turn 去世界之窗", "interrupt"), backend.calls)
        assertRefused(JsonRpcErrorCodes.INVALID_PARAMS) { client.request(ClientRequests.TURN_START, TurnStartParams(" ")) }
        assertRefused(JsonRpcErrorCodes.INVALID_PARAMS) { client.request(ClientRequests.THREAD_SELECT, ThreadIdParams("nope")) }
    }

    @Test
    fun nothingButTheHandshakeBeforeIt() = runBlocking {
        val (clientEnd, serverEnd) = InProcessChannels.pair()
        hub.connect(serverEnd)
        val peer = cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcPeer(clientEnd, scope, onRequest = { null }, onNotification = {})
        peer.start()
        assertRefused(JsonRpcErrorCodes.NOT_INITIALIZED) { peer.request(ClientRequests.TURN_INTERRUPT, TurnInterruptParams()) }
    }

    @Test
    fun theFirstClientToAnswerAnApprovalDecidesAndTheOtherIsCalledOff() = runBlocking {
        backend.publish(start)
        val quick = connect(ClientCapabilities(approvals = true))
        val slow = connect(ClientCapabilities(approvals = true))
        val slowAsked = CompletableDeferred<Unit>()
        val slowCalledOff = CompletableDeferred<Unit>()
        quick.approvals = { ask -> if (ask.toolName == "car_climate") ApprovalDecision.APPROVE else ApprovalDecision.DENY }
        slow.approvals = {
            slowAsked.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                slowCalledOff.complete(Unit)
            }
        }
        val tool = ConversationItem.ToolCallItem(3, "c1", "car_climate", """{"seat":"driver"}""", null, ToolCallStatus.RUNNING, startedAt = 1)
        val steer = ConversationItem.TurnHeader(4, USER_STEERING_LABEL, "Only the driver", "Only the driver")
        val approval = ConversationItem.ConfirmationItem(5, "p1", mode = ConfirmationMode.ASK_EVERY_TIME, createdAt = 2)
        backend.publish(start.copy(items = start.items + tool + steer + approval))
        withTimeout(TIMEOUT_MS) { slowCalledOff.await() }
        assertEquals(listOf("approval p1 APPROVE"), backend.calls)
        assertTrue(slowAsked.isCompleted)
    }

    @Test
    fun aClientLearnsWhetherAKeyIsSavedButNeverTheKey() = runBlocking {
        val keyed = start.copy(configEditor = ProviderEditState(model = "m", hasApiKey = true))
        backend.publish(keyed)
        val client = connect()
        assertTrue(client.state.value.configEditor.hasApiKey)
        backend.publish(keyed.copy(configEditor = keyed.configEditor.copy(model = "n")))
        val seen = withTimeout(TIMEOUT_MS) { client.state.first { it.configEditor.model == "n" } }
        assertTrue(seen.configEditor.hasApiKey)
    }

    @Test
    fun onlyAClientThatAskedGetsTheTrajectory() = runBlocking {
        val traced = start.copy(trajectory = TrajectorySnapshot(sessionId = "t1", revision = 2))
        backend.publish(traced)
        val follower = connect(ClientCapabilities(trajectory = true))
        val plain = connect()
        assertEquals(2L, follower.state.value.trajectory.revision)
        assertEquals(0L, plain.state.value.trajectory.revision)
        backend.publish(traced.copy(trajectory = traced.trajectory.copy(revision = 3), runtimeStatus = "moved"))
        withTimeout(TIMEOUT_MS) { follower.state.first { it.trajectory.revision == 3L } }
        val after = withTimeout(TIMEOUT_MS) { plain.state.first { it.runtimeStatus == "moved" } }
        assertEquals(0L, after.trajectory.revision)
    }

    @Test
    fun settingsRequestsReachTheirBackendInTheOrderSent() = runBlocking {
        backend.publish(start)
        val client = connect()
        client.request(SettingsRequests.MODEL_SELECT, ModelSelectParams(profileId = "p", model = "flash", variant = "thinking"))
        val saved = client.request(SettingsRequests.MCP_SAVE, McpServerDraft(null, "", McpServerKind.HTTP))
        assertEquals(text("label needed"), saved.error)
        assertEquals(listOf("profile p", "model flash", "variant thinking", "mcp "), settings.calls)
        assertRefused(JsonRpcErrorCodes.REJECTED) {
            client.request(SettingsRequests.PROVIDER_FETCH_MODELS, ProviderFetchParams("p", ModelProvider.OPENAI, ProviderEndpoint.CHAT_COMPLETIONS, "", "", "https://down.invalid"))
        }
    }


    @Test
    fun aSavedKeyThatWouldMoveToAnotherHostIsRefusedWithItsFinding() = runBlocking {
        backend.publish(start)
        val client = connect()
        val save = ProviderSaveParams(ModelProvider.OPENAI, "", "m", "https://evil.test/v1", ProviderEndpoint.CHAT_COMPLETIONS)
        val refused = runCatching { client.request(SettingsRequests.PROVIDER_SAVE, save) }.exceptionOrNull() as JsonRpcException
        assertEquals(JsonRpcErrorCodes.REJECTED, refused.code)
        assertEquals(FindingCode.MODEL_KEY_TYPE_AGAIN.name, refused.data?.jsonObject?.get("code")?.jsonPrimitive?.content)
        client.request(SettingsRequests.PROVIDER_SAVE, save.copy(apiKey = "sk-typed"))
        assertEquals(listOf("save https://evil.test/v1"), settings.calls.filter { it.startsWith("save") })
        val list = runCatching {
            client.request(SettingsRequests.PROVIDER_FETCH_MODELS, ProviderFetchParams("p", ModelProvider.OPENAI, ProviderEndpoint.CHAT_COMPLETIONS, "", "", "https://evil.test/v1"))
        }.exceptionOrNull() as JsonRpcException
        assertEquals(FindingCode.MODEL_KEY_TYPE_AGAIN.name, list.data?.jsonObject?.get("code")?.jsonPrimitive?.content)
    }

    @Test
    fun theHandshakeNamesTheFeaturesTheHostTurnedOn() = runBlocking {
        val features = BuiltInFeatures(web = true).offered()
        assertEquals(setOf(ServerFeatures.WEB), features)
        assertEquals(emptySet<String>(), BuiltInFeatures.NONE.offered())
        backend.publish(start)
        val (clientEnd, serverEnd) = InProcessChannels.pair()
        ServerHub(backend, settings, main, scope, ServerInfo("test", features = features)).connect(serverEnd)
        val shook = ServerClient(clientEnd, ClientInfo("test"), ClientCapabilities(), scope).connect()
        assertEquals(features, shook.serverInfo.features)
    }

    @Test
    fun withoutPlansTheirRequestsAreNotThere() = runBlocking {
        backend.publish(start)
        val client = connect()
        val trigger = PlanTrigger.Repeat(minuteOfDay = 8 * 60, days = setOf(1))
        assertRefused(JsonRpcErrorCodes.METHOD_NOT_FOUND) { client.request(FeatureRequests.PLAN_CREATE, PlanDraft("带伞", message = "记得带伞", trigger = trigger)) }
        assertRefused(JsonRpcErrorCodes.METHOD_NOT_FOUND) { client.request(CapabilityRequests.SET_ANDROID_USE, FlagParams(true)) }
        // Nor those of subagents.
        assertRefused(JsonRpcErrorCodes.METHOD_NOT_FOUND) { client.request(CapabilityRequests.SET_SUBAGENTS, SubagentSettingsView()) }
        assertRefused(JsonRpcErrorCodes.METHOD_NOT_FOUND) { client.request(CapabilityRequests.SUBAGENT_KIND_TEXT, IdParams("trip")) }
        assertEquals(emptyList<String>(), settings.calls)
    }

    @Test
    fun theAppsPresentationIsAskedAndSaysHowTheAssistantStands() = runBlocking {
        val presenter = connect(ClientCapabilities(presentation = true, state = false)) {
            handle(PresentationRequests.CARD) { PresentResult(refusal = if (it.command == OverlayCommand.HIDE) "nothing to fold" else null) }
        }
        assertEquals("nothing to fold", hub.presentation.ask(PresentationRequests.CARD, PresentCardParams(OverlayCommand.HIDE))?.refusal)
        assertNull(hub.presentation.ask(PresentationRequests.CARD, PresentCardParams(OverlayCommand.SHOW))?.refusal)
        val readiness = CompletableDeferred<Boolean>()
        hub.presentation.changed = { readiness.complete(it) }
        presenter.notify(PresentationReports.STATE, PresentationState(ready = true, inFront = true))
        assertTrue(withTimeout(TIMEOUT_MS) { readiness.await() })
        assertTrue(hub.presentation.state.inFront)
        presenter.close()
        withTimeout(TIMEOUT_MS) { while (hub.presentation.state.ready) delay(10) }
        assertEquals("no assistant on screen", hub.presentation.present { hub.presentation.ask(PresentationRequests.START, PresentStartParams(open = true)) })
    }

    @Test
    fun aClientFollowsAThreadBesideTheCurrentOneAndOnlyItHearsIt() = runBlocking {
        backend.publish(start)
        val follower = connect(ClientCapabilities(approvals = true)).apply { approvals = { ApprovalDecision.APPROVE } }
        val bystander = connect()
        val heard = record(bystander)
        assertEquals(listOf(other), follower.follow("t2").items)
        val reply = ConversationItem.MessageItem(10, TranscriptRole.ASSISTANT, "开好了", final = true)
        backend.publish(backend.state.copy(threads = mapOf("t2" to listOf(other, reply)), runningThreads = mapOf("t2" to "r2")))
        val seen = withTimeout(TIMEOUT_MS) { follower.state.first { it.threads["t2"]?.size == 2 } }
        assertEquals(listOf(header), seen.items)
        val approval = ConversationItem.ConfirmationItem(11, "p2", mode = ConfirmationMode.ASK_EVERY_TIME, createdAt = 3)
        backend.publish(backend.state.copy(threads = mapOf("t2" to listOf(other, reply, approval)), runningThreads = emptyMap()))
        withTimeout(TIMEOUT_MS) { while ("approval p2 APPROVE" !in backend.calls) delay(10) }
        follower.unfollow("t2")
        assertTrue("unfollow t2" in backend.calls)
        assertTrue(follower.state.value.threads.isEmpty())
        assertTrue(heard.none { it.threadOf() == "t2" || it.method == ServerNotifications.TURN_STARTED.name })
    }

    @Test
    fun aTurnGoesToAThreadWithoutMakingItCurrent() = runBlocking {
        backend.publish(start)
        val client = connect()
        assertEquals(listOf(other), client.request(ClientRequests.THREAD_READ, ThreadIdParams("t2")).items)
        client.request(ClientRequests.TURN_START, TurnStartParams("开空调", threadId = "t2", focus = false))
        client.request(ClientRequests.TURN_INTERRUPT, TurnInterruptParams("t2"))
        assertEquals(listOf("read t2", "turn t2 开空调", "interrupt t2"), backend.calls)
        assertEquals("t1", backend.state.activeSessionId)
    }

    @Test
    fun aClientThatTakesNoStateStartsNothing() = runBlocking {
        val started = AtomicInteger()
        val lazy = ServerHub(backend, settings, main, scope, ServerInfo("test"), startSources = { started.incrementAndGet() })
        val host = connect(ClientCapabilities(presentation = true, state = false), lazy)
        assertEquals(0, started.get())
        assertTrue(host.state.value.sessions.isEmpty())
        connect(to = lazy)
        connect(to = lazy)
        assertEquals(1, started.get())
    }

    @Test
    fun aClientThatStopsReadingIsLetGo() = runBlocking {
        backend.publish(start)
        val (clientEnd, serverEnd) = InProcessChannels.pair()
        val stalling = StallingChannel(serverEnd)
        hub.connect(stalling, queueLimit = 4)
        ServerClient(clientEnd, ClientInfo("test"), ClientCapabilities(), scope).connect()
        stalling.stalled = true
        repeat(10) { n -> backend.publish(start.copy(items = start.items + answer.copy(content = "第 $n 句"))) }
        withTimeout(TIMEOUT_MS) { while (hub.connectionCount > 0) delay(10) }
    }

    /** A connection whose client stops reading once [stalled]: what is sent to it then never leaves. */
    private class StallingChannel(private val inner: MessageChannel) : MessageChannel {
        @Volatile var stalled = false
        override val incoming = inner.incoming

        override suspend fun send(message: JsonRpcMessage) = if (stalled) awaitCancellation() else inner.send(message)

        override fun close() = inner.close()
    }

    private suspend fun connect(
        capabilities: ClientCapabilities = ClientCapabilities(),
        to: ServerHub = hub,
        setUp: ServerClient.() -> Unit = {},
    ): ServerClient {
        val (clientEnd, serverEnd) = InProcessChannels.pair()
        to.connect(serverEnd)
        return ServerClient(clientEnd, ClientInfo("test"), capabilities, scope).apply(setUp).also { it.connect() }
    }

    private fun record(client: ServerClient): List<JsonRpcNotification> {
        val seen = CopyOnWriteArrayList<JsonRpcNotification>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { client.notifications.collect { seen += it } }
        return seen
    }

    private suspend fun assertRefused(code: Int, block: suspend () -> Unit) {
        try {
            block()
            fail("accepted")
        } catch (error: JsonRpcException) {
            assertEquals(code, error.code)
        }
    }

    /** What a client holds of [this]: no fold choices of the server's, connected. */
    private fun ServerState.forClient() = copy(folded = emptyMap(), connected = true)

    private val header = ConversationItem.TurnHeader(1, "You", "去世界之窗", "去世界之窗")
    private val answer = ConversationItem.MessageItem(2, TranscriptRole.ASSISTANT, "", final = true)
    private val other = ConversationItem.TurnHeader(9, "You", "开空调", "开空调")
    private val start = ServerState(
        sessions = listOf(SessionSummary("t1", "去世界之窗", 1, 1, ""), SessionSummary("t2", "开空调", 2, 1, "")),
        activeSessionId = "t1",
        items = listOf(header),
    )

    /** A runtime that answers at once and publishes on the hub's main thread, as the real one does. */
    private inner class FakeBackend : ServerBackend {
        @Volatile var state = ServerState()
        val calls = CopyOnWriteArrayList<String>()

        suspend fun publish(next: ServerState) = withContext(main) {
            state = next
            hub.publish(next)
        }

        private fun publishNow(next: ServerState) {
            state = next
            hub.publish(next)
        }

        override fun clientJoined() = Unit

        override fun startThread() = publishNow(state.copy(activeSessionId = "new", items = emptyList()))

        override fun selectThread(threadId: String) =
            publishNow(state.copy(activeSessionId = threadId, items = if (threadId == "t2") listOf(other) else listOf(header)))

        override fun deleteThread(threadId: String) {
            calls += "delete $threadId"
        }

        override fun startTurn(text: String) {
            calls += "turn $text"
        }

        override fun steer(text: String) {
            calls += "steer $text"
        }

        override fun interrupt(threadId: String?) {
            calls += listOfNotNull("interrupt", threadId).joinToString(" ")
        }

        override fun readThread(threadId: String): List<ConversationItem> {
            calls += "read $threadId"
            return if (threadId == "t2") listOf(other) else state.items
        }

        override fun followThread(threadId: String): List<ConversationItem> {
            calls += "follow $threadId"
            publishNow(state.copy(threads = state.threads + (threadId to listOf(other))))
            return listOf(other)
        }

        override fun unfollowThread(threadId: String) {
            calls += "unfollow $threadId"
            publishNow(state.copy(threads = state.threads - threadId))
        }

        override fun startTurnIn(threadId: String, text: String) {
            calls += "turn $threadId $text"
        }

        override fun respondApproval(pendingId: String, decision: ApprovalDecision) {
            calls += "approval $pendingId $decision"
            val approved = { items: List<ConversationItem> ->
                items.map { if (it is ConversationItem.ConfirmationItem && it.pendingId == pendingId) it.copy(status = ConfirmationStatus.APPROVED) else it }
            }
            publishNow(state.copy(items = approved(state.items), threads = state.threads.mapValues { approved(it.value) }))
        }
    }

    /** Settings that record what they were asked; a nameless MCP server is refused, and model lists never load. */
    private class FakeSettings : SettingsBackend {
        val calls = CopyOnWriteArrayList<String>()

        override fun selectModel(params: ModelSelectParams) {
            params.profileId?.let { calls += "profile $it" }
            params.model?.let { calls += "model $it" }
            params.variant?.let { calls += "variant $it" }
        }

        override fun setRun(params: RunSettingsParams) = Unit
        override fun reloadConfig() = Unit
        override fun saveProvider(params: ProviderSaveParams) { calls += "save ${params.baseUrl}" }

        override fun savedKeyMoves(params: ProviderSaveParams): Finding? =
            Finding(FindingCode.MODEL_KEY_TYPE_AGAIN.name, listOf("evil.test")).takeIf { params.apiKey.isBlank() && "evil.test" in params.baseUrl }
        override fun createProvider(params: ProviderCreateParams) = "new"
        override fun deleteProvider(profileId: String) = Unit
        override suspend fun fetchModels(params: ProviderFetchParams): List<String> =
            if ("evil.test" in params.baseUrl) {
                throw java.io.IOException("refused", cc.noharry.agent.appserver.FoundException(Finding(FindingCode.MODEL_KEY_TYPE_AGAIN.name, listOf("evil.test"))))
            } else {
                throw java.io.IOException("unreachable")
            }
        override fun saveProviderModels(params: ProviderModelsParams) = Unit
        override fun setMcpEnabled(id: String, enabled: Boolean) = Unit

        override fun saveMcp(draft: McpServerDraft): Finding? {
            calls += "mcp ${draft.label}"
            return if (draft.label.isBlank()) text("label needed") else null
        }

        override fun deleteMcp(id: String) = Unit
        override fun selectWorkspace(uri: String) = Unit
        override fun clearWorkspace() = Unit
        override fun reportWorkspaceUnavailable(message: String) = Unit
        override suspend fun clearData(also: Set<String>) = true
        override fun refreshTrajectory() = Unit
        override fun loadOlderTrajectory() = Unit
        override fun loadTrajectoryRun(runId: String) = Unit
        override fun answerQuestion(question: UserQuestion, text: String) = Unit
        override fun toolsChanged(serverIds: List<String>) = Unit
        override fun appInFront(inFront: Boolean) = Unit
        override fun setSessionPolicy(policy: SessionPolicy) = Unit
        override fun setBasicTool(name: String, enabled: Boolean) = Unit
        override fun setSkill(id: String, enabled: Boolean) = Unit
        override fun skillText(id: String) = ""
        override fun checkSkill(text: String, editing: String?) = cc.noharry.agent.appserver.protocol.SkillCheckView()
        override fun saveSkill(text: String, editing: String?): Finding? = null
        override fun deleteSkill(id: String) = Unit
        override fun setSubagents(settings: cc.noharry.agent.appserver.protocol.SubagentSettingsView) = Unit
        override fun setSubagentKind(id: String, enabled: Boolean) = Unit
        override fun setSubagentTools(id: String, tools: Set<cc.noharry.agent.appserver.protocol.SubagentToolKind>, maxLookups: Int) = Unit
        override fun resetSubagentTools(id: String) = Unit
        override fun subagentKindText(id: String) = ""
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
