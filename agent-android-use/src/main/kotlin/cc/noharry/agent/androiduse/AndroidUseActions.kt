package cc.noharry.agent.androiduse

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal enum class AndroidUseAction(val wireName: String) {
    TAP("tap"),
    LONG_PRESS("long_press"),
    SWIPE("swipe"),
    SCROLL("scroll"),
    INPUT_TEXT("input_text"),
    SET_VALUE("set_value"),
    KEY("key"),
    LAUNCH_APP("launch_app"),
    WAIT("wait"),
    ;

    /** Actions whose result may only show after the app round-trips to a service (a play/pause icon). */
    fun mayRedrawLate(): Boolean = this == TAP || this == LONG_PRESS || this == SET_VALUE

    companion object {
        fun fromWire(value: String?): AndroidUseAction? = entries.firstOrNull { it.wireName == value }
    }
}

internal sealed interface ActionOutcome {
    /**
     * The action ran; [ok] is false when the device refused it. [target] says where it landed, and [focus] is the
     * display area to watch for a change.
     */
    data class Done(val ok: Boolean, val target: JsonObject? = null, val focus: PixelRect? = null) : ActionOutcome

    /** The arguments cannot describe an action on this observation; nothing ran. */
    data class Refused(val code: String, val message: String) : ActionOutcome
}

/** Where a press lands: display pixels for the device, model coordinates for the result. */
private class PressTarget(val element: ObservedElement?, val displayX: Int, val displayY: Int, val modelX: Int, val modelY: Int) {
    fun json(): JsonObject = buildJsonObject {
        element?.let { put("elementId", it.id) }
        put("x", modelX)
        put("y", modelY)
    }
}

