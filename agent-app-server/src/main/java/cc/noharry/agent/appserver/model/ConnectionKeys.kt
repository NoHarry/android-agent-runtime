package cc.noharry.agent.appserver.model

import android.content.Context
import cc.noharry.agent.appserver.AppServerPluginApi
import cc.noharry.agent.appserver.FoundException
import cc.noharry.agent.appserver.protocol.FindingCode
import cc.noharry.agent.appserver.protocol.finding
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The key a request about a connection sends (a balance, a check, a model list), and where it may go. A key typed in
 * the request goes where the request says. A saved one goes only to the host its saved profile points at, or to an
 * address that a check made with the key typed found for that profile and the server recorded ([KeyDestinations]),
 * and only over https (plain http to this device or the local network); anywhere else it is not sent, and the person
 * types the key again ([FindingCode.MODEL_KEY_TYPE_AGAIN]). The key is read only for an address it may go to
 * ([sendTo]), so an adapter cannot send it elsewhere by mistake.
 */
@AppServerPluginApi
class ConnectionKey internal constructor(
    private val value: String,
    /** Whether it was typed in this request; a saved key goes only where [allows] says. */
    val typed: Boolean,
    private val destinations: (String) -> Boolean,
) {
    /** Whether there is no key at all. */
    val isBlank: Boolean get() = value.isBlank()

    /** The key for the server's own records ([KeyDestinations]), never sent. */
    internal val plain: String get() = value

    /** Whether this key may be sent to [url]. */
    fun allows(url: String): Boolean = value.isNotBlank() && (typed || destinations(url))

    /** The key, to send to [url]; a [FoundException] ([FindingCode.MODEL_KEY_TYPE_AGAIN]) where it may not go. */
    fun sendTo(url: String): String {
        if (!allows(url)) throw FoundException(finding(FindingCode.MODEL_KEY_TYPE_AGAIN, url.urlHost() ?: url.trim()))
        return value
    }

    /** A SHA-256 digest of [parts] and the key, one per line, for telling checks apart; never the key itself. */
    fun fingerprint(vararg parts: String): String = digest((parts.toList() + value.trim()).joinToString("\n"))

    override fun toString(): String = "ConnectionKey(typed=$typed)"

    companion object {
        /** A key [value] the caller has from the person directly (typed in, or its own), which goes where it is sent. */
        fun typed(value: String): ConnectionKey = ConnectionKey(value.trim(), typed = true) { true }
    }
}

/**
 * The key a request about [profileId]'s connection at [baseUrl] uses: [typed] when given, else the one saved for that
 * profile (as a run on it would use it), which goes only where [ConnectionKey] says; else a local server's stand-in.
 */
internal fun ProviderConfig.connectionKey(context: Context, profileId: String, typed: String?, baseUrl: String): ConnectionKey {
    typed?.trim()?.takeIf { it.isNotEmpty() }?.let { return ConnectionKey.typed(it) }
    val saved = profileId.matchProfileId(profiles)?.let { id -> profiles.firstOrNull { it.id == id } }
    val savedKey = saved?.apiKey?.trim().orEmpty()
    if (saved == null || savedKey.isEmpty()) {
        val standIn = DEFAULT_LOCAL_API_KEY.takeIf { baseUrl.isLocalBaseUrl() }.orEmpty()
        return ConnectionKey(standIn, typed = false) { it.isLocalBaseUrl() }
    }
    return ConnectionKey(savedKey, typed = false) { url -> savedKeyMayGo(saved.baseUrl, url) || KeyDestinations.recorded(context, saved.id, savedKey, url) }
}

/** Whether a saved key of a profile at [profileBaseUrl] may go to [url]: over a safe scheme, to the same scheme, host and port. */
internal fun savedKeyMayGo(profileBaseUrl: String, url: String): Boolean {
    val target = url.origin() ?: return false
    return target.safe && target == profileBaseUrl.origin()
}

/** Whether [a] and [b] are on one scheme, host and port: a saved key that stays there goes nowhere new. */
internal fun sameOrigin(a: String, b: String): Boolean = a.origin()?.let { it == b.origin() } == true

/**
 * Addresses a check made with a typed key found for a profile ([ProviderAdapter.check]), kept per profile id with a
 * digest of that key: the profile's saved key may go to one later only when it is the key the check was made with.
 */
internal object KeyDestinations {
    /** Records [urls] for [profileId], reached with [key] (typed in the check's request). */
    fun record(context: Context, profileId: String, key: String, urls: Collection<String>) {
        val safe = urls.map { it.trim().trimEnd('/') }.filter { it.origin()?.safe == true }.distinct()
        if (safe.isEmpty() || key.isBlank()) return
        val keyDigest = digest(key.trim())
        val kept = read(context, profileId).filterNot { it.first in safe } + safe.map { it to keyDigest }
        val json = buildJsonArray {
            kept.takeLast(MAX_KEPT).forEach { (url, digest) -> add(buildJsonObject { put("url", url); put("key", digest) }) }
        }
        prefs(context).edit().putString(profileId, json.toString()).apply()
    }

    /** Whether [url] is, or is under, an address recorded for [profileId] with [key]. */
    fun recorded(context: Context, profileId: String, key: String, url: String): Boolean {
        val target = url.trim().trimEnd('/')
        if (target.origin()?.safe != true) return false
        val keyDigest = digest(key.trim())
        return read(context, profileId).any { (recorded, digest) -> digest == keyDigest && (target == recorded || target.startsWith("$recorded/")) }
    }

    /** The addresses kept for [profileId], each with the digest of the key that reached it. */
    private fun read(context: Context, profileId: String): List<Pair<String, String>> =
        prefs(context).getString(profileId, null)?.let { text ->
            runCatching {
                Json.parseToJsonElement(text).jsonArray.map { it.jsonObject }.map { it.getValue("url").jsonPrimitive.content to it.getValue("key").jsonPrimitive.content }
            }.getOrNull()
        }.orEmpty()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "app_server_key_destinations"
    private const val MAX_KEPT = 8
}

/** Scheme, host (lower case) and port of a URL, and whether a saved key may travel on it. */
private data class Origin(val scheme: String, val host: String, val port: Int) {
    /** https anywhere; plain http only to this device or a private network address written as one. */
    val safe: Boolean get() = scheme == "https" || scheme == "http" && host.isLocalHost()
}

private fun String.origin(): Origin? = runCatching {
    val uri = URI(trim())
    val scheme = uri.scheme?.lowercase() ?: return null
    val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    if (uri.rawUserInfo != null) return null
    val port = if (uri.port != -1) uri.port else DEFAULT_PORTS[scheme] ?: return null
    Origin(scheme, host, port)
}.getOrNull()

/** localhost, or an address literal on the loopback, a private or a link-local network; a name is never looked up. */
private fun String.isLocalHost(): Boolean {
    if (this == "localhost") return true
    val literal = removePrefix("[").removeSuffix("]")
    if (!IPV4.matches(literal) && ':' !in literal) return false
    val address = runCatching { InetAddress.getByName(literal) }.getOrNull() ?: return false
    val uniqueLocal = address.address.size == IPV6_BYTES && (address.address[0].toInt() and 0xfe) == 0xfc
    return address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress || uniqueLocal
}

private fun digest(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

private val DEFAULT_PORTS = mapOf("https" to 443, "http" to 80)
private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
private const val IPV6_BYTES = 16
