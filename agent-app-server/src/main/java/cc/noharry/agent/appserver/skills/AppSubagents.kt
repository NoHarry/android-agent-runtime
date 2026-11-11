@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.skills
import android.content.Context
import android.util.Log
import cc.noharry.agent.appserver.R
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.model.hasWebLookup
import cc.noharry.agent.appserver.protocol.DEFAULT_SUBAGENT_LOOKUPS
import cc.noharry.agent.appserver.protocol.TokenUsageState
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.BasicTool
import cc.noharry.agent.appserver.runtime.append
import cc.noharry.agent.appserver.runtime.modelText
import cc.noharry.agent.appserver.trajectory.appSessionEventLogRoot
import cc.noharry.agent.appserver.usage.plus
import cc.noharry.agent.appserver.usage.toUsageDelta
import cc.noharry.agent.ThreadEvent
import cc.noharry.agent.appserver.runtime.RuntimeTurnRequest
import cc.noharry.agent.AgentRuntime
import cc.noharry.agent.spi.HookContext
import cc.noharry.agent.spi.HookDecision
import cc.noharry.agent.spi.RuntimeHook
import cc.noharry.agent.spi.ToolUse
import cc.noharry.agent.spi.model.ModelRef
import cc.noharry.agent.runtime.SubagentConfig
import cc.noharry.agent.runtime.SubagentLauncher
import cc.noharry.agent.runtime.SubagentReport
import cc.noharry.agent.runtime.SubagentSpec
import cc.noharry.agent.runtime.SubagentState
import cc.noharry.agent.runtime.SubagentStatus
import cc.noharry.agent.runtime.SubagentType
import cc.noharry.agent.runtime.Subagents
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Background subagents: the main agent decides when to hand parallel, time-consuming
 * work to spawn_agent. One process-wide [Subagents]; each child runs on a runtime built like its parent's (the
 * parent's run registers the recipe with [rememberChildRuntime]) behind [SubagentGuard]. A result or stall the
 * parent's run did not collect becomes a spoken turn, like an A2A report. While a conversation has unfinished
 * children a wake keeps it rather than starting a new session. Settings: device control off and the parent's model,
 * unless changed ([saveSubagentSettings]).
 */
internal object AppSubagents {
    private val recipes = ConcurrentHashMap<String, ChildRuntimeRecipe>()
    private val usage = ConcurrentHashMap<String, AtomicReference<TokenUsageState>>()
    private val boardState = MutableStateFlow<Map<String, List<SubagentStatus>>>(emptyMap())
    private val lastState = ConcurrentHashMap<String, SubagentState>()
    private val deliveryState = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val settingsState = MutableStateFlow(SubagentSettings())
    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var lifecycle: ((SubagentStatus, SubagentChange) -> Unit)? = null
    @Volatile
    private var types: List<SubagentType> = emptyList()

    val instance = Subagents(SubagentConfig(), SubagentLauncher(::launch))

    /** The app's context, for the lines built here that the screen shows ([transcriptLine], a report's display). */
    internal val displayContext: Context get() = checkNotNull(appContext) { "AppSubagents is not installed." }

    /** The Board's view: each conversation's latest children, finished ones included, oldest first. */
    val board: StateFlow<Map<String, List<SubagentStatus>>> = boardState.asStateFlow()

    /**
     * Children whose late result is on its way to the conversation, by [deliveryKey]: [REPORT_QUEUED] until the report's
     * turn starts, then when it started. The card keeps their lanes up meanwhile.
     */
    internal val deliveries: StateFlow<Map<String, Long>> = deliveryState.asStateFlow()

    fun install(context: Context, deliver: (SubagentReport) -> Unit) {
        appContext = context.applicationContext
        apply(context)
        instance.onStatus(::observe)
        instance.onReport { report ->
            markReport(report, REPORT_QUEUED)
            deliver(report)
        }
    }

    /** [report]'s turn has started: the agent is telling the user now. */
    internal fun reportDelivered(report: SubagentReport, now: Long = System.currentTimeMillis()) = markReport(report, now)

