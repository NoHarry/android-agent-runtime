package cc.noharry.agent.appserver.protocol.view

import kotlinx.serialization.Serializable
/**
 * Options the model offered with `offer_options`, each optionally bound to one checked tool call.
 *
 * The reply still shows its numbered list as today; the offer only remembers what each item does. When the next
 * user turn picks an item (a tap sends "第N个" or "Option N", or the user says it or the item's label), the host hands the model
 * the bound call instead of a bare ordinal, so it acts in one round without searching again. An offer answers only
 * the turn that made it: any later turn leaves it behind.
 */
@Serializable
data class Offer(
    val sessionId: String,
    /** The turn whose reply shows the list: its request ID, the same as [PreviousExchange.turnId]. */
    val turnId: String,
    val options: List<OfferOption>,
    val createdAtMillis: Long,
    /** The panel (`offer_slider`, `offer_switch`, `offer_choice`), in the order offered and confirmed together. */
    val controls: List<Control> = emptyList(),
    /**
     * Information shown as cards (`show_card`), such as the weather; they ask nothing. One per thing the reply covers
     * (two cities' events are two cards, not one), in the order shown.
     */
    val cards: List<WidgetCard> = emptyList(),
) {
    /** Option label to icon name, for rows that show one. */
    val icons: Map<String, String> get() = options.mapNotNull { option -> option.icon?.let { option.label to it } }.toMap()
}

@Serializable
data class OfferOption(val label: String, val call: BoundCall?, val icon: String? = null)

/** A tool call the host checked against the tool's input schema when the offer was made. */
@Serializable
data class BoundCall(
    val providerId: String,
    val toolName: String,
    val qualifiedName: String,
    val argumentsJson: String,
)

/** Spaces, punctuation, case and polite particles dropped, so "第二个吧。", "蓝色！" and "Blue." compare by their words. */
fun String.offerKey(): String =
    trim().lowercase().replace(OFFER_KEY_NOISE, "").removeSuffix("吧").removeSuffix("啊").removeSuffix("呀")

private val OFFER_KEY_NOISE = Regex("""[\s，,。.！!？?、~～“”"'‘’：:；;]""")

const val MAX_OFFER_OPTIONS = 8
