@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.skills
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.protocol.SessionPolicy
import cc.noharry.agent.appserver.protocol.SessionSummary
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.server.OwnToolServer
import cc.noharry.agent.appserver.server.PluginAdditions
import cc.noharry.agent.appserver.server.PluginToolServer
import cc.noharry.agent.appserver.runtime.BasicTool
import cc.noharry.agent.appserver.runtime.BuiltInFeatures
import cc.noharry.agent.appserver.runtime.Persona
import cc.noharry.agent.appserver.runtime.basicToolPrompt
import cc.noharry.agent.appserver.runtime.disabledBasicTools
import cc.noharry.agent.appserver.runtime.wakeStartsNewSession
import cc.noharry.agent.spi.HookContext
import cc.noharry.agent.spi.HookDecision
import cc.noharry.agent.spi.ToolSource
import cc.noharry.agent.spi.ToolUse
import cc.noharry.agent.spi.model.ModelRef
import cc.noharry.agent.runtime.SubagentReport
import cc.noharry.agent.runtime.SubagentSpec
import cc.noharry.agent.runtime.SubagentState
import cc.noharry.agent.runtime.SubagentStatus
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The lines the screen shows and the model reads are checked in Chinese, for a Chinese persona; the English ones at the end. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "zh", application = Application::class)
class SubagentsTest {
    private val context = HookContext(sessionId = "s", runId = "r", requestId = "q")
    private val appContext: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun chinese() {
        AppHost.install(persona = CHINESE, additions = cc.noharry.agent.appserver.server.PluginAdditions(offers = setOf(cc.noharry.agent.appserver.protocol.ServerFeatures.CARDS)))
    }

    @After
    fun generic() {
        AppHost.install(persona = Persona.GENERIC, additions = cc.noharry.agent.appserver.server.PluginAdditions.NONE)
    }

    @Test
    fun withoutCardsNoChildLooksForPicturesAndNoReportAsksForACard() {
        AppHost.install(additions = cc.noharry.agent.appserver.server.PluginAdditions.NONE)
        val status = SubagentStatus(
            id = "sub-1", label = "苏州活动", state = SubagentState.COMPLETED, startedAtEpochMillis = 0L,
            lastActivityEpochMillis = 40_000L, finishedAtEpochMillis = 42_000L, toolCalls = 3, latest = "",
            result = "草莓音乐节\n图片：https://img.example.com/a.jpg", error = "",
        )
        assertFalse(SubagentReport("s", listOf(status)).toReport().model.contains("show_card"))
        assertFalse(subagentInput(SubagentSpec("sub-1", "s-sub-1", "s", "r", "苏州活动", "查苏州周末活动", 1, null, false)).contains("图片："))
    }

    @Test
    fun byDefaultAChildQueriesTheDeviceButNeitherOperatesItNorTalksToTheUser() = runBlocking {
        val guard = SubagentGuard(allowDeviceControl = false, deviceServers = CABIN)

        assertTrue(guard.decide("mcp__cabin__command_cmd_QueryActiveAudioTrack") is HookDecision.Allow)
        assertTrue(guard.decide("web_fetch", ToolSource.NETWORK) is HookDecision.Allow)
        assertTrue(guard.decide("mcp__cabin__command_ac_TemperatureAdjust") is HookDecision.Deny)
        assertTrue(guard.decide("mcp__cabin__window_open") is HookDecision.Deny)
        assertTrue(withOwnServers { guard.decide("mcp__app_ui__show_card") } is HookDecision.Deny)
        assertTrue(guard.decide("ask", ToolSource.INTERACTION) is HookDecision.Deny)
    }

    @Test
    fun aChildStopsLookingThingsUpAfterItsShare() = runBlocking {
        val guard = SubagentGuard(allowDeviceControl = false, deviceServers = CABIN)

        repeat(MAX_CHILD_LOOKUPS) { assertTrue(guard.decide(if (it % 2 == 0) "web_fetch" else "web_search", ToolSource.NETWORK) is HookDecision.Allow) }
        val spent = guard.decide("web_fetch", ToolSource.NETWORK)

        assertTrue(spent is HookDecision.Deny && (spent as HookDecision.Deny).reason.contains("finish"))
        // Its other tools, and another child's lookups, are not counted.
        assertTrue(guard.decide("mcp__cabin__command_cmd_QueryActiveAudioTrack") is HookDecision.Allow)
        assertTrue(SubagentGuard(allowDeviceControl = false, deviceServers = CABIN).decide("web_fetch", ToolSource.NETWORK) is HookDecision.Allow)
        // A typed child has its own share.
        val typed = SubagentGuard(allowDeviceControl = false, maxLookups = 2, deviceServers = CABIN)
        repeat(2) { assertTrue(typed.decide("web_search", ToolSource.NETWORK) is HookDecision.Allow) }
        assertTrue((typed.decide("web_search", ToolSource.NETWORK) as HookDecision.Deny).reason.startsWith("已经查了 2 次"))
    }

