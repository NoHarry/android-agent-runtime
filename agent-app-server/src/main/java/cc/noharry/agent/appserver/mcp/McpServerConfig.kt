package cc.noharry.agent.appserver.mcp
import android.content.Context
import cc.noharry.agent.appserver.R
import cc.noharry.agent.appserver.protocol.McpServer
import cc.noharry.agent.appserver.protocol.McpServerDraft
import cc.noharry.agent.appserver.protocol.McpServerKind
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The configured servers; none before the first one is added. */
@cc.noharry.agent.appserver.AppServerPluginApi
fun loadMcpServers(context: Context): List<McpServer> {
    val stored = context.getSharedPreferences(MCP_SERVER_PREFS, Context.MODE_PRIVATE).getString(KEY_SERVERS, null)
    return stored?.let(::parseMcpServers).orEmpty()
}

internal sealed interface McpSaveResult {
    data class Saved(val id: String) : McpSaveResult

    data class Rejected(val reason: String) : McpSaveResult
}

/** Adds or replaces the server [draft] describes. */
internal fun saveMcpServerDraft(context: Context, draft: McpServerDraft): McpSaveResult {
    val servers = loadMcpServers(context)
    val existing = draft.id?.let { id ->
        servers.firstOrNull { it.id == id }
            ?: return McpSaveResult.Rejected(context.getString(R.string.mcp_server_deleted))
    }
    validateMcpDraft(context, draft, existing)?.let { return McpSaveResult.Rejected(it) }
    val headers = parseMcpHeaders(context, draft.headers)
        .getOrElse { return McpSaveResult.Rejected(it.message.orEmpty()) }
    val server = McpServer(
        id = existing?.id ?: draft.preferredId?.takeIf { id -> servers.none { it.id == id } && id !in fixedProviderIds() }
            ?: newMcpServerId(draft.label, servers),
        label = draft.label.trim(),
        kind = draft.kind,
        enabled = existing?.enabled ?: true,
        url = draft.url.trim(),
    )
    if (draft.clearSecrets) McpSecrets.clear(context, server.id)
    if (draft.kind == McpServerKind.HTTP && (draft.token.isNotBlank() || headers.isNotEmpty() || draft.query.isNotEmpty())) {
        val kept = McpSecrets.read(context, server.id)
        val secrets = McpSecrets.Stored(
            draft.token.trim().ifEmpty { kept.token },
            headers.ifEmpty { kept.headers },
            draft.query.ifEmpty { kept.query },
        )
        McpSecrets.save(context, server.id, secrets)?.let { return McpSaveResult.Rejected(it) }
    }
    storeMcpServers(context, if (existing == null) servers + server else servers.map { if (it.id == server.id) server else it })
    return McpSaveResult.Saved(server.id)
}

/** Removes a server and its stored token. */
internal fun deleteMcpServerConfig(context: Context, id: String): Boolean {
    val servers = loadMcpServers(context)
    if (servers.none { it.id == id && it.deletable }) return false
    storeMcpServers(context, servers.filterNot { it.id == id })
    McpSecrets.clear(context, id)
    return true
}

/** False when [id] is not a configured server. */
internal fun setMcpServerEnabled(context: Context, id: String, enabled: Boolean): Boolean {
    val servers = loadMcpServers(context)
    if (servers.none { it.id == id }) return false
    storeMcpServers(context, servers.map { if (it.id == id) it.copy(enabled = enabled) else it })
    return true
}

internal fun validateMcpDraft(context: Context, draft: McpServerDraft, existing: McpServer?): String? = when {
    draft.label.isBlank() -> context.getString(R.string.mcp_name_required)
    draft.label.trim().length > MAX_LABEL_CHARS -> context.getString(R.string.mcp_name_too_long, MAX_LABEL_CHARS)
    else -> validateMcpUrl(context, draft.url.trim())
}

private fun validateMcpUrl(context: Context, url: String): String? {
    val uri = try {
        URI(url)
    } catch (_: URISyntaxException) {
        return context.getString(R.string.mcp_url_invalid)
    }
    return when {
        uri.scheme !in setOf("http", "https") -> context.getString(R.string.mcp_url_scheme)
        uri.host.isNullOrBlank() -> context.getString(R.string.mcp_url_no_host)
        // network_security_config.xml permits cleartext only on loopback (a PC server through adb reverse).
        uri.scheme == "http" && uri.host !in LOOPBACK_MCP_HOSTS ->
            context.getString(R.string.mcp_url_http_loopback_only)
        else -> null
    }
}

private val LOOPBACK_MCP_HOSTS = setOf("127.0.0.1", "localhost")

/** `Name: value` per line. Names the client sets itself (content type, session, protocol version) are refused. */
internal fun parseMcpHeaders(context: Context, text: String): Result<Map<String, String>> = runCatching {
    text.lines().map(String::trim).filter(String::isNotEmpty).associate { line ->
        val name = line.substringBefore(':', "").trim()
        val value = line.substringAfter(':', "").trim()
        require(HEADER_NAME.matches(name) && value.isNotEmpty()) { context.getString(R.string.mcp_header_format, line) }
        require(name.lowercase() !in RESERVED_HEADERS) { context.getString(R.string.mcp_header_reserved, name) }
        name to value
    }
}

