package cc.noharry.agent.appserver.protocol

import kotlinx.serialization.Serializable

/**
 * Something the server found, reports or refuses, for a screen to put in its own words: the server sends no wording
 * made for a screen. [code] says what; its wording is the app's string named after it (`CHECKS_MIC_OK` is
 * `R.string.checks_mic_ok`). [args] are the values it is about, in the order the wording takes them: names, model ids,
 * raw numbers, an HTTP status, or free-form text from outside (an error's own message, a service's answer), shown as it
 * is. [more] are findings worded after [args], each as one more value (the cause, the parts of a check), and [balance]
 * a key's balance worded after those. [FindingCode.TEXT], [FindingCode.PARTS], [FindingCode.LIST] and
 * [FindingCode.ALTERNATIVES] only arrange what they hold.
 *
 * On the wire [code] is a name: the protocol's own codes ([FindingCode]) and an app's ([FindingKind]) alike, so a
 * server that adds its own findings needs no change here.
 */
@Serializable
data class Finding(
    val code: String,
    val args: List<String> = emptyList(),
    val more: List<Finding> = emptyList(),
    val balance: BalanceView? = null,
    /** How many, for a wording that changes with the number (one model, two models); not itself a value. */
    val count: Int? = null,
) {
    constructor(
        kind: FindingKind,
        args: List<String> = emptyList(),
        more: List<Finding> = emptyList(),
        balance: BalanceView? = null,
        count: Int? = null,
    ) : this(kind.name, args, more, balance, count)

    /** Whether this is a [kind] finding. */
    fun isOf(kind: FindingKind): Boolean = code == kind.name
}

/** A kind of [Finding], by its name: the protocol's own codes ([FindingCode]) or an app's (an enum of its own). */
interface FindingKind {
    val name: String
}

/** [code] about [args] (each as its plain `toString`, numbers unformatted). */
fun finding(code: FindingKind, vararg args: Any, more: List<Finding> = emptyList()): Finding =
    Finding(code, args.map { it.toString() }, more)

/** [value] shown as it is: a name, an id, a number, or what a service or an error said. */
fun text(value: Any): Finding = finding(FindingCode.TEXT, value)

/** [parts] side by side (the screens put a dot between them). */
fun parts(vararg parts: Finding): Finding = Finding(FindingCode.PARTS, more = parts.toList())

/** [items] as a list (the screens put the language's list separator between them). */
fun listing(items: List<Finding>): Finding = Finding(FindingCode.LIST, more = items)

/** [items] as alternatives that each failed (the screens put a slash between them). */
fun alternatives(items: List<Finding>): Finding = Finding(FindingCode.ALTERNATIVES, more = items)

/** [items] one per line. */
fun lines(items: List<Finding>): Finding = Finding(FindingCode.LINES, more = items)

/** For logs: the code, its values and the findings under it, in no language. */
fun Finding.logText(): String =
    (listOf(code) + args + more.map { "(${it.logText()})" }).joinToString(" ") +
        (balance?.let { " balance=${it.amounts.joinToString { a -> "${a.currency} ${a.total}" }}" } ?: "")

/** One currency's amounts in a key's balance, as the service gives them (decimal strings). */
@Serializable
data class BalanceAmountView(val currency: String, val total: String, val toppedUp: String, val granted: String)

/** A model service key's balance: whether it can still be used, and its amounts per currency. */
@Serializable
data class BalanceView(val available: Boolean, val amounts: List<BalanceAmountView> = emptyList())
