package cc.noharry.agent.appserver.protocol.view
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToLong
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/**
 * One control of a reply's panel: a slider, a switch or a choice, each bound to checked tool calls. A reply may hold
 * a few; one confirm sends the changed ones together ("媒体音量设为22档，座椅加热打开", "Volume to 22, Seat heating on")
 * as the next user turn, and [controlPicks] reads that, or the user's "音量调到20" or "set the volume to 20", back into
 * checked calls.
 */
@Serializable
sealed interface Control {
    val label: String
}

/** A value to set within a range, bound to one tool and the numeric argument it varies. */
@Serializable
@SerialName("slider")
data class SliderOffer(
    override val label: String,
    /** The tool and its fixed arguments; [param] is filled in at the chosen value. */
    val call: BoundCall,
    val param: String,
    val min: Double,
    val max: Double,
    val step: Double,
    val value: Double,
    val unit: String,
    val integer: Boolean,
) : Control {
    fun format(number: Double): String =
        if (integer || abs(number - round(number)) < 1e-9) number.roundToLong().toString() else (round(number * 10) / 10).toString()

    /** One slider's part of the confirmed reply, as the user would say it. */
    fun replyText(number: Double, words: ReplyWords): String = words.setTo(label, format(snap(number) ?: number) + unit)

    /** On the step grid and in range, or null outside it. */
    fun snap(number: Double): Double? {
        if (number < min - EPSILON || number > max + EPSILON) return null
        val steps = round((number - min) / step)
        return (min + steps * step).coerceIn(min, max)
    }

    /** "设为30%", "调到 30", "to 30" or a bare "30": the value said for this slider, on its grid, or null. */
    fun valueSaid(text: String): Double? {
        // The unit the reply was built with ("24℃") is dropped first, whatever unit the model gave the slider.
        val said = text.trim().replace(SLIDER_NOISE, "").let { if (unit.isNotEmpty()) it.removeSuffix(unit) else it }
        val match = SLIDER_VALUE.matchEntire(said) ?: return null
        return match.groupValues[1].toDoubleOrNull()?.let(::snap)
    }

    fun callAt(number: Double): BoundCall {
        val fixed = parseJsonObject(call.argumentsJson) ?: JsonObject(emptyMap())
        val chosen = if (integer) JsonPrimitive(number.roundToLong()) else JsonPrimitive(number)
        return call.copy(argumentsJson = JsonObject(fixed + (param to chosen)).toString())
    }
}

/** On or off, each bound to its own checked call (one tool with a state argument, or two tools). */
@Serializable
@SerialName("switch")
data class SwitchOffer(
    override val label: String,
    val on: BoundCall,
    val off: BoundCall,
    /** The state now, as read before offering. */
    val value: Boolean,
) : Control {
    fun replyText(state: Boolean, words: ReplyWords): String = words.switched(label, state)

    fun callAt(state: Boolean): BoundCall = if (state) on else off
}

/** One option of a choice: what it shows and the checked call it stands for. */
@Serializable
data class ChoiceOption(val label: String, val call: BoundCall)

/**
 * A mode or level from a few values ("自动 / 制冷 / 制热"), or with [multiple] several actions to run together
 * ("主驾 / 副驾" seat heating). [value] holds the indices chosen now: the current one for a single choice, none for
 * multiple.
 */
@Serializable
@SerialName("choice")
data class ChoiceOffer(
    override val label: String,
    val options: List<ChoiceOption>,
    val value: List<Int>,
    val multiple: Boolean,
) : Control {
    fun replyText(chosen: List<Int>, words: ReplyWords): String {
        val names = chosen.sorted().mapNotNull { options.getOrNull(it)?.label }
        return if (multiple) words.chose(label, names) else words.setTo(label, names.firstOrNull().orEmpty())
    }

    fun callsAt(chosen: List<Int>): List<BoundCall> = chosen.sorted().mapNotNull { options.getOrNull(it)?.call }

    /**
     * The option a word names exactly ("制冷", "改成制冷", "制冷模式", "to cool"), or null. Only a whole label counts, like
     * a pick by label: "帮我找台自动售货机" must not set the mode 自动. A label that starts like a verb ("Tomato") counts
     * as said.
     */
    fun optionSaid(said: String): Int? {
        val key = said.offerKey()
        return listOf(key, key.replace(CHOICE_VERB_LEADING, "")).map { it.removeOptionSuffix() }.filter { it.isNotEmpty() }.firstNotNullOfOrNull { word ->
            options.indexOfFirst { it.label.offerKey().removeOptionSuffix() == word }.takeIf { it >= 0 }
        }
    }
}

