@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime
import android.content.Context
import cc.noharry.agent.appserver.evidence.AppDiagnostics
import cc.noharry.agent.appserver.evidence.DiagnosticEntry
import cc.noharry.agent.appserver.evidence.RunEvidenceRecorder
import cc.noharry.agent.appserver.evidence.RunReplayResult
import cc.noharry.agent.appserver.evidence.RunReplayVerifier
import cc.noharry.agent.appserver.evidence.RuntimeMemoryEvidence
import cc.noharry.agent.appserver.evidence.appDiagnostics
import cc.noharry.agent.appserver.evidence.appendRunEvidence
import cc.noharry.agent.appserver.mcp.McpProgressiveEvidenceRecorder
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.model.ProviderProfile
import cc.noharry.agent.appserver.protocol.ConfirmationMode
import cc.noharry.agent.appserver.protocol.ContextPanelState
import cc.noharry.agent.appserver.protocol.McpProviderState
import cc.noharry.agent.appserver.session.SessionCoordinator
import cc.noharry.agent.appserver.session.SessionTurnRecorder
import cc.noharry.agent.appserver.session.TranscriptStore
import cc.noharry.agent.appserver.skills.AppSubagents
import cc.noharry.agent.appserver.skills.MAX_CHILD_LOOKUPS
import cc.noharry.agent.appserver.skills.SubagentGuard
import cc.noharry.agent.appserver.skills.appSubagentKind
import cc.noharry.agent.appserver.skills.subagentDeviceServers
import cc.noharry.agent.appserver.trajectory.appSessionEventLogRoot
import cc.noharry.agent.appserver.usage.TokenDelta
import cc.noharry.agent.ThreadEvent
import cc.noharry.agent.AgentRuntime
import cc.noharry.agent.runtime.NetworkToolConfirmationPolicy
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RuntimeRunScope(
    val requestId: String,
    val sessionId: String,
) {
    fun ownsUi(activeRequestId: String?, activeSessionId: String?, selectedSessionId: String): Boolean =
        activeRequestId == requestId && activeSessionId == sessionId && selectedSessionId == sessionId
}

internal data class RuntimeFinalization(
    val replay: RunReplayResult,
    val contextPanel: ContextPanelState?,
)

internal data class RuntimeRunRecorders(
    val evidenceRecorder: McpProgressiveEvidenceRecorder?,
    val runEvidenceRecorder: RunEvidenceRecorder,
    val turnRecorder: SessionTurnRecorder,
)

internal fun scopedDiagnostics(
    context: Context,
    runScope: RuntimeRunScope,
    ownsUi: (RuntimeRunScope) -> Boolean,
    onEntry: (DiagnosticEntry) -> Unit,
    onTokenUsage: (TokenDelta) -> Unit,
): AppDiagnostics =
    appDiagnostics(
        context = context,
        requestId = runScope.requestId,
        onEntry = { entry -> if (ownsUi(runScope)) onEntry(entry) },
        onTokenUsage = { delta -> if (ownsUi(runScope)) onTokenUsage(delta) },
    )

internal fun buildScopedRuntime(
    context: Context,
    config: ProviderConfig,
    profile: ProviderProfile,
    streamEnabled: Boolean,
    useSimulatedModel: Boolean,
    workspaceRoot: File,
    sessionContext: String,
    diagnostics: AppDiagnostics,
    runScope: RuntimeRunScope,
    recorders: RuntimeRunRecorders,
    mcpProviders: List<McpProviderState>,
    confirmationMode: () -> ConfirmationMode = { ConfirmationMode.ASK_EVERY_TIME },
    /** Same text as RuntimeTurnRequest.input — hashed into evidence when [caseBound] is true. */
    userInput: String = "",
    /**
     * Production case-bound flag. Default false. Session runner sets true only for the
     * exact sample bench prompt so [buildRuntime] calls [runtimeEvidence].
     */
    caseBound: Boolean = false,
    previousExchange: PreviousExchange? = null,
    /** Another agent's task: the person's mode, which answers its tools that act ([ActingToolGate]); its children's too. */
    outsideAnswer: ConfirmationMode? = null,
): AgentRuntime {
    // A child of this conversation is built like this run's runtime: same connection and tools, its own log, no
    // conversation history or recap, the guard, and the model the settings or the spawning run chose.
    AppSubagents.rememberChildRuntime(runScope.sessionId) { spec ->
        val kind = spec.agentType?.let { appSubagentKind(context, it) }
        buildRuntime(
            context, config, profile.forSubagent(config, spec.modelRef), streamEnabled, useSimulatedModel, workspaceRoot,
            appSessionEventLogRoot(context, spec.sessionId),
            sessionContext = "",
            evidenceRecorder = null,
            diagnostics = appDiagnostics(context, "${spec.sessionId}-run", onEntry = {}, onTokenUsage = {}),
            mcpProviders = mcpProviders,
            networkConfirmationPolicy = confirmationMode().toNetworkPolicy(),
            userInput = spec.task,
            hostHook = SubagentGuard(
                spec.allowDeviceControl, kind?.maxLookups ?: MAX_CHILD_LOOKUPS, kind?.tools, kind?.id.orEmpty(),
                subagentDeviceServers(context),
            ),
            subagentChild = true,
            subagentKind = kind,
            approvalMode = confirmationMode,
            outsideAnswer = outsideAnswer,
        )
    }
    return buildRuntime(
        context, config, profile, streamEnabled, useSimulatedModel, workspaceRoot,
        appSessionEventLogRoot(context, runScope.sessionId),
        sessionContext,
        recorders.evidenceRecorder,
        diagnostics,
        mcpProviders,
        networkConfirmationPolicy = confirmationMode().toNetworkPolicy(),
        userInput = userInput,
        caseBound = caseBound,
        hostHook = ExecutionToolHook(),
        previousExchange = previousExchange,
        sessionId = runScope.sessionId,
        approvalMode = confirmationMode,
        outsideAnswer = outsideAnswer,
    )
}

