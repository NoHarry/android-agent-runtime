package cc.noharry.agent.androiduse

import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.ToolPrefetchRequest
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidUseToolProviderTest {
    private val device = FakeDevice()
    private val presenter = RecordingPresenter()
    private var now = 0L
    private val lease = AndroidUseLease()

    private fun provider(options: AndroidUseOptions = AndroidUseOptions()) =
        AndroidUseToolProvider(device, presenter, options, lease) { now }

    @Test
    fun observeAttachesAScreenshotAndElementsInScreenshotPixels() = runTest {
        val result = provider().callTool(ANDROID_OBSERVE, "{}", CONTEXT)

        assertTrue(result.success)
        assertEquals(listOf("android-use/obs_1.jpg"), result.images.map { it.sourcePath })
        val observation = result.observation()
        assertEquals("screenshot pixels", observation.text("coordinates"))
        assertEquals(listOf("搜索", "播放", "下一首"), observation.elements().map { it.text("text") ?: it.text("desc") })
        val scale = observation["image"]!!.jsonObject.int("width") / 2560.0
        assertEquals(PixelRect(2200, 300, 2400, 400).scaled(scale).left, observation.elements()[2].bounds()[0])
        assertEquals(listOf("before-capture", "after-capture"), presenter.events.filter { "capture" in it })
    }

    @Test
    fun tapOnAnElementClicksTheNodeThenReturnsTheNextObservation() = runTest {
        val tools = provider()
        tools.callTool(ANDROID_OBSERVE, "{}", CONTEXT)

        val result = tools.callTool(ANDROID_ACTION, """{"observationId":"obs_1","action":"tap","elementId":"e3"}""", CONTEXT)

        assertTrue(result.success)
        assertTrue("click:1" in device.calls)
        assertFalse("stroke" in device.calls)
        val json = result.json()
        // The fake screen never changes, so the provider takes a second, later look before saying "no change".
        assertEquals("obs_3", json["observation"]!!.jsonObject.text("observationId"))
        assertEquals("false", json.text("changed"))
        assertEquals("e3", json["target"]!!.jsonObject.text("elementId"))
        assertEquals(1, result.images.size)
        assertTrue("gesture TAP 2300,350" in presenter.events)
    }

    @Test
    fun tapFallsBackToAGestureWhenTheNodeRefusesTheClick() = runTest {
        device.clickResult = false
        val tools = provider()
        tools.callTool(ANDROID_OBSERVE, "{}", CONTEXT)

        tools.callTool(ANDROID_ACTION, """{"observationId":"obs_1","action":"tap","elementId":"e3"}""", CONTEXT)

        assertEquals(Stroke(2300, 350, 2300, 350, 80), device.lastStroke)
    }

    @Test
    fun coordinatesAreScreenshotPixelsMappedBackToTheDisplay() = runTest {
        val tools = provider()
        val width = tools.callTool(ANDROID_OBSERVE, "{}", CONTEXT).observation()["image"]!!.jsonObject.int("width")
        val scale = width / 2560.0

        tools.callTool(ANDROID_ACTION, """{"observationId":"obs_1","action":"tap","x":100,"y":50}""", CONTEXT)

        assertEquals(Math.round(100 / scale).toInt(), device.lastStroke!!.fromX)
        assertEquals(Math.round(50 / scale).toInt(), device.lastStroke!!.fromY)
    }

    @Test
    fun staleObservationIsRefusedWithAFreshOneAttached() = runTest {
        val tools = provider()
        tools.callTool(ANDROID_OBSERVE, "{}", CONTEXT)
        tools.callTool(ANDROID_OBSERVE, "{}", CONTEXT)

        val result = tools.callTool(ANDROID_ACTION, """{"observationId":"obs_1","action":"tap","elementId":"e3"}""", CONTEXT)

        assertFalse(result.success)
        assertEquals(STALE_OBSERVATION, result.json().text("code"))
        assertEquals("obs_3", result.json()["observation"]!!.jsonObject.text("observationId"))
        assertEquals(1, result.images.size)
        assertFalse(device.calls.any { it.startsWith("click") })
    }

    @Test
    fun textOnlyModelGetsElementsInDisplayPixelsAndNoScreenshot() = runTest {
        val result = provider(AndroidUseOptions(screenshots = false)).callTool(ANDROID_OBSERVE, "{}", CONTEXT)

        assertTrue(result.images.isEmpty())
        assertFalse("capture" in device.calls)
        val observation = result.observation()
        assertEquals("screen pixels", observation.text("coordinates"))
        assertEquals(SCREENSHOT_TEXT_ONLY, observation.text("screenshot"))
        assertEquals(2200, observation.elements()[2].bounds()[0])
    }

    @Test
    fun anotherRunWaitsUntilTheHolderGoesIdle() = runTest {
        val tools = provider()
        tools.callTool(ANDROID_OBSERVE, "{}", CONTEXT)

        now = 1_000
        assertEquals(DEVICE_BUSY, tools.callTool(ANDROID_OBSERVE, "{}", OTHER_RUN).json().text("code"))
        now = 20_000
        assertTrue(tools.callTool(ANDROID_OBSERVE, "{}", OTHER_RUN).success)
    }

    @Test
    fun theNextTurnOfTheSameSessionTakesTheScreenAtOnce() = runTest {
        val tools = provider()
        tools.callTool(ANDROID_OBSERVE, "{}", CONTEXT)

        // "打开QQ音乐" ended a moment ago; the follow-up "然后返回" in the same conversation must not wait it out.
        now = 1_000
        assertTrue(tools.callTool(ANDROID_OBSERVE, "{}", NEXT_TURN).success)
        // Another conversation still waits for the new holder.
        assertEquals(DEVICE_BUSY, tools.callTool(ANDROID_OBSERVE, "{}", OTHER_RUN).json().text("code"))
    }

    @Test
    fun theIdleWaitStartsWhenTheHoldersCallEnds() = runTest {
        val tools = provider()
        tools.callTool(ANDROID_OBSERVE, "{}", CONTEXT)
        // A long action: it began at 0 and ends 15 s later, once the screen settled.
        device.onSettle = { now = 15_000 }
        tools.callTool(ANDROID_ACTION, """{"observationId":"obs_1","action":"key","key":"back"}""", CONTEXT)

        now = 16_000
        assertEquals(DEVICE_BUSY, tools.callTool(ANDROID_OBSERVE, "{}", OTHER_RUN).json().text("code"))
        now = 26_000
        assertTrue(tools.callTool(ANDROID_OBSERVE, "{}", OTHER_RUN).success)
    }

    @Test
    fun aHandleFromAnOlderReadIsStaleNotAnotherNode() = runTest {
        val tools = provider()
        tools.callTool(ANDROID_OBSERVE, "{}", CONTEXT)
        device.staleHandles = true

        val result = tools.callTool(ANDROID_ACTION, """{"observationId":"obs_1","action":"tap","elementId":"e3"}""", CONTEXT)

        assertFalse(result.success)
        assertEquals(STALE_OBSERVATION, result.json().text("code"))
        assertEquals("obs_2", result.json()["observation"]!!.jsonObject.text("observationId"))
        // No fallback tap at the stale element's coordinates, and the host still hears the gesture is over.
        assertFalse("stroke" in device.calls)
        assertEquals("after-gesture", presenter.events.last { "gesture" in it })
    }

    @Test
    fun closingPresenterCallsRunEvenWhenTheRunIsCancelled() = runTest {
        presenter.exitDelayMillis = 10
        device.strokeDelayMillis = 60_000
        val tools = provider()
        tools.callTool(ANDROID_OBSERVE, "{}", CONTEXT)

        val swipe = """{"observationId":"obs_1","action":"swipe","x":10,"y":10,"toX":200,"toY":10}"""
        val call = launch { tools.callTool(ANDROID_ACTION, swipe, CONTEXT) }
        delay(1_000)
        call.cancelAndJoin()

        assertEquals("after-gesture", presenter.events.last())
    }

    @Test
    fun aCancelledCaptureStillRestoresTheHost() = runTest {
        presenter.exitDelayMillis = 10
        device.captureDelayMillis = 60_000

        val call = launch { provider().callTool(ANDROID_OBSERVE, "{}", CONTEXT) }
        delay(1_000)
        call.cancelAndJoin()

        assertEquals(listOf("before-capture", "after-capture"), presenter.events.filter { "capture" in it })
    }

    @Test
    fun invalidActionsAreRefusedWithoutTouchingTheScreen() = runTest {
        val tools = provider()
        tools.callTool(ANDROID_OBSERVE, "{}", CONTEXT)

        val swipe = tools.callTool(ANDROID_ACTION, """{"observationId":"obs_1","action":"swipe","x":1,"y":1}""", CONTEXT)
        val missing = tools.callTool(ANDROID_ACTION, """{"observationId":"obs_1","action":"tap","elementId":"e9"}""", CONTEXT)
        val app = tools.callTool(ANDROID_ACTION, """{"observationId":"obs_1","action":"launch_app","app":"不存在"}""", CONTEXT)

        assertEquals(ARGUMENT_INVALID, swipe.json().text("code"))
        assertEquals(ELEMENT_NOT_FOUND, missing.json().text("code"))
        assertEquals(APP_NOT_FOUND, app.json().text("code"))
        val ambiguous = tools.callTool(ANDROID_ACTION, """{"observationId":"obs_1","action":"launch_app","app":"播放器"}""", CONTEXT)
        assertEquals(APP_AMBIGUOUS, ambiguous.json().text("code"))
        assertTrue(ambiguous.json().text("message")!!.contains("QQ音乐"))
        assertFalse(device.calls.any { it == "stroke" || it.startsWith("click") })
    }

    @Test
    fun scrollDragsAcrossTheListTowardTheContentWanted() = runTest {
        val tools = provider()
        tools.callTool(ANDROID_OBSERVE, "{}", CONTEXT)
        val nothing = tools.callTool(ANDROID_ACTION, """{"observationId":"obs_1","action":"scroll","direction":"down"}""", CONTEXT)
        assertEquals(NOT_SCROLLABLE, nothing.json().text("code"))

        device.extraNodes = listOf(device.list(PixelRect(200, 400, 1400, 1000)))
        val list = tools.callTool(ANDROID_OBSERVE, "{}", CONTEXT).observation()
        val down = tools.callTool(ANDROID_ACTION, """{"observationId":"${list.text("observationId")}","action":"scroll","direction":"down"}""", CONTEXT)
        assertTrue(down.resultJson, down.success)
        // Content below comes up: the finger moves up across the middle 60% of the list, slowly.
        assertEquals(Stroke(800, 880, 800, 520, 450), device.lastStroke)

        val next = down.observation().text("observationId")
        val up = tools.callTool(ANDROID_ACTION, """{"observationId":"$next","action":"scroll","direction":"up"}""", CONTEXT)
        assertEquals(Stroke(800, 520, 800, 880, 450), device.lastStroke)
        val latest = up.observation().text("observationId")
        val noDirection = tools.callTool(ANDROID_ACTION, """{"observationId":"$latest","action":"scroll"}""", CONTEXT)
        assertEquals(ARGUMENT_INVALID, noDirection.json().text("code"))
    }

    @Test
    fun launchAppReportsThePackageAndKeyPressesReachTheDevice() = runTest {
        val tools = provider()
        tools.callTool(ANDROID_OBSERVE, "{}", CONTEXT)
        val launched = tools.callTool(ANDROID_ACTION, """{"observationId":"obs_1","action":"launch_app","app":"音乐"}""", CONTEXT)
        val back = tools.callTool(ANDROID_ACTION, """{"observationId":"obs_2","action":"key","key":"back"}""", CONTEXT)

        assertEquals("com.example.music", launched.json()["target"]!!.jsonObject.text("package"))
        assertTrue(back.success)
        assertTrue("key:back" in device.calls)
    }

    @Test
    fun aSlowPresenterNeverHoldsTheActionPastItsBudget() = runTest {
        presenter.gestureDelayMillis = 10_000
        val tools = provider()
        tools.callTool(ANDROID_OBSERVE, "{}", CONTEXT)

        val result = tools.callTool(ANDROID_ACTION, """{"observationId":"obs_1","action":"tap","elementId":"e3"}""", CONTEXT)

        assertTrue(result.success)
        assertTrue(testScheduler.currentTime < 10_000)
    }

    @Test
    fun disconnectedServiceIsReported() = runTest {
        device.connected = false

        val result = provider().callTool(ANDROID_OBSERVE, "{}", CONTEXT)

        assertEquals(SERVICE_NOT_CONNECTED, result.json().text("code"))
        assertTrue(presenter.events.isEmpty())
    }

    @Test
    fun theDockAndStatusBarAreOffLimits() = runTest {
        device.systemAreas = listOf(PixelRect(0, 1100, 2560, 1216))
        val tools = provider()
        val observation = tools.callTool(ANDROID_OBSERVE, "{}", CONTEXT).observation()
        val dockY = (1150 * observation["image"]!!.jsonObject.int("width") / 2560.0).toInt()

        val tap = tools.callTool(ANDROID_ACTION, """{"observationId":"obs_1","action":"tap","x":100,"y":$dockY}""", CONTEXT)

        assertEquals(SYSTEM_AREA_BLOCKED, tap.json().text("code"))
        assertTrue(observation["blockedAreas"]!!.jsonArray.isNotEmpty())
        assertFalse("stroke" in device.calls)
    }

    @OptIn(ExperimentalAgentRuntimeApi::class)
    @Test
    fun bothToolsArePreloadedUnlessTheHostOptsOut() {
        val request = ToolPrefetchRequest("氛围灯在哪里设置", emptyList(), CONTEXT, maxTools = 24)

        assertEquals(listOf(ANDROID_OBSERVE, ANDROID_ACTION), provider().localToolNames(request))
        assertEquals(emptyList<String>(), provider(AndroidUseOptions(preloadTools = false)).localToolNames(request))
        assertFalse(provider().remotePrefetchEnabled)
    }

    @Test
    fun aPixelOnlyChangeStillCountsAsChanged() = runTest {
        val tools = provider()
        tools.callTool(ANDROID_OBSERVE, "{}", CONTEXT)
        device.signatureSeed = 1

        val result = tools.callTool(ANDROID_ACTION, """{"observationId":"obs_1","action":"tap","x":100,"y":50}""", CONTEXT)

        assertEquals("true", result.json().text("changed"))
        assertEquals(true, visuallyChanged(ByteArray(10), ByteArray(10) { if (it < 4) 40 else 0 }))
        assertEquals(false, visuallyChanged(ByteArray(10), ByteArray(10) { if (it < 1) 40 else 0 }))

        val cells = SIGNATURE_WIDTH * SIGNATURE_HEIGHT
        val iconFlip = ByteArray(cells).also { it[72 * SIGNATURE_WIDTH + 128] = 60 }
        assertEquals(false, visuallyChanged(ByteArray(cells), iconFlip))
        assertEquals(true, visuallyChangedNear(ByteArray(cells), iconFlip, PixelRect(1240, 690, 1320, 770), 2560, 1440))
        assertEquals(false, visuallyChangedNear(ByteArray(cells), iconFlip, PixelRect(0, 0, 80, 80), 2560, 1440))
    }
}