/** A control's value: a slider's number, a switch's state, a choice's chosen indices. */
fun Control.initialValue(): Any = when (this) {
    is SliderOffer -> value
    is SwitchOffer -> value
    is ChoiceOffer -> value
}

/** What [value] says for this control, as the user would say it. */
fun Control.replyText(value: Any, words: ReplyWords): String = when (this) {
    is SliderOffer -> replyText(value as Double, words)
    is SwitchOffer -> replyText(value as Boolean, words)
    is ChoiceOffer -> replyText(value.indices(), words)
}

/** The checked calls that set [value]; none for a multiple choice with nothing chosen. */
fun Control.callsAt(value: Any): List<BoundCall> = when (this) {
    is SliderOffer -> listOf(callAt(snap(value as Double) ?: value))
    is SwitchOffer -> listOf(callAt(value as Boolean))
    is ChoiceOffer -> callsAt(value.indices())
}

/** How a chosen value reads in the next turn's frame: "「空调温度」24℃", "「座椅加热」打开", "\"Seat heating\" on". */
fun Control.valueText(value: Any, words: ReplyWords): String = words.value(
    label,
    when (this) {
        is SliderOffer -> "${format(value as Double)}$unit"
        is SwitchOffer -> words.state(value as Boolean)
        is ChoiceOffer -> value.indices().sorted().mapNotNull { options.getOrNull(it)?.label }.joinToString(words.list)
    },
)

@Suppress("UNCHECKED_CAST")
private fun Any.indices(): List<Int> = this as List<Int>

/** "制冷模式", "2档" and "cool mode" name the options 制冷, 2 and cool. */
private fun String.removeOptionSuffix(): String = removeSuffix("模式").removeSuffix("档").removeSuffix("挡").removeSuffix("mode")

/**
 * What one confirm of [controls] at [values] sends: the controls the user changed, or all of them when nothing
 * changed (a bare confirm sets what is shown). A multiple choice with nothing chosen says nothing.
 */
fun panelReply(controls: List<Control>, values: List<Any>, words: ReplyWords): String {
    val pairs = controls.zip(values)
    val changed = pairs.filter { (control, value) -> !control.sameValue(value) }
    return changed.ifEmpty { pairs }
        .filterNot { (control, value) -> control is ChoiceOffer && value.indices().isEmpty() }
        .joinToString(words.parts) { (control, value) -> control.replyText(value, words) }
}

private fun Control.sameValue(value: Any): Boolean = when (this) {
    is SliderOffer -> abs((snap(value as Double) ?: value) - this.value) < EPSILON
    is SwitchOffer -> value == this.value
    is ChoiceOffer -> value.indices().sorted() == this.value.sorted()
}

/**
 * The values a reply sets on [controls], in Chinese or English whatever the client shows: "媒体音量设为22档", "座椅加热打开",
 * "空调模式改成制冷", "主驾、副驾", "Volume to 22", "Seat heating on", "Mode: Cool". Each part names
 * its control unless one control could take it; a part naming only an option joins the previous part's multiple
 * choice. Null when a part names nothing, names a control twice, or its value is out of range, so the text then
 * reaches the model as said.
 */
fun controlPicks(text: String, controls: List<Control>): List<Pair<Control, Any>>? {
    if (controls.isEmpty()) return null
    val parts = text.trim().split(SLIDER_PARTS).map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    val picks = mutableListOf<Pair<Control, Any>>()
    for (part in parts) {
        val previous = picks.lastOrNull()?.first as? ChoiceOffer
        val (control, value) = controlPart(part, controls, previous) ?: return null
        val earlier = picks.indexOfFirst { it.first == control }
        when {
            earlier < 0 -> picks += control to value
            control is ChoiceOffer && control.multiple ->
                picks[earlier] = control to (picks[earlier].second.indices() + value.indices()).distinct()
            else -> return null
        }
    }
    return picks
}

private fun controlPart(part: String, controls: List<Control>, previous: ChoiceOffer?): Pair<Control, Any>? {
    sliderPart(part, controls.filterIsInstance<SliderOffer>())?.let { return it }
    switchPart(part, controls.filterIsInstance<SwitchOffer>())?.let { return it }
    return choicePart(part, controls.filterIsInstance<ChoiceOffer>(), previous)
}

