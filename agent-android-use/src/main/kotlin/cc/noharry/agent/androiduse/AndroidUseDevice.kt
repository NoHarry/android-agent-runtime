package cc.noharry.agent.androiduse

import kotlin.math.roundToInt

/**
 * What the provider needs from the device. The accessibility service implements it; tests use a fake,
 * so observation, validation and coordinate rules stay plain JVM code.
 *
 * Node actions throw [StaleNodeException] when a handle is not from its display's latest [readScreen]; the
 * provider then answers STALE_OBSERVATION instead of acting on whatever node now sits behind that number.
 */
internal interface AndroidUseDevice {
    fun isConnected(): Boolean

    /** The host's own package; its windows (the assistant overlay) never become elements. */
    fun ownPackage(): String

    suspend fun readScreen(displayId: Int): ScreenState

    /** A screenshot scaled to at most [maxPixels], or null when this device cannot take one. */
    suspend fun capture(displayId: Int, maxPixels: Int): ScreenCapture?

    /** Clicks the live node behind [handle] or its nearest clickable ancestor; false lets the caller tap. */
    suspend fun click(handle: Int): Boolean

    suspend fun stroke(displayId: Int, stroke: Stroke): Boolean

    /** Sets text on [handle], or on the focused editable node when [handle] is null. */
    suspend fun setText(handle: Int?, text: String): Boolean

    /** Sets a slider's value through accessibility; false when the node refuses it. */
    suspend fun setProgress(handle: Int, value: Float): Boolean

    suspend fun pressKey(key: GlobalKey): Boolean

    /** Starts an app by launcher label or package; several matching labels start nothing. */
    suspend fun launchApp(app: String): LaunchOutcome

    /** Returns once no window event arrived for [quietMillis], or after [maxMillis]. */
    suspend fun awaitSettled(quietMillis: Long, maxMillis: Long)
}

/** A rectangle in display pixels. */
internal data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun isEmpty(): Boolean = right <= left || bottom <= top

    fun contains(x: Int, y: Int): Boolean = x in left until right && y in top until bottom

    fun scaled(scale: Double): PixelRect =
        PixelRect((left * scale).roundToInt(), (top * scale).roundToInt(), (right * scale).roundToInt(), (bottom * scale).roundToInt())
}

/** One visible accessibility node, flattened. [handle] names the live node until its display is read again. */
internal data class ScreenNode(
    val handle: Int,
    val text: String,
    val description: String,
    val viewId: String,
    val packageName: String,
    val bounds: PixelRect,
    val clickable: Boolean,
    val inClickableContext: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    /** Short class name, such as SeekBar. */
    val className: String = "",
    /** Present on sliders and progress bars that report their value. */
    val range: NodeRange? = null,
)

internal data class NodeRange(val min: Float, val max: Float, val current: Float)

/**
 * A status bar or dock: a system window spanning nearly the whole width (or height) along a display edge. The
 * launcher's card rows are system windows too but sit inside the screen, so they stay usable.
 */
internal fun isSystemBar(area: PixelRect, displayWidth: Int, displayHeight: Int): Boolean {
    val horizontal = area.width >= displayWidth * BAR_SPAN && area.height <= displayHeight * BAR_THICKNESS &&
        (area.top <= 0 || area.bottom >= displayHeight)
    val vertical = area.height >= displayHeight * BAR_SPAN && area.width <= displayWidth * BAR_THICKNESS &&
        (area.left <= 0 || area.right >= displayWidth)
    return horizontal || vertical
}

private const val BAR_SPAN = 0.9
private const val BAR_THICKNESS = 0.2

internal data class ScreenState(
    val displayId: Int,
    val width: Int,
    val height: Int,
    val packageName: String,
    val nodes: List<ScreenNode>,
    /** Status bar, dock and other system windows: their system controls are off limits to Android Use. */
    val systemAreas: List<PixelRect> = emptyList(),
) {
    fun inSystemArea(x: Int, y: Int): Boolean = systemAreas.any { it.contains(x, y) }

    /** Changes when the visible package or any node's text, place or role changes. */
    fun fingerprint(): Int = listOf(packageName, nodes.map { listOf(it.text, it.description, it.bounds, it.editable) }).hashCode()
}

/**
 * A screenshot for the model, plus a small grayscale [signature] of the same frame so visual-only changes (a
 * slider's fill, a play/pause icon) count as changes even when no node text or bounds moved.
 */
internal class ScreenCapture(
    val bytes: ByteArray,
    val mediaType: String,
    val width: Int,
    val height: Int,
    val signature: ByteArray? = null,
)

/** True when two signatures differ in at least [VISUAL_CHANGE_CELLS] cells; null when they cannot be compared. */
internal fun visuallyChanged(before: ByteArray?, after: ByteArray?): Boolean? {
    if (before == null || after == null || before.size != after.size) return null
    return before.indices.count { cellDiff(before, after, it) > VISUAL_CHANGE_LEVELS } >= VISUAL_CHANGE_CELLS
}

/**
 * True when any cell around [focus] (display pixels) changed: a play/pause icon flipping under the finger is a
 * few cells, too small for the whole-screen test. Null when the signatures cannot be compared.
 */
internal fun visuallyChangedNear(before: ByteArray?, after: ByteArray?, focus: PixelRect, displayWidth: Int, displayHeight: Int): Boolean? {
    if (before == null || after == null || before.size != SIGNATURE_WIDTH * SIGNATURE_HEIGHT || after.size != before.size) return null
    if (displayWidth <= 0 || displayHeight <= 0) return null
    val columns = (focus.left * SIGNATURE_WIDTH / displayWidth).coerceIn(0, SIGNATURE_WIDTH - 1)..
        (focus.right * SIGNATURE_WIDTH / displayWidth).coerceIn(0, SIGNATURE_WIDTH - 1)
    val rows = (focus.top * SIGNATURE_HEIGHT / displayHeight).coerceIn(0, SIGNATURE_HEIGHT - 1)..
        (focus.bottom * SIGNATURE_HEIGHT / displayHeight).coerceIn(0, SIGNATURE_HEIGHT - 1)
    return rows.any { row -> columns.any { column -> cellDiff(before, after, row * SIGNATURE_WIDTH + column) > FOCUS_CHANGE_LEVELS } }
}

private fun cellDiff(before: ByteArray, after: ByteArray, index: Int): Int =
    kotlin.math.abs((before[index].toInt() and BYTE_MASK) - (after[index].toInt() and BYTE_MASK))

internal const val SIGNATURE_WIDTH = 256
internal const val SIGNATURE_HEIGHT = 144
private const val BYTE_MASK = 0xFF
private const val VISUAL_CHANGE_LEVELS = 16
private const val VISUAL_CHANGE_CELLS = 4
private const val FOCUS_CHANGE_LEVELS = 16

internal sealed interface LaunchOutcome {
    data class Launched(val packageName: String) : LaunchOutcome

    /** More than one launcher label matched, such as 音乐 for QQ音乐 and 酷我音乐. */
    data class Ambiguous(val labels: List<String>) : LaunchOutcome

    data object NotFound : LaunchOutcome
}

/** A gesture from one point to another in display pixels; a tap starts and ends in the same place. */
internal data class Stroke(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int, val durationMs: Long)

internal enum class GlobalKey(val wireName: String) {
    BACK("back"),
    HOME("home"),
    RECENTS("recents"),
    NOTIFICATIONS("notifications"),
    ;

    companion object {
        fun fromWire(value: String?): GlobalKey? = entries.firstOrNull { it.wireName == value }
    }
}
