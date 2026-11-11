package cc.noharry.agent.appserver.packages

import cc.noharry.agent.appserver.protocol.HostReach
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * `host.http {method, url, headers, body}`: one request to a host the package named, answered as `{status, headers,
 * body}` (text). HTTPS, or HTTP to this device when the package named it; no redirect followed (a 3xx comes back as
 * it is); at most [ScriptLimits.sentBytes] sent and [ScriptLimits.readBytes] read; within what is left of the call's
 * time (no limit of its own). A secret goes in as `${name}` in a header value or as a whole query value and the host
 * fills it in as it sends: the script never has it, and an answer that repeats it has it replaced by its placeholder
 * again.
 * What a refusal or failure says never holds the address it was sent to or a secret.
 */
internal class ScriptHttp(
    private val grant: ScriptGrant,
    private val limits: ScriptLimits,
    private val client: OkHttpClient,
) {
    suspend fun send(request: JsonObject, leftMillis: Long): JsonElement {
        val prepared = prepare(request)
        val call = client.newBuilder().callTimeout(leftMillis, TimeUnit.MILLISECONDS).build().newCall(prepared)
        val fetched = call.fetch(limits.readBytes)
        return JsonObject(
            mapOf(
                "status" to JsonPrimitive(fetched.status),
                "headers" to JsonObject(fetched.headers.mapValues { JsonPrimitive(scrub(it.value)) }),
                "body" to JsonPrimitive(scrub(fetched.body)),
            ),
        )
    }

    private fun prepare(request: JsonObject): Request {
        (request.keys - REQUEST_KEYS).firstOrNull()?.let { refused("host.http: $it is not a member of a request") }
        val method = (text(request, "method") ?: GET).uppercase()
        if (method !in METHODS) refused("host.http: method $method is not one of ${METHODS.joinToString()}")
        val url = url(text(request, "url") ?: refused("host.http: no url"))
        val headers = headers(request["headers"])
        val body = text(request, "body")?.toByteArray(Charsets.UTF_8)
        if (body != null && method in NO_BODY) refused("host.http: a $method request has no body")
        val sent = url.toString().length + headers.sumOf { (name, value) -> name.length + value.length } + (body?.size ?: 0)
        if (sent > limits.sentBytes) refused("host.http: the request is over ${limits.sentBytes / KIB} KiB")
        val type = headers.lastOrNull { it.first.equals(CONTENT_TYPE, ignoreCase = true) }?.second?.toMediaTypeOrNull()
        val requestBody = body?.toRequestBody(type ?: DEFAULT_TYPE.toMediaTypeOrNull()) ?: if (method in NEEDS_BODY) ByteArray(0).toRequestBody(null) else null
        return try {
            Request.Builder().url(url).apply { headers.forEach { (name, value) -> header(name, value) } }.method(method, requestBody).build()
        } catch (_: IllegalArgumentException) {
            // OkHttp's message may repeat a header's value, a secret among them.
            refused("host.http: a header is not one HTTP sends")
        }
    }

    /** The address, on a host the package named, with each whole `${name}` query value filled in. */
    private fun url(raw: String): HttpUrl {
        if (raw.length > MAX_URL_CHARS || raw.any { it.isWhitespace() || it.isISOControl() } || '#' in raw) refused("host.http: the url is not one")
        val base = raw.substringBefore('?')
        if (PLACEHOLDER_START in base) refused("host.http: a secret goes in a header value or a whole query value, not in the address")
        val parsed = base.toHttpUrlOrNull() ?: refused("host.http: the url is not one")
        val host = parsed.host
        if (host !in grant.network) refused("host.http: $host is not among the hosts this package asked for")
        val authority = raw.removePrefix("${parsed.scheme}://$host")
        if (authority.length == raw.length || authority.firstOrNull()?.let { it in ":/?" } == false) refused("host.http: the url is not one")
        if (parsed.scheme != HTTPS && !(parsed.scheme == HTTP && hostReach(host) == HostReach.DEVICE)) refused("host.http: HTTPS only (HTTP only to this device)")
        val parameters = raw.substringAfter('?', "").split('&').filter(String::isNotEmpty).map(::parameter)
        val full = if (parameters.isEmpty()) base else "$base?${parameters.joinToString("&")}"
        return full.toHttpUrlOrNull()?.takeIf { it.host == host && it.scheme == parsed.scheme && it.port == parsed.port }
            ?: refused("host.http: the url is not one")
    }

    private fun parameter(parameter: String): String {
        val name = parameter.substringBefore('=')
        val value = parameter.substringAfter('=', "")
        if (PLACEHOLDER_START in name) refused("host.http: a secret in a parameter's name")
        if (PLACEHOLDER_START !in value) return parameter
        val secret = wholeSecretName(value) ?: refused("host.http: a secret is not a whole query value")
        return "$name=${URLEncoder.encode(secretValue(secret), Charsets.UTF_8.name())}"
    }

    private fun headers(element: JsonElement?): List<Pair<String, String>> {
        val headers = element?.let { it as? JsonObject ?: refused("host.http: headers is not an object") } ?: return emptyList()
        if (headers.size > MAX_HEADERS) refused("host.http: over $MAX_HEADERS headers")
        return headers.map { (name, value) ->
            if (!HEADER_NAME.matches(name) || name.lowercase() in RESERVED_HEADERS) refused("host.http: a script does not set the header ${name.take(MAX_NAME_CHARS)}")
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: refused("host.http: header $name is not a string")
            if (text.length > MAX_HEADER_CHARS || text.any { it.isISOControl() }) refused("host.http: header $name is too long or has control characters")
            val secrets = secretNamesIn(text) ?: refused("host.http: header $name has a broken placeholder")
            val values = secrets.associateWith(::secretValue)
            name to checkNotNull(substituteSecrets(text, values))
        }
    }

    private fun secretValue(name: String): String {
        if (name !in grant.secretNames) refused("host.http: \${$name} names no secret of this package")
        return grant.values[name]?.takeIf(String::isNotEmpty) ?: refused("host.http: the secret $name is not set")
    }

    /** [text] with each secret's value (and its form in an address) put back as its placeholder. */
    private fun scrub(text: String): String = grant.values.entries.filter { it.value.length >= MIN_SCRUBBED_CHARS }.fold(text) { scrubbed, (name, value) ->
        val placeholder = "\${$name}"
        scrubbed.replace(value, placeholder).replace(URLEncoder.encode(value, Charsets.UTF_8.name()), placeholder)
    }

    private fun text(request: JsonObject, key: String): String? =
        request[key]?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: refused("host.http: $key is not a string") }
}

