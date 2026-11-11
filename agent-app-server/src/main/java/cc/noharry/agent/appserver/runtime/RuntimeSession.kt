@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)
package cc.noharry.agent.appserver.runtime
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import cc.noharry.agent.appserver.R
import cc.noharry.agent.appserver.console.*
import cc.noharry.agent.appserver.console.ui.UiObserverDispatcher
import cc.noharry.agent.appserver.evidence.*
import cc.noharry.agent.appserver.http.AppServerHttpTransport
import cc.noharry.agent.appserver.mcp.*
import cc.noharry.agent.appserver.model.*
import cc.noharry.agent.appserver.protocol.*
import cc.noharry.agent.appserver.protocol.view.*
import cc.noharry.agent.appserver.server.*
import cc.noharry.agent.appserver.session.*
import cc.noharry.agent.appserver.skills.*
import cc.noharry.agent.appserver.trajectory.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*

/** Process singleton execution service; presentation and transports observe its asynchronous run topics. */
internal object RuntimeSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val trajectoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = linkedSetOf<(UiState) -> Unit>()
    private val trajectoryNotifications = UiObserverDispatcher(scope, ::notifyChanged)
    private val transcript = TranscriptStore()
    private var config: ProviderConfig? = null
    private var provider: ModelProvider = ModelProvider.OPENAI
    private var streamEnabled: Boolean = DEFAULT_STREAM_ENABLED
    private var useSimulatedModel: Boolean = DEFAULT_USE_SIMULATED_MODEL
    private var mcpProviders: List<McpProviderState> = emptyList()
    private var workspaceSelection: WorkspaceSelectionState = WorkspaceSelectionState()
    private var trajectoryGateway: TrajectoryEventLogGateway? = null
    private var trajectoryRepository: DefaultTrajectoryRepository? = null
    private val sessionCoordinator = SessionCoordinator(transcript)
    private var status: String = "Idle"
    private val modelOptions = ModelOptionsController(onChanged = ::notifyChanged)
    private val runner = RuntimeSessionRunner(
        scope = scope,
        transcript = transcript,
        sessionCoordinator = sessionCoordinator,
        currentConfig = { config },
        loadConfig = { context -> ProviderConfig.load(context).also(::applyLoadedConfig) },
        setProvider = { provider = it },
        setStatus = { status = it },
        streamEnabled = { streamEnabled },
        useSimulatedModel = { useSimulatedModel },
        mcpProviders = { mcpProviders },
        streamLabel = ::streamLabel,
        backendLabel = ::backendLabel,
        confirmationMode = { ApprovalSettings.mode },
        notifyChanged = ::notifyChanged,
        trajectoryRepository = { trajectoryRepository },
    )
    fun attach(context: Context, listener: (UiState) -> Unit): () -> Unit {
        ensureConfig(context.applicationContext)
        ensureMcpProviders(context.applicationContext)
        workspaceSelection = workspaceSelectionState(context.applicationContext)
        sessionCoordinator.ensure(context.applicationContext)
        refreshModelOptions(context.applicationContext)
        listeners += listener
        runner.refreshContextPanel(context.applicationContext)
        refreshTrajectory(context)
        listener(state())
        return { listeners -= listener }
    }
    /**
     * A wake after [SessionPolicy.idleMinutes] without a turn, or every wake when so set ([SessionPolicy]), starts a
     * new conversation, so the model sees a clean context; a wake within it
     * carries on, so 「再高一点」 still follows the last turn. A session with no turns yet, or a run still going, stays. True
     * when a new session was started.
     */
    fun freshSessionForWake(context: Context, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (isRunning) return false
        sessionCoordinator.ensure(context.applicationContext)
        val active = sessionCoordinator.sessions.firstOrNull { it.sessionId == sessionCoordinator.activeSessionId }
        val backgroundWork = active != null && AppSubagents.instance.hasUnfinished(active.sessionId)
        if (!wakeStartsNewSession(active, nowMillis, backgroundWork)) return false
        newSession(context)
        return true
    }
    fun newSession(context: Context) {
        sessionCoordinator.newSession(context.applicationContext)
        status = "New session"
        runner.resetForSession(context.applicationContext)
        refreshTrajectory(context)
        notifyChanged()
        pruneSessions(context)
    }

    /** Deletes the sessions past [SessionPolicy.maxSessions], touched longest ago first; see [sessionsOverLimit]. */
    internal fun pruneSessions(context: Context) {
        val appContext = context.applicationContext
        sessionCoordinator.ensure(appContext)
        val protected = runner.activeRunSnapshots().map { it.sessionId }.toSet() + sessionCoordinator.activeSessionId +
            sessionCoordinator.sessions.map { it.sessionId }.filter(AppSubagents.instance::hasUnfinished)
        val over = sessionsOverLimit(sessionCoordinator.sessions, SessionSettings.policy.value.maxSessions, protected)
        if (over.isEmpty()) return
        scope.launch {
            over.forEach { id -> runCatching { deleteSessionData(appContext, id) }; forgetSessionContinuity(id) }
            android.util.Log.i("DebugSessions", "deleted ${over.size} session(s) over the limit of ${SessionSettings.policy.value.maxSessions}")
            notifyChanged()
        }
    }
    internal fun selectSession(context: Context, sessionId: String) {
        sessionCoordinator.selectSession(context.applicationContext, sessionId)
        status = "Session resumed"
        runner.clearRequestAndRefresh(context.applicationContext)
        refreshTrajectory(context)
        notifyChanged()
    }

    /** Background refresh: attach and session switches must never block on EventLog IO. */
    internal fun refreshTrajectory(context: Context) {
        trajectoryRepository(context.applicationContext)
            .refreshSessionAsync(sessionCoordinator.activeSessionId)
    }

    internal fun loadTrajectoryRun(context: Context, runId: String) {
        trajectoryRepository(context.applicationContext)
            .loadRunAsync(sessionCoordinator.activeSessionId, runId)
    }

    internal fun trajectoryRaw(context: Context, sessionId: String, eventId: String): String? =
        trajectoryRepository(context.applicationContext).rawText(sessionId, eventId)

    internal fun loadOlderTrajectoryRuns(context: Context) {
        trajectoryRepository(context.applicationContext)
            .loadOlderRuns(sessionCoordinator.activeSessionId)
    }
    internal fun deleteSession(context: Context, sessionId: String) {
        val appContext = context.applicationContext
        scope.launch {
            val deletion = runCatching { deleteSessionData(appContext, sessionId) }
            forgetSessionContinuity(sessionId)
            val result = deletion.getOrNull()
            status = when {
                result == null -> "Session deletion failed"
                result.ok -> "Session deleted"
                else -> "Session deleted; cleanup incomplete"
            }
            when {
                result == null -> transcript.appendStatus(
                    "Session deletion failed: ${deletion.exceptionOrNull()?.message.orEmpty()}",
                )
                !result.ok -> transcript.appendStatus(
                    "Session data was deleted, but cleanup failed for: ${result.failedTargets.joinToString()}. " +
                        "Retry cleanup for this session.",
                )
            }
            runner.clearRequestAndRefresh(appContext)
            notifyChanged()
        }
    }

    private suspend fun deleteSessionData(appContext: Context, sessionId: String): SessionDeletionResult {
        val repository = trajectoryRepository(appContext)
        return deleteRuntimeSession(
            sessionId = sessionId,
            deleteActiveSession = runner::deleteActiveSession,
            dropTrajectory = { repository.dropSession(it); !appSessionEventLogRoot(appContext, it).exists() },
            deletePersistedSession = { sessionCoordinator.deleteSession(appContext, it) },
            // Generated pictures and what the running plugins keep of it (drawn pages) are this session's artifacts too;
            // `and` runs every deletion.
            deleteArtifacts = {
                ArtifactStore.deleteSessionArtifacts(appWorkspaceRoot(appContext), it) and
                    deleteSessionGeneratedImages(appContext, it) and
                    AppHost.additions.clearData(DataClearing(it))
            },
        )
    }

    /**
     * Stops every active run, deletes sessions / memory workspace / evidence logs / EventLog
     * roots, and opens a blank session. Provider config and MCP toggles are preserved.
     *
     * Deletion runs while run admission is still closed, so a new run cannot start writing
     * into a root that is about to be removed.
     */
    fun clearRuntimeData(context: Context, also: Set<String> = emptySet(), onDone: (Boolean) -> Unit = {}) {
        val app = context.applicationContext
        val repository = trajectoryRepository(app)
        status = "Clearing runtime data"
        notifyChanged()
        scope.launch {
            var result: RuntimeDataCleanupResult? = null
            var refusedForUnstoppedWriter = false
            var pluginsCleared = true
            runner.stopAllRunsThen { writersStopped ->
                if (!writersStopped) {
                    refusedForUnstoppedWriter = true
                    return@stopAllRunsThen
                }
                // What the plugins keep for conversations (drawn pages), and what those named in [also] keep beyond them
                // (what is remembered about the person), goes with the history once no run can write any of it back.
                pluginsCleared = AppHost.additions.clearData(DataClearing(sessionId = null, also = also))
                repository.clear()
                forgetSessionContinuity()
                result = sessionCoordinator.clearAllRuntimeData(app)
            }.join()
            if (refusedForUnstoppedWriter) {
                onDone(false)
                status = "Clear aborted: a run did not stop"
                transcript.appendStatus(
                    "Clear runtime data aborted: a run did not confirm cancellation, so nothing was deleted. Retry.",
                )
                notifyChanged()
                return@launch
            }
            val cleanup = result ?: RuntimeDataCleanupResult(emptyList(), emptyList())
            status = cleanup.summaryLine()
            transcript.appendStatus(
                buildString {
                    append(cleanup.summaryLine())
                    append(" (sessions, generated images, workspace memory, run evidence, logs, trajectory event log)")
                    if (cleanup.failedPaths.isNotEmpty()) {
                        append("; failed: ")
                        append(cleanup.failedPaths.joinToString())
                    }
                },
            )
            runner.resetForSession(app)
            notifyChanged()
            onDone(pluginsCleared && cleanup.failedPaths.isEmpty())
        }
    }
    internal fun reloadConfig(context: Context) {
        val loaded = ProviderConfig.load(context.applicationContext)
        applyLoadedConfig(loaded)
        status = "Config reloaded"
        transcript.appendStatus(loaded.summary(loaded.activeProfile))
        refreshModelOptions(context.applicationContext)
        notifyChanged()
    }
    /** The screens changed language: the config's worded names follow (on-device model); this session's choices stay. */
    internal fun languageChanged(context: Context) {
        if (config == null) return
        config = ProviderConfig.load(context.applicationContext)
        refreshModelOptions(context.applicationContext)
        notifyChanged()
    }
    internal fun selectProvider(context: Context, selected: ModelProvider) {
        ensureConfig(context.applicationContext)
        config?.profiles?.firstOrNull { it.provider == selected }?.let { profile ->
            selectProfile(context, profile.id)
            return
        }
        forceLiveBackend()
        provider = selected
        status = "Provider: ${selected.label}"
        transcript.appendStatus("Provider switched to ${selected.label}")
        refreshModelOptions(context.applicationContext)
        notifyChanged()
    }
    fun selectProfile(context: Context, profileId: String) {
        val loaded = ProviderConfig.saveSelectedProfile(context.applicationContext, profileId)
        AppHost.models.selected(loaded.activeProfile)
        forceLiveBackend()
        applyLoadedConfig(loaded)
        status = "Profile: ${loaded.activeProfile.displayLabel}"
        transcript.appendStatus("Profile switched to ${loaded.activeProfile.displayLabel}")
        refreshModelOptions(context.applicationContext)
        notifyChanged()
    }
    internal fun setManualProfileEnabled(context: Context, enabled: Boolean) {
        val loaded = ProviderConfig.saveManualProfileEnabled(context.applicationContext, enabled)
        forceLiveBackend()
        applyLoadedConfig(loaded)
        status = if (enabled) "Manual profile" else "Config default profile"
        transcript.appendStatus("Profile mode: ${status}")
        refreshModelOptions(context.applicationContext)
        notifyChanged()
    }
    fun saveProviderOverride(
        context: Context,
        selectedProvider: ModelProvider,
        apiKey: String,
        model: String,
        baseUrl: String,
        endpoint: ProviderEndpoint,
    ) {
        val profile = activeProfile(context.applicationContext) ?: return
        config = saveProviderOverrideChoice(context.applicationContext, profile, config, apiKey, model, baseUrl, endpoint, selectedProvider)
        forceLiveBackend()
        provider = config?.activeProfile?.provider ?: profile.provider
        status = "Config saved"
        transcript.appendStatus("${profile.displayLabel} config saved in app settings.")
        refreshModelOptions(context.applicationContext)
        notifyChanged()
    }
    internal fun saveNewProviderProfile(
        context: Context,
        label: String,
        selectedProvider: ModelProvider,
        endpoint: ProviderEndpoint,
        apiKey: String,
        model: String,
        baseUrl: String,
    ): String {
        val loaded = ProviderConfig.saveNewProfile(
            context.applicationContext,
            label,
            selectedProvider,
            endpoint,
            apiKey,
            model,
            baseUrl,
        )
        forceLiveBackend()
        applyLoadedConfig(loaded)
        status = "Profile added: ${loaded.activeProfile.displayLabel}"
        transcript.appendStatus("${loaded.activeProfile.displayLabel} provider saved in app settings.")
        refreshModelOptions(context.applicationContext)
        notifyChanged()
        return loaded.activeProfile.id
    }
    internal fun saveProviderModels(context: Context, editor: ProviderEditState, models: List<String>) {
        saveProviderModelList(context.applicationContext, editor, models)
        refreshModelOptions(context.applicationContext)
        notifyChanged()
    }
    internal fun deleteProviderProfile(context: Context, profileId: String) {
        val loaded = ProviderConfig.deleteSavedProfile(context.applicationContext, profileId)
        if (profileId.isManualProfileId()) deleteProviderModels(context.applicationContext, profileId)
        applyLoadedConfig(loaded)
        status = "Profile deleted"
        transcript.appendStatus("Provider profile deleted.")
        refreshModelOptions(context.applicationContext)
        notifyChanged()
    }
    internal fun fetchProviderModels(
        context: Context,
        profileId: String,
        selectedProvider: ModelProvider,
        endpoint: ProviderEndpoint,
        apiKey: String,
        model: String,
        baseUrl: String,
        onModels: (Result<List<String>>) -> Unit = {},
    ) {
        val profile = ProviderProfile(profileId, selectedProvider.label, selectedProvider, apiKey, model, baseUrl, endpoint)
        val effectiveKey = catalogKey(context.applicationContext, config, profileId, apiKey, baseUrl)
        scope.launch {
            status = "Fetching models"
            notifyChanged()
            runCatching {
                listModels(context.applicationContext, AppServerHttpTransport(context.applicationContext), profile, effectiveKey)
            }.onSuccess { models ->
                status = "Models loaded: ${models.size}"
                transcript.appendStatus(
                    "${profile.displayLabel} model list loaded: ${models.size}.",
                )
                onModels(Result.success(models))
            }.onFailure { error ->
                status = "Model fetch failed"
                transcript.appendStatus("Model list fetch failed. Check connection settings.")
                onModels(Result.failure(error))
            }
            notifyChanged()
        }
    }
    internal fun selectModel(context: Context, model: String) {
        ensureConfig(context.applicationContext)
        val activeConfig = config ?: return
        val profile = activeConfig.activeProfile
        config = saveSelectedModel(context.applicationContext, profile, activeConfig, model) ?: return
        config?.activeProfile?.let(AppHost.models::selected)
        provider = config?.activeProfile?.provider ?: profile.provider
        status = "Model: ${model.trim()}"
        transcript.appendStatus("Model selected: ${model.trim()}")
        notifyChanged()
    }
    internal fun selectVariant(context: Context, variant: String) {
        ensureConfig(context.applicationContext)
        val activeConfig = config ?: return
        val profile = activeConfig.activeProfile
        config = saveSelectedVariant(context.applicationContext, profile, activeConfig, variant) ?: return
        provider = config?.activeProfile?.provider ?: profile.provider
        val label = variant.trim().takeUnless { it.equals(DEFAULT_DEBUG_VARIANT_ID, ignoreCase = true) }.orEmpty().ifBlank { "Default" }
        status = "Variant: $label"
        transcript.appendStatus("Reasoning variant selected: $label")
        notifyChanged()
    }
    internal fun toggleStream() {
        streamEnabled = !streamEnabled
        status = streamLabel()
        transcript.appendStatus("Stream mode: ${streamLabel()}")
        notifyChanged()
    }
    /** Whether each start of the process begins on the simulated backend ([AppServerConfig.simulatedBackend]). */
    internal fun startWith(simulated: Boolean) { useSimulatedModel = simulated }
    internal fun selectBackend(simulated: Boolean) {
        useSimulatedModel = simulated
        status = backendLabel()
        transcript.appendStatus("Backend: ${backendLabel()}")
        notifyChanged()
    }
    /** The person chose [mode] ([chosen]: kept across restarts, [ApprovalSettings]), or the host's applies again. */
    internal fun selectConfirmationMode(mode: ConfirmationMode, chosen: Boolean = true) {
        if (chosen) ApprovalSettings.choose(mode)
        runner.applyConfirmationMode(mode)
        status = "Approval: ${mode.label}"
        transcript.appendStatus("Approval mode: ${mode.label}")
        notifyChanged()
    }
    /** Whether the selected session's agent is running. */
    val isRunning: Boolean get() = runner.isRunning

    /** Whether the assistant's own announcer is speaking (not a voice service that speaks for itself). */
    val speaking: Boolean get() = runner.speaking
    fun stopRun(): Job = runner.stopRun()

    /** Sessions beside the selected one: those a client follows, and what runs in them. */
    internal val threads = SessionThreads(sessionCoordinator, runner, ::notifyChanged)
    internal fun stopAllRuns(): Job = runner.stopAllRuns()
    internal fun postSteeringText(rawText: String) = runner.postSteeringText(rawText)
    fun announceFirst(context: Context, text: String) = runner.announceFirst(context.applicationContext, text)

    /** 陪聊: the latest exchanges of the selected session (agent and companion alike), oldest first, for its context. */
    suspend fun recentExchanges(context: Context, limit: Int = OUTSIDE_CONTEXT_TURNS): List<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            runCatching { sessionCoordinator.ensure(context.applicationContext).readTurns(sessionCoordinator.activeSessionId) }
                .getOrDefault(emptyList())
                .filter { it.userText.isNotBlank() && it.finalResponse.isNotBlank() }
                .takeLast(limit)
                .map { it.userText to it.finalResponse }
        }

    /**
     * A companion reply becomes a turn like the agent's (shown on the card, kept in the session so the agent sees it
     * later) and is spoken in the assistant's voice unless [speak] is off (a voice service has already said it).
     * [label] names the service for the status line.
     */
    fun recordCompanionTurn(
        context: Context,
        words: String,
        reply: String,
        label: String,
        speak: Boolean = true,
        scene: String = OUTSIDE_TURN_SCENE,
        statusText: String? = null,
    ) {
        val app = context.applicationContext
        ensureConfig(app)
        val now = System.currentTimeMillis()
        val id = "$scene-$now"
        transcript.openTurn(words)
        transcript.appendFinalResponse(reply)
        // A chat line while the assistant still runs a task must not take that run's card.
        if (!runner.isRunning) runner.showOutsideRequest(id, words)
        status = statusText ?: app.getString(R.string.runtime_status_chat, label)
        notifyChanged()
        val turn = SessionTurn(
            turnId = id, requestId = id, runId = id, sessionId = sessionCoordinator.activeSessionId, scene = scene,
            userText = words, startedAtEpochMillis = now, completedAtEpochMillis = now, finalStatus = "COMPLETED",
            finalResponse = reply, tools = emptyList(), completionOutcome = "SUCCEEDED",
        )
        scope.launch(Dispatchers.IO) { runCatching { sessionCoordinator.appendTurn(turn) } }
        if (speak) runner.announceReply(app, reply)
    }

    /** A voice preview: loads the configuration if nothing has run yet, then speaks [text]; false when TTS is off. */
    fun previewTts(context: Context, text: String, done: (Boolean) -> Unit) {
        scope.launch {
            RuntimeVoice.port.ready(context.applicationContext)
            done(runner.announceFirst(context.applicationContext, text))
        }
    }
    /** Voice settings were saved: replies read them from now on; the chosen model and the rest stay as they are. */
    fun refreshTts(context: Context) = RuntimeVoice.port.refresh(context.applicationContext)
    /**
     * A voice turn: appended to the running run's next model request as [wireText] (the transcript shows [rawText]),
     * or [rawText] as a new turn when nothing runs, the model reading [modelText] when given; [onNewTurn] tells the
     * caller which happened.
     */
    fun postSpokenInput(context: Context, rawText: String, wireText: String = rawText, modelText: String? = null, onNewTurn: () -> Unit = {}) {
        runner.postSteeringText(wireText, notLanded = {
            onNewTurn()
            submitText(context.applicationContext, rawText, backend = null, spoken = true, modelText = modelText)
        }, title = context.getString(R.string.runtime_voice_steering_title), shown = rawText)
    }
    /** A 连接应用 event (connected, tested, disconnected) as a visible line in the console, from any thread. */
    fun noteConnection(text: String) {
        scope.launch {
            transcript.appendMessage(TranscriptRole.NOTICE, text)
            notifyChanged()
        }
    }

    /** A subagent started, stalled, moved again or finished: a status line in its conversation, when on screen. */
    internal fun noteSubagent(status: cc.noharry.agent.runtime.SubagentStatus, change: SubagentChange) {
        scope.launch {
            if (status.parentSessionId != sessionCoordinator.activeSessionId) return@launch
            // A runtime row, not a status line: the console never shows ambient status.
            val shown = AppSubagents.displayContext
            val detail = when (change) {
                SubagentChange.STARTED -> shown.getString(R.string.console_subagent_task, status.task)
                SubagentChange.FINISHED -> status.result.ifEmpty { status.error }.take(SUBAGENT_DETAIL_CHARS)
                else -> status.latest
            }
            transcript.appendRuntime(status.transcriptLine(change, shown), detail)
            notifyChanged()
        }
    }

    /** Results or stalls of one run's children that the run did not collect: one turn, delivered like a delegation report. */
    internal fun deliverSubagentReport(context: Context, report: cc.noharry.agent.runtime.SubagentReport) {
        val app = context.applicationContext
        scope.launch {
            val since = SystemClock.elapsedRealtime()
            reportQueue.withLock {
                awaitIdleDevice(holdForReading = false)
                // Read now, not when the report came: siblings may have finished while it waited.
                val working = AppSubagents.instance.statuses(report.parentSessionId).filter { !it.state.finished && it.id !in report.statuses.map { s -> s.id } }
                val turn = report.toReport(working)
                AppSubagents.reportDelivered(report)
                val title = app.getString(R.string.runtime_subagent_report_title)
                turnOrSteer(turn.model, turn.display, title, { submitText(app, turn.display, backend = null, spoken = true, modelText = turn.model) })
            }
            followUpOnceSpoken(app, since)
        }
    }

    /**
     * What a plugin hands the conversation ([cc.noharry.agent.appserver.server.PluginContext.deliver]): once the device
     * is idle (at most [DELEGATION_REPORT_WAIT_MS], in turn with the reports) the card opens when it asks. Words said
     * without the model ([Delivery.said], a reminder) are shown and spoken as written; otherwise it is a spoken turn the
     * agent runs. A device still busy then gets [Delivery.forModel] into the running turn, like a late report (a
     * reminder for the agent to pass on, never just spoken over it, where the run's own answer would cut it), and as
     * usual if that turn ends first.
     */
    internal fun deliver(context: Context, delivery: Delivery) {
        val app = context.applicationContext
        scope.launch {
            val since = SystemClock.elapsedRealtime()
            reportQueue.withLock {
                awaitIdleDevice()
                if (delivery.showCard) Presenter.card(OverlayCommand.SHOW)?.let { android.util.Log.w("AppServer", "card not shown for a delivery: $it") }
                val said = delivery.said
                val sayIt = { said?.let { recordCompanionTurn(app, delivery.shown, it, delivery.title, scene = SAID_TURN_SCENE, statusText = delivery.title) } }
                val asTurn = {
                    delivery.delivered()
                    submitText(app, delivery.shown, backend = null, spoken = true, modelText = delivery.forModel)
                }
                when {
                    said == null -> turnOrSteer(delivery.forModel, delivery.shown, delivery.title, { asTurn() }, beforeSteer = delivery.delivered)
                    runner.isRunning -> {
                        delivery.delivered()
                        runner.postSteeringText(delivery.forModel, notLanded = { sayIt() }, title = delivery.title, shown = delivery.shown, fromPerson = false)
                    }
                    else -> { delivery.delivered(); sayIt() }
                }
            }
            // A turn's answer is spoken like a report and may well be answered, so listening opens again after it (a
            // plan's turn that asks something otherwise goes unheard). Words said as written only pop up and are spoken.
            if (delivery.said == null) followUpOnceSpoken(app, since)
        }
    }

    /**
     * Once the turn just delivered has been answered aloud (at most [DELEGATION_REPORT_WAIT_MS]), listening opens
     * again, so a reply to it is heard without the wake word ([VoicePort.listenAfterReport]). Not before: a voice
     * service's session open meanwhile speaks for the assistant, and the answer it never heard would go unsaid. Not at
     * all when the card's X closed voice after [since].
     */
    private suspend fun followUpOnceSpoken(app: Context, since: Long) {
        if (awaitIdleDevice(holdForReading = false) != null) RuntimeVoice.port.listenAfterReport(app, since)
    }

    /** The card's X: the run going on now finishes without speaking ([RunSpeech]). */
    fun silenceRunning() {
        val shown = sessionCoordinator.activeSessionId
        runner.activeRunSnapshots().firstOrNull { it.sessionId == shown && it.isActive }?.let { RuntimeVoice.port.silence(it.requestId) }
    }

    /** One report at a time: the next one checks only after this one's turn has started. */
    private val reportQueue = Mutex()

    /**
     * Until no run is going and the card's result has been read (at most [DELEGATION_REPORT_WAIT_MS]; the card needs a
     * moment after a run ends to show its result and start holding reports). A subagent's report instead waits only until the answer
     * is spoken ([holdForReading] false): it adds to the result on the card rather than replacing it with something else,
     * and holding it for the reading time would keep a report back long after that answer ended.
     */
    private suspend fun awaitIdleDevice(holdForReading: Boolean = true) = withTimeoutOrNull(DELEGATION_REPORT_WAIT_MS) {
        val busy = { runner.isRunning || if (holdForReading) Presenter.reading() else RuntimeVoice.port.active.value }
        do {
            while (busy()) delay(DELEGATION_REPORT_POLL_MS)
            delay(RESULT_SETTLE_MS)
        } while (busy())
    }

    /**
     * [asTurn] on an idle device, waiting until that turn runs, so the next one in [reportQueue] cannot replace it; on a
     * busy device [model] goes into the running turn ([beforeSteer] first), and becomes a turn if that one ends first.
     */
    private suspend fun turnOrSteer(model: String, shown: String, title: String, asTurn: () -> Unit, beforeSteer: () -> Unit = {}) {
        if (!runner.isRunning) {
            asTurn()
            withTimeoutOrNull(REPORT_START_WAIT_MS) { while (!runner.isRunning) delay(DELEGATION_REPORT_POLL_MS / 10) }
        } else {
            beforeSteer()
            runner.postSteeringText(model, notLanded = { asTurn() }, title = title, shown = shown, fromPerson = false)
        }
    }

    /** Late answers to an ended run become a new turn in the selected session; the ended run never revives. */
    internal fun answerQuestion(context: Context, question: UserQuestion, text: String) =
        runner.answerQuestion(question, text, context.resources) { lateTurn -> submitText(context, lateTurn) }

    internal suspend fun postExternalInput(sessionId: String, runId: String, requestId: String, text: String, questionId: String?) =
        runner.postInput(sessionId, runId, requestId, text, questionId)
    internal fun resolveConfirmation(pendingId: String, approve: Boolean) = runner.resolveConfirmation(pendingId, approve)
    internal fun setItemFolded(itemId: Long, folded: Boolean) { if (transcript.setFolded(itemId, folded)) notifyChanged() }
    fun setMcpProviderEnabled(context: Context, id: String, enabled: Boolean) {
        mcpProviders = saveMcpProviderEnabled(context.applicationContext, id, enabled)
        mcpProviders.firstOrNull { it.id == id }?.server?.let { server -> McpToolCatalogs.onServerSwitch(context, server, enabled) }
        status = "MCP: ${mcpProviders.enabledProviderLabels().ifEmpty { listOf("none") }.joinToString()}"
        notifyChanged()
    }
    /** Adds or edits a configured MCP server; returns why it was refused, or null once saved. */
    fun saveMcpServer(context: Context, draft: McpServerDraft): String? {
        val saved = when (val result = saveMcpServerDraft(context.applicationContext, draft)) {
            is McpSaveResult.Rejected -> return result.reason
            is McpSaveResult.Saved -> result.id
        }
        mcpProviders = loadMcpProviders(context.applicationContext)
        McpToolCatalogs.onServerChanged(context.applicationContext, saved)
        status = "MCP saved: ${draft.label.trim()}"
        notifyChanged()
        return null
    }
    /** The MCP list read again: a plugin's tool servers came or went. */
    internal fun reloadMcpProviders(context: Context) { mcpProviders = loadMcpProviders(context.applicationContext); notifyChanged() }
    /** Deletes a configured MCP server and its stored token; a fixed provider is refused. */
    fun deleteMcpServer(context: Context, id: String) {
        val label = mcpProviders.firstOrNull { it.id == id }?.label ?: id
        if (!deleteMcpServerConfig(context.applicationContext, id)) return
        McpToolCatalogs.onServerChanged(context.applicationContext, id)
        mcpProviders = loadMcpProviders(context.applicationContext)
        status = "MCP removed: $label"
        notifyChanged()
    }
    internal fun selectSafWorkspace(context: Context, uri: Uri) {
        saveSafWorkspace(context.applicationContext, uri)
        workspaceSelection = workspaceSelectionState(context.applicationContext)
        status = "Workspace: ${workspaceSelection.label}"
        transcript.appendStatus("SAF workspace selected: ${workspaceSelection.label}")
        notifyChanged()
    }
    internal fun reportSafWorkspaceUnavailable(message: String) {
        status = "Workspace picker unavailable"
        transcript.appendStatus(message)
        notifyChanged()
    }
    internal fun clearSafWorkspace(context: Context) {
        clearSafWorkspaceSelection(context.applicationContext)
        workspaceSelection = workspaceSelectionState(context.applicationContext)
        status = "Workspace: App private"
        transcript.appendStatus("SAF workspace cleared")
        notifyChanged()
    }
    fun submitText(context: Context, rawText: String) = submitText(context, rawText, backend = null, stream = null)
    /** Shared service intake for external adapters; never changes the selected UI session. */
    internal suspend fun submitExternal(context: Context, text: String, intake: RunIntake) = withContext(Dispatchers.Main.immediate) {
        prepareExecutionConfiguration(context.applicationContext)
        trajectoryRepository(context.applicationContext)
        runner.start(context.applicationContext, text, intake).join()
    }

    internal suspend fun cancelExternal(sessionId: String, runId: String) = withContext(Dispatchers.Main.immediate) {
        runner.cancelRun(sessionId, runId).join()
    }
    fun validateHostedSearch(context: Context, rawText: String, stream: String? = null) {
        val appContext = context.applicationContext
        scope.launch {
            val activeConfig = withContext(Dispatchers.IO) { ProviderConfig.load(appContext) }.also(::applyLoadedConfig)
            val profile = activeConfig.activeProfile
            if (!activeConfig.isReady(profile)) {
                status = "Provider configuration incomplete"
                transcript.appendMessage(TranscriptRole.ERROR, activeConfig.missingMessage(appContext, profile))
                notifyChanged()
                return@launch
            }
            val validationStream = stream?.toBooleanOverride() ?: false
            val requestId = RequestIds.next("web-search")
            val diagnostics = appDiagnostics(
                context = appContext,
                requestId = requestId,
                onEntry = transcript::appendDiagnostics,
                onTokenUsage = {},
            )
            diagnostics.recordSessionStart(profile.provider, if (validationStream) "stream" else "non-stream", activeConfig.summary(profile))
            status = "Validating web_search"
            transcript.appendRuntime(
                "web_search validation",
                "profile=${profile.displayLabel} capability=${profile.searchCapability} localTools=0 requestId=$requestId",
            )
            notifyChanged()
            val result = withContext(Dispatchers.IO) { runHostedSearchValidation(
                requestId = requestId,
                profile = profile,
                config = activeConfig,
                client = activeConfig.client(profile, validationStream, diagnostics),
                prompt = rawText.trim().ifBlank { DEFAULT_HOSTED_SEARCH_VALIDATION_PROMPT },
            ) }
            val evidence = withContext(Dispatchers.IO) { writeHostedSearchValidationEvidence(appContext, result) }
            status = if (result.passed) "web_search passed" else "web_search failed"
            transcript.appendRuntime(
                "web_search validation result",
                "passed=${result.passed} completed=${result.completed} searchEvents=${result.searchEventCount} " +
                    "resultCount=${result.resultCount} toolCalls=${result.toolCallCount}\n${result.response}\n${evidence?.absolutePath.orEmpty()}",
            )
            notifyChanged()
        }
    }

    fun submitTextReplacingRun(context: Context, rawText: String, backend: String?, stream: String? = null) {
        val appContext = context.applicationContext
        scope.launch {
            if (runner.isRunning) runner.stopRun()
            // Only spoken input replaces a run this way (the ASR port, a voice turn that drops the task).
            submitText(appContext, rawText, backend, stream, spoken = true)
        }
    }
    /** [modelText], when given, is what the model reads instead of [rawText], which the transcript and overlay show. */
    fun submitText(
        context: Context,
        rawText: String,
        backend: String?,
        stream: String? = null,
        spoken: Boolean = false,
        modelText: String? = null,
    ) = scope.launch {
        val text = rawText.trim()
        if (text.isEmpty()) return@launch
        prepareExecutionConfiguration(context.applicationContext)
        when (backend?.lowercase()) {
            BACKEND_LIVE, LEGACY_BACKEND_LIVE_ALIAS -> selectBackend(simulated = false)
        }
        when (stream?.lowercase()) {
            "1", "true" -> { streamEnabled = true; transcript.appendStatus("Stream forced on") }
            "0", "false" -> { streamEnabled = false; transcript.appendStatus("Stream forced off") }
        }
        transcript.openTurn(text)
        // A run started on the device itself: the plugins hear it (one may take the focus back from another agent's task).
        AppHost.additions.localTurnStarted()
        val intake = if (spoken) RunIntake(SpokenRuns.next()) else null
        runner.start(context.applicationContext, text, intake, modelText)
    }
    private suspend fun prepareExecutionConfiguration(context: Context) {
        val needModel = config == null
        val needMcp = mcpProviders.isEmpty()
        val loaded = withContext(Dispatchers.IO) {
            (if (needModel) ProviderConfig.load(context) else null) to (if (needMcp) loadMcpProviders(context) else null)
        }
        if (config == null) loaded.first?.let(::applyLoadedConfig)
        if (mcpProviders.isEmpty()) loaded.second?.let { mcpProviders = it }
    }

    private fun trajectoryGateway(context: Context): TrajectoryEventLogGateway {
        val app = context.applicationContext
        return trajectoryGateway ?: TrajectoryEventLogGateway(
            baseRoot = { sessionEventLogBaseRoot(app) },
            rootForSession = { sessionId -> appSessionEventLogRoot(app, sessionId) },
        ).also { trajectoryGateway = it }
    }

    private fun trajectoryRepository(context: Context): DefaultTrajectoryRepository {
        val app = context.applicationContext
        return trajectoryRepository ?: DefaultTrajectoryRepository(
            gateway = trajectoryGateway(app),
            readTurns = { sessionId ->
                runCatching { sessionCoordinator.ensure(app).readTurns(sessionId) }.getOrDefault(emptyList())
            },
            readRunEvidence = { requestId -> readRunEvidenceFile(app, requestId) },
            activeRunSnapshots = runner::activeRunSnapshots,
            ioScope = trajectoryScope,
            onChanged = { sessionId ->
                // Background sessions keep building their own trajectory but must not
                // repaint the currently selected page.
                if (sessionId == sessionCoordinator.activeSessionId) {
                    trajectoryNotifications.requestNotify()
                }
            },
        ).also { trajectoryRepository = it }
    }

    /** The model profile and tool servers chosen now, for a runtime built outside a session's run (the ACP door). */
    internal fun chosen(context: Context): Pair<ProviderConfig, List<McpProviderState>> {
        ensureConfig(context.applicationContext); ensureMcpProviders(context.applicationContext)
        return checkNotNull(config) to mcpProviders
    }
    private fun ensureConfig(context: Context) {
        if (config == null) {
            applyLoadedConfig(ProviderConfig.load(context))
        }
    }
    private fun applyLoadedConfig(loaded: ProviderConfig) {
        config = loaded
        provider = loaded.activeProfile.provider
        streamEnabled = loaded.streamEnabled
    }
    private fun activeProfile(context: Context): ProviderProfile? {
        ensureConfig(context)
        return config?.activeProfile
    }
    private fun ensureMcpProviders(context: Context) {
        if (mcpProviders.isEmpty()) {
            mcpProviders = loadMcpProviders(context)
        }
    }
    private fun notifyChanged() {
        val current = state()
        // A copy: a listener may detach (or another attach) while it is told, as a voice service waiting on a run does.
        listeners.toList().forEach { it(current) }
    }
    private fun state(): UiState {
        val activeConfig = config
        val profile = activeConfig?.activeProfile
        val providerLabel = profile?.displayLabel ?: provider.label
        val items = transcript.snapshot()
        return UiState(
            providerLabel = providerLabel,
            stream = streamEnabled,
            simulated = useSimulatedModel,
            confirmationMode = ApprovalSettings.mode,
            isRunning = runner.isRunning,
            configSource = activeConfig?.sourcePath.orEmpty(),
            configEditor = appConfigEditor(profile, activeConfig, modelOptions),
            sessions = runner.decorateSessions(sessionCoordinator.sessions),
            activeSessionCount = runner.activeRunCount,
            runningThreads = runner.activeRunSnapshots().filter { it.isActive }.associate { it.sessionId to it.requestId },
            maxActiveSessions = runner.maxActiveSessions,
            activeSessionId = sessionCoordinator.activeSessionId,
            items = items,
            threads = threads.items(),
            folded = transcript.folded(),
            contextPanel = runner.contextPanelState,
            board = sessionCoordinator.board(),
            tokenUsage = runner.tokenUsage,
            runtimeDemo = runner.runtimeDemo,
            mcpProviders = mcpProviders,
            workspaceSelection = workspaceSelection,
            currentRequest = runner.currentRequest,
            // Derived from the transcript (not a live-run cache) so the preview survives
            // activity recreation, session switches, and runs not owned by the active UI.
            artifactPreview = latestHtmlArtifact(items),
            trajectory = trajectoryRepository?.snapshot(sessionCoordinator.activeSessionId)
                ?: TrajectorySnapshot(sessionId = sessionCoordinator.activeSessionId),
            runStartedAtEpochMillis = runner.runStartedAtEpochMillis,
        ).withRun(status, runner.isRunning, runner.lastRunSummary)
    }
    private fun refreshModelOptions(context: Context) {
        config?.let { modelOptions.refresh(context, it.activeProfile, it) }
    }

    private fun forceLiveBackend() {
        useSimulatedModel = false
    }

    private fun streamLabel(): String = if (streamEnabled) "stream" else "non-stream"
    private fun backendLabel(): String = if (useSimulatedModel) SIMULATED_MODEL_LABEL else LIVE_MODEL_LABEL
}

private const val DEFAULT_HOSTED_SEARCH_VALIDATION_PROMPT =
    "Use hosted web search. Find the Model Context Protocol specification's home page. Return the page title and one cited URL."

private const val SUBAGENT_DETAIL_CHARS = 600
