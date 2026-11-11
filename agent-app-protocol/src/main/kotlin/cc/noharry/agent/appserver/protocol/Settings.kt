package cc.noharry.agent.appserver.protocol

import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import kotlinx.serialization.Serializable

/**
 * What the add/edit form (or 连接应用) submits. A blank [token], [headers] or [query] keeps the stored one unless
 * [clearSecrets].
 */
@Serializable
data class McpServerDraft(
    /** Null for a new server. */
    val id: String?,
    val label: String,
    val kind: McpServerKind,
    val url: String = "",
    val token: String = "",
    /** One `Name: value` per line. */
    val headers: String = "",
    val clearSecrets: Boolean = false,
    /** A key the server wants in its address (`?key=…`): kept with the token, added only when a request is sent. */
    val query: Map<String, String> = emptyMap(),
    /** The id a new server gets when it is free (连接应用 names its servers, so their tools keep stable names). */
    val preferredId: String? = null,
)

/** What the Add form submits; [token] is kept only until the server stores it, and never comes back. */
@Serializable
data class A2aProfileDraft(
    val label: String,
    val moduleId: String,
    val endpoint: String,
    val allowLoopback: Boolean,
    val description: String = "",
    val token: String = "",
)

/** A question a run asked the person, as its trajectory recorded it; [state] is PENDING until answered. */
@Serializable
data class UserQuestion(
    val id: String,
    val runId: String,
    val sessionId: String,
    val title: String,
    val options: List<String>,
    val state: String,
)

/** The next turn's model: each field set is applied, in this order; the rest stay as they are. */
@Serializable
data class ModelSelectParams(
    val provider: ModelProvider? = null,
    val profileId: String? = null,
    val manualProfileEnabled: Boolean? = null,
    val model: String? = null,
    val variant: String? = null,
)

/** How runs go: each field set is applied; [toggleStream] flips streaming. */
@Serializable
data class RunSettingsParams(
    val confirmationMode: ConfirmationMode? = null,
    val simulatedBackend: Boolean? = null,
    val toggleStream: Boolean = false,
)

/** The current profile's connection, as the provider form saves it; a blank [apiKey] keeps the saved key. */
@Serializable
data class ProviderSaveParams(
    val provider: ModelProvider,
    val apiKey: String = "",
    val model: String,
    val baseUrl: String,
    val endpoint: ProviderEndpoint,
)

@Serializable
data class ProviderCreateParams(
    val label: String,
    val provider: ModelProvider,
    val endpoint: ProviderEndpoint,
    val apiKey: String,
    val model: String,
    val baseUrl: String,
)

@Serializable
data class ProviderCreateResult(val profileId: String)

/** A connection to list models from, saved or not yet. */
@Serializable
data class ProviderFetchParams(
    /** The profile whose saved key is used when [apiKey] is blank. */
    val profileId: String,
    val provider: ModelProvider,
    val endpoint: ProviderEndpoint,
    val apiKey: String,
    val model: String,
    val baseUrl: String,
)

@Serializable
data class ModelListResult(val models: List<String>)

@Serializable
data class ProviderModelsParams(val editor: ProviderEditState, val models: List<String>)

@Serializable
data class IdParams(val id: String)

@Serializable
data class EnabledParams(val id: String, val enabled: Boolean)

/** A save that may be refused: [error] says why, null when it was saved. */
@Serializable
data class SaveResult(val error: Finding? = null)

@Serializable
data class UriParams(val uri: String)

@Serializable
data class DataClearParams(
    /**
     * Plugins, by id, whose own data beyond conversations goes too (`memory`: what is remembered about the person);
     * a plugin not named keeps it, and one not running clears it all the same.
     */
    val also: List<String> = emptyList(),
)

/** Whether everything asked for was cleared. */
@Serializable
data class DataClearResult(val cleared: Boolean)

@Serializable
data class QuestionAnswerParams(val question: UserQuestion, val text: String)

/** Words for the remote agent in client mode, and where they were typed. */
@Serializable
data class A2aClientTextParams(val text: String, val source: A2aInputSource)

@Serializable
data class A2aClientAnswerParams(val questionId: String, val text: String, val source: A2aInputSource)

@Serializable
data class FlagParams(val on: Boolean)

@Serializable
data class IdsParams(val ids: List<String>)