    private fun markReport(report: SubagentReport, at: Long) {
        deliveryState.update { marks -> (marks + report.statuses.associate { it.deliveryKey() to at }).entries.toList().takeLast(MAX_DELIVERY_MARKS).associate { it.toPair() } }
    }

    /** Started, stalled, moving again, finished: for the transcript of the conversation on screen. */
    internal fun onLifecycle(listener: ((SubagentStatus, SubagentChange) -> Unit)?) {
        lifecycle = listener
    }

    /** The saved settings, for the Settings page. */
    val settings: StateFlow<SubagentSettings> = settingsState.asStateFlow()

    /** Reads the saved settings and the switched-on subagent types into the next spawns. */
    fun apply(context: Context) {
        types = offeredTypes(context, ProviderConfig.load(context.applicationContext).hasWebLookup(context))
        applySettings(loadSubagentSettings(context))
    }

    /**
     * Before a main agent's run: a type that needs a lookup API comes and goes with it. That changes the tool
     * list, so only an actual change is applied (it happens when a key is added or removed, not run to run).
     */
    fun refreshTypes(context: Context, webLookup: Boolean) {
        val offered = offeredTypes(context, webLookup)
        if (offered == types) return
        types = offered
        instance.config = instance.config.copy(types = offered)
    }

    /** Whether spawn_agent offers the type [name] now. */
    fun offers(name: String): Boolean = types.any { it.name == name }

    /** The types spawn_agent offers now, by id. */
    fun offeredTypeIds(): Set<String> = types.mapTo(linkedSetOf()) { it.name }

    private fun offeredTypes(context: Context, webLookup: Boolean): List<SubagentType> =
        loadSubagentKinds(context).filter { it.enabled && (webLookup || !it.requiresWebLookup) }.map { it.toSubagentType() }

    internal fun applySettings(settings: SubagentSettings) {
        settingsState.value = settings
        instance.config = settings.toConfig().copy(types = types)
    }

    /** How to build a child of [sessionId]: the latest parent run's runtime recipe. */
    internal fun rememberChildRuntime(sessionId: String, recipe: (SubagentSpec) -> AgentRuntime) {
        val parentUsage = usage.computeIfAbsent(sessionId) { AtomicReference(TokenUsageState()) }
        recipes[sessionId] = ChildRuntimeRecipe(recipe, parentUsage)
    }

    /** What [sessionId]'s children used this process, for the Board's session total. */
    fun usage(sessionId: String): TokenUsageState = usage[sessionId]?.get() ?: TokenUsageState()

    fun forget(sessionId: String?) {
        instance.cancelSession(sessionId)
        if (sessionId == null) {
            recipes.clear()
            usage.clear()
            boardState.value = emptyMap()
            deliveryState.value = emptyMap()
        } else {
            recipes.remove(sessionId)
            usage.remove(sessionId)
            boardState.update { it - sessionId }
            deliveryState.update { marks -> marks.filterKeys { !it.startsWith("$sessionId/") } }
        }
    }

    private fun observe(status: SubagentStatus) {
        boardState.update { boards ->
            val rows = boards[status.parentSessionId].orEmpty()
            val updated = if (rows.any { it.id == status.id }) rows.map { if (it.id == status.id) status else it } else rows + status
            boards + (status.parentSessionId to updated.takeLast(MAX_BOARD_ROWS))
        }
        val before = lastState.put("${status.parentSessionId}/${status.id}", status.state)
        val change = subagentChange(before, status.state) ?: return
        val type = status.agentType.takeIf(String::isNotEmpty)?.let { " [$it]" }.orEmpty()
        Log.i(TAG, "${status.parentSessionId} ${status.id} 「${status.label}」$type ${change.name.lowercase()}: ${status.logLine()}")
        if (change == SubagentChange.FINISHED) appContext?.let { record(it, status) }
        lifecycle?.invoke(status, change)
    }