/** An answer as read: its status, its headers (names in lower case, repeated ones joined) and its body as text. */
internal class Fetched(val status: Int, val headers: Map<String, String>, val body: String)

/** Runs the call, cancelled with the coroutine, and reads at most [maxBytes] of the answer. */
private suspend fun Call.fetch(maxBytes: Int): Fetched = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(ScriptRefusal("host.http: ${failure(e)}"))
        }

        override fun onResponse(call: Call, response: Response) {
            val read = try {
                Result.success(response.use { read(it, maxBytes) })
            } catch (failed: IOException) {
                Result.failure(ScriptRefusal("host.http: ${failure(failed)}"))
            } catch (refusal: ScriptRefusal) {
                Result.failure(refusal)
            }
            read.fold({ continuation.resume(it) }, { continuation.resumeWithException(it) })
        }
    })
}

private fun read(response: Response, maxBytes: Int): Fetched {
    val headers = response.headers.names().associate { name -> name.lowercase() to response.headers.values(name).joinToString(", ") }
    val bytes = response.body?.byteStream()?.use { stream ->
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            out.write(buffer, 0, count)
            if (out.size() > maxBytes) refused("host.http: the answer is over ${maxBytes / KIB} KiB")
        }
        out.toByteArray()
    } ?: ByteArray(0)
    return Fetched(response.code, headers, String(bytes, Charsets.UTF_8))
}