/** Runs one validated action against the device, showing it through the presenter first. */
internal class AndroidUseActionRunner(
    private val device: AndroidUseDevice,
    private val presenter: AndroidUsePresenter,
    private val presenterTimeoutMillis: Long = PRESENTER_TIMEOUT_MILLIS,
) {
    var presenterFailures: Int = 0
        private set

    suspend fun perform(action: AndroidUseAction, args: JsonObject, observation: Observation): ActionOutcome =
        when (action) {
            AndroidUseAction.TAP, AndroidUseAction.LONG_PRESS -> press(action, args, observation)
            AndroidUseAction.SWIPE -> swipe(args, observation)
            AndroidUseAction.SCROLL -> scroll(args, observation)
            AndroidUseAction.INPUT_TEXT -> inputText(args, observation)
            AndroidUseAction.SET_VALUE -> setValue(args, observation)
            AndroidUseAction.KEY -> key(args)
            AndroidUseAction.LAUNCH_APP -> launch(args)
            AndroidUseAction.WAIT -> wait(args)
        }

    /** Runs a presenter callback within [timeoutMillis]; a failing host never stops the action. */
    suspend fun bounded(timeoutMillis: Long = presenterTimeoutMillis, block: suspend AndroidUsePresenter.() -> Unit) {
        try {
            withTimeoutOrNull(timeoutMillis) { presenter.block() }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            presenterFailures += 1
        }
    }

    /**
     * The closing presenter call ([AndroidUsePresenter.afterCapture], [AndroidUsePresenter.afterGesture]): bounded
     * like [bounded], and run even when the run is being cancelled, so the host never stays hidden or mid-gesture.
     */
    suspend fun boundedOnExit(block: suspend AndroidUsePresenter.() -> Unit) {
        withContext(NonCancellable) { bounded(block = block) }
    }

    /** Shows [preview] (if any), runs [block], then always closes the gesture on the presenter, even on failure. */
    private suspend fun <T> gesture(preview: AndroidUseGesturePreview?, block: suspend () -> T): T =
        try {
            preview?.let { bounded { beforeGesture(it) } }
            block()
        } finally {
            boundedOnExit { afterGesture() }
        }

    private suspend fun press(action: AndroidUseAction, args: JsonObject, observation: Observation): ActionOutcome {
        val target = pressTarget(args, observation) ?: return refusedTarget(args, observation)
        if (observation.state.inSystemArea(target.displayX, target.displayY)) return systemAreaRefused()
        val displayId = observation.state.displayId
        val kind = if (action == AndroidUseAction.TAP) AndroidUseGesturePreview.Kind.TAP else AndroidUseGesturePreview.Kind.LONG_PRESS
        val ok = gesture(AndroidUseGesturePreview(kind, displayId, target.displayX, target.displayY)) {
            if (action == AndroidUseAction.TAP) {
                target.element?.let { device.click(it.node.handle) } == true ||
                    device.stroke(displayId, tapStroke(target.displayX, target.displayY, TAP_MILLIS))
            } else {
                device.stroke(displayId, tapStroke(target.displayX, target.displayY, LONG_PRESS_MILLIS))
            }
        }
        return ActionOutcome.Done(ok, target.json(), around(target.displayX, target.displayY))
    }

    private suspend fun swipe(args: JsonObject, observation: Observation): ActionOutcome {
        val x = args.int("x")
        val y = args.int("y")
        val toX = args.int("toX")
        val toY = args.int("toY")
        if (x == null || y == null || toX == null || toY == null) {
            return ActionOutcome.Refused(ARGUMENT_INVALID, "swipe needs x, y, toX and toY.")
        }
        val (fromDisplayX, fromDisplayY) = observation.toDisplay(x, y)
        val (toDisplayX, toDisplayY) = observation.toDisplay(toX, toY)
        if (observation.state.inSystemArea(fromDisplayX, fromDisplayY) || observation.state.inSystemArea(toDisplayX, toDisplayY)) {
            return systemAreaRefused()
        }
        val duration = (args.long("durationMs") ?: DEFAULT_SWIPE_MILLIS).coerceIn(MIN_SWIPE_MILLIS, MAX_SWIPE_MILLIS)
        val displayId = observation.state.displayId
        val preview = AndroidUseGesturePreview(AndroidUseGesturePreview.Kind.SWIPE, displayId, fromDisplayX, fromDisplayY, toDisplayX, toDisplayY, duration)
        val ok = gesture(preview) { device.stroke(displayId, Stroke(fromDisplayX, fromDisplayY, toDisplayX, toDisplayY, duration)) }
        val focus = PixelRect(minOf(fromDisplayX, toDisplayX), minOf(fromDisplayY, toDisplayY), maxOf(fromDisplayX, toDisplayX), maxOf(fromDisplayY, toDisplayY))
        return ActionOutcome.Done(ok, buildJsonObject { put("x", x); put("y", y); put("toX", toX); put("toY", toY) }, focus)
    }

    /**
     * Scrolls a list toward [ScrollDirection] (where the wanted content is: down shows what is below) with one
     * unhurried drag across the middle of its bounds. The model names a direction, never a start point or a sign:
     * on the bench it swiped the wrong way, on the picture beside the list, or in steps too short to move it.
     * Without elementId, the largest scrollable element on screen.
     */
    private suspend fun scroll(args: JsonObject, observation: Observation): ActionOutcome {
        val direction = ScrollDirection.fromWire(args.string("direction"))
            ?: return ActionOutcome.Refused(ARGUMENT_INVALID, "scroll needs direction up, down, left or right: where the content you want is.")
        val elementId = args.string("elementId")
        val element = if (elementId != null) {
            observation.element(elementId) ?: return elementNotFound(elementId, observation)
        } else {
            observation.elements.filter { it.node.scrollable }.maxByOrNull { it.node.bounds.width.toLong() * it.node.bounds.height }
                ?: return ActionOutcome.Refused(NOT_SCROLLABLE, "Nothing on screen reports scrolling; drag with swipe instead.")
        }
        val bounds = element.node.bounds
        val stroke = scrollStroke(bounds, direction)
        if (observation.state.inSystemArea(stroke.fromX, stroke.fromY) || observation.state.inSystemArea(stroke.toX, stroke.toY)) {
            return systemAreaRefused()
        }
        val displayId = observation.state.displayId
        val preview = AndroidUseGesturePreview(AndroidUseGesturePreview.Kind.SWIPE, displayId, stroke.fromX, stroke.fromY, stroke.toX, stroke.toY, stroke.durationMs)
        val ok = gesture(preview) { device.stroke(displayId, stroke) }
        return ActionOutcome.Done(ok, buildJsonObject { put("elementId", element.id); put("direction", direction.wireName) }, bounds)
    }

    private suspend fun inputText(args: JsonObject, observation: Observation): ActionOutcome {
        val text = args.string("text") ?: return ActionOutcome.Refused(ARGUMENT_INVALID, "input_text needs text.")
        val elementId = args.string("elementId")
        val element = observation.element(elementId)
        if (elementId != null && element == null) return elementNotFound(elementId, observation)
        val preview = element?.let { target ->
            val bounds = target.node.bounds
            AndroidUseGesturePreview(AndroidUseGesturePreview.Kind.TYPE, observation.state.displayId, bounds.centerX, bounds.centerY)
        }
        val ok = gesture(preview) { device.setText(element?.node?.handle, text) }
        return ActionOutcome.Done(ok, buildJsonObject { element?.let { put("elementId", it.id) } })
    }

    private suspend fun setValue(args: JsonObject, observation: Observation): ActionOutcome {
        val elementId = args.string("elementId") ?: return ActionOutcome.Refused(ARGUMENT_INVALID, "set_value needs elementId and value.")
        val element = observation.element(elementId) ?: return elementNotFound(elementId, observation)
        val range = element.node.range
            ?: return ActionOutcome.Refused(NOT_ADJUSTABLE, "$elementId reports no range; drag along its bounds with swipe instead.")
        val value = args.double("value")?.toFloat()?.coerceIn(range.min, range.max)
            ?: return ActionOutcome.Refused(ARGUMENT_INVALID, "set_value needs a numeric value.")
        val bounds = element.node.bounds
        val fraction = (value - range.min) / (range.max - range.min).coerceAtLeast(MIN_RANGE_SPAN)
        val x = bounds.left + (fraction * bounds.width).toInt()
        val preview = AndroidUseGesturePreview(AndroidUseGesturePreview.Kind.TAP, observation.state.displayId, x, bounds.centerY)
        val ok = gesture(preview) { device.setProgress(element.node.handle, value) }
        return ActionOutcome.Done(ok, buildJsonObject { put("elementId", element.id); put("value", value) }, bounds)
    }

    private suspend fun key(args: JsonObject): ActionOutcome {
        val key = GlobalKey.fromWire(args.string("key"))
            ?: return ActionOutcome.Refused(ARGUMENT_INVALID, "key must be back, home, recents or notifications.")
        return ActionOutcome.Done(device.pressKey(key), buildJsonObject { put("key", key.wireName) })
    }

    private suspend fun launch(args: JsonObject): ActionOutcome {
        val app = args.string("app")?.trim().orEmpty()
        if (app.isEmpty()) return ActionOutcome.Refused(ARGUMENT_INVALID, "launch_app needs app (label or package).")
        return when (val outcome = device.launchApp(app)) {
            is LaunchOutcome.Launched -> ActionOutcome.Done(true, buildJsonObject { put("package", outcome.packageName) })
            is LaunchOutcome.Ambiguous -> ActionOutcome.Refused(
                APP_AMBIGUOUS,
                "Several apps match \"$app\": ${outcome.labels.joinToString("、")}. Call launch_app again with one exact name.",
            )
            LaunchOutcome.NotFound -> ActionOutcome.Refused(APP_NOT_FOUND, "No launchable app matches \"$app\".")
        }
    }

    private suspend fun wait(args: JsonObject): ActionOutcome {
        delay((args.long("durationMs") ?: DEFAULT_WAIT_MILLIS).coerceIn(0, MAX_WAIT_MILLIS))
        return ActionOutcome.Done(true)
    }

    private fun pressTarget(args: JsonObject, observation: Observation): PressTarget? {
        observation.element(args.string("elementId"))?.let { element ->
            val bounds = element.node.bounds
            val (modelX, modelY) = observation.toModel(bounds.centerX, bounds.centerY)
            return PressTarget(element, bounds.centerX, bounds.centerY, modelX, modelY)
        }
        if (args.string("elementId") != null) return null
        val x = args.int("x") ?: return null
        val y = args.int("y") ?: return null
        val (displayX, displayY) = observation.toDisplay(x, y)
        return PressTarget(null, displayX, displayY, x, y)
    }

    private fun refusedTarget(args: JsonObject, observation: Observation): ActionOutcome.Refused =
        args.string("elementId")?.let { elementNotFound(it, observation) }
            ?: ActionOutcome.Refused(ARGUMENT_INVALID, "tap and long_press need elementId or x and y.")

    private fun elementNotFound(elementId: String, observation: Observation): ActionOutcome.Refused =
        ActionOutcome.Refused(ELEMENT_NOT_FOUND, "No element $elementId in ${observation.id}.")

    private fun tapStroke(x: Int, y: Int, durationMs: Long): Stroke = Stroke(x, y, x, y, durationMs)

    private fun around(x: Int, y: Int): PixelRect = PixelRect(x - FOCUS_RADIUS, y - FOCUS_RADIUS, x + FOCUS_RADIUS, y + FOCUS_RADIUS)

    /** The status bar and dock hold system controls; Android Use never presses them. */
    private fun systemAreaRefused(): ActionOutcome.Refused =
        ActionOutcome.Refused(
            SYSTEM_AREA_BLOCKED,
            "That point is in the status bar or dock (system controls). Use launch_app to open apps and key for back/home.",
        )
}