private fun sliderPart(part: String, sliders: List<SliderOffer>): Pair<Control, Any>? {
    if (sliders.isEmpty()) return null
    val split = SLIDER_VERB.find(part)?.range?.first ?: part.indexOfFirst { it.isDigit() || it == '-' }.coerceAtLeast(0)
    val said = part.substring(0, split)
    val slider = when {
        said.isEnglish() -> said.englishName().let { name -> if (name.isEmpty()) sliders.singleOrNull() else sliders.namedExactly(name) }
        else -> said.offerKey().let { name -> if (name.isEmpty()) sliders.singleOrNull() else sliders.named(name) }
    } ?: return null
    return slider.valueSaid(part.substring(split))?.let { slider to it }
}

/**
 * "座椅加热打开", "打开座椅加热", "关掉方向盘加热", "Seat heating on", "turn off the wheel heating", or a bare "打开" or
 * "on" when there is one switch.
 */
private fun switchPart(part: String, switches: List<SwitchOffer>): Pair<Control, Any>? {
    if (switches.isEmpty()) return null
    chineseSwitch(part.offerKey())?.let { (name, on) ->
        return (if (name.isEmpty()) switches.singleOrNull() else switches.named(name))?.let { it to on }
    }
    val (name, on) = englishSwitch(part.trim().trimEnd('.', '!', '。', '！')) ?: return null
    return (if (name.isEmpty()) switches.singleOrNull() else switches.namedExactly(name))?.let { it to on }
}

private fun chineseSwitch(key: String): Pair<String, Boolean>? {
    val verb = SWITCH_LEADING.find(key) ?: SWITCH_TRAILING.find(key) ?: return null
    return key.removeRange(verb.range) to ('关' !in verb.value)
}

/** The state is a word of its own, so "Moon" is no switch turned on. */
private fun englishSwitch(said: String): Pair<String, Boolean>? {
    SWITCH_BARE_EN.matchEntire(said)?.let { return "" to it.groupValues[1].equals("on", ignoreCase = true) }
    SWITCH_LEADING_EN.matchEntire(said)?.let { return it.groupValues[2].englishName() to it.groupValues[1].equals("on", ignoreCase = true) }
    SWITCH_TRAILING_EN.matchEntire(said)?.let { return it.groupValues[1].englishName() to it.groupValues[2].equals("on", ignoreCase = true) }
    return null
}

/** Latin words and no Chinese: what the user named is read the English way ([englishName]). */
private fun String.isEnglish(): Boolean =
    any { it in 'a'..'z' || it in 'A'..'Z' } && none { Character.isIdeographic(it.code) }

/** "set the volume" names the volume; a leading verb and "the" are dropped, the rest must be the control's name. */
private fun String.englishName(): String = trim().replace(ENGLISH_NAME_LEAD, "").offerKey()

/** "空调模式设为制冷", "模式改成制冷", "制冷"; an option alone goes to the one choice that has it, else to [previous]. */
private fun choicePart(part: String, choices: List<ChoiceOffer>, previous: ChoiceOffer?): Pair<Control, Any>? {
    if (choices.isEmpty()) return null
    val key = part.offerKey()
    val split = CHOICE_VERB.find(key)?.range
    val named = split?.let { key.substring(0, it.first) }?.takeIf { it.isNotEmpty() }?.let { choices.named(it) }
        ?: choices.filter { it.label.offerKey().let { label -> label.isNotEmpty() && key.startsWith(label) } }.maxByOrNull { it.label.length }
    if (named != null) {
        val said = if (key.startsWith(named.label.offerKey())) key.removePrefix(named.label.offerKey()) else key.substring(split?.first ?: 0)
        return named.optionSaid(said)?.let { named to listOf(it) }
    }
    previous?.optionSaid(key)?.let { return previous to listOf(it) }
    val holders = choices.mapNotNull { choice -> choice.optionSaid(key)?.let { choice to listOf(it) } }
    return holders.singleOrNull()
}

/** The one control whose label contains [name] or is contained in it ("音量" for 媒体音量). */
private fun <T : Control> List<T>.named(name: String): T? =
    filter { it.label.offerKey().let { label -> label.contains(name) || name.contains(label) } }.singleOrNull()

/**
 * The one control whose label is [name] or holds it ("volume" for Media volume), for English: a sentence that only
 * holds a label ("Don't set the volume to 30", "is the seat heating on") names nothing.
 */
private fun <T : Control> List<T>.namedExactly(name: String): T? = filter { it.label.offerKey().contains(name) }.singleOrNull()

