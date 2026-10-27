@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.model.ModelRef
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.core.tools.TERMINAL_TOOLS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Subagents a model starts with spawn_agent: background child runs for independent, time-consuming work, after dsh's
 * subagent tool and Codex's spawn_agent / wait_agent. Pass one instance per process to every
 * [AgentRuntimeBuilder.subagents]: children outlive the run that started them (and, in a host that builds a runtime
 * per run, that runtime).
 *
 * The host runs each child ([SubagentLauncher]); this class tracks them per parent session. Every child event counts
 * as progress; a child with none for [SubagentConfig.stallAfterMillis] is marked stalled, and one still running after
 * [SubagentConfig.maxDurationMillis] is stopped. While the run that spawned or last waited for a child is going, its
 * result or stall reaches the model through wait_agent; whatever that run did not collect before it ended goes to
 * [onReport], for the host to deliver as a new turn: one report per run's children, once none of them still works (or
 * the first news has waited [SubagentConfig.reportHoldMillis]). Children live in memory only. Candidate API; not frozen.
 */
@ExperimentalAgentRuntimeApi
class Subagents(
    config: SubagentConfig = SubagentConfig(),
    private val launcher: SubagentLauncher,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
) {
    /** Read at each spawn, so a host can change it between runs. */
    @Volatile
    var config: SubagentConfig = config

    private val lock = Any()
    private val children = linkedMapOf<String, Child>()
    /** Children's answers as their model wrote them, by child session, until their public (redacted) result arrives. */
    private val answers = mutableMapOf<String, String>()
    private val activeRuns = hashSetOf<String>()
    private val pendingReports = mutableListOf<SubagentReport>()
    private var listener: ((SubagentReport) -> Unit)? = null
    @Volatile
    private var statusListener: ((SubagentStatus) -> Unit)? = null
    private var nextId = 0
    private var watchdog: Job? = null

    /** Bumped on every settle or stall, so a waiting wait_agent re-checks. */
    private val changes = MutableStateFlow(0L)

    /** Where results and stalls go once the run that would have collected them has ended; queued until set. */
    fun onReport(listener: ((SubagentReport) -> Unit)?) {
        val queued = synchronized(lock) {
            this.listener = listener
            if (listener == null) emptyList() else pendingReports.toList().also { pendingReports.clear() }
        }
        queued.forEach { listener?.invoke(it) }
    }

    /**
     * Every visible change of a child, for a host to show and log: started, each tool call and model call, stalled
     * or moving again, finished. Called off the caller's thread, outside any lock.
     */
    fun onStatus(listener: ((SubagentStatus) -> Unit)?) {
        statusListener = listener
    }

    /** [parentSessionId]'s children, oldest first, finished ones included until their result was handed over. */
    fun statuses(parentSessionId: String): List<SubagentStatus> = synchronized(lock) {
        children.values.filter { it.spec.parentSessionId == parentSessionId && !it.handedOver }.map { it.status() }
    }

    /** Whether [parentSessionId] has a child still working or a result not handed over yet. */
    fun hasUnfinished(parentSessionId: String): Boolean = statuses(parentSessionId).isNotEmpty()

    /** Whether [sessionId] is a child's own session, which a host keeps out of its conversation list. */
    fun isSubagentSession(sessionId: String): Boolean = synchronized(lock) { children.values.any { it.spec.sessionId == sessionId } }

    /** Stops [parentSessionId]'s children (every child without one) and reports nothing, e.g. when conversations go. */
    fun cancelSession(parentSessionId: String? = null) {
        val stopped = synchronized(lock) {
            children.values.filter { parentSessionId == null || it.spec.parentSessionId == parentSessionId }
        }
        stopped.forEach { child ->
            synchronized(lock) { child.handedOver = true }
            settle(child, SubagentState.CANCELLED, error = "The conversation was closed.")
            child.job?.cancel()
        }
    }

    internal suspend fun execute(toolName: String, argumentsJson: String, call: SubagentCall): String {
        val arguments = runCatching { Json.parseToJsonElement(argumentsJson) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
        return when (toolName) {
            SPAWN_AGENT -> spawn(arguments, call)
            WAIT_AGENT -> waitFor(arguments, call)
            CANCEL_AGENT -> cancel(arguments, call)
            else -> failure("UNSUPPORTED_SUBAGENT_TOOL", "Unknown subagent tool $toolName.")
        }
    }

    /** A child run's own result, before the host's redacted copy: dates and numbers reach the parent as written. */
    internal fun childAnswered(sessionId: String, result: RuntimeResult) {
        synchronized(lock) {
            if (children.values.any { it.spec.sessionId == sessionId && !it.state.finished }) answers[sessionId] = result.response
        }
    }

    /** The run has ended: what it did not collect goes to [onReport] with its children's, once they are all done. */
    internal fun runEnded(runId: String) {
        val report = synchronized(lock) {
            if (!activeRuns.remove(runId)) return
            batchReport(runId)
        }
        report?.let(::report)
    }

    private fun spawn(arguments: JsonObject, call: SubagentCall): String {
        val task = arguments.string("task")?.trim().orEmpty()
        if (task.isEmpty()) return failure("SUBAGENT_TASK_REQUIRED", "spawn_agent needs a task.")
        val label = arguments.string("label")?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_LABEL_CHARS) ?: task.take(MAX_LABEL_CHARS)
        val agentType = arguments.string("agent")?.trim()?.takeIf { it.isNotEmpty() }
        val types = config.types
        if (agentType != null && types.none { it.name == agentType }) {
            val known = if (types.isEmpty()) "none" else types.joinToString { it.name }
            return failure("SUBAGENT_TYPE_UNKNOWN", "There is no agent type $agentType (types: $known); leave agent out for a general subagent.")
        }
        val child = synchronized(lock) {
            val depth = (children.values.firstOrNull { it.spec.sessionId == call.sessionId }?.spec?.depth ?: 0) + 1
            if (depth > config.maxDepth) {
                return failure("SUBAGENT_DEPTH_LIMIT", "A subagent cannot start subagents of its own; do the work yourself.")
            }
            val running = children.values.count { it.spec.parentSessionId == call.sessionId && !it.state.finished }
            if (running >= config.maxActivePerSession) {
                return failure("SUBAGENT_LIMIT", "$running subagents are already running; wait for one to finish.")
            }
            nextId += 1
            val id = "sub-$nextId"
            val spec = SubagentSpec(
                id = id,
                sessionId = "${call.sessionId}-$id",
                parentSessionId = call.sessionId,
                parentRunId = call.runId,
                label = label,
                task = task,
                depth = depth,
                modelRef = config.modelRef ?: call.modelRef,
                allowDeviceControl = config.allowDeviceControl,
                agentType = agentType,
            )
            activeRuns += call.runId
            Child(spec, nowEpochMillis()).also { children[id] = it }
        }
        // Assigned before it starts: a conversation closed right after the spawn must find the job to stop it.
        val job = scope.launch(start = CoroutineStart.LAZY) { runChild(child) }
        child.job = job
        job.start()
        startWatchdog()
        notifyStatus(child)
        return buildJsonObject {
            put("ok", true)
            put("id", child.spec.id)
            put("label", child.spec.label)
            put("status", "running")
        }.toString()
    }

    private suspend fun waitFor(arguments: JsonObject, call: SubagentCall): String {
        val ids = (arguments["ids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet()
        val timeoutMillis = ((arguments["timeout_seconds"] as? JsonPrimitive)?.intOrNull ?: DEFAULT_WAIT_SECONDS)
            .coerceIn(0, MAX_WAIT_SECONDS) * MILLIS_PER_SECOND
        val targets = synchronized(lock) {
            val own = children.values.filter {
                it.spec.parentSessionId == call.sessionId && !it.handedOver && (ids == null || it.spec.id in ids)
            }
            // The waiting run now collects these, also children an earlier run of the conversation started.
            own.forEach { it.ownerRunId = call.runId }
            if (own.isNotEmpty()) activeRuns += call.runId
            own
        }
        if (targets.isEmpty()) {
            return buildJsonObject {
                put("ok", true)
                put("message", "No unfinished subagents.")
                putJsonArray("agents") {}
            }.toString()
        }
        val seen = changes.value
        val hasNews = { synchronized(lock) { targets.any { it.hasNews() } } }
        if (timeoutMillis > 0 && !hasNews()) {
            withTimeoutOrNull(timeoutMillis) { changes.first { it != seen && hasNews() } }
        }
        val statuses = synchronized(lock) { targets.map { it.collect() } }
        return buildJsonObject {
            put("ok", true)
            putJsonArray("agents") { statuses.forEach { add(it.toJson(nowEpochMillis())) } }
        }.toString()
    }

    private fun cancel(arguments: JsonObject, call: SubagentCall): String {
        val id = arguments.string("id").orEmpty()
        val child = synchronized(lock) {
            children[id]?.takeIf { it.spec.parentSessionId == call.sessionId }?.also {
                it.ownerRunId = call.runId
                activeRuns += call.runId
            }
        } ?: return failure("SUBAGENT_NOT_FOUND", "No subagent $id in this conversation.")
        settle(child, SubagentState.CANCELLED, error = "Cancelled by the agent.")
        child.job?.cancel()
        val status = synchronized(lock) { child.collect() }
        return buildJsonObject {
            put("ok", true)
            putJsonArray("agents") { add(status.toJson(nowEpochMillis())) }
        }.toString()
    }

    private suspend fun runChild(child: Child) {
        try {
            withTimeout(config.maxDurationMillis) {
                launcher.launch(child.spec).collect { observe(child, it) }
            }
            settle(child, SubagentState.FAILED, error = "The subagent ended without a result.")
        } catch (_: TimeoutCancellationException) {
            settle(child, SubagentState.TIMED_OUT, error = "Stopped after ${config.maxDurationMillis / MILLIS_PER_SECOND} seconds.")
        } catch (cancelled: CancellationException) {
            settle(child, SubagentState.CANCELLED, error = "Cancelled.")
            throw cancelled
        } catch (failure: Exception) {
            settle(child, SubagentState.FAILED, error = failure.message ?: failure.javaClass.simpleName)
        }
    }

    private fun observe(child: Child, event: AgentEvent) {
        val visible = synchronized(lock) {
            if (child.state.finished) return
            child.lastActivity = nowEpochMillis()
            val recovered = child.state == SubagentState.STALLED
            if (recovered) child.state = SubagentState.RUNNING
            when (event) {
                // Its closing finish is not a step of the work.
                is AgentEvent.NativeToolCallStarted -> if (event.toolName in TERMINAL_TOOL_NAMES) recovered else {
                    child.toolCalls += 1
                    child.latest = "${event.toolName} ${event.arguments}".take(MAX_LATEST_CHARS)
                    true
                }
                is AgentEvent.TokenUsageDelta -> {
                    child.modelCalls += 1
                    child.inputTokens += event.inputTokens.toLong()
                    child.cachedTokens += event.cacheReadTokens.toLong()
                    child.outputTokens += event.outputTokens.toLong() + event.reasoningTokens.toLong()
                    true
                }
                else -> recovered
            }
        }
        if (visible) notifyStatus(child)
        if (event is AgentEvent.ResultAvailable) {
            val result = event.result
            when (result.status) {
                RuntimeRunStatus.COMPLETED -> {
                    val answer = synchronized(lock) { answers[child.spec.sessionId] } ?: result.response
                    settle(child, SubagentState.COMPLETED, result = answer)
                }
                RuntimeRunStatus.CANCELLED -> settle(child, SubagentState.CANCELLED, error = result.errorCode ?: "Cancelled.")
                else -> settle(child, SubagentState.FAILED, error = listOfNotNull(result.errorCode, result.response.takeIf { it.isNotBlank() }, result.status.name).first())
            }
        }
    }

    private fun settle(child: Child, state: SubagentState, result: String = "", error: String = "") {
        val report = synchronized(lock) {
            if (child.state.finished) return
            answers.remove(child.spec.sessionId)
            child.state = state
            child.result = result.take(MAX_RESULT_CHARS)
            child.error = error.take(MAX_LATEST_CHARS)
            child.finishedAt = nowEpochMillis()
            child.newsAt = child.finishedAt
            changes.value += 1
            batchReport(child.ownerRunId)
        }
        notifyStatus(child)
        report?.let(::report)
    }

    private fun notifyStatus(child: Child) {
        val listener = statusListener ?: return
        listener(synchronized(lock) { child.status() })
    }

    private fun startWatchdog() {
        synchronized(lock) {
            if (watchdog != null) return
            watchdog = scope.launch {
                while (true) {
                    delay(watchdogTickMillis())
                    val (stalled, reports) = synchronized(lock) {
                        val running = children.values.filter { !it.state.finished }
                        if (running.isEmpty()) {
                            watchdog = null
                            return@launch
                        }
                        val now = nowEpochMillis()
                        val stalled = running.filter { it.state == SubagentState.RUNNING && now - it.lastActivity >= config.stallAfterMillis }
                        stalled.forEach { child ->
                            child.state = SubagentState.STALLED
                            child.stallReported = false
                            child.newsAt = now
                            changes.value += 1
                        }
                        // Also news that has waited long enough for its siblings.
                        stalled to children.values.map { it.ownerRunId }.distinct().mapNotNull(::batchReport)
                    }
                    stalled.forEach(::notifyStatus)
                    reports.forEach(::report)
                }
            }
        }
    }

    private fun watchdogTickMillis(): Long = (config.stallAfterMillis / STALL_CHECKS).coerceIn(MIN_TICK_MILLIS, MAX_TICK_MILLIS)

    /**
     * The news of [ownerRunId]'s children once that run has ended, as one report: when none of them still works, or
     * when the oldest news has waited [SubagentConfig.reportHoldMillis]. Caller holds the lock.
     */
    private fun batchReport(ownerRunId: String): SubagentReport? {
        if (ownerRunId in activeRuns) return null
        val batch = children.values.filter { it.ownerRunId == ownerRunId && !it.handedOver }
        val news = batch.filter { it.hasNews() }
        if (news.isEmpty()) return null
        val working = batch.any { it.state == SubagentState.RUNNING }
        if (working && nowEpochMillis() - news.minOf { it.newsAt } < config.reportHoldMillis) return null
        return SubagentReport(news.first().spec.parentSessionId, news.map { it.collect() })
    }

    private fun report(report: SubagentReport) {
        val target = synchronized(lock) { listener ?: run { pendingReports += report; null } }
        target?.invoke(report)
    }

    private inner class Child(val spec: SubagentSpec, val startedAt: Long) {
        @Volatile var job: Job? = null
        var state = SubagentState.RUNNING
        var lastActivity = startedAt
        var finishedAt = 0L
        var toolCalls = 0
        var modelCalls = 0
        var inputTokens = 0L
        var cachedTokens = 0L
        var outputTokens = 0L
        var latest = ""
        var result = ""
        var error = ""
        var ownerRunId = spec.parentRunId
        var handedOver = false
        var stallReported = false
        /** When the latest result or stall came in. */
        var newsAt = 0L

        fun hasNews(): Boolean = (state.finished && !handedOver) || (state == SubagentState.STALLED && !stallReported)

        /** The status, marking a result handed over and a stall reported. Caller holds the lock. */
        fun collect(): SubagentStatus {
            if (state.finished) handedOver = true
            if (state == SubagentState.STALLED) stallReported = true
            return status()
        }

        fun status() = SubagentStatus(
            id = spec.id,
            label = spec.label,
            state = state,
            startedAtEpochMillis = startedAt,
            lastActivityEpochMillis = lastActivity,
            finishedAtEpochMillis = finishedAt,
            toolCalls = toolCalls,
            latest = latest,
            result = result,
            error = error,
            parentSessionId = spec.parentSessionId,
            sessionId = spec.sessionId,
            task = spec.task,
            modelCalls = modelCalls,
            inputTokens = inputTokens,
            cachedTokens = cachedTokens,
            outputTokens = outputTokens,
            agentType = spec.agentType.orEmpty(),
        )
    }
}

/** Limits and defaults for [Subagents]. Candidate API; not frozen. */
@ExperimentalAgentRuntimeApi
data class SubagentConfig(
    /** 1: subagents cannot start their own (dsh and Codex default). */
    val maxDepth: Int = 1,
    /** Children of one conversation running at once. */
    val maxActivePerSession: Int = 3,
    /** Passed to the launcher, which decides what device control means for its tools. */
    val allowDeviceControl: Boolean = false,
    /** The children's model; null uses the spawning run's. */
    val modelRef: ModelRef? = null,
    /** No event for this long marks a child stalled. */
    val stallAfterMillis: Long = 90_000L,
    /** A child still running after this long is stopped. */
    val maxDurationMillis: Long = 10 * 60_000L,
    /** A late result waits this long for its siblings from the same run, so they come back as one turn. */
    val reportHoldMillis: Long = 60_000L,
    /** Kinds of subagent the model may name in spawn_agent's `agent`; the launcher gives each its own instructions. */
    val types: List<SubagentType> = emptyList(),
) {
    init {
        require(maxDepth >= 0) { "maxDepth must not be negative." }
        require(types.map { it.name }.toSet().size == types.size) { "Subagent type names must differ." }
        require(maxActivePerSession > 0) { "maxActivePerSession must be positive." }
        require(stallAfterMillis > 0 && maxDurationMillis > 0 && reportHoldMillis >= 0) { "Subagent time limits must be positive." }
    }
}

/** One child for the host to run. Candidate API; not frozen. */
@ExperimentalAgentRuntimeApi
data class SubagentSpec(
    val id: String,
    /** The child's own session, separate from the conversation's. */
    val sessionId: String,
    val parentSessionId: String,
    val parentRunId: String,
    val label: String,
    /** What the spawning model asked for; the child's input. */
    val task: String,
    /** 1 for a child of a conversation. */
    val depth: Int,
    /** [SubagentConfig.modelRef], else the spawning run's model. */
    val modelRef: ModelRef?,
    val allowDeviceControl: Boolean,
    /** The [SubagentType] the model named, or null for a general subagent. */
    val agentType: String? = null,
)

/**
 * A kind of subagent the host offers (such as one that looks up train and flight times): the model names it in
 * spawn_agent's `agent`, and the host's launcher runs that child with the type's own instructions, tools and limits.
 * Candidate API; not frozen.
 */
@ExperimentalAgentRuntimeApi
data class SubagentType(
    /** What the model writes: lowercase letters, digits and hyphens. */
    val name: String,
    /** When to use it, in the words the model reads. */
    val description: String,
) {
    init {
        require(SUBAGENT_TYPE_NAME.matches(name)) { "A subagent type name is lowercase letters, digits and hyphens: $name" }
        require(description.isNotBlank()) { "A subagent type needs a description." }
    }
}

private val SUBAGENT_TYPE_NAME = Regex("[a-z][a-z0-9-]{0,39}")

/**
 * Runs one child to its end, e.g. on a runtime built for it; [Subagents] reads progress and the result from the events.
 * Build that runtime with the same [Subagents]: its answer then reaches the parent as written, not as the host's
 * redacted copy in the events (where a venue's phone number reads [REDACTED_PHONE]).
 */
@ExperimentalAgentRuntimeApi
fun interface SubagentLauncher {
    fun launch(spec: SubagentSpec): Flow<AgentEvent>
}

@ExperimentalAgentRuntimeApi
enum class SubagentState(val wireName: String) {
    RUNNING("running"),
    STALLED("stalled"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled"),
    TIMED_OUT("timed_out"),
    ;

    val finished: Boolean get() = this != RUNNING && this != STALLED
}

@ExperimentalAgentRuntimeApi
data class SubagentStatus(
    val id: String,
    val label: String,
    val state: SubagentState,
    val startedAtEpochMillis: Long,
    val lastActivityEpochMillis: Long,
    val finishedAtEpochMillis: Long,
    val toolCalls: Int,
    /** The child's latest tool call, shortened. */
    val latest: String,
    /** The child's answer when [state] is COMPLETED, as its model wrote it (it goes back to the parent's model). */
    val result: String,
    val error: String,
    val parentSessionId: String = "",
    /** The child's own session, where its event log is. */
    val sessionId: String = "",
    val task: String = "",
    val modelCalls: Int = 0,
    /** Input tokens of the child's model calls, cache reads included. */
    val inputTokens: Long = 0,
    val cachedTokens: Long = 0,
    /** Output tokens, reasoning included. */
    val outputTokens: Long = 0,
    /** The [SubagentType] it runs as; blank for a general subagent. */
    val agentType: String = "",
)

/** Results and stalls of one run's children that the run did not collect, for the host to deliver as one new turn. */
@ExperimentalAgentRuntimeApi
data class SubagentReport(val parentSessionId: String, val statuses: List<SubagentStatus>)

/** Who calls a subagent tool. */
internal data class SubagentCall(val sessionId: String, val runId: String, val modelRef: ModelRef?)

internal val SUBAGENT_TOOL_NAMES = setOf(SPAWN_AGENT, WAIT_AGENT, CANCEL_AGENT)

private fun SubagentStatus.toJson(now: Long) = buildJsonObject {
    put("id", id)
    put("label", label)
    put("status", state.wireName)
    put("seconds", ((if (finishedAtEpochMillis > 0) finishedAtEpochMillis else now) - startedAtEpochMillis) / MILLIS_PER_SECOND)
    put("toolCalls", toolCalls)
    put("modelCalls", modelCalls)
    if (!state.finished) put("idleSeconds", (now - lastActivityEpochMillis) / MILLIS_PER_SECOND)
    if (latest.isNotEmpty()) put("latest", latest)
    if (result.isNotEmpty()) put("result", result)
    if (error.isNotEmpty()) put("error", error)
}

private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

private fun failure(code: String, message: String): String = buildJsonObject {
    put("ok", false)
    put("code", code)
    put("message", message)
}.toString()

private const val SPAWN_AGENT = "spawn_agent"
private const val WAIT_AGENT = "wait_agent"
private const val CANCEL_AGENT = "cancel_agent"
private const val DEFAULT_WAIT_SECONDS = 30
private const val MAX_WAIT_SECONDS = 120
private const val MILLIS_PER_SECOND = 1_000L
private const val MAX_LABEL_CHARS = 24
private const val MAX_LATEST_CHARS = 160
private val TERMINAL_TOOL_NAMES = TERMINAL_TOOLS.map { it.name }.toSet()
/** A child's answer goes into the parent's conversation; past this it is cut. */
private const val MAX_RESULT_CHARS = 6_000
private const val STALL_CHECKS = 3
private const val MIN_TICK_MILLIS = 20L
private const val MAX_TICK_MILLIS = 5_000L