    /** One line per finished child in its conversation's log folder, for looking back after the fact. */
    private fun record(context: Context, status: SubagentStatus) {
        runCatching {
            val line = buildJsonObject {
                put("id", status.id)
                put("label", status.label)
                if (status.agentType.isNotEmpty()) put("agentType", status.agentType)
                put("state", status.state.wireName)
                put("sessionId", status.sessionId)
                put("task", status.task)
                put("startedAt", status.startedAtEpochMillis)
                put("finishedAt", status.finishedAtEpochMillis)
                put("toolCalls", status.toolCalls)
                put("modelCalls", status.modelCalls)
                put("inputTokens", status.inputTokens)
                put("cachedTokens", status.cachedTokens)
                put("outputTokens", status.outputTokens)
                put("result", status.result)
                put("error", status.error)
            }.toString()
            File(appSessionEventLogRoot(context, status.parentSessionId), SUBAGENT_RECORDS).apply {
                parentFile?.mkdirs()
                appendText(line + "\n")
            }
        }.onFailure { Log.w(TAG, "subagent record not written: ${it.message}") }
    }

    internal fun launch(spec: SubagentSpec): Flow<ThreadEvent> = flow {
        val recipe = recipes[spec.parentSessionId] ?: error("No runtime recipe for ${spec.parentSessionId}")
        val runtime = recipe.build(spec)
        // Keep this session generation's bucket: forgotten children cannot recreate or alter a reopened session.
        val parentUsage = recipe.usage
        var latestUsage: cc.noharry.agent.Usage? = null
        var usageUpdates = 0
        try {
            val kind = spec.agentType?.let { id -> appContext?.let { appSubagentKind(it, id) } }
            val instructions = kind?.let { appContext?.let { context -> subagentKindText(context, it) } }.orEmpty()
            val request = RuntimeTurnRequest(
                requestId = "${spec.sessionId}-run",
                sessionId = spec.sessionId,
                input = subagentInput(spec, kind, instructions),
                modelRef = spec.modelRef,
                approvalPolicy = spec.approvalPolicy,
            )
            request.thread(runtime).runStreamed(request.input, request.options()).collect { event ->
                when (event) {
                    is ThreadEvent.UsageUpdated -> { latestUsage = event.usage; usageUpdates += 1 }
                    is ThreadEvent.TurnCompleted -> event.result.usage?.let { latestUsage = it }
                    else -> Unit
                }
                emit(event)
            }
        } finally {
            latestUsage?.let { snapshot ->
                // UsageUpdated is cumulative. The count is observed usage updates, or one terminal-only report.
                val total = TokenUsageState().plus(snapshot.toUsageDelta()).copy(requestCount = usageUpdates.coerceAtLeast(1))
                parentUsage.updateAndGet { it + total }
            }
            runtime.close()
        }
    }
}

private class ChildRuntimeRecipe(
    val build: (SubagentSpec) -> AgentRuntime,
    val usage: AtomicReference<TokenUsageState>,
)

internal enum class SubagentChange { STARTED, STALLED, RESUMED, FINISHED }

/** The transition worth a transcript line and a log line, or null when only progress moved. */
internal fun subagentChange(before: SubagentState?, now: SubagentState): SubagentChange? = when {
    before == now -> null
    now.finished -> SubagentChange.FINISHED
    now == SubagentState.STALLED -> SubagentChange.STALLED
    before == null -> SubagentChange.STARTED
    before == SubagentState.STALLED -> SubagentChange.RESUMED
    else -> null
}

/** Seconds since start (or until the end), tool and model calls, tokens and cache hit. */
internal fun SubagentStatus.logLine(now: Long = System.currentTimeMillis()): String {
    val seconds = ((if (finishedAtEpochMillis > 0) finishedAtEpochMillis else now) - startedAtEpochMillis) / 1_000
    val cache = if (inputTokens > 0) " cached ${cachedTokens * 100 / inputTokens}%" else ""
    return "${seconds}s · tools $toolCalls · model $modelCalls · in $inputTokens$cache · out $outputTokens" +
        (if (error.isNotEmpty()) " · $error" else "")
}