    @Test
    fun aTypedChildCallsOnlyItsTypesTools() = runBlocking {
        val tools = setOf(SubagentToolGroup.SEARCH, SubagentToolGroup.FETCH, SubagentToolGroup.DEVICE_QUERY)
        val guard = SubagentGuard(allowDeviceControl = true, maxLookups = 1, tools = tools, kindId = "trip-transport", deviceServers = CABIN)

        val write = guard.decide("write_file", ToolSource.NATIVE_WRITE)
        assertTrue(write is HookDecision.Deny && (write as HookDecision.Deny).reason.startsWith("这个子 agent（trip-transport）不能用「写文件」"))
        assertTrue(guard.decide("mcp__amap__route_plan") is HookDecision.Deny)
        // Allowed by the settings, but not this type's to do.
        assertTrue(guard.decide("mcp__cabin__command_ac_TemperatureAdjust") is HookDecision.Deny)
        assertTrue(guard.decide("mcp__cabin__command_cmd_QueryActiveAudioTrack") is HookDecision.Allow)
        // What every child has.
        assertTrue(guard.decide("search_tools", ToolSource.NATIVE_READ) is HookDecision.Allow)
        assertTrue(guard.decide("finish", ToolSource.TERMINAL) is HookDecision.Allow)
        // A refused tool spends none of the share.
        assertTrue(guard.decide("web_fetch", ToolSource.NETWORK) is HookDecision.Allow)
        assertTrue(guard.decide("web_search", ToolSource.NETWORK) is HookDecision.Deny)
    }

    @Test
    fun toolsAreGroupedByWhatTheyDo() {
        assertEquals(SubagentToolGroup.READ, subagentToolGroup("grep"))
        assertEquals(SubagentToolGroup.TODO, subagentToolGroup("update_todo"))
        assertEquals(SubagentToolGroup.DEVICE_CONTROL, subagentToolGroup("mcp__cabin__window_open", CABIN))
        assertEquals(SubagentToolGroup.DEVICE_QUERY, subagentToolGroup("mcp__cabin__get_seat_status", CABIN))
        assertEquals(SubagentToolGroup.SERVICES, subagentToolGroup("mcp__memory__user_memory"))
        // The screen's plugin says its tool server works a device.
        assertEquals(SubagentToolGroup.DEVICE_CONTROL, withOwnServers { subagentToolGroup("mcp__android_use__android_action") })
        assertEquals(SubagentToolGroup.DEVICE_CONTROL, withOwnServers { subagentToolGroup("mcp__android_use__android_observe") })
        // A plugin's tool server that says it works a device (the device tools' media).
        withDeviceMedia {
            assertEquals(SubagentToolGroup.DEVICE_CONTROL, subagentToolGroup("mcp__device_media__media_play_from_search"))
            assertEquals(SubagentToolGroup.DEVICE_QUERY, subagentToolGroup("mcp__device_media__media_status"))
        }
        assertEquals(SubagentToolGroup.SERVICES, subagentToolGroup("mcp__device_media__media_status"))
        // The host's devices are the ones it names.
        assertEquals(SubagentToolGroup.SERVICES, subagentToolGroup("mcp__cabin__get_cabin_status"))
        assertEquals(SubagentToolGroup.DEVICE_QUERY, subagentToolGroup("mcp__cabin__get_cabin_status", setOf("cabin")))
        assertNull(subagentToolGroup("spawn_agent"))
        // A running plugin's tool servers that are the main agent's alone (cards, A2A's hand-offs) are no kind of the child's.
        withOwnServers {
            assertNull(subagentToolGroup("mcp__app_ui__show_card"))
            assertNull(subagentToolGroup("mcp__experimental_a2a_agent__a2a_message_send"))
        }
    }