/**
 * Information shown as a card, weather style: a lead icon and value, a few labelled fields, progress bars, and items
 * laid out as rows, a grid of readings, a comparison, or a strip (a forecast).
 */
@Serializable
data class WidgetCard(
    val title: String,
    val icon: String?,
    val value: String?,
    val subtitle: String?,
    val fields: List<Pair<String, String>>,
    val items: List<CardItem>,
    /** A saved picture (`generated-images/<folder>/<file>`) shown above the card, such as a delegated report's photo. */
    val image: String? = null,
    val layout: CardLayout = CardLayout.LIST,
    val progress: List<CardProgress> = emptyList(),
) {
    /**
     * The picture shown as the big tappable frame beside the reply: only a card that is mostly its photo (a delegated
     * restaurant). With items (a trip's stops) the photo is a banner inside the card, so the rows still fit.
     */
    val framedPicture: String? get() = image?.takeIf { items.isEmpty() }
}

/** One item; [value] is its reading ("2.4 bar", "30/22°"), [fields] its rows in a comparison. */
@Serializable
data class CardItem(
    val icon: String?,
    val title: String,
    val detail: String?,
    val value: String? = null,
    /** A saved picture, like the card's own. */
    val image: String? = null,
    val fields: List<Pair<String, String>> = emptyList(),
    /** A list section, such as a trip's day; consecutive items of one group sit under one heading. */
    val group: String? = null,
    /** What the item is on a [CardLayout.TRIP] card: a stop of a day, a leg there or back, or a hotel. */
    val kind: CardItemKind = CardItemKind.PLACE,
)

@Serializable
enum class CardItemKind(val wire: String) { PLACE("place"), TRANSPORT("transport"), STAY("stay"), WEATHER("weather") }

@Serializable
data class CardProgress(val label: String, val value: Double, val max: Double, val text: String?)

/** How a card lays out its items, and how many it takes. */
@Serializable
enum class CardLayout(val wire: String, val items: IntRange) {
    /** Icon or picture rows, the reading at the end, in sections when items carry a group (a trip's days). */
    /** Up to 14: a three-day trip with its flights and hotels. */
    LIST("list", 0..14),
    /** Two columns of tiles: four tyres, battery and range. */
    GRID("grid", 2..6),
    /** Two or three side by side, a row per field: charging stations, parking. */
    COMPARE("compare", 2..3),
    /** One row of short columns: the days of a forecast. */
    STRIP("strip", 2..6),
    /** A trip on the wide card: legs as tickets, a column per day, hotels with pictures. */
    TRIP("trip", 1..18),
}

/** A built-in icon name as is; a device or weather glyph as an SVG path the Icon component draws. */
fun iconJson(name: String): JsonElement =
    DEBUG_SVG_ICONS[name]?.let { path -> buildJsonObject { put("svgPath", path) } } ?: JsonPrimitive(name)

fun iconKnown(name: String): Boolean = name in DEBUG_SVG_ICONS || name in DEBUG_BUILT_IN_ICONS

/** The A2UI basic catalog's own icons (lower camel case, as the protocol names them). */
val DEBUG_BUILT_IN_ICONS = setOf(
    "accountCircle", "add", "arrowBack", "arrowForward", "calendarToday", "call", "camera", "check", "close", "delete",
    "download", "edit", "error", "event", "favorite", "folder", "help", "home", "info", "locationOn", "lock",
    "lockOpen", "mail", "menu", "notifications", "pause", "person", "phone", "photo", "play", "refresh", "search",
    "send", "settings", "share", "skipNext", "skipPrevious", "star", "stop", "visibility", "volumeDown", "volumeMute",
    "volumeOff", "volumeUp", "warning",
)

/**
 * Glyphs the catalog lacks, on a 24×24 grid: weather and travel places; then places and trips from Material Icons
 * (Apache 2.0, the catalog's own look; models often ask for 「food」, which would otherwise fall back to 「star」).
 * Holes run counter-clockwise.
 */