private class RecordingPresenter : AndroidUsePresenter {
    val events = mutableListOf<String>()
    var gestureDelayMillis = 0L

    /** Closing calls that suspend, to prove they still run while the call is being cancelled. */
    var exitDelayMillis = 0L

    override suspend fun onRunStarted(runId: String) {
        events += "onRunStarted"
    }

    override suspend fun beforeCapture() {
        events += "before-capture"
    }

    override suspend fun afterCapture() {
        delay(exitDelayMillis)
        events += "after-capture"
    }

    override suspend fun beforeGesture(preview: AndroidUseGesturePreview) {
        delay(gestureDelayMillis)
        events += "gesture ${preview.kind} ${preview.x},${preview.y}"
    }

    override suspend fun afterGesture() {
        delay(exitDelayMillis)
        events += "after-gesture"
    }
}

private class FakeDevice : AndroidUseDevice {
    var connected = true
    var clickResult = true
    var systemAreas: List<PixelRect> = emptyList()
    var signatureSeed = 0
    var lastStroke: Stroke? = null
    var staleHandles = false
    var strokeDelayMillis = 0L
    var captureDelayMillis = 0L
    var onSettle: () -> Unit = {}
    var extraNodes: List<ScreenNode> = emptyList()
    val calls = mutableListOf<String>()
    private val screen = ScreenState(
        displayId = 0,
        width = 2560,
        height = 1216,
        packageName = "com.example.music",
        nodes = listOf(
            node(1, text = "下一首", bounds = PixelRect(2200, 300, 2400, 400), clickable = true),
            node(2, text = "晴天", bounds = PixelRect(100, 300, 600, 380)),
            node(3, description = "播放", bounds = PixelRect(1900, 300, 2100, 400), inClickableContext = true),
            node(4, text = "助手", bounds = PixelRect(0, 0, 100, 100), clickable = true, packageName = "com.example.host"),
            node(5, text = "搜索", bounds = PixelRect(100, 100, 800, 180), editable = true),
        ),
    )

