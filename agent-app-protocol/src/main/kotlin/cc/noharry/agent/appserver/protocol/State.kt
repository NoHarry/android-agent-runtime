package cc.noharry.agent.appserver.protocol

import cc.noharry.agent.appserver.protocol.jsonrpc.ProtocolJson
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** How many threads may run at once unless the server says otherwise. */
const val DEFAULT_MAX_ACTIVE_SESSIONS = 8

/**
 * A part of the server's state other than the threads and their items, sent whole when anything in it changes
 * (`state/changed`). Each kind appears at most once ([key]); a client keeps the latest of each.
 */
@Serializable
sealed interface StateSection {
    /** Which section this is: its kind, or an extension's name. */
    val key: String get() = this::class.simpleName.orEmpty()
}

/**
 * A section an app's server adds beyond the protocol's own (its voice, an on-device model…): its [name] and its fields
 * as [data]. A client that knows the name reads it with [value]; one that does not keeps it as it came.
 */
@Serializable
@SerialName("extension")
data class ExtensionSection(val name: String, val data: JsonObject = JsonObject(emptyMap())) : StateSection {
    override val key: String get() = "extension:$name"

    @Transient
    private val decoded = ConcurrentHashMap<KSerializer<*>, Any>()

    /** [data] as [serializer] reads it, decoded once. */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> value(serializer: KSerializer<T>): T =
        decoded.getOrPut(serializer) { ProtocolJson.decodeFromJsonElement(serializer, data) } as T

    companion object {
        fun <T> of(name: String, serializer: KSerializer<T>, value: T): ExtensionSection =
            ExtensionSection(name, ProtocolJson.encodeToJsonElement(serializer, value).jsonObject)
    }
}

/** What the current thread's run is doing, for a screen to word. */
@Serializable
enum class RunActivity {
    @SerialName("idle")
    IDLE,

    /** Just started, before the model answered anything. */
    @SerialName("starting")
    STARTING,

    @SerialName("working")
    WORKING,

    @SerialName("thinking")
    THINKING,

    /** A tool is running ([RunSection.tool]). */
    @SerialName("runningTool")
    RUNNING_TOOL,

    @SerialName("waitingForApproval")
    WAITING_FOR_APPROVAL,

    @SerialName("blocked")
    BLOCKED,

    @SerialName("failed")
    FAILED,

    @SerialName("stopped")
    STOPPED,
}

/** How a run ended. */
@Serializable
enum class RunOutcome {
    @SerialName("completed")
    COMPLETED,

    /** Answered, with parts the model said it could not get (its `finish` outcome `partial`). */
    @SerialName("partial")
    PARTIAL,

    /**
     * Answered that what was asked could not be done, something standing in the way (its `finish` outcome `blocked`),
     * and asking the person nothing: an answer, not a failed run. One that asks ends [COMPLETED], as a question does.
     */
    @SerialName("blocked")
    BLOCKED,

    /** Answered, but its replay check failed. */
    @SerialName("replayFailed")
    REPLAY_FAILED,

    @SerialName("failed")
    FAILED,

    @SerialName("stopped")
    STOPPED,
}

/** A run that ended: how, and after how long. */
@Serializable
data class RunResult(val outcome: RunOutcome, val durationMs: Long)

/** The current thread's run: what it is doing, since when, and how the last one ended. */
@Serializable
@SerialName("run")
data class RunSection(
    val activity: RunActivity = RunActivity.IDLE,
    /** The tool running while [activity] is [RunActivity.RUNNING_TOOL]. */
    val tool: String = "",
    /** The runtime's own status line, in English, for debug views only: a screen words [activity], never this. */
    val runtimeStatus: String = "",
    val isRunning: Boolean = false,
    val currentRequest: CurrentRequest? = null,
    /** When the run going on started (epoch milliseconds); null when idle. */
    val runStartedAtEpochMillis: Long? = null,
    /** How the latest run ended; null until one has. */
    val lastRun: RunResult? = null,
    /** How many threads have a run going on, and how many may at once. */
    val activeSessionCount: Int = 0,
    val maxActiveSessions: Int = DEFAULT_MAX_ACTIVE_SESSIONS,
    /** Every thread with a run going on, current or not, with the run's request id. */
    val runningThreads: Map<String, String> = emptyMap(),
) : StateSection

