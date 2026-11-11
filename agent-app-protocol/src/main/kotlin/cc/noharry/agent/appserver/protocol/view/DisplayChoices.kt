package cc.noharry.agent.appserver.protocol.view
/**
 * Speak-then-show choices. When a finished reply asks the user to pick from a numbered list
 * ("1. 东广场停车场（推荐，离进站口近）"), the overlay shows that list as tappable rows. A tap sends the same
 * ordinal reply the user would say, so it reaches the model through the existing short-reply frame and needs
 * no runtime protocol. Listed results or route details that ask nothing stay plain text.
 */
data class DisplayChoice(val ordinal: Int, val label: String, val recommended: Boolean) {
    /** What a tap sends: the spoken ordinal reply ("第2个", "Option 2"), short enough to be framed against the reply it answers. */
    fun replyText(words: ReplyWords): String = words.pick(ordinal)
}

/** [text] is the reply without the choice lines; [choices] is empty when the reply offers no pick. */
data class DisplayReply(val text: String, val choices: List<DisplayChoice>)

/**
 * [offeredLabels]: what offer_options registered for this reply; a list starting with them is a pick list too.
 * [markedPicks]: whether a "（推荐…）" mark alone makes the list a pick; beside a card it does not.
 */
fun String.displayChoiceReply(offeredLabels: List<String> = emptyList(), markedPicks: Boolean = true): DisplayReply {
    val none = DisplayReply(this, emptyList())
    val lines = lines()
    val numbered = lines.mapIndexedNotNull { index, line -> line.numberedChoice()?.let { index to it } }
    // One recommendation per list: a model that marks several gets the first; the others keep their reasons as text.
    val firstRecommended = numbered.indexOfFirst { it.second.recommended }
    val choices = numbered.mapIndexed { index, (_, choice) -> if (choice.recommended && index != firstRecommended) choice.copy(recommended = false) else choice }
    if (choices.size !in MIN_DISPLAY_CHOICES..MAX_DISPLAY_CHOICES) return none
    if (choices.map { it.ordinal } != (1..choices.size).toList()) return none
    // One block, blank lines allowed: detail lines between items would lose their item once the rows split off.
    val choiceLines = numbered.map { it.first }.toSet()
    val block = choiceLines.min()..choiceLines.max()
    if (block.any { it !in choiceLines && lines[it].isNotBlank() }) return none
    val rest = lines.filterIndexed { index, _ -> index !in block }
    if ((!markedPicks || choices.none { it.recommended }) && !rest.asksForChoice() && !choices.areOffered(offeredLabels)) return none
    return DisplayReply(rest.joinToString("\n").replace(BLANK_LINE_RUN, "\n\n").trim(), choices)
}

private fun String.numberedChoice(): DisplayChoice? {
    val match = NUMBERED_CHOICE.find(this) ?: return null
    val ordinal = (match.groupValues[1].ifEmpty { match.groupValues[2] }).toInt()
    val label = match.groupValues[3].replace(MARKDOWN_EMPHASIS_MARKS, "").trim()
    if (label.isEmpty()) return null
    return DisplayChoice(ordinal, label, recommended = "推荐" in label || RECOMMENDED_EN.containsMatchIn(label))
}

/** Every listed line starts with a label the model registered with offer_options for this list. */
private fun List<DisplayChoice>.areOffered(labels: List<String>): Boolean {
    val keys = labels.map { it.offerKey() }.filter { it.isNotEmpty() }
    return keys.isNotEmpty() && all { choice -> choice.label.offerKey().let { line -> keys.any { line.startsWith(it) } } }
}

/**
 * The skill marks the pick "（推荐，理由）" or "(recommended: why)"; without it, a question must ask which one. "还要别的吗？"
 * does not.
 */
private fun List<String>.asksForChoice(): Boolean =
    any { line -> (line.contains('？') || line.contains('?')) && CHOICE_WORDS.containsMatchIn(line) }

/** "1. ", "2、", "3）", "（4）"; a decimal such as "12.5公里" is not a marker. Bullets carry no ordinal to say. */
private val NUMBERED_CHOICE = Regex("""^\s*(?:(\d{1,2})[.、)）](?!\d)|[（(](\d{1,2})[）)])\s*(.*)$""")
private val MARKDOWN_EMPHASIS_MARKS = Regex("""\*\*|__|`""")
private val CHOICE_WORDS = Regex("""选|哪个|哪一个|哪家|哪条|哪种|哪首|哪部|第几|(?i:\b(?:which|choose|pick|prefer)\b)""")
private val RECOMMENDED_EN = Regex("""\brecommended\b""", RegexOption.IGNORE_CASE)
private val BLANK_LINE_RUN = Regex("""\n{3,}""")
private const val MIN_DISPLAY_CHOICES = 2
/** Same cap as ask_async options; a longer list stays text and can still be answered by voice. */
private const val MAX_DISPLAY_CHOICES = 8