/** The transcript line for a change, in the screen's language. */
internal fun SubagentStatus.transcriptLine(
    change: SubagentChange,
    context: Context = AppSubagents.displayContext,
): String = when (change) {
    SubagentChange.STARTED -> context.getString(R.string.skills_subagent_started, label, id)
    SubagentChange.STALLED -> {
        val seconds = (System.currentTimeMillis() - lastActivityEpochMillis) / 1_000
        if (latest.isNotEmpty()) {
            context.getString(R.string.skills_subagent_stalled_latest, label, seconds, latest.take(60))
        } else {
            context.getString(R.string.skills_subagent_stalled, label, seconds)
        }
    }
    SubagentChange.RESUMED -> context.getString(R.string.skills_subagent_resumed, label)
    SubagentChange.FINISHED -> {
        val stats = context.resources.subagentLogText(this)
        context.getString(R.string.skills_subagent_finished, label, context.getString(state.labelRes()), stats)
    }
}

/**
 * What a child may call. Never: questions to the user (no one is there), cards, plans and external delegation (the
 * main agent's). Device tools ([deviceServers]: the screen, media playback and the host's own devices) only when the
 * settings allow it; otherwise their queries only. At most [MAX_CHILD_LOOKUPS] pages and searches of its own: a child
 * hunting train times can open 20 pages, and what pages bring in is new input every round, the part of a trip that
 * costs (cache hits are 1/50 of it).
 * A typed child also keeps to its type's [tools]; the tool list itself stays the main agent's.
 */
internal class SubagentGuard(
    private val allowDeviceControl: Boolean,
    /** A typed child's own share ([SubagentKind.maxLookups]). */
    private val maxLookups: Int = MAX_CHILD_LOOKUPS,
    /** A typed child's tools ([SubagentKind.tools]); null for a general child. */
    private val tools: Set<SubagentToolGroup>? = null,
    private val kindId: String = "",
    private val deviceServers: Set<String> = builtInDeviceServers(),
) : RuntimeHook {
    private val lookups = java.util.concurrent.atomic.AtomicInteger()

    override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision {
        val group = subagentToolGroup(call.toolName, deviceServers)
        if (tools != null && group != null && group !in tools) {
            return HookDecision.Deny(
                reason = modelText(
                    "这个子 agent（$kindId）不能用「${group.label}」：用能用的工具完成，或把要做的写进结果。",
                    "This subagent ($kindId) may not use \"${group.label}\": finish with the tools it has, or put what should be done in the result.",
                ),
            )
        }
        if (call.toolName in CHILD_LOOKUP_TOOLS && lookups.incrementAndGet() > maxLookups) {
            return HookDecision.Deny(reason = lookupsSpent(maxLookups))
        }
        val name = call.toolName
        val server = name.removePrefix(MCP_PREFIX).substringBefore("__").takeIf { name.startsWith(MCP_PREFIX) }.orEmpty()
        val tool = name.substringAfterLast("__")
        return when {
            name in USER_FACING_TOOLS ->
                HookDecision.Deny(reason = "A subagent cannot ask the user; decide on the likeliest reading and say so in the result.")
            server.mainAgentOnly() ->
                HookDecision.Deny(reason = "Only the main agent uses this tool; put what should be done in the result.")
            server in deviceServers && !allowDeviceControl && !tool.isQuery() ->
                HookDecision.Deny(reason = "A subagent cannot operate the device; put the change you would make in the result.")
            else -> HookDecision.Allow()
        }
    }
}

/**
 * The child's input: its role, a typed child's tools and own instructions ([kind] and its text), then the task the
 * main agent wrote. A type says itself what pictures it brings, so the general picture rule is for untyped children only,
 * and only while the cards that show the pictures are offered (a plugin offers them).
 */
internal fun subagentInput(spec: SubagentSpec, kind: SubagentKind? = null, instructions: String = ""): String = buildString {
    childRules(spec, kind).forEach(::appendLine)
    if (kind != null && instructions.isNotBlank()) {
        appendLine()
        appendLine(modelText("你的分工（${kind.id}）：", "Your role (${kind.id}):"))
        appendLine(instructions.trim())
    }
    appendLine()
    appendLine(modelText("任务（${spec.label}）：", "Task (${spec.label}):"))
    append(spec.task)
}