/** A short ascii id from [label] (letters and digits only, so it is one group in the prompt), unique in [servers]. */
internal fun newMcpServerId(label: String, servers: List<McpServer>): String {
    val base = label.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(MAX_ID_CHARS).ifEmpty { "mcp" }
    val taken = servers.map { it.id }.toSet() + fixedProviderIds()
    return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base$it" }.first { it !in taken }
}

private fun storeMcpServers(context: Context, servers: List<McpServer>) {
    val json = buildJsonArray {
        servers.forEach { server ->
            add(
                buildJsonObject {
                    put("id", server.id)
                    put("label", server.label)
                    put("kind", server.kind.name)
                    put("enabled", server.enabled)
                    put("url", server.url)
                },
            )
        }
    }
    context.getSharedPreferences(MCP_SERVER_PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SERVERS, json.toString()).apply()
}

internal fun parseMcpServers(text: String): List<McpServer>? {
    val array = try {
        Json.parseToJsonElement(text) as? JsonArray
    } catch (_: SerializationException) {
        null
    } ?: return null
    return array.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val field = { name: String -> (item[name] as? JsonPrimitive)?.contentOrNull.orEmpty() }
        McpServer(
            id = field("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null,
            label = field("label"),
            kind = McpServerKind.entries.firstOrNull { it.name == field("kind") } ?: return@mapNotNull null,
            enabled = (item["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true,
            url = field("url"),
        )
    }
}

/**
 * A standard server's bearer token, request headers and address keys, which may hold keys: kept out of settings, in
 * no-backup storage, and never shown again, logged or uploaded. The rows only say that some are set.
 */
@cc.noharry.agent.appserver.AppServerPluginApi
object McpSecrets {
    internal data class Stored(
        val token: String = "",
        val headers: Map<String, String> = emptyMap(),
        /** Query parameters added to the address on each request (高德 and 滴滴 take their key this way). */
        val query: Map<String, String> = emptyMap(),
    ) {
        val isEmpty: Boolean get() = token.isEmpty() && headers.isEmpty() && query.isEmpty()
    }

    internal fun read(context: Context, id: String): Stored {
        val text = runCatching { file(context, id).readText(Charsets.UTF_8) }.getOrNull() ?: return Stored()
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return Stored()
        val map = { name: String -> (root[name] as? JsonObject)?.mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }.orEmpty() }
        return Stored((root["token"] as? JsonPrimitive)?.contentOrNull.orEmpty(), map("headers"), map("query"))
    }

    /** Returns why it could not be saved, or null. */
    internal fun save(context: Context, id: String, stored: Stored): String? {
        if (stored.token.any(Char::isWhitespace)) return context.getString(R.string.mcp_token_spaces)
        if (stored.query.values.any { value -> value.any(Char::isWhitespace) }) {
            return context.getString(R.string.mcp_key_spaces)
        }
        val json = buildJsonObject {
            put("token", stored.token)
            put("headers", JsonObject(stored.headers.mapValues { JsonPrimitive(it.value) }))
            put("query", JsonObject(stored.query.mapValues { JsonPrimitive(it.value) }))
        }
        return try {
            file(context, id).apply { parentFile?.mkdirs() }.writeText(json.toString(), Charsets.UTF_8)
            null
        } catch (_: IOException) {
            context.getString(R.string.mcp_token_save_failed)
        }
    }

    fun clear(context: Context, id: String) {
        file(context, id).delete()
    }

    /** What a request to the server carries: its headers, and `Authorization: Bearer` when a token is set. */
    fun requestHeaders(context: Context, id: String): Map<String, String> {
        val stored = read(context, id)
        val auth = stored.token.takeIf { it.isNotEmpty() && stored.headers.keys.none { it.equals(AUTHORIZATION, true) } }
        return stored.headers + listOfNotNull(auth?.let { AUTHORIZATION to "Bearer $it" })
    }

    /** The query parameters a request to the server adds to its address. */
    fun requestQuery(context: Context, id: String): Map<String, String> = read(context, id).query

    private fun file(context: Context, id: String): File =
        File(File(context.noBackupFilesDir, SECRETS_DIR), "${id.filter { it.isLetterOrDigit() || it == '_' }}.json")
}

private const val MCP_SERVER_PREFS = "app_server_mcp_servers"
private const val KEY_SERVERS = "servers"
private const val SECRETS_DIR = "mcp-secrets"
private const val AUTHORIZATION = "Authorization"
private const val MAX_LABEL_CHARS = 40
private const val MAX_ID_CHARS = 24
private val HEADER_NAME = Regex("""[A-Za-z0-9!#$%&'*+.^_`|~-]+""")
private val RESERVED_HEADERS = setOf("content-type", "accept", "mcp-session-id", "mcp-protocol-version", "content-length", "host")