private fun failure(e: IOException): String = when (e) {
    is UnknownHostException -> "the host was not found where its name says it is"
    is SSLException -> "the connection was not secure"
    is ConnectException -> "could not connect"
    is InterruptedIOException -> "no answer in time"
    else -> "the request failed (${e.javaClass.simpleName})"
}

/**
 * A script's lookups: a name answers only with addresses where the name says it is ([hostReach]), so a name on the
 * internet that points into this device or the person's network is refused, and `localhost` stays on this device.
 */
internal class ScriptDns(private val system: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = system.lookup(hostname)
        val reach = hostReach(hostname)
        if (addresses.isEmpty() || addresses.any { addressReach(it) != reach }) throw UnknownHostException("not where its name says")
        return addresses
    }
}

/** Where an address is: this device, the person's own network (private, link-local, unique-local, shared), or the internet. */
internal fun addressReach(address: InetAddress): HostReach {
    val bytes = address.address
    val first = bytes[0].toInt() and BYTE
    val second = bytes.getOrNull(1)?.toInt()?.and(BYTE) ?: 0
    val local = address.isSiteLocalAddress || address.isLinkLocalAddress || address.isMulticastAddress ||
        (address is Inet6Address && first and IPV6_UNIQUE_LOCAL_MASK == IPV6_UNIQUE_LOCAL) ||
        (address !is Inet6Address && first == SHARED_FIRST && second and SHARED_SECOND_MASK == SHARED_SECOND)
    return when {
        address.isLoopbackAddress || address.isAnyLocalAddress -> HostReach.DEVICE
        local -> HostReach.LOCAL_NETWORK
        else -> HostReach.INTERNET
    }
}

/**
 * An HTTP client for scripts from [base] (the host's trust roots): no redirects, no retries, lookups as [ScriptDns],
 * and never through a proxy, which would look the name up itself and pass [ScriptDns] by.
 */
internal fun scriptHttpClient(base: OkHttpClient.Builder = OkHttpClient.Builder()): OkHttpClient =
    base.proxy(Proxy.NO_PROXY).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).dns(ScriptDns()).build()

private const val HTTPS = "https"
private const val HTTP = "http"
private const val GET = "GET"
private const val CONTENT_TYPE = "Content-Type"
private const val DEFAULT_TYPE = "text/plain; charset=utf-8"
private const val PLACEHOLDER_START = "\${"
private val METHODS = listOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE")
private val NO_BODY = setOf("GET", "HEAD")
private val NEEDS_BODY = setOf("POST", "PUT", "PATCH")
private val REQUEST_KEYS = setOf("method", "url", "headers", "body")
private val RESERVED_HEADERS = setOf(
    "host", "content-length", "transfer-encoding", "connection", "keep-alive", "upgrade", "te", "trailer",
    "proxy-authorization", "proxy-connection", "expect", "accept-encoding",
)
private val HEADER_NAME = Regex("""[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}""")
private const val MAX_URL_CHARS = 4096
private const val MAX_HEADERS = 20
private const val MAX_HEADER_CHARS = 4096
private const val MAX_NAME_CHARS = 64
private const val KIB = 1024
private const val BUFFER_BYTES = 8192

/** Shorter values are left in an answer: replacing them would garble it and hide little. */
private const val MIN_SCRUBBED_CHARS = 4
private const val BYTE = 0xFF
private const val IPV6_UNIQUE_LOCAL_MASK = 0xFE
private const val IPV6_UNIQUE_LOCAL = 0xFC

/** 100.64.0.0/10, the carriers' shared space. */
private const val SHARED_FIRST = 100
private const val SHARED_SECOND_MASK = 0xC0
private const val SHARED_SECOND = 64