/** The child's role and rules, a line each: what it may do, its tools, how it searches and, untyped, its pictures. */
private fun childRules(spec: SubagentSpec, kind: SubagentKind?): List<String> {
    val tools = kind?.tools
    val operates = spec.allowDeviceControl && (tools == null || SubagentToolGroup.DEVICE_CONTROL in tools)
    val looksUp = tools == null || tools.any { it == SubagentToolGroup.SEARCH || it == SubagentToolGroup.FETCH }
    return listOfNotNull(
        modelText(
            "你是主助手派出的后台子助手，独立完成下面的任务，然后用 finish 交回结果。",
            "You are a background helper the main assistant sent: do the task below on your own, then hand the result back with finish.",
        ),
        modelText(
            "- 你看不到和用户的对话，也不能问用户；信息不全时按最合理的理解去做，并在结果里说明。",
            "- You cannot see the conversation with the user or ask the user anything; when something is missing, go with the " +
                "likeliest reading and say so in the result.",
        ),
        if (operates) modelText("- 只做任务要求的设备操作，不展示卡片。", "- Operate devices only as the task asks; show no cards.") else queriesOnly(),
        kind?.let { modelText("- 能用的工具：${it.toolLabels()}；别的工具会被拒绝。", "- What you can use: ${it.toolLabels()}; other tools are refused.") },
        modelText("- 结果要完整、简洁，写成主助手可以直接转述的要点。", "- Make the result complete and concise: points the main assistant can pass on as they are."),
        searchRule().takeIf { kind == null || kind.searches },
        lookupRule(kind?.maxLookups ?: MAX_CHILD_LOOKUPS).takeIf { looksUp },
        pictureRule().takeIf { kind == null && AppHost.offers(cc.noharry.agent.appserver.protocol.ServerFeatures.CARDS) },
    )
}

private fun queriesOnly() = modelText(
    "- 不能操作设备、不能展示卡片，只能查询；需要操作的写进结果，由主助手决定。",
    "- You cannot operate devices or show cards, only look things up; put any change to make in the result for the main assistant to decide.",
)

private fun searchRule() = modelText(
    "- 查资料先联网搜索：有 web_search 就用它，没有时服务端搜索一直可用。web_fetch 只用来打开搜索结果里出现过的详情页或你的分工里写明的网址，不要猜网址。",
    "- Search the web first: with web_search when you have it, else the server-side search, which is always there. Use " +
        "web_fetch only for detail pages that appeared in search results or addresses your role names; never guess an address.",
)

private fun lookupRule(maxLookups: Int) = modelText(
    "- 打开网页和搜索一共最多 $maxLookups 次，够用就停；搜索摘要里已有的信息不用再打开详情页。互不依赖的几次查询在同一步里一起发（一次回复里调用多次），不要一步只查一个。",
    "- Open pages and search at most $maxLookups times in all, and stop once you have enough; what a search summary already " +
        "says needs no detail page. Send lookups that do not depend on each other together in one step (several calls in " +
        "one reply), not one per step.",
)

private fun pictureRule() = modelText(
    "- 查活动、景点、展览、餐厅这类有图的东西时，挑最值得推荐的 2–3 项，用 web_fetch 打开搜索结果里它们的详情页（最多 3 次）：结果里的 image 是那一页的配图，在该项后面另起一行写「图片：https://…」。只用工具结果给出的图片地址，没有就不写，不要编造。",
    "- When looking up things with pictures, such as events, sights, exhibitions or restaurants, pick the 2–3 most worth " +
        "recommending and open their detail pages from the search results with web_fetch (at most 3): the result's image " +
        "is that page's picture; put it on its own line after the item as \"${IMAGE_LINE_EN}https://…\". Use only image " +
        "addresses a tool result gave; write none otherwise, and never make one up.",
)

/**
 * A report as one turn: [ReportTurn.display] for the transcript and card, [ReportTurn.model] for the agent. The display
 * is kept as the turn's user text, which later requests show the model, so it stays in the model's words whatever the
 * screens' language. It carries every child of the batch (a turn per child makes the agent repeat itself and say it is
 * still looking about results it has not been handed yet), and [stillWorking] names siblings that are not done.
 * [closingOf] gives a type's own closing ([SubagentKind.reportClosing]).
 */