    @Test
    fun theScreenMediaAndTheHostsOwnDevicesAreOnlyQueriedByDefault() = runBlocking {
        val guard = SubagentGuard(allowDeviceControl = false, deviceServers = withDeviceMedia { subagentDeviceServers(appContext) } + "cabin")
        // The screen's plugin names its tool server as one that works a device.
        assertTrue("android_use" in withDeviceMedia { subagentDeviceServers(appContext) })

        // Looking at the screen holds it too.
        assertTrue(guard.decide("mcp__android_use__android_observe") is HookDecision.Deny)
        assertTrue(guard.decide("mcp__android_use__android_action") is HookDecision.Deny)
        assertTrue(guard.decide("mcp__device_media__media_status") is HookDecision.Allow)
        assertTrue(guard.decide("mcp__device_media__media_control") is HookDecision.Deny)
        assertTrue(guard.decide("mcp__device_media__media_volume") is HookDecision.Deny)
        // An action word wins over a query word.
        assertTrue(guard.decide("mcp__device_media__media_play_from_search") is HookDecision.Deny)
        assertTrue(guard.decide("mcp__cabin__get_cabin_status") is HookDecision.Allow)
        assertTrue(guard.decide("mcp__cabin__search_places") is HookDecision.Allow)
        assertTrue(guard.decide("mcp__cabin__set_climate") is HookDecision.Deny)
        assertTrue(guard.decide("mcp__cabin__start_navigation") is HookDecision.Deny)
        // A service is not a device.
        assertTrue(guard.decide("mcp__amap__route_plan") is HookDecision.Allow)
        // The settings let it operate them.
        val allowed = SubagentGuard(allowDeviceControl = true, deviceServers = setOf("cabin"))
        assertTrue(allowed.decide("mcp__cabin__set_climate") is HookDecision.Allow)
        // A typed child without device queries keeps away from the host's devices too.
        val stay = SubagentGuard(false, tools = setOf(SubagentToolGroup.SERVICES), kindId = "trip-stay", deviceServers = setOf("cabin"))
        assertTrue(stay.decide("mcp__cabin__get_cabin_status") is HookDecision.Deny)
    }

    @Test
    fun deviceControlCanBeAllowedButCardsAndQuestionsStayWithTheMainAgent() = runBlocking {
        val guard = SubagentGuard(allowDeviceControl = true, deviceServers = CABIN)

        assertTrue(guard.decide("mcp__cabin__command_ac_TemperatureAdjust") is HookDecision.Allow)
        assertTrue(withOwnServers { guard.decide("mcp__app_ui__show_card") } is HookDecision.Deny)
        assertTrue(guard.decide("ask_async", ToolSource.INTERACTION) is HookDecision.Deny)
        // A running plugin's tool server that is the main agent's alone (plans) is refused too, and only while it runs.
        assertTrue(guard.decide("mcp__plans__plan_create") is HookDecision.Allow)
        AppHost.install(additions = PluginAdditions(ownToolServers = mapOf("plans" to OwnToolServer(mainAgentOnly = true))))
        try {
            assertTrue(guard.decide("mcp__plans__plan_create") is HookDecision.Deny)
        } finally {
            AppHost.install(additions = PluginAdditions.NONE)
        }
    }

    @Test
    fun aConversationWithBackgroundWorkIsKeptOnWake() {
        val idle = SessionSummary("s", "t", updatedAtEpochMillis = 0L, turnCount = 3, compactedSummary = "")
        val policy = SessionPolicy()
        val later = policy.idleMillis + 1

        assertTrue(wakeStartsNewSession(idle, later, policy = policy))
        assertFalse(wakeStartsNewSession(idle, later, backgroundWork = true, policy = policy))
    }

    @Test
    fun theModelSettingIsProviderModelAndOptionalVariantAndBlankInherits() {
        assertEquals(ModelRef("anthropic", "deepseek-flash", "none"), SubagentSettings(model = "anthropic:deepseek-flash:none").toConfig().modelRef)
        assertEquals(ModelRef("openai", "gpt-5", null), SubagentSettings(model = "openai:gpt-5").toConfig().modelRef)
        assertNull(SubagentSettings(model = "").toConfig().modelRef)
        assertFalse(SubagentSettings().toConfig().allowDeviceControl)
    }

