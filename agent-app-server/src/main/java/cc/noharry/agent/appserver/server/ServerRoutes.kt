package cc.noharry.agent.appserver.server

import cc.noharry.agent.appserver.FoundException
import cc.noharry.agent.appserver.protocol.CapabilityRequests
import cc.noharry.agent.appserver.protocol.ClientRequests
import cc.noharry.agent.appserver.protocol.DataClearResult
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.FeatureRequests
import cc.noharry.agent.appserver.protocol.Finding
import cc.noharry.agent.appserver.protocol.McpServerDraft
import cc.noharry.agent.appserver.protocol.ModelListResult
import cc.noharry.agent.appserver.protocol.ModelSelectParams
import cc.noharry.agent.appserver.protocol.PreferenceRequests
import cc.noharry.agent.appserver.protocol.ProviderCreateParams
import cc.noharry.agent.appserver.protocol.ProviderCreateResult
import cc.noharry.agent.appserver.protocol.ProviderFetchParams
import cc.noharry.agent.appserver.protocol.ProviderModelsParams
import cc.noharry.agent.appserver.protocol.ProviderSaveParams
import cc.noharry.agent.appserver.protocol.RunSettingsParams
import cc.noharry.agent.appserver.protocol.SaveResult
import cc.noharry.agent.appserver.protocol.SessionPolicy
import cc.noharry.agent.appserver.protocol.SettingsRequests
import cc.noharry.agent.appserver.protocol.SkillCheckView
import cc.noharry.agent.appserver.protocol.SubagentSettingsView
import cc.noharry.agent.appserver.protocol.SubagentToolKind
import cc.noharry.agent.appserver.protocol.TextResult
import cc.noharry.agent.appserver.protocol.ThreadListResult
import cc.noharry.agent.appserver.protocol.ThreadSnapshot
import cc.noharry.agent.appserver.protocol.ThreadStartResult
import cc.noharry.agent.appserver.protocol.TrajectoryRaw
import cc.noharry.agent.appserver.protocol.UserQuestion
import cc.noharry.agent.appserver.protocol.client.ServerState
import cc.noharry.agent.appserver.protocol.logText
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcErrorCodes
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcException
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcRequest
import cc.noharry.agent.appserver.protocol.jsonrpc.ProtocolJson
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import cc.noharry.agent.appserver.protocol.jsonrpc.paramsOf
import cc.noharry.agent.appserver.protocol.jsonrpc.resultOf
import cc.noharry.agent.appserver.runtime.AppHost
import kotlinx.serialization.json.JsonElement

/** What the settings screens ask the server; every call comes on the hub's main dispatcher. */
internal interface SettingsBackend {
    fun selectModel(params: ModelSelectParams)

    fun setRun(params: RunSettingsParams)

    fun reloadConfig()

    fun saveProvider(params: ProviderSaveParams)

    /** Saves a new profile and returns its id. */
    fun createProvider(params: ProviderCreateParams): String

    fun deleteProvider(profileId: String)

    suspend fun fetchModels(params: ProviderFetchParams): List<String>

    /**
     * Why saving [params] is refused, null when it is not: a blank key keeps the saved one only while the base URL stays
     * on the host (scheme, host and port) the saved profile points at; elsewhere the person types the key again.
     */
    fun savedKeyMoves(params: ProviderSaveParams): Finding? = null

    fun saveProviderModels(params: ProviderModelsParams)

    fun setMcpEnabled(id: String, enabled: Boolean)

    /** Null when saved, else why not. */
    fun saveMcp(draft: McpServerDraft): Finding?

    fun deleteMcp(id: String)

    fun selectWorkspace(uri: String)

    fun clearWorkspace()

    fun reportWorkspaceUnavailable(message: String)

    /** Whether everything was cleared; [also] are the plugins whose own data goes too ([DataClearing.also]). */
    suspend fun clearData(also: Set<String>): Boolean

    fun refreshTrajectory()

    fun loadOlderTrajectory()

    fun loadTrajectoryRun(runId: String)

    /** The event behind a trajectory record, in full; null when it is not held. */
    fun trajectoryRaw(sessionId: String, eventId: String): String? = null

    fun answerQuestion(question: UserQuestion, text: String)

    fun toolsChanged(serverIds: List<String>)


    fun appInFront(inFront: Boolean)

    fun setSessionPolicy(policy: SessionPolicy)

    fun setBasicTool(name: String, enabled: Boolean)

    fun setSkill(id: String, enabled: Boolean)

    fun skillText(id: String): String

    fun checkSkill(text: String, editing: String?): SkillCheckView

    fun saveSkill(text: String, editing: String?): Finding?

    fun deleteSkill(id: String)


    fun setSubagents(settings: SubagentSettingsView)

    fun setSubagentKind(id: String, enabled: Boolean)