    override fun isConnected(): Boolean = connected
    override fun ownPackage(): String = "com.example.host"
    override suspend fun readScreen(displayId: Int): ScreenState =
        screen.copy(nodes = screen.nodes + extraNodes, systemAreas = systemAreas).also { calls += "read" }

    fun list(bounds: PixelRect) = node(6, bounds = bounds).copy(scrollable = true, className = "RecyclerView")

    override suspend fun capture(displayId: Int, maxPixels: Int): ScreenCapture {
        delay(captureDelayMillis)
        calls += "capture"
        val scale = screenshotScale(screen.width, screen.height, maxPixels)
        val signature = ByteArray(SIGNATURE_PIXELS) { (signatureSeed * SIGNATURE_STEP).toByte() }
        return ScreenCapture(byteArrayOf(1, 2, 3), MEDIA_TYPE_JPEG, (screen.width * scale).toInt(), (screen.height * scale).toInt(), signature)
    }

    override suspend fun click(handle: Int): Boolean = clickResult.also { checkFresh(handle); calls += "click:$handle" }

    override suspend fun stroke(displayId: Int, stroke: Stroke): Boolean {
        delay(strokeDelayMillis)
        lastStroke = stroke
        calls += "stroke"
        return true
    }

    override suspend fun setText(handle: Int?, text: String): Boolean = true.also {
        handle?.let(::checkFresh)
        calls += "text:$handle:$text"
    }
    override suspend fun pressKey(key: GlobalKey): Boolean = true.also { calls += "key:${key.wireName}" }
    override suspend fun setProgress(handle: Int, value: Float): Boolean = true.also {
        checkFresh(handle)
        calls += "progress:$handle:$value"
    }
    override suspend fun launchApp(app: String): LaunchOutcome {
        calls += "launch:$app"
        return when (app) {
            "音乐" -> LaunchOutcome.Launched("com.example.music")
            "播放器" -> LaunchOutcome.Ambiguous(listOf("QQ音乐", "酷我音乐"))
            else -> LaunchOutcome.NotFound
        }
    }
    override suspend fun awaitSettled(quietMillis: Long, maxMillis: Long) {
        calls += "settle"
        onSettle()
    }