/** Requests for client mode and the plans. */
object FeatureRequests {
    val A2A_CLIENT_SET_ENABLED = RequestMethod("a2aClient/setEnabled", FlagParams.serializer(), Empty.serializer())
    val A2A_CLIENT_SET_SPEAK_EXTERNAL = RequestMethod("a2aClient/setSpeakExternal", FlagParams.serializer(), Empty.serializer())
    val A2A_CLIENT_SEND = RequestMethod("a2aClient/send", A2aClientTextParams.serializer(), Empty.serializer())
    val A2A_CLIENT_STEER = RequestMethod("a2aClient/steer", A2aClientTextParams.serializer(), Empty.serializer())
    val A2A_CLIENT_ANSWER = RequestMethod("a2aClient/answer", A2aClientAnswerParams.serializer(), Empty.serializer())
    val A2A_CLIENT_CANCEL = RequestMethod("a2aClient/cancel", Empty.serializer(), Empty.serializer())
    val A2A_CLIENT_NEW_CONVERSATION = RequestMethod("a2aClient/newConversation", Empty.serializer(), Empty.serializer())
    val PLAN_SET_ENABLED = RequestMethod("plans/setEnabled", EnabledParams.serializer(), Empty.serializer())
    val PLAN_DELETE = RequestMethod("plans/delete", IdParams.serializer(), Empty.serializer())
    val PLAN_CREATE = RequestMethod("plans/create", PlanDraft.serializer(), SaveResult.serializer())

    /** A full-screen front end came to the front ([FlagParams.on]) or left: the one moment the microphone service may start. */
    val APP_IN_FRONT = RequestMethod("app/inFront", FlagParams.serializer(), Empty.serializer())

    /** The screens' language changed: what the server words for them (model options, settings labels) is worded again. */
    val LANGUAGE_CHANGED = RequestMethod("app/languageChanged", Empty.serializer(), Empty.serializer())

    /** These MCP servers offer other tools now: the next run lists them anew. */
    val MCP_TOOLS_CHANGED = RequestMethod("mcp/toolsChanged", IdsParams.serializer(), Empty.serializer())
}

/** Requests a settings screen sends, beside [ClientRequests]. */
object SettingsRequests {
    val MODEL_SELECT = RequestMethod("model/select", ModelSelectParams.serializer(), Empty.serializer())
    val RUN_SET = RequestMethod("run/set", RunSettingsParams.serializer(), Empty.serializer())
    val CONFIG_RELOAD = RequestMethod("config/reload", Empty.serializer(), Empty.serializer())
    val PROVIDER_SAVE = RequestMethod("provider/save", ProviderSaveParams.serializer(), Empty.serializer())
    val PROVIDER_CREATE = RequestMethod("provider/create", ProviderCreateParams.serializer(), ProviderCreateResult.serializer())
    val PROVIDER_DELETE = RequestMethod("provider/delete", IdParams.serializer(), Empty.serializer())
    val PROVIDER_FETCH_MODELS = RequestMethod("provider/fetchModels", ProviderFetchParams.serializer(), ModelListResult.serializer())
    val PROVIDER_SAVE_MODELS = RequestMethod("provider/saveModels", ProviderModelsParams.serializer(), Empty.serializer())
    val MCP_SET_ENABLED = RequestMethod("mcp/setEnabled", EnabledParams.serializer(), Empty.serializer())
    val MCP_SAVE = RequestMethod("mcp/save", McpServerDraft.serializer(), SaveResult.serializer())
    val MCP_DELETE = RequestMethod("mcp/delete", IdParams.serializer(), Empty.serializer())
    val A2A_PROFILE_SET_ENABLED = RequestMethod("a2a/profile/setEnabled", EnabledParams.serializer(), Empty.serializer())
    val A2A_PROFILE_SAVE = RequestMethod("a2a/profile/save", A2aProfileDraft.serializer(), SaveResult.serializer())
    val A2A_PROFILE_DELETE = RequestMethod("a2a/profile/delete", IdParams.serializer(), Empty.serializer())
    /** Reveals this device's incoming A2A credential on explicit settings request; never included in state. */
    val A2A_SERVER_TOKEN = RequestMethod("a2a/server/token", Empty.serializer(), TextResult.serializer())
    val WORKSPACE_SELECT = RequestMethod("workspace/select", UriParams.serializer(), Empty.serializer())
    val WORKSPACE_CLEAR = RequestMethod("workspace/clear", Empty.serializer(), Empty.serializer())

    /** The person's folder picker could not open; [TextParams.text] is why it failed to launch, blank when there is none. */
    val WORKSPACE_UNAVAILABLE = RequestMethod("workspace/unavailable", TextParams.serializer(), Empty.serializer())
    val DATA_CLEAR = RequestMethod("data/clear", DataClearParams.serializer(), DataClearResult.serializer())
    val TRAJECTORY_REFRESH = RequestMethod("trajectory/refresh", Empty.serializer(), Empty.serializer())
    val TRAJECTORY_LOAD_OLDER = RequestMethod("trajectory/loadOlder", Empty.serializer(), Empty.serializer())
    val TRAJECTORY_LOAD_RUN = RequestMethod("trajectory/loadRun", IdParams.serializer(), Empty.serializer())
    val TRAJECTORY_RAW = RequestMethod("trajectory/raw", TrajectoryRawParams.serializer(), TrajectoryRaw.serializer())
    val QUESTION_ANSWER = RequestMethod("question/answer", QuestionAnswerParams.serializer(), Empty.serializer())
}
