package cc.noharry.agent.appserver.protocol

import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import kotlinx.serialization.Serializable

/** A native tool the model may be offered, with what Settings says it does; [required] ones cannot be switched off. */
@Serializable
data class BasicToolView(val name: String, val description: String, val enabled: Boolean, val required: Boolean = false)

/** Where operating the screen stands: switched off, connected, waiting for the system to bind it, or not working. */
@Serializable
enum class AndroidUseState { OFF, CONNECTED, WAITING, FAILED }

/** Operating the screen (Android Use): switched on, and where it stands (accessibility granted or not). */
@Serializable
data class AndroidUseView(val enabled: Boolean = false, val state: AndroidUseState = AndroidUseState.OFF)

/** A skill the runtime loads; [custom] ones were added on the device and may be edited or deleted. */
@Serializable
data class RuntimeSkillView(
    val id: String,
    val label: String,
    val description: String,
    val enabled: Boolean,
    val custom: Boolean = false,
)

/** What subagents may do, for every type: control the device, which model they use, whether they run at all. */
@Serializable
data class SubagentSettingsView(
    val allowDeviceControl: Boolean = false,
    val model: String = "",
    val enabled: Boolean = true,
    val searchApi: Boolean = false,
)

/** A group of tools a subagent type may be given; [changes] marks the ones that change something. */
@Serializable
enum class SubagentToolKind(val changes: Boolean = false) {
    SEARCH,
    FETCH,
    READ,
    WRITE(changes = true),
    TODO,
    DEVICE_QUERY,
    DEVICE_CONTROL(changes = true),
    SERVICES(changes = true),
}

/** A subagent's pages and searches when its type does not say. */
const val DEFAULT_SUBAGENT_LOOKUPS = 8

const val MIN_SUBAGENT_LOOKUPS = 1

const val MAX_SUBAGENT_LOOKUPS = 20

/** One subagent type, as set, and as it ships ([defaultTools], [defaultLookups]); [label] is its name in Settings. */
@Serializable
data class SubagentKindView(
    val id: String,
    val label: String,
    val description: String,
    val maxLookups: Int,
    val searchApi: Boolean? = null,
    val enabled: Boolean = true,
    val tools: Set<SubagentToolKind> = emptySet(),
    /** Offered only while some profile has a service's search and fetch APIs. */
    val requiresWebLookup: Boolean = false,
    val defaultTools: Set<SubagentToolKind> = emptySet(),
    val defaultLookups: Int = 0,
)

/** What a custom skill's text was found to be: its name and description, what is wrong, what to look at. */
@Serializable
data class SkillCheckView(
    val name: String? = null,
    val description: String? = null,
    val errors: List<Finding> = emptyList(),
    val warnings: List<Finding> = emptyList(),
    val normalized: String = "",
)

@Serializable
data class NameEnabledParams(val name: String, val enabled: Boolean)

@Serializable
data class SkillTextParams(val text: String, val editing: String? = null)

@Serializable
data class TextResult(val text: String)

@Serializable
data class SubagentToolsParams(val id: String, val tools: Set<SubagentToolKind>, val maxLookups: Int)

/** Requests for tools, skills and subagents ([Preferences] holds what they set). */
object CapabilityRequests {
    val SET_BASIC_TOOL = RequestMethod("tools/setBasic", NameEnabledParams.serializer(), Empty.serializer())
    val SET_ANDROID_USE = RequestMethod("tools/setAndroidUse", FlagParams.serializer(), Empty.serializer())
    val SET_SKILL = RequestMethod("skills/set", EnabledParams.serializer(), Empty.serializer())

    /** A skill's whole text as the model reads it, or a custom skill's as written. */
    val SKILL_TEXT = RequestMethod("skills/text", IdParams.serializer(), TextResult.serializer())
    val CHECK_SKILL = RequestMethod("skills/check", SkillTextParams.serializer(), SkillCheckView.serializer())
    val SAVE_SKILL = RequestMethod("skills/save", SkillTextParams.serializer(), SaveResult.serializer())
    val DELETE_SKILL = RequestMethod("skills/delete", IdParams.serializer(), Empty.serializer())
    val SET_SUBAGENTS = RequestMethod("subagents/set", SubagentSettingsView.serializer(), Empty.serializer())
    val SET_SUBAGENT_KIND = RequestMethod("subagents/setKind", EnabledParams.serializer(), Empty.serializer())
    val SET_SUBAGENT_TOOLS = RequestMethod("subagents/setKindTools", SubagentToolsParams.serializer(), Empty.serializer())
    val RESET_SUBAGENT_TOOLS = RequestMethod("subagents/resetKindTools", IdParams.serializer(), Empty.serializer())

    /** A subagent type's instructions as it reads them. */
    val SUBAGENT_KIND_TEXT = RequestMethod("subagents/kindText", IdParams.serializer(), TextResult.serializer())
}