    /** The real device resolves handles per display read; here every handle goes stale on demand. */
    private fun checkFresh(handle: Int) {
        if (staleHandles) throw StaleNodeException(handle)
    }

    private fun node(
        handle: Int,
        text: String = "",
        description: String = "",
        bounds: PixelRect,
        clickable: Boolean = false,
        inClickableContext: Boolean = false,
        editable: Boolean = false,
        packageName: String = "com.example.music",
    ) = ScreenNode(handle, text, description, "", packageName, bounds, clickable, inClickableContext, editable, scrollable = false)
}

private fun ToolCallResult.json(): JsonObject = Json.parseToJsonElement(resultJson).jsonObject
private fun ToolCallResult.observation(): JsonObject = json()["observation"]!!.jsonObject
private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content
private fun JsonObject.int(key: String): Int = this[key]!!.jsonPrimitive.int
private fun JsonObject.elements(): List<JsonObject> = this["elements"]!!.jsonArray.map { it.jsonObject }
private fun JsonObject.bounds(): List<Int> = this["bounds"]!!.jsonArray.map { it.jsonPrimitive.int }

private val CONTEXT = ToolCallContext(sessionId = "s", runId = "run-1", requestId = "q")
private val OTHER_RUN = ToolCallContext(sessionId = "s2", runId = "run-2", requestId = "q2")
private val NEXT_TURN = ToolCallContext(sessionId = "s", runId = "run-3", requestId = "q3")

private const val SIGNATURE_PIXELS = 16
private const val SIGNATURE_STEP = 40