    fun setSubagentTools(id: String, tools: Set<SubagentToolKind>, maxLookups: Int)

    fun resetSubagentTools(id: String)

    fun subagentKindText(id: String): String
}

/** Where a part of the server says which requests it answers. */
interface RouteTable {
    fun <P, R> on(method: RequestMethod<P, R>, block: suspend (P) -> R)
}

/** A part of the server with requests of its own (image model, companion, ...), installed beside the core routes. */
fun interface RouteModule {
    fun RouteTable.install()
}

/** Every request a client may send, by method name, each answered through the hub's backends and [modules]. */
internal class ServerRoutes(
    private val hub: ServerHub,
    modules: List<RouteModule>,
) : RouteTable {
    private val routes = HashMap<String, suspend (JsonRpcRequest) -> JsonElement>()

    init {
        threads()
        turns()
        models()
        connections()
        data()
        features()
        on(PreferenceRequests.SET_SESSION_POLICY) { done { hub.settings.setSessionPolicy(it) } }
        capabilities()
        modules.forEach { module -> with(module) { install() } }
    }

    private fun capabilities() {
        val settings = hub.settings
        on(CapabilityRequests.SET_BASIC_TOOL) { done { settings.setBasicTool(it.name, it.enabled) } }
        // A feature the host leaves off has no requests: a client asking gets "method not found". Operating the screen's
        // switch is its plugin's request.
        on(CapabilityRequests.SET_SKILL) { done { settings.setSkill(it.id, it.enabled) } }
        on(CapabilityRequests.SKILL_TEXT) { TextResult(settings.skillText(it.id)) }
        on(CapabilityRequests.CHECK_SKILL) { settings.checkSkill(it.text, it.editing) }
        on(CapabilityRequests.SAVE_SKILL) { SaveResult(settings.saveSkill(it.text, it.editing)) }
        on(CapabilityRequests.DELETE_SKILL) { done { settings.deleteSkill(it.id) } }
        if (AppHost.builtIns.subagents) subagents()
    }

    private fun subagents() {
        val settings = hub.settings
        on(CapabilityRequests.SET_SUBAGENTS) { done { settings.setSubagents(it) } }
        on(CapabilityRequests.SET_SUBAGENT_KIND) { done { settings.setSubagentKind(it.id, it.enabled) } }
        on(CapabilityRequests.SET_SUBAGENT_TOOLS) { done { settings.setSubagentTools(it.id, it.tools, it.maxLookups) } }
        on(CapabilityRequests.RESET_SUBAGENT_TOOLS) { done { settings.resetSubagentTools(it.id) } }
        on(CapabilityRequests.SUBAGENT_KIND_TEXT) { TextResult(settings.subagentKindText(it.id)) }
    }

    /** The methods answered here; `initialize` is the connection's own. */
    internal val methodNames: Set<String> get() = routes.keys

    suspend fun handle(request: JsonRpcRequest): JsonElement =
        (routes[request.method] ?: AppHost.additions.routes[request.method])?.invoke(request)
            ?: throw JsonRpcException(JsonRpcErrorCodes.METHOD_NOT_FOUND, request.method)

    override fun <P, R> on(method: RequestMethod<P, R>, block: suspend (P) -> R) {
        check(method.name !in routes) { "two routes for ${method.name}" }
        routes[method.name] = { request -> resultOf(method, block(request.paramsOf(method))) }
    }

    private fun threads() {
        val backend = hub.backend
        on(ClientRequests.THREAD_LIST) { state().let { ThreadListResult(it.sessions, it.activeSessionId) } }
        on(ClientRequests.THREAD_START) {
            backend.startThread()
            ThreadStartResult(state().activeSessionId)
        }
        on(ClientRequests.THREAD_SELECT) { params ->
            if (state().sessions.none { it.sessionId == params.threadId }) throw invalid("no thread ${params.threadId}")
            backend.selectThread(params.threadId)
            state().let { ThreadSnapshot(it.activeSessionId, it.items) }
        }
        on(ClientRequests.THREAD_DELETE) { done { backend.deleteThread(it.threadId) } }
        on(ClientRequests.THREAD_READ) { params ->
            if (state().sessions.none { it.sessionId == params.threadId }) throw invalid("no thread ${params.threadId}")
            ThreadSnapshot(params.threadId, backend.readThread(params.threadId))
        }
    }

    private fun turns() {
        val backend = hub.backend
        on(ClientRequests.TURN_START) { params ->
            if (params.text.isBlank()) throw invalid("empty turn")
            val other = params.threadId?.takeIf { it != state().activeSessionId }
            when {
                other == null -> done { backend.startTurn(params.text) }
                state().sessions.none { it.sessionId == other } -> throw invalid("no thread $other")
                params.focus -> done { backend.selectThread(other); backend.startTurn(params.text) }
                else -> done { backend.startTurnIn(other, params.text) }
            }
        }
        on(ClientRequests.TURN_STEER) { done { backend.steer(it.text) } }
        on(ClientRequests.TURN_INTERRUPT) { done { backend.interrupt(it.threadId) } }
        on(ClientRequests.APPROVAL_RESPOND) { done { backend.respondApproval(it.pendingId, it.decision) } }
        on(SettingsRequests.QUESTION_ANSWER) { done { hub.settings.answerQuestion(it.question, it.text) } }
    }

    private fun models() {
        val settings = hub.settings
        on(SettingsRequests.MODEL_SELECT) { done { settings.selectModel(it) } }
        on(SettingsRequests.RUN_SET) { done { settings.setRun(it) } }
        on(SettingsRequests.CONFIG_RELOAD) { done { settings.reloadConfig() } }
        on(SettingsRequests.PROVIDER_SAVE) { params ->
            settings.savedKeyMoves(params)?.let { refused -> throw JsonRpcException(JsonRpcErrorCodes.REJECTED, refused.logText(), findingData(refused)) }
            done { settings.saveProvider(params) }
        }
        on(SettingsRequests.PROVIDER_CREATE) { ProviderCreateResult(settings.createProvider(it)) }
        on(SettingsRequests.PROVIDER_DELETE) { done { settings.deleteProvider(it.id) } }
        on(SettingsRequests.PROVIDER_FETCH_MODELS) { params ->
            val models = try {
                settings.fetchModels(params)
            } catch (failed: java.io.IOException) {
                // A failure the server named (a saved key that may not go to this address) comes with its finding.
                val found = generateSequence<Throwable>(failed) { it.cause }.filterIsInstance<FoundException>().firstOrNull()
                throw JsonRpcException(JsonRpcErrorCodes.REJECTED, failed.message ?: "model list not loaded", found?.finding?.let(::findingData))
            }
            ModelListResult(models)
        }
        on(SettingsRequests.PROVIDER_SAVE_MODELS) { done { settings.saveProviderModels(it) } }
    }

    private fun connections() {
        val settings = hub.settings
        on(SettingsRequests.MCP_SET_ENABLED) { done { settings.setMcpEnabled(it.id, it.enabled) } }
        on(SettingsRequests.MCP_SAVE) { SaveResult(settings.saveMcp(it)) }
        on(SettingsRequests.MCP_DELETE) { done { settings.deleteMcp(it.id) } }
    }

    private fun data() {
        val settings = hub.settings
        on(SettingsRequests.WORKSPACE_SELECT) { done { settings.selectWorkspace(it.uri) } }
        on(SettingsRequests.WORKSPACE_CLEAR) { done { settings.clearWorkspace() } }
        on(SettingsRequests.WORKSPACE_UNAVAILABLE) { done { settings.reportWorkspaceUnavailable(it.text) } }
        on(SettingsRequests.DATA_CLEAR) { DataClearResult(settings.clearData(it.also.toSet())) }
        on(SettingsRequests.TRAJECTORY_REFRESH) { done { settings.refreshTrajectory() } }
        on(SettingsRequests.TRAJECTORY_LOAD_OLDER) { done { settings.loadOlderTrajectory() } }
        on(SettingsRequests.TRAJECTORY_LOAD_RUN) { done { settings.loadTrajectoryRun(it.id) } }
        on(SettingsRequests.TRAJECTORY_RAW) { TrajectoryRaw(settings.trajectoryRaw(it.sessionId, it.eventId)) }
    }

    private fun features() {
        val settings = hub.settings
        on(FeatureRequests.MCP_TOOLS_CHANGED) { done { settings.toolsChanged(it.ids) } }
        on(FeatureRequests.APP_IN_FRONT) { done { settings.appInFront(it.on) } }
    }


    private fun state(): ServerState = hub.published ?: ServerState()

    private inline fun done(block: () -> Unit): Empty {
        block()
        return Empty
    }

    private fun invalid(why: String) = JsonRpcException(JsonRpcErrorCodes.INVALID_PARAMS, why)
}

/** The requests one plugin answers, kept apart so they go when it stops. */
internal class RouteMap : RouteTable {
    private val routes = LinkedHashMap<String, PluginRoute>()

    val entries: Set<Map.Entry<String, PluginRoute>> get() = routes.entries

    override fun <P, R> on(method: RequestMethod<P, R>, block: suspend (P) -> R) {
        check(method.name !in routes) { "two routes for ${method.name}" }
        routes[method.name] = { request -> resultOf(method, block(request.paramsOf(method))) }
    }
}

/** [finding] as a refusal's data, for a client to word. */
private fun findingData(finding: Finding): JsonElement = ProtocolJson.encodeToJsonElement(Finding.serializer(), finding)