/** The model the next turn uses and how the run is set up. No API key is ever in it ([ProviderEditState.hasApiKey]). */
@Serializable
@SerialName("model")
data class ModelSection(
    /** The selected profile's name, as the person or the preset named it. */
    val providerLabel: String = "",
    /** Whether replies stream. */
    val stream: Boolean = false,
    /** Whether runs use the simulated model (the debug backend) instead of the live one. */
    val simulated: Boolean = false,
    /** Where the provider config was loaded from; blank when none was. */
    val configSource: String = "",
    val confirmationMode: ConfirmationMode = ConfirmationMode.ALLOW_ALL_SESSION,
    val configEditor: ProviderEditState = ProviderEditState(),
) : StateSection

/** What the current thread knows: its to-dos, lessons and skills, the board, and the run's token use. */
@Serializable
@SerialName("context")
data class ContextSection(
    val contextPanel: ContextPanelState = ContextPanelState(visible = false, todo = emptyList(), lessons = emptyList(), skills = emptyList()),
    val board: SessionBoardState = SessionBoardState(activeSession = null, recentTurns = emptyList(), lastAction = null),
    val tokenUsage: TokenUsageState = TokenUsageState(),
    val contextUsage: ContextUsage = ContextUsage(),
) : StateSection

/** The live run as the runtime's events tell it, for the debug views. */
@Serializable
@SerialName("demo")
data class DemoSection(val runtimeDemo: RuntimeDemoState = RuntimeDemoState()) : StateSection

@Serializable
@SerialName("mcp")
data class McpSection(val mcpProviders: List<McpProviderState> = emptyList()) : StateSection

@Serializable
@SerialName("a2a")
data class A2aSection(val a2aClients: A2aModuleState = A2aModuleState()) : StateSection

@Serializable
@SerialName("workspace")
data class WorkspaceSection(val workspaceSelection: WorkspaceSelectionState = WorkspaceSelectionState()) : StateSection

/** The latest HTML page a run wrote, if any. */
@Serializable
@SerialName("artifact")
data class ArtifactSection(val artifactPreview: ArtifactPreview? = null) : StateSection

/** The current thread's run trajectory, loaded run by run. */
@Serializable
@SerialName("trajectory")
data class TrajectorySection(val trajectory: TrajectorySnapshot = TrajectorySnapshot(sessionId = "")) : StateSection

/** Client mode: talking to a remote A2A agent instead of the local runtime. */
@Serializable
@SerialName("a2aClient")
data class A2aClientSection(val a2aClient: A2aClientState = A2aClientState()) : StateSection

/** The plans, what came due lately, and how each watched resource stands (by plan id). */
@Serializable
@SerialName("plans")
data class PlansSection(
    val plans: List<Plan> = emptyList(),
    val planHistory: List<PlanFiring> = emptyList(),
    val planFeeds: Map<String, SubscriptionState> = emptyMap(),
) : StateSection

/** Tasks handed to remote agents and followed until they report. */
@Serializable
@SerialName("delegations")
data class DelegationsSection(val delegations: List<DelegationView> = emptyList()) : StateSection

/** Everything a client needs to start from: the threads, the current one with its items, and every section. */
@Serializable
data class ServerSnapshot(
    val threads: List<SessionSummary> = emptyList(),
    val activeThreadId: String = "",
    val thread: ThreadSnapshot = ThreadSnapshot(threadId = "", items = emptyList()),
    val sections: List<StateSection> = emptyList(),
)
