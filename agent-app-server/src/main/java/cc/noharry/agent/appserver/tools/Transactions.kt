package cc.noharry.agent.appserver.tools

/**
 * A tool that places an order or pays: createOrder, submit_order, payOrder, checkout… (previewOrder and queries pass).
 * A plan never calls one; a host may hold one back until the user says yes.
 */
@cc.noharry.agent.appserver.AppServerPluginApi
fun isTransaction(toolName: String): Boolean {
    val words = toolName.substringAfterLast("__").toWords()
    return ("order" in words && words.any { it in ORDER_VERBS }) || words.any { it in PAY_WORDS }
}

/** 「createOrder」「submit_order」「android_observe」 as lower-case words. */
private fun String.toWords(): List<String> = split('_').flatMap { it.split(CAMEL_BOUNDARY) }.map(String::lowercase)

private val ORDER_VERBS = setOf("create", "submit", "place", "confirm")
private val PAY_WORDS = setOf("pay", "payment", "checkout", "purchase")
private val CAMEL_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])")
