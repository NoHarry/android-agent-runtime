package cc.noharry.agent.mcp

/**
 * What [McpHttpClient] and [listMcpTools] say when a server cannot be used, for a settings row or a run's tool error.
 * Every message has an English default; an app passes its own to speak its users' language. Candidate API, not frozen.
 */
interface McpClientMessages {
    /** No answer within [seconds]. */
    fun timedOut(seconds: Long): String = "No answer in $seconds s"

    /** No HTTP answer at all (refused, no route, TLS); [detail] is the error's own words. */
    fun unreachable(detail: String): String = "Cannot connect: $detail"

    /** The server answered, but not with a JSON-RPC message. */
    fun notJsonRpc(): String = "The server's answer is not JSON-RPC"

    /** `initialize` brought no JSON-RPC result; [rawHint] is the start of what came back, possibly empty. */
    fun initializeRefused(rawHint: String): String =
        if (rawHint.isEmpty()) "The server did not answer initialize" else "The server did not answer initialize: $rawHint"

    /** Why an HTTP status is not an answer; null for 2xx. */
    fun httpProblem(status: Int): String? = when (status) {
        in 200..299 -> null
        401, 403 -> "The server refused (HTTP $status); check the token or headers"
        404 -> "Wrong address (HTTP 404)"
        405 -> "This address does not take POST (HTTP 405); it may be an old SSE server, " +
            "and only Streamable HTTP is supported"
        406, 415 -> "The server does not accept this format (HTTP $status)"
        in 500..599 -> "The server failed (HTTP $status)"
        else -> "HTTP $status"
    }

    fun toolsListFailed(statusCode: Int): String = "tools/list failed (status=$statusCode)"

    fun toolsListNotJson(): String = "The tools/list answer is not JSON"

    fun toolsListError(message: String): String = "tools/list failed: $message"

    fun toolsListCursorRepeated(): String = "tools/list repeated a page cursor"

    fun toolsListTooManyPages(maxPages: Int): String = "tools/list has more than $maxPages pages"

    /** The English messages. */
    companion object Default : McpClientMessages
}
