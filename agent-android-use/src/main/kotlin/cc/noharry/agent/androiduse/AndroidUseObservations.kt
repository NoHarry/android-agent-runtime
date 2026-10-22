package cc.noharry.agent.androiduse

import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** An element the model may name, with its node. [id] is stable only within one observation. */
internal data class ObservedElement(val id: String, val node: ScreenNode)

/**
 * What the model saw. [scale] maps display pixels to the coordinates the model uses: screenshot pixels
 * when a screenshot is attached, display pixels (1.0) otherwise.
 */
internal class Observation(
    val id: String,
    val state: ScreenState,
    val capture: ScreenCapture?,
    val scale: Double,
    val elements: List<ObservedElement>,
    val elementsTruncated: Boolean,
    val screenshotNote: String,
) {
    fun element(id: String?): ObservedElement? = id?.let { wanted -> elements.firstOrNull { it.id == wanted } }

    /** The caption a slider sits under (中控屏, 仪表), so two sliders both named seekbar stay apart. */
    fun labelFor(node: ScreenNode): String = sliderLabel(node, state.nodes)

    /** Model coordinates to display pixels, clamped to the display. */
    fun toDisplay(x: Int, y: Int): Pair<Int, Int> =
        (x / scale).roundToInt().coerceIn(0, state.width - 1) to (y / scale).roundToInt().coerceIn(0, state.height - 1)

    /** Display pixels to model coordinates. */
    fun toModel(x: Int, y: Int): Pair<Int, Int> = (x * scale).roundToInt() to (y * scale).roundToInt()
}

/** Latest observation per display. Actions must name the latest one; anything older is stale. */
internal class ObservationBook(private val maxElements: Int) {
    private var counter = 0
    private val latest = mutableMapOf<Int, Observation>()

    fun record(state: ScreenState, capture: ScreenCapture?, ownPackage: String, screenshotNote: String): Observation {
        val selected = selectElements(state.nodes, ownPackage, state.systemAreas)
        val scale = capture?.let { it.width.toDouble() / state.width.coerceAtLeast(1) } ?: 1.0
        counter += 1
        val observation = Observation(
            id = "$OBSERVATION_ID_PREFIX$counter",
            state = state,
            capture = capture,
            scale = scale,
            elements = elementIds(selected.take(maxElements)),
            elementsTruncated = selected.size > maxElements,
            screenshotNote = screenshotNote,
        )
        latest[state.displayId] = observation
        return observation
    }

    fun latest(displayId: Int): Observation? = latest[displayId]
}

/**
 * Nodes worth naming, the 可见即可说 way: labelled nodes that can be clicked (or sit inside something
 * clickable), editable fields and scrollable containers. The host's own windows never count.
 */
internal fun selectElements(nodes: List<ScreenNode>, ownPackage: String, systemAreas: List<PixelRect> = emptyList()): List<ScreenNode> =
    nodes.asSequence()
        .filter { it.packageName != ownPackage && !it.bounds.isEmpty() }
        .filter { node -> systemAreas.none { it.contains(node.bounds.centerX, node.bounds.centerY) } }
        .filter { node ->
            val labelled = node.text.isNotBlank() || node.description.isNotBlank()
            (labelled && (node.clickable || node.inClickableContext)) || node.editable || node.scrollable ||
                (node.clickable && node.viewId.isNotBlank()) || node.isSlider()
        }
        .distinctBy { listOf(it.bounds, it.text, it.description) }
        .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        .toList()

/**
 * Element IDs that survive from one observation to the next: a node with a view ID unique on screen is named
 * by it (`iv_play`, `next_image`), so a model that remembers an ID still hits the same button after the list
 * shifts. Other nodes get positional `e<n>` IDs, valid only within their observation.
 */
internal fun elementIds(nodes: List<ScreenNode>): List<ObservedElement> {
    val names = nodes.map { it.viewId.substringAfter(VIEW_ID_SEPARATOR).filter { c -> c.isLetterOrDigit() || c == '_' } }
    val counts = names.filter { it.isNotEmpty() }.groupingBy { it }.eachCount()
    return nodes.mapIndexed { index, node ->
        val name = names[index]
        val id = if (name.isNotEmpty() && counts[name] == 1 && !POSITIONAL_ID.matches(name)) name else "e${index + 1}"
        ObservedElement(id, node)
    }
}

/**
 * A readable description. Some car apps put a JSON protocol for 可见即可说 in contentDescription; only its
 * `action` label ("收藏", "[打开]") means anything to the model, so the rest is dropped.
 */
internal fun readableDescription(description: String): String {
    val trimmed = description.trim()
    if (!trimmed.startsWith("{")) return trimmed
    return ACTION_FIELD.find(trimmed)?.groupValues?.get(1).orEmpty()
}

internal fun ScreenNode.isSlider(): Boolean = range != null || SLIDER_CLASSES.any { className.contains(it) }

/** The nearest text above [slider] that overlaps it horizontally, within a few slider heights. */
internal fun sliderLabel(slider: ScreenNode, nodes: List<ScreenNode>): String =
    nodes.asSequence()
        .filter { it.text.isNotBlank() && it.bounds.bottom <= slider.bounds.top + LABEL_OVERLAP_PX }
        .filter { slider.bounds.top - it.bounds.bottom <= slider.bounds.height * LABEL_REACH + LABEL_OVERLAP_PX }
        .filter { it.bounds.left < slider.bounds.right && it.bounds.right > slider.bounds.left }
        .minByOrNull { slider.bounds.top - it.bounds.bottom }
        ?.text.orEmpty()

/** Scale that fits a [width]×[height] display into [maxPixels], never enlarging it. */
internal fun screenshotScale(width: Int, height: Int, maxPixels: Int): Double {
    val pixels = width.toLong() * height.toLong()
    if (pixels <= 0) return 1.0
    return min(1.0, sqrt(maxPixels.toDouble() / pixels))
}

internal const val OBSERVATION_ID_PREFIX = "obs_"
internal const val VIEW_ID_SEPARATOR = ":id/"
private val SLIDER_CLASSES = listOf("SeekBar", "Slider")
private const val LABEL_REACH = 4
private const val LABEL_OVERLAP_PX = 4
private val POSITIONAL_ID = Regex("""^e\d+$""")
private val ACTION_FIELD = Regex(""""action"\s*:\s*"([^"]*)"""")