internal fun SubagentReport.toReport(
    stillWorking: List<SubagentStatus> = emptyList(),
    now: Long = System.currentTimeMillis(),
    closingOf: (type: String) -> String? = { type -> AppHost.runs.subagentKinds().firstOrNull { it.id == type }?.reportClosing },
): ReportTurn {
    val labels = statuses.joinToString(modelText("", ", ")) { modelText("「${it.label}」", "\"${it.label}\"") }
    val display = when {
        statuses.size == 1 -> statuses.single().reportHeadline()
        statuses.all { it.state == SubagentState.COMPLETED } -> modelText("后台任务${labels}都完成了", "Background tasks $labels are all done")
        else -> modelText("后台任务${labels}有消息了", "Background tasks $labels have news")
    }
    val model = buildString {
        appendLine(modelText("【后台子任务回报】你之前用 spawn_agent 委派的后台任务：", "[Background task report] Tasks you delegated with spawn_agent:"))
        statuses.forEach { append(it.reportSection(now)) }
        if (stillWorking.isNotEmpty()) {
            appendLine(
                modelText(
                    "还在进行：${stillWorking.joinToString("") { "「${it.label}」（${it.id}）" }}，结束后会再回报，不要现在等它们。",
                    "Still running: ${stillWorking.joinToString(", ") { "\"${it.label}\" (${it.id})" }}; they report when done, so do not wait for them now.",
                ),
            )
        } else if (statuses.none { it.state == SubagentState.STALLED }) {
            appendLine(modelText("这一批都在这里了，不用再调用 wait_agent。", "This is the whole batch; no need to call wait_agent."))
        }
        if (AppHost.offers(cc.noharry.agent.appserver.protocol.ServerFeatures.CARDS) && statuses.any { it.result.hasImageLines() }) appendLine(pictureCardHint())
        // A type may say what its report is for ([SubagentKind.reportClosing]); a batch of one such type gets it.
        val closing = statuses.map { it.agentType }.distinct().singleOrNull()?.let(closingOf)
        if (closing != null) {
            append(closing)
        } else {
            append(
                modelText(
                    "用一段话把对用户有用的部分汇总告诉用户，不要逐个复述过程，也不要用「好的」之类的话开头。",
                    "Tell the user, in one paragraph, what of it is useful to them; do not go through the steps one by one, and do not open with \"OK\" or the like.",
                ),
            )
        }
    }
    return ReportTurn(display = display, model = model)
}

private fun SubagentStatus.reportHeadline(): String = when (state) {
    SubagentState.COMPLETED -> modelText("后台任务「$label」完成了", "Background task \"$label\" is done")
    SubagentState.STALLED -> modelText("后台任务「$label」没有进展", "Background task \"$label\" is making no progress")
    else -> modelText("后台任务「$label」没有完成", "Background task \"$label\" did not finish")
}

private fun SubagentStatus.reportSection(now: Long): String {
    val name = modelText("「$label」（$id）", "\"$label\" ($id)")
    val seconds = ((if (finishedAtEpochMillis > 0) finishedAtEpochMillis else now) - startedAtEpochMillis) / 1_000
    val quiet = (now - lastActivityEpochMillis) / 1_000
    return when (state) {
        SubagentState.COMPLETED -> modelText("$name 已完成，用时 $seconds 秒。结果：\n$result\n", "$name is done, in $seconds s. Result:\n$result\n")
        SubagentState.STALLED -> modelText(
            "$name 已经 $quiet 秒没有动静" + (if (latest.isNotEmpty()) "（最近：$latest）" else "") +
                "，可以用 wait_agent 继续等，或用 cancel_agent 取消。\n",
            "$name has been quiet for $quiet s" + (if (latest.isNotEmpty()) " (latest: $latest)" else "") +
                "; keep waiting with wait_agent, or cancel it with cancel_agent.\n",
        )
        else -> modelText(
            "$name 没有完成（${state.wireName}：$error），需要的话重新委派或自己处理。\n",
            "$name did not finish (${state.wireName}: $error); delegate it again or handle it yourself if needed.\n",
        )
    }
}

/** The key of [deliveries][AppSubagents.deliveries]. */
internal fun SubagentStatus.deliveryKey(): String = "$parentSessionId/$id"