/** The profile a child runs on: the parent's, with the model the subagent settings chose (same provider only). */
private fun ProviderProfile.forSubagent(config: ProviderConfig, ref: cc.noharry.agent.spi.model.ModelRef?): ProviderProfile {
    if (ref == null || (ref.providerId == provider.wireName && ref.modelId == model)) return this
    val base = if (ref.providerId == provider.wireName) this else config.profiles.firstOrNull { it.provider.wireName == ref.providerId } ?: return this
    return base.copy(model = ref.modelId, variant = ref.variant.orEmpty())
}

private fun ConfirmationMode.toNetworkPolicy(): NetworkToolConfirmationPolicy =
    if (this == ConfirmationMode.ALLOW_ALL_SESSION) {
        NetworkToolConfirmationPolicy.ALLOW_ALL_HOSTS
    } else {
        NetworkToolConfirmationPolicy.REQUIRE_PER_HOST
    }

internal fun scopedEventHandler(
    runScope: RuntimeRunScope,
    ownsUi: (RuntimeRunScope) -> Boolean,
    transcriptOf: (RuntimeRunScope) -> TranscriptStore?,
    setStatus: (String) -> Unit,
    notifyChanged: () -> Unit,
    confirmationMode: () -> ConfirmationMode = { ConfirmationMode.ASK_EVERY_TIME },
): (ThreadEvent) -> Unit = { event ->
    handleScopedThreadEvent(
        event = event,
        ownsUi = ownsUi(runScope),
        transcript = transcriptOf(runScope),
        setStatus = setStatus,
        notifyChanged = notifyChanged,
        confirmationMode = confirmationMode,
    )
}

/**
 * A run's event in its session's live [transcript] (null when the session is not loaded, or the run no longer its own:
 * nothing to do). Only the run on screen ([ownsUi]) sets the status line.
 */
internal fun handleScopedThreadEvent(
    event: ThreadEvent,
    ownsUi: Boolean,
    transcript: TranscriptStore?,
    setStatus: (String) -> Unit,
    notifyChanged: () -> Unit,
    confirmationMode: () -> ConfirmationMode = { ConfirmationMode.ASK_EVERY_TIME },
) {
    if (transcript == null) return
    transcript.recordThreadEvent(event, confirmationMode())
    event.statusText()?.let { if (ownsUi) setStatus(it) }
    notifyChanged()
}


internal suspend fun finalizeRuntimeRun(
    context: Context,
    runtime: AgentRuntime,
    transcript: TranscriptStore,
    sessionCoordinator: SessionCoordinator,
    evidenceRecorder: McpProgressiveEvidenceRecorder?,
    runEvidenceRecorder: RunEvidenceRecorder,
    turnRecorder: SessionTurnRecorder,
    ownsUi: Boolean,
): RuntimeFinalization {
    if (ownsUi) transcript.markPendingToolCallsUnknown()
    evidenceRecorder?.let { recorder ->
        withContext(Dispatchers.IO) {
            recorder.drainMcpDebugTrace(runtime, turnRecorder.snapshot().sessionId)
            // Write MCP evidence to disk only — do not dump paths into the chat stream.
            runCatching { recorder.write() }
        }
    }
    val memoryEvidence = withContext(Dispatchers.IO) {
        runCatching { loadRuntimeMemoryEvidence(context) }.getOrDefault(RuntimeMemoryEvidence())
    }
    runEvidenceRecorder.recordMemoryEvidence(memoryEvidence)
    val replay = if (ownsUi) {
        // Always write evidence; only surface a timeline row when replay fails.
        transcript.appendRunEvidence(runEvidenceRecorder, surfaceOnTimeline = false)
    } else {
        withContext(Dispatchers.IO) {
            RunReplayVerifier.verify(runEvidenceRecorder.snapshot()).also {
                runCatching { runEvidenceRecorder.write() }
            }
        }
    }
    turnRecorder.recordReplay(replay)
    val turn = turnRecorder.snapshot()
    withContext(Dispatchers.IO) { sessionCoordinator.appendTurn(turn) }
    // Persist HTML pages next to the session so they outlive run scratch cleanup.
    runCatching {
        withContext(Dispatchers.IO) {
            ArtifactStore.persistHtmlArtifacts(appWorkspaceRoot(context), turn.sessionId, turn.tools)
        }
    }
    return RuntimeFinalization(
        replay = replay,
        contextPanel = if (ownsUi) withContext(Dispatchers.IO) { loadContextPanel(context) } else null,
    )
}
