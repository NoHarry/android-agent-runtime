package cc.noharry.agent.appserver.protocol

import cc.noharry.agent.appserver.protocol.jsonrpc.NotificationMethod
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import kotlinx.serialization.KSerializer

/** Who sends a method, and whether it is answered. */
enum class MethodKind {
    CLIENT_REQUEST,
    CLIENT_NOTIFICATION,
    SERVER_REQUEST,
    SERVER_NOTIFICATION,
}

/** One method as the schema lists it: its name, who sends it, and how its params and its result (requests) look. */
class ProtocolMethod(val name: String, val kind: MethodKind, val params: KSerializer<*>, val result: KSerializer<*>? = null)

/**
 * Every method of the app server's protocol, one line each. An app lists its own methods on top of these in its own
 * protocol module, and its schema files are written from that list; tests check it against the methods defined and
 * the requests the server answers, so a new method needs its line too.
 */
object AppServerMethods {
    val all: List<ProtocolMethod> = listOf(
        // Requests a client sends the server.
        request(ClientRequests.INITIALIZE),
        request(ClientRequests.THREAD_LIST),
        request(ClientRequests.THREAD_START),
        request(ClientRequests.THREAD_SELECT),
        request(ClientRequests.THREAD_DELETE),
        request(ClientRequests.THREAD_READ),
        request(ClientRequests.THREAD_SUBSCRIBE),
        request(ClientRequests.THREAD_UNSUBSCRIBE),
        request(ClientRequests.TURN_START),
        request(ClientRequests.TURN_STEER),
        request(ClientRequests.TURN_INTERRUPT),
        request(ClientRequests.APPROVAL_RESPOND),
        request(CapabilityRequests.SET_BASIC_TOOL),
        request(CapabilityRequests.SET_ANDROID_USE),
        request(CapabilityRequests.SET_SKILL),
        request(CapabilityRequests.SKILL_TEXT),
        request(CapabilityRequests.CHECK_SKILL),
        request(CapabilityRequests.SAVE_SKILL),
        request(CapabilityRequests.DELETE_SKILL),
        request(CapabilityRequests.SET_SUBAGENTS),
        request(CapabilityRequests.SET_SUBAGENT_KIND),
        request(CapabilityRequests.SET_SUBAGENT_TOOLS),
        request(CapabilityRequests.RESET_SUBAGENT_TOOLS),
        request(CapabilityRequests.SUBAGENT_KIND_TEXT),
        request(McpRequests.STATUS),
        request(McpRequests.TOOLS),
        request(FileRequests.MOUNTS),
        request(FileRequests.LIST),
        request(FileRequests.READ),
        request(PageRequests.READ),
        request(ImageModelRequests.READ),
        request(ImageModelRequests.SAVE),
        request(ImageModelRequests.MODELS),
        request(ImageModelRequests.TRY),
        request(MemoryRequests.SET_ENABLED),
        request(MemoryRequests.WRITE),
        request(MemoryRequests.DELETE),
        request(MemoryRequests.CLEAR),
        request(MemoryRequests.LESSONS),
        request(ModelSettingsRequests.MODELS_READ),
        request(ModelSettingsRequests.MODELS_SAVE),
        request(ModelSettingsRequests.LOOKUP_READ),
        request(ModelSettingsRequests.LOOKUP_SET_SEARCH_API),
        request(ModelSettingsRequests.LOOKUP_SET_FETCH_FALLBACK),
        request(PreferenceRequests.SET_SESSION_POLICY),
        request(FeatureRequests.A2A_CLIENT_SET_ENABLED),
        request(FeatureRequests.A2A_CLIENT_SET_SPEAK_EXTERNAL),
        request(FeatureRequests.A2A_CLIENT_SEND),
        request(FeatureRequests.A2A_CLIENT_STEER),
        request(FeatureRequests.A2A_CLIENT_ANSWER),
        request(FeatureRequests.A2A_CLIENT_CANCEL),
        request(FeatureRequests.A2A_CLIENT_NEW_CONVERSATION),
        request(FeatureRequests.PLAN_SET_ENABLED),
        request(FeatureRequests.PLAN_DELETE),
        request(FeatureRequests.PLAN_CREATE),
        request(FeatureRequests.APP_IN_FRONT),
        request(FeatureRequests.LANGUAGE_CHANGED),
        request(FeatureRequests.MCP_TOOLS_CHANGED),
        request(SettingsRequests.MODEL_SELECT),
        request(SettingsRequests.RUN_SET),
        request(SettingsRequests.CONFIG_RELOAD),
        request(SettingsRequests.PROVIDER_SAVE),
        request(SettingsRequests.PROVIDER_CREATE),
        request(SettingsRequests.PROVIDER_DELETE),
        request(SettingsRequests.PROVIDER_FETCH_MODELS),
        request(SettingsRequests.PROVIDER_SAVE_MODELS),
        request(SettingsRequests.MCP_SET_ENABLED),
        request(SettingsRequests.MCP_SAVE),
        request(SettingsRequests.MCP_DELETE),
        request(SettingsRequests.A2A_PROFILE_SET_ENABLED),
        request(SettingsRequests.A2A_PROFILE_SAVE),
        request(SettingsRequests.A2A_PROFILE_DELETE),
        request(SettingsRequests.A2A_SERVER_TOKEN),
        request(SettingsRequests.WORKSPACE_SELECT),
        request(SettingsRequests.WORKSPACE_CLEAR),
        request(SettingsRequests.WORKSPACE_UNAVAILABLE),
        request(SettingsRequests.DATA_CLEAR),
        request(SettingsRequests.TRAJECTORY_REFRESH),
        request(SettingsRequests.TRAJECTORY_LOAD_OLDER),
        request(SettingsRequests.TRAJECTORY_LOAD_RUN),
        request(SettingsRequests.TRAJECTORY_RAW),
        request(SettingsRequests.QUESTION_ANSWER),
        request(UsageRequests.READ),
        request(UsageRequests.SET_PRICE),
        request(UsageRequests.SET_RATE),
        request(UsageRequests.SYNC_PRICES),
        // Notifications a client sends the server.
        clientNotification(ClientNotifications.INITIALIZED),
        clientNotification(PresentationReports.STATE),
        // Requests the server sends a client that took them on.
        serverRequest(ServerRequests.APPROVAL_REQUEST),
        serverRequest(PresentationRequests.ENSURE_RUNNING),
        serverRequest(PresentationRequests.START),
        serverRequest(PresentationRequests.CARD),
        serverRequest(PresentationRequests.WAKE),
        serverRequest(PresentationRequests.ANDROID_USE_BEGIN),
        serverRequest(PresentationRequests.ANDROID_USE_POINT),
        serverRequest(PresentationRequests.ANDROID_USE_DRAG),
        serverRequest(PresentationRequests.ANDROID_USE_HIDE),
        // Notifications the server sends its clients.
        request(PluginRequests.LIST),
        request(PluginRequests.SET),
        request(PluginRequests.INSTALL),
        request(PluginRequests.CONFIRM),
        request(PluginRequests.REMOVE),
        serverNotification(PluginNotifications.FEATURES),
        serverNotification(ServerNotifications.THREAD_LIST_CHANGED),
        serverNotification(ServerNotifications.THREAD_SELECTED),
        serverNotification(ServerNotifications.TURN_STARTED),
        serverNotification(ServerNotifications.TURN_COMPLETED),
        serverNotification(ServerNotifications.ITEM_STARTED),
        serverNotification(ServerNotifications.ITEM_UPDATED),
        serverNotification(ServerNotifications.ITEM_DELTA),
        serverNotification(ServerNotifications.ITEM_REMOVED),
        serverNotification(ServerNotifications.STATE_CHANGED),
        serverNotification(ServerNotifications.APPROVAL_RESOLVED),
        serverNotification(PresentationNotifications.PLAN_DUE),
        serverNotification(PresentationNotifications.PLAN_RAN),
        serverNotification(PresentationNotifications.ANDROID_USE_REVEAL),
    )
}


fun request(method: RequestMethod<*, *>) = ProtocolMethod(method.name, MethodKind.CLIENT_REQUEST, method.params, method.result)

fun clientNotification(method: NotificationMethod<*>) = ProtocolMethod(method.name, MethodKind.CLIENT_NOTIFICATION, method.params)

fun serverRequest(method: RequestMethod<*, *>) = ProtocolMethod(method.name, MethodKind.SERVER_REQUEST, method.params, method.result)

fun serverNotification(method: NotificationMethod<*>) = ProtocolMethod(method.name, MethodKind.SERVER_NOTIFICATION, method.params)