    @Test
    fun aLateResultBecomesATurnThatHandsTheAnswerToTheMainAgent() {
        val status = SubagentStatus(
            id = "sub-1", label = "苏州天气", state = SubagentState.COMPLETED, startedAtEpochMillis = 0L,
            lastActivityEpochMillis = 40_000L, finishedAtEpochMillis = 42_000L, toolCalls = 3, latest = "",
            result = "明天晴，22度", error = "",
        )
        val report = SubagentReport("s", listOf(status)).toReport(now = 50_000L)

        assertEquals("后台任务「苏州天气」完成了", report.display)
        assertTrue(report.model, report.model.contains("用时 42 秒") && report.model.contains("明天晴，22度"))
        assertTrue(report.model, report.model.contains("不用再调用 wait_agent") && report.model.contains("不要用「好的」"))

        // A batch is one turn naming every child; a sibling still at work is named, not waited for.
        val other = status.copy(id = "sub-2", label = "杭州天气", result = "明天多云")
        val batch = SubagentReport("s", listOf(status, other)).toReport(
            stillWorking = listOf(other.copy(id = "sub-3", label = "南京天气", state = SubagentState.RUNNING)),
        )
        assertEquals("后台任务「苏州天气」「杭州天气」都完成了", batch.display)
        assertTrue(batch.model, batch.model.contains("明天多云") && batch.model.contains("还在进行：「南京天气」（sub-3）"))
        // 图文: pictures a child found go into the summary card.
        assertFalse(batch.model.contains("图文卡片"))
        val pictured = SubagentReport("s", listOf(status.copy(result = "草莓音乐节\n图片：https://img.example.com/a.jpg")))
            .toReport()
        assertTrue(pictured.model, pictured.model.contains("图文卡片"))
        // A type that says how its report closes gets that instead of the summing up.
        val checked = SubagentReport("s", listOf(status.copy(agentType = "check")))
            .toReport(closingOf = { type -> "把核实到的放进卡片。".takeIf { type == "check" } })
        assertTrue(checked.model, checked.model.endsWith("把核实到的放进卡片。") && !checked.model.contains("用一段话"))
        val input = subagentInput(SubagentSpec("sub-1", "s-sub-1", "s", "r", "苏州天气", "查苏州明天的天气", 1, null, false))
        assertTrue(input.contains("不能操作设备") && input.endsWith("查苏州明天的天气"))
        assertTrue(input.contains("web_fetch") && input.contains("图片："))
    }

    @Test
    @Config(qualifiers = "en")
    fun theReportTurnIsInThePersonasWordsWhateverTheScreensLanguage() {
        val status = SubagentStatus(
            id = "sub-1", label = "Suzhou weather", state = SubagentState.COMPLETED, startedAtEpochMillis = 0L,
            lastActivityEpochMillis = 40_000L, finishedAtEpochMillis = 42_000L, toolCalls = 3, latest = "",
            result = "Sunny tomorrow", error = "",
        )
        val other = status.copy(id = "sub-2", label = "Hangzhou weather")
        val batch = SubagentReport("s", listOf(status, other)).toReport()

        // The display becomes the turn's user text, which later requests show the model.
        assertEquals("后台任务「Suzhou weather」「Hangzhou weather」都完成了", batch.display)
        assertTrue(batch.model, batch.model.contains("「Suzhou weather」（sub-1）"))
    }

    @Test
    fun aPersonaWithoutChineseGetsEnglishReportsPromptsAndRefusals() = runBlocking {
        AppHost.install(persona = Persona.GENERIC)
        val status = SubagentStatus(
            id = "sub-1", label = "Suzhou weather", state = SubagentState.COMPLETED, startedAtEpochMillis = 0L,
            lastActivityEpochMillis = 40_000L, finishedAtEpochMillis = 42_000L, toolCalls = 3, latest = "",
            result = "Sunny tomorrow\nImage: https://img.example.com/a.jpg", error = "",
        )
        val other = status.copy(id = "sub-2", label = "Hangzhou weather", result = "Cloudy")
        val batch = SubagentReport("s", listOf(status, other)).toReport(
            stillWorking = listOf(other.copy(id = "sub-3", label = "Nanjing weather", state = SubagentState.RUNNING)),
        )

        assertEquals("Background tasks \"Suzhou weather\", \"Hangzhou weather\" are all done", batch.display)
        assertTrue(batch.model, batch.model.contains("\"Suzhou weather\" (sub-1) is done, in 42 s. Result:"))
        assertTrue(batch.model, batch.model.contains("Still running: \"Nanjing weather\" (sub-3)") && batch.model.contains("picture card"))
        val input = subagentInput(SubagentSpec("sub-1", "s-sub-1", "s", "r", "Suzhou weather", "Find tomorrow's weather in Suzhou", 1, null, false))
        assertTrue(input, input.contains("cannot operate devices") && input.contains("Task (Suzhou weather):\nFind tomorrow's weather in Suzhou"))
        val typed = SubagentGuard(allowDeviceControl = false, maxLookups = 0, tools = setOf(SubagentToolGroup.SEARCH), kindId = "trip")
        assertEquals(
            "This subagent (trip) may not use \"write files\": finish with the tools it has, or put what should be done in the result.",
            (typed.decide("write_file", ToolSource.NATIVE_WRITE) as HookDecision.Deny).reason,
        )
        assertTrue((typed.decide("web_search", ToolSource.NETWORK) as HookDecision.Deny).reason.startsWith("0 pages and searches are used up"))
        val all = SubagentStatus(
            id = "sub-1", label = "x", state = SubagentState.STALLED, startedAtEpochMillis = 0L, lastActivityEpochMillis = 5_000L,
            finishedAtEpochMillis = 0L, toolCalls = 0, latest = "web_search", result = "", error = "",
        )
        assertEquals(
            "Background task \"x\" is making no progress",
            SubagentReport("s", listOf(all)).toReport(now = 35_000L).display,
        )
    }