val DEBUG_SVG_ICONS = mapOf(
    "sunny" to "M12 7a5 5 0 1 0 0 10a5 5 0 1 0 0-10Z M11 1h2v3h-2Z M11 20h2v3h-2Z M1 11h3v2H1Z M20 11h3v2h-3Z " +
        "M4.2 5.6L5.6 4.2L7.7 6.3L6.3 7.7Z M16.3 17.7L17.7 16.3L19.8 18.4L18.4 19.8Z " +
        "M4.2 18.4L6.3 16.3L7.7 17.7L5.6 19.8Z M16.3 6.3L18.4 4.2L19.8 5.6L17.7 7.7Z",
    "cloudy" to CLOUD,
    "partlyCloudy" to "M8 4a3 3 0 1 0 0 6a3 3 0 1 0 0-6Z M7.25 0.5h1.5v2h-1.5Z M0.5 6.25h2v1.5h-2Z " +
        "M2.4 3.5L3.5 2.4L4.9 3.8L3.8 4.9Z M11.1 3.8L12.5 2.4L13.6 3.5L12.2 4.9Z " +
        "M9 22a4 4 0 0 1-0.4-7.98A5.3 5.3 0 0 1 18.9 13.2A4.2 4.2 0 0 1 18.8 22Z",
    "rain" to "$CLOUD_HIGH M8 17h1.6l-1.1 4.5H6.9Z M12 17h1.6l-1.1 4.5H10.9Z M16 17h1.6l-1.1 4.5H14.9Z",
    "snow" to "$CLOUD_HIGH M8 17.5a1.2 1.2 0 1 0 0 2.4a1.2 1.2 0 1 0 0-2.4Z M12 19a1.2 1.2 0 1 0 0 2.4a1.2 1.2 0 1 0 0-2.4Z " +
        "M16 17.5a1.2 1.2 0 1 0 0 2.4a1.2 1.2 0 1 0 0-2.4Z",
    "thunderstorm" to "$CLOUD_HIGH M12.5 15L9.5 20.5H12L11 24L15.5 18H13L14 15Z",
    "fog" to "M3 7h18v2H3Z M3 11h18v2H3Z M3 15h18v2H3Z M6 19h12v2H6Z",
    "wind" to "M3 8h10a2 2 0 1 0-2-2H9a4 4 0 1 1 4 4H3Z M3 12h14a3 3 0 1 1-3 3h2a1 1 0 1 0 1-1H3Z",
    "charging" to "M13 2L4 14h6l-1 8l9-12h-6l1-8Z",
    "parking" to "M6 3h7a6 6 0 0 1 0 12H9v6H6Z M9 6v6h4a3 3 0 0 0 0-6Z",
    "fuel" to "M5 3h9v18H5Z M7 5v5h5V5Z M14 8h2l3 3v7a1.5 1.5 0 0 1-3 0v-4h-2v-2h3v-2.2L15.8 10H14Z",
    "temperature" to "M10 4a2 2 0 0 1 4 0v9.5a4.5 4.5 0 1 1-4 0Z",
    "music" to "M9 3v11.6A3.5 3.5 0 1 0 11 18V7h6V3Z",
    "food" to "M11 9H9V2H7v7H5V2H3v7c0 2.12 1.66 3.84 3.75 3.97V22h2.5v-9.03C11.34 12.84 13 11.12 13 9V2h-2v7z" +
        "m5-3v8h2.5v8H21V2c-2.76 0-5 2.24-5 4z",
    "hotel" to "M7 13c1.66 0 3-1.34 3-3S8.66 7 7 7s-3 1.34-3 3 1.34 3 3 3z" +
        "m12-6h-8v7H3V5H1v15h2v-3h18v3h2v-9c0-2.21-1.79-4-4-4z",
    "flight" to "M21 16v-2l-8-5V3.5c0-.83-.67-1.5-1.5-1.5S10 2.67 10 3.5V9l-8 5v2l8-2.5V19l-2 1.5V22" +
        "l3.5-1 3.5 1v-1.5L13 19v-5.5l8 2.5z",
    "train" to "M12 2c-4 0-8 .5-8 4v9.5C4 17.43 5.57 19 7.5 19L6 20.5v.5h2.23l2-2H14l2 2h2v-.5" +
        "L16.5 19c1.93 0 3.5-1.57 3.5-3.5V6c0-3.5-3.58-4-8-4z" +
        "M7.5 17c-.83 0-1.5-.67-1.5-1.5S6.67 14 7.5 14s1.5.67 1.5 1.5S8.33 17 7.5 17zm3.5-7H6V6h5v4z" +
        "m2 0V6h5v4h-5zm3.5 7c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5z",
    "coffee" to "M20 3H4v10c0 2.21 1.79 4 4 4h6c2.21 0 4-1.79 4-4v-3h2c1.11 0 2-.89 2-2V5c0-1.11-.89-2-2-2z" +
        "m0 5h-2V5h2v3zM4 19h16v2H4z",
    "shopping" to "M19 6h-2c0-2.76-2.24-5-5-5S7 3.24 7 6H5c-1.1 0-1.99.9-1.99 2L3 20c0 1.1.9 2 2 2" +
        "h14c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2z" +
        "m-7-3c1.66 0 3 1.34 3 3H9c0-1.66 1.34-3 3-3z" +
        "m0 10c-2.76 0-5-2.24-5-5h2c0 1.66 1.34 3 3 3s3-1.34 3-3h2c0 2.76-2.24 5-5 5z",
    "scenic" to "M14 6l-3.75 5 2.85 3.8-1.6 1.2C9.81 13.75 7 10 7 10l-6 8h22L14 6z",
    "ticket" to "M20 12c0-1.1.9-2 2-2V6c0-1.1-.9-2-2-2H4c-1.1 0-1.99.9-1.99 2v4c1.1 0 1.99.9 1.99 2" +
        "s-.89 2-2 2v4c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2v-4c-1.1 0-2-.9-2-2z" +
        "m-4.42 4.8L12 14.5l-3.58 2.3 1.08-4.12-3.29-2.69 4.24-.25L12 5.8l1.54 3.95 4.24.25-3.29 2.69 1.09 4.11z",
)

