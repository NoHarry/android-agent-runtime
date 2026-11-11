package cc.noharry.agent.appserver.mcp
import android.content.Context
import cc.noharry.agent.appserver.R
import cc.noharry.agent.appserver.http.AppServerHttpTransport
import cc.noharry.agent.appserver.protocol.McpServer
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.ModelWords
import cc.noharry.agent.mcp.McpClientMessages
import cc.noharry.agent.mcp.McpHttpClient
import cc.noharry.agent.mcp.McpToolProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The app's one connection per standard MCP server, shared by runs, the catalog cache and the tool preview. An edit
 * to the server's address makes a new one.
 */
internal object McpHttpClients {
    private val clients = mutableMapOf<String, Pair<String, McpHttpClient>>()
    /** Where a server nobody could reach is tried again, out of every run's way. */
    private val retries = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun get(context: Context, server: McpServer): McpHttpClient {
        val app = context.applicationContext
        return synchronized(clients) {
            clients[server.id]?.takeIf { it.first == server.url }?.second
                ?: create(app, server.url, { McpSecrets.requestHeaders(app, server.id) }, { McpSecrets.requestQuery(app, server.id) })
                    .also { client -> clients.put(server.id, server.url to client)?.second?.close() }
        }
    }

    /**
     * A new connection to [url], with [headers] and [query] read for each request; one that must not reach another host
     * than [url]'s does not follow redirects ([followRedirects]).
     */
    fun create(
        app: Context,
        url: String,
        headers: () -> Map<String, String>,
        query: () -> Map<String, String>,
        followRedirects: Boolean = true,
    ): McpHttpClient = McpHttpClient(
        url = url,
        headers = headers,
        http = AppServerHttpTransport(app, followRedirects = followRedirects),
        clientName = app.packageName,
        clientVersion = app.versionName(),
        query = query,
        unreachableCooldownMillis = UNREACHABLE_COOLDOWN_MS,
        retryScope = retries,
        messages = AppHost.words.mcp,
    )

    fun close(serverId: String) {
        synchronized(clients) { clients.remove(serverId) }?.second?.close()
    }

    /** How often a server nobody could reach is tried again (in the background); its switch toggled tries it at once. */
    private const val UNREACHABLE_COOLDOWN_MS = 5 * 60 * 1000L
}

/** A configured standard MCP server over HTTP, with the app's shared connection and catalog for it. */
internal fun httpMcpToolProvider(context: Context, server: McpServer): McpToolProvider =
    McpToolProvider(
        client = McpHttpClients.get(context, server),
        serverId = server.id,
        displayName = server.label,
        catalog = McpToolCatalogs.cache(server.id),
        describe = { AppHost.runs.describeTool(it) },
    )

/**
 * MCP problems in Chinese, for a Chinese persona's runs ([ModelWords.mcp]): a run's tool errors, which the model reads,
 * so they follow the assistant's language, not the screen's ([ScreenMcpMessages] has the screen's own).
 */
internal object ChineseMcpMessages : McpClientMessages {
    override fun timedOut(seconds: Long): String = "$seconds 秒没有回应"

    override fun unreachable(detail: String): String = "连不上：$detail"

    override fun notJsonRpc(): String = "服务的回应不是 JSON-RPC"

    override fun initializeRefused(rawHint: String): String =
        if (rawHint.isEmpty()) "服务没有回应 initialize" else "服务没有回应 initialize：$rawHint"

    override fun httpProblem(status: Int): String? = when (status) {
        in 200..299 -> null
        401, 403 -> "服务拒绝了（HTTP $status），检查令牌或请求头"
        404 -> "地址不对（HTTP 404）"
        405 -> "这个地址不收 POST（HTTP 405），可能是旧版 SSE 服务，只支持 Streamable HTTP"
        406, 415 -> "服务不接受这个格式（HTTP $status）"
        in 500..599 -> "服务出错了（HTTP $status）"
        else -> "HTTP $status"
    }

    override fun toolsListFailed(statusCode: Int): String = "tools/list 失败（status=$statusCode）"

    override fun toolsListNotJson(): String = "tools/list 的回应不是 JSON"

    override fun toolsListError(message: String): String = "tools/list 出错：$message"

    override fun toolsListCursorRepeated(): String = "tools/list 的分页游标重复了"

    override fun toolsListTooManyPages(maxPages: Int): String = "tools/list 超过 $maxPages 页"
}

/**
 * The words for MCP problems that only the screen shows (连接应用's check, the tool preview), in the app's
 * language; the same problems as [ChineseMcpMessages].
 */
@cc.noharry.agent.appserver.AppServerPluginApi
class ScreenMcpMessages(private val context: Context) : McpClientMessages {
    override fun timedOut(seconds: Long): String = context.getString(R.string.mcp_problem_timed_out, seconds)

    override fun unreachable(detail: String): String = context.getString(R.string.mcp_problem_unreachable, detail)

    override fun notJsonRpc(): String = context.getString(R.string.mcp_problem_not_json_rpc)

    override fun initializeRefused(rawHint: String): String =
        if (rawHint.isEmpty()) {
            context.getString(R.string.mcp_problem_no_initialize)
        } else {
            context.getString(R.string.mcp_problem_no_initialize_hint, rawHint)
        }

    override fun httpProblem(status: Int): String? = when (status) {
        in 200..299 -> null
        401, 403 -> context.getString(R.string.mcp_problem_http_refused, status)
        404 -> context.getString(R.string.mcp_problem_http_not_found)
        405 -> context.getString(R.string.mcp_problem_http_no_post)
        406, 415 -> context.getString(R.string.mcp_problem_http_format, status)
        in 500..599 -> context.getString(R.string.mcp_problem_http_server, status)
        else -> "HTTP $status"
    }

    override fun toolsListFailed(statusCode: Int): String =
        context.getString(R.string.mcp_problem_tools_list_failed, statusCode)

    override fun toolsListNotJson(): String = context.getString(R.string.mcp_problem_tools_list_not_json)

    override fun toolsListError(message: String): String =
        context.getString(R.string.mcp_problem_tools_list_error, message)

    override fun toolsListCursorRepeated(): String = context.getString(R.string.mcp_problem_tools_list_cursor)

    override fun toolsListTooManyPages(maxPages: Int): String =
        context.getString(R.string.mcp_problem_tools_list_pages, maxPages)

    /** True when [problem] says the server was not reached at all, or not in time (a bare 连不上 included). */
    fun isNetwork(problem: String): Boolean {
        val timedOut = context.getString(R.string.mcp_problem_timed_out)
        return problem.startsWith(context.getString(R.string.mcp_problem_unreachable_plain)) ||
            (problem.startsWith(timedOut.substringBefore(SECONDS_PLACEHOLDER)) &&
                problem.endsWith(timedOut.substringAfter(SECONDS_PLACEHOLDER)))
    }
}

/** Where [ScreenMcpMessages.timedOut]'s resource puts the seconds. */
private const val SECONDS_PLACEHOLDER = "%1\$d"

private fun Context.versionName(): String =
    runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()