    @Test
    fun switchingSubagentsOffHidesTheirToolsAndPromptsTheRest() {
        val tools = listOf(BasicTool("spawn_agent", 0), BasicTool("wait_agent", 0), BasicTool("read_file", 0))

        assertEquals(tools, tools.withSubagentSwitch(enabled = true))
        val off = tools.withSubagentSwitch(enabled = false)
        assertEquals(setOf("spawn_agent", "wait_agent"), disabledBasicTools(off))
        assertTrue(basicToolPrompt(off).contains("spawn_agent"))
        assertTrue(SubagentSettings().enabled)
    }

    @Test
    fun onlyStateChangesBecomeTranscriptAndLogLines() {
        assertEquals(SubagentChange.STARTED, subagentChange(null, SubagentState.RUNNING))
        assertNull(subagentChange(SubagentState.RUNNING, SubagentState.RUNNING))
        assertEquals(SubagentChange.STALLED, subagentChange(SubagentState.RUNNING, SubagentState.STALLED))
        assertEquals(SubagentChange.RESUMED, subagentChange(SubagentState.STALLED, SubagentState.RUNNING))
        assertEquals(SubagentChange.FINISHED, subagentChange(SubagentState.STALLED, SubagentState.TIMED_OUT))

        val done = SubagentStatus(
            id = "sub-2", label = "上海油价", state = SubagentState.COMPLETED, startedAtEpochMillis = 0L,
            lastActivityEpochMillis = 5_000L, finishedAtEpochMillis = 5_500L, toolCalls = 1, latest = "", result = "8.57",
            error = "", modelCalls = 1, inputTokens = 27_376, cachedTokens = 20_608, outputTokens = 639,
        )
        assertEquals("5s · tools 1 · model 1 · in 27376 cached 75% · out 639", done.logLine())
        assertEquals(
            "后台任务「上海油价」完成：5s · 工具 1 次 · 模型 1 次 · 输入 27376 缓存 75% · 输出 639",
            done.transcriptLine(SubagentChange.FINISHED, appContext),
        )
    }

    private suspend fun SubagentGuard.decide(name: String, source: ToolSource = ToolSource.BACKEND) =
        onPreToolUse(ToolUse(name, "{}", source), context)

    private companion object {
        val CHINESE = Persona("助手", "", language = "zh-CN")
    }

    /** With a plugin's tool server that works a device running, as the device tools plugin's media server does. */
    private fun <T> withDeviceMedia(block: () -> T): T {
        val media = PluginToolServer("device_media", "Device Media", { "" }, worksDevice = true) { _, _ -> error("rows only") }
        AppHost.install(additions = PluginAdditions(toolServers = listOf(media), ownToolServers = OWN_SERVERS))
        try {
            return block()
        } finally {
            AppHost.install(additions = PluginAdditions.NONE)
        }
    }
}

/**
 * The tool servers plugins name as their own, as the cards, operating the screen and A2A plugins do: cards and hand-offs
 * are the main agent's, the screen works a device.
 */
private val OWN_SERVERS = mapOf(
    "app_ui" to OwnToolServer(mainAgentOnly = true),
    "android_use" to OwnToolServer(service = false, worksDevice = true),
    "experimental_a2a*" to OwnToolServer(mainAgentOnly = true),
)

/** [block] while the cards, operating the screen and A2A plugins run. */
private inline fun <T> withOwnServers(block: () -> T): T {
    AppHost.install(additions = PluginAdditions(ownToolServers = OWN_SERVERS))
    try {
        return block()
    } finally {
        AppHost.install(additions = PluginAdditions.NONE)
    }
}

/** A host's device server, as a car app names its own ([cc.noharry.agent.appserver.runtime.RunAddOns.deviceServers]). */
private val CABIN = setOf("cabin")