/**
 * [enabled]: the agent may start subagents on its own (a runtime setting); off hides the three tools. [searchApi]:
 * children search with a service's search API ([ProviderAdapter.lookup][cc.noharry.agent.appserver.model.ProviderAdapter.lookup]) whatever the main agent uses (owner 2026-10-17: 子 agent 搜索改用 API 接口再对比下费用).
 */
@cc.noharry.agent.appserver.AppServerPluginApi
data class SubagentSettings(
    val allowDeviceControl: Boolean = false,
    val model: String = "",
    val enabled: Boolean = true,
    val searchApi: Boolean = false,
) {
    /** [model] is `provider:model[:variant]`; blank keeps the parent run's model. */
    internal fun toConfig(): SubagentConfig = SubagentConfig(allowDeviceControl = allowDeviceControl, modelRef = parseModelRef(model))

    private fun parseModelRef(text: String): ModelRef? {
        val parts = text.trim().split(':').map(String::trim)
        if (parts.size < 2 || parts[0].isEmpty() || parts[1].isEmpty()) return null
        return ModelRef(parts[0], parts[1], parts.getOrNull(2)?.takeIf { it.isNotEmpty() })
    }
}

@cc.noharry.agent.appserver.AppServerPluginApi
fun loadSubagentSettings(context: Context): SubagentSettings {
    val prefs = context.getSharedPreferences(SUBAGENT_PREFS, Context.MODE_PRIVATE)
    return SubagentSettings(
        allowDeviceControl = prefs.getBoolean(KEY_DEVICE_CONTROL, false),
        model = prefs.getString(KEY_MODEL, "").orEmpty(),
        enabled = prefs.getBoolean(KEY_ENABLED, true),
        searchApi = prefs.getBoolean(KEY_SEARCH_API, false),
    )
}

@cc.noharry.agent.appserver.AppServerPluginApi
fun saveSubagentSettings(context: Context, settings: SubagentSettings) {
    context.getSharedPreferences(SUBAGENT_PREFS, Context.MODE_PRIVATE).edit()
        .putBoolean(KEY_DEVICE_CONTROL, settings.allowDeviceControl)
        .putString(KEY_MODEL, settings.model)
        .putBoolean(KEY_ENABLED, settings.enabled)
        .putBoolean(KEY_SEARCH_API, settings.searchApi)
        .apply()
    AppSubagents.applySettings(settings)
}

/** With subagents switched off the three tools are switched off too, whatever the basic tools page says. */
internal fun List<BasicTool>.withSubagentSwitch(enabled: Boolean): List<BasicTool> =
    if (enabled) this else map { if (it.name in DEBUG_SUBAGENT_TOOL_NAMES) it.copy(enabled = false) else it }

internal val DEBUG_SUBAGENT_TOOL_NAMES = setOf("spawn_agent", "wait_agent", "cancel_agent")

private val CHILD_LOOKUP_TOOLS = setOf("web_fetch", "web_search")

/**
 * The kind of tool [toolName] is, for a type's tools; null for what every child has (finish, search_tools, the subagent
 * tools) and for the main agent's own, which [SubagentGuard] refuses anyway.
 */
internal fun subagentToolGroup(toolName: String, deviceServers: Set<String> = builtInDeviceServers()): SubagentToolGroup? {
    when (toolName) {
        "web_search" -> return SubagentToolGroup.SEARCH
        "web_fetch" -> return SubagentToolGroup.FETCH
        "list_dir", "read_file", "grep" -> return SubagentToolGroup.READ
        "write_file", "edit_file" -> return SubagentToolGroup.WRITE
        "update_todo" -> return SubagentToolGroup.TODO
    }
    if (!toolName.startsWith(MCP_PREFIX)) return null
    val server = toolName.removePrefix(MCP_PREFIX).substringBefore("__")
    return when {
        server.mainAgentOnly() -> null
        server in deviceServers -> if (toolName.substringAfterLast("__").isQuery()) SubagentToolGroup.DEVICE_QUERY else SubagentToolGroup.DEVICE_CONTROL
        else -> SubagentToolGroup.SERVICES
    }
}