private const val TAP_MILLIS = 80L
private const val FOCUS_RADIUS = 40
private const val MIN_RANGE_SPAN = 1e-6f
private const val LONG_PRESS_MILLIS = 800L
private const val DEFAULT_SWIPE_MILLIS = 300L
/** A scroll drags across this share of the list, slowly enough not to fling past what it brings into view. */
private const val SCROLL_SPAN = 0.6
private const val SCROLL_MILLIS = 450L
private const val MIN_SWIPE_MILLIS = 100L
private const val MAX_SWIPE_MILLIS = 3_000L
private const val DEFAULT_WAIT_MILLIS = 1_000L
private const val MAX_WAIT_MILLIS = 5_000L

/** Where the content the model wants is; the finger moves the other way. */
internal enum class ScrollDirection(val wireName: String) {
    UP("up"),
    DOWN("down"),
    LEFT("left"),
    RIGHT("right"),
    ;

    companion object {
        fun fromWire(value: String?): ScrollDirection? = entries.firstOrNull { it.wireName == value }
    }
}

/** The drag that brings [direction]'s content into [bounds]: centred, across [SCROLL_SPAN] of the list. */
internal fun scrollStroke(bounds: PixelRect, direction: ScrollDirection): Stroke {
    val halfHeight = (bounds.height * SCROLL_SPAN / 2).toInt()
    val halfWidth = (bounds.width * SCROLL_SPAN / 2).toInt()
    val x = bounds.centerX
    val y = bounds.centerY
    return when (direction) {
        ScrollDirection.DOWN -> Stroke(x, y + halfHeight, x, y - halfHeight, SCROLL_MILLIS)
        ScrollDirection.UP -> Stroke(x, y - halfHeight, x, y + halfHeight, SCROLL_MILLIS)
        ScrollDirection.RIGHT -> Stroke(x + halfWidth, y, x - halfWidth, y, SCROLL_MILLIS)
        ScrollDirection.LEFT -> Stroke(x - halfWidth, y, x + halfWidth, y, SCROLL_MILLIS)
    }
}