private const val CLOUD = "M6.5 19a4.5 4.5 0 0 1-0.5-8.97A6 6 0 0 1 17.65 9.5A4.75 4.75 0 0 1 17.5 19Z"
private const val CLOUD_HIGH = "M6.5 15a4.5 4.5 0 0 1-0.5-8.97A6 6 0 0 1 17.65 5.5A4.75 4.75 0 0 1 17.5 15Z"

private const val EPSILON = 1e-6

/** "设为30%", "调到 30", "to 30", "30" as a tap, a keyboard or the user says it (spaces already dropped). */
private val SLIDER_VALUE =
    Regex("""^(?:设为|设到|设成|调到|调成|调为|改成|改为|到|(?i:setto|to|at))?(-?\d+(?:\.\d+)?)(?:%|％|度|档|级|格|℃|°C|°)?$""")
private val SLIDER_NOISE = Regex("""[\s。！!？?~～，,]""")
/** Parts of a reply that sets several sliders: "媒体音量设为22档，中控屏亮度设为60%", "音量调到20和亮度调到60", "Volume to 20 and Brightness to 60". */
private val SLIDER_PARTS = Regex("""[，,；;、]|和|并且|再把|(?i:\s+and\s+)""")
/** Where a part's slider name ends and its value begins: "调到", or " to " in "Volume to 30%". */
private val SLIDER_VERB = Regex("""设为|设到|设成|调到|调成|调为|改成|改为|到|(?i:(?:^|\s+)(?:set\s+)?(?:to|at)\s+)""")
/** A switch's state said before or after its name: "打开座椅加热", "座椅加热关掉". */
private val SWITCH_LEADING = Regex("""^(?:请|把)?(?:打开|开启|关闭|关掉|关上|开|关)""")
private val SWITCH_TRAILING = Regex("""(?:打开|开启|关闭|关掉|关上|开|关)(?:一下)?$""")
/** "on", "turn off". */
private val SWITCH_BARE_EN = Regex("""^(?:(?:turn|switch)\s+)?(on|off)$""", RegexOption.IGNORE_CASE)
/** "turn on the seat heating". */
private val SWITCH_LEADING_EN = Regex("""^(?:please\s+)?(?:turn|switch)\s+(on|off)\s+(?:the\s+)?(.+)$""", RegexOption.IGNORE_CASE)
/** "Seat heating on", "turn the seat heating off". */
private val SWITCH_TRAILING_EN = Regex("""^(?:(?:please\s+)?(?:turn|switch)\s+)?(?:the\s+)?(.+?)\s+(on|off)$""", RegexOption.IGNORE_CASE)
/** What leads an English control's name: "please set the", "turn the". */
private val ENGLISH_NAME_LEAD = Regex("""^(?:please\s+)?(?:(?:set|change|turn|switch|put|make)\s+)?(?:the\s+)?""", RegexOption.IGNORE_CASE)
/** Where a choice's name ends and its option begins: "空调模式设为制冷", "座椅加热选主驾". */
private val CHOICE_VERB = Regex("""设为|设成|改成|改为|换成|切换到|切换成|切到|调成|调为|选|用""")
/** A verb before an option, spaces dropped: "改成制冷", "to cool", "set to cool". */
private val CHOICE_VERB_LEADING = Regex("""^(?:设为|设成|改成|改为|换成|切换到|切换成|切到|调成|调为|选|用|setto|changeto|switchto|to)""")