/** The servers whose tools work a device: the screen, the plugins' that say so (media playback), and the host's own. */
internal fun subagentDeviceServers(context: Context): Set<String> = builtInDeviceServers() + AppHost.runs.queryOnlyInSubagents(context)
internal const val MAX_CHILD_LOOKUPS = DEFAULT_SUBAGENT_LOOKUPS

private fun lookupsSpent(max: Int) = modelText(
    "已经查了 $max 次网页和搜索，不再查了：用已有的结果调用 finish，查不到的写「待确认」。",
    "$max pages and searches are used up, so no more: call finish with what you have, and write \"to be confirmed\" for what you did not find.",
)

private const val IMAGE_LINE_ZH = "图片："
private const val IMAGE_LINE_EN = "Image: "

/** Whether a child's result has the picture lines its input asked for, in the persona's words. */
private fun String.hasImageLines(): Boolean = modelText(IMAGE_LINE_ZH, IMAGE_LINE_EN) in this

/** Pictures with text: a subagent's 「图片：https://…」 (picture) lines go into the summary card. */
internal fun pictureCardHint(): String = modelText(
    "子 agent 的结果里有「图片：https://…」时，汇总时先用一次 show_card 把这一批结果做成一张图文卡片（卡片一次只显示一张，不同城市或主题用 " +
        "items 的 group 分组）：每项 image 填它对应的图片，头图 image 用最有代表性的一张，没有图片的项照样列出；然后 finish 只说要点。",
    "When a subagent's result has \"${IMAGE_LINE_EN}https://…\" lines, first make the batch into one picture card with a single " +
        "show_card while summing up (a card shows one at a time; group different cities or topics with the items' group): each " +
        "item's image is its own picture, the header image the most telling one, and items without a picture are listed all the " +
        "same; then finish says only the main points.",
)

private const val TAG = "DebugSubagents"
private const val MAX_BOARD_ROWS = 8
/** In the parent conversation's event-log folder; the runtime repo's `tools/session_cache.py --children` reads it. */
internal const val SUBAGENT_RECORDS = "subagents.jsonl"
/** A late result waiting for its turn, in [AppSubagents.deliveries]. */
internal const val REPORT_QUEUED = 0L
private const val MAX_DELIVERY_MARKS = 64
private const val MCP_PREFIX = "mcp__"
private val USER_FACING_TOOLS = setOf("ask", "ask_async")

/** A running plugin's tool server that is the main agent's alone (cards, plans, handing a task to another agent). */
private fun String.mainAgentOnly(): Boolean = AppHost.additions.mainAgentOnly(this)

/** A report as one turn: what the transcript and the card show, and what the agent reads. */
internal data class ReportTurn(val display: String, val model: String)
// The screen has no query a child may make: looking at it holds it too, so the main agent's taps wait meanwhile.
private fun builtInDeviceServers(): Set<String> = AppHost.additions.deviceServers

/**
 * Whole words only ("TemperatureAdjust" holds "reAd" but no word "read"): split at underscores and capitals. An action
 * word wins over a query word: "media_play_from_search" plays.
 */
private fun String.isQuery(): Boolean {
    val words = split('_').flatMap { it.split(CAMEL_BOUNDARY) }.map { it.lowercase() }
    return words.any { it in QUERY_WORDS } && words.none { it in ACTION_WORDS }
}

private val CAMEL_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])")
private val QUERY_WORDS = setOf("query", "get", "read", "list", "search", "info", "status", "check")
private val ACTION_WORDS = setOf(
    "set", "play", "start", "stop", "pause", "resume", "open", "close", "send", "create", "run", "control", "adjust",
    "change", "add", "remove", "replace", "reorder", "navigate", "toggle", "turn", "lock", "unlock", "launch", "delete",
    "update", "write", "action", "begin", "cancel", "call", "order", "pay", "submit", "book", "move", "press", "tap",
)
private const val SUBAGENT_PREFS = "app_server_subagents"
private const val KEY_SEARCH_API = "search_api"
private const val KEY_DEVICE_CONTROL = "allow_device_control"
private const val KEY_MODEL = "model"
private const val KEY_ENABLED = "enabled"
