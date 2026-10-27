package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.core.loop.failed
import cc.noharry.agent.kernel.core.tools.NetworkToolActivityResult
import cc.noharry.agent.kernel.core.tools.NetworkToolPolicyDecision
import cc.noharry.agent.kernel.core.tools.NetworkToolPort
import cc.noharry.agent.transport.NetworkAddressDeniedException
import cc.noharry.agent.transport.OkHttpWebFetchTransport
import cc.noharry.agent.transport.WebFetchRequest
import cc.noharry.agent.transport.WebFetchResponse
import cc.noharry.agent.transport.WebFetchTransport
import cc.noharry.agent.transport.isDeniedNetworkTarget
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal class NetworkToolBackend(
    private val transport: WebFetchTransport = OkHttpWebFetchTransport(),
    private val confirmationPolicy: NetworkToolConfirmationPolicy = NetworkToolConfirmationPolicy.REQUIRE_PER_HOST,
    private val fetchDeadlineMillis: Long = WEB_FETCH_TOTAL_TIMEOUT_MILLIS,
    /** Runs web_search. */
    private val searchService: WebSearchService? = null,
    /** Fetches a page web_fetch's own request could not get. */
    private val fetchFallback: WebFetchService? = null,
) : NetworkToolPort {
    private val confirmedHostsByRun = ConcurrentHashMap<String, MutableSet<String>>()

    init {
        require(fetchDeadlineMillis > 0L) { "fetchDeadlineMillis must be positive." }
    }

    override fun authorize(runId: String, argumentsJson: String): NetworkToolPolicyDecision {
        val target = parseFetchTarget(argumentsJson)
        target.errorResult?.let { return NetworkToolPolicyDecision.Denied(it) }
        val host = requireNotNull(target.host)
        if (confirmationPolicy == NetworkToolConfirmationPolicy.ALLOW_ALL_HOSTS) {
            return NetworkToolPolicyDecision.Allowed
        }
        if (confirmedHostsByRun[runId]?.contains(host) == true) {
            return NetworkToolPolicyDecision.Allowed
        }
        return NetworkToolPolicyDecision.RequiresConfirmation(
            "Allow web_fetch to access ${host}?",
        )
    }

    /** A search goes to the configured service, never to a page's host: nothing to confirm, only a query to check. */
    override fun authorize(runId: String, toolName: String, argumentsJson: String): NetworkToolPolicyDecision =
        if (toolName == WEB_SEARCH_TOOL_NAME) {
            webSearchArgumentsError(argumentsJson)?.let { NetworkToolPolicyDecision.Denied(it) } ?: NetworkToolPolicyDecision.Allowed
        } else {
            authorize(runId, argumentsJson)
        }

    override fun rememberConfirmed(runId: String, argumentsJson: String) {
        val target = parseFetchTarget(argumentsJson)
        val host = target.host ?: return
        if (target.errorResult != null) return
        confirmedHostsByRun.computeIfAbsent(runId) { ConcurrentHashMap.newKeySet() }.add(host)
    }

    fun clearRun(runId: String) {
        confirmedHostsByRun.remove(runId)
    }

    internal fun cachedConfirmedRunCount(): Int = confirmedHostsByRun.size

    override fun activityDomain(toolName: String, argumentsJson: String): String =
        when (toolName) {
            WEB_FETCH_TOOL_NAME -> parseFetchTarget(argumentsJson).host.orEmpty()
            WEB_SEARCH_TOOL_NAME -> searchService?.label.orEmpty()
            else -> ""
        }

    override fun activityResult(resultJson: String): NetworkToolActivityResult {
        val result = parseObject(resultJson) ?: return NetworkToolActivityResult()
        return NetworkToolActivityResult(
            statusCode = result.intField("statusCode"),
            truncated = result["truncated"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
        )
    }

    override suspend fun execute(toolName: String, argumentsJson: String): String =
        when (toolName) {
            WEB_FETCH_TOOL_NAME -> webFetch(argumentsJson)
            WEB_SEARCH_TOOL_NAME -> searchService?.search?.invoke(argumentsJson)
                ?: errorResult(RuntimeErrorCode.UNSUPPORTED_NATIVE_TOOL.wireCode, "web_search is not configured.")
            else -> errorResult(
                RuntimeErrorCode.UNSUPPORTED_NATIVE_TOOL.wireCode,
                "Unsupported network tool: $toolName",
            )
        }

    private suspend fun webFetch(argumentsJson: String): String {
        val target = parseFetchTarget(argumentsJson)
        target.errorResult?.let { return it }
        val url = requireNotNull(target.url)
        return withFallback(url, target.maxBytes, fetchDirect(url, target.maxBytes))
    }

    /**
     * When the site refused the device or the request failed, the fetch fallback may get the page instead (Markdown,
     * without pictures); a page the device's own request read is never replaced, nor a refused private address.
     */
    private suspend fun withFallback(url: HttpUrl, maxBytes: Int, direct: String): String {
        val service = fetchFallback ?: return direct
        if (!direct.fetchFailedOrRefused()) return direct
        val (title, markdown) = try {
            withTimeoutOrNull(FALLBACK_FETCH_TIMEOUT_MILLIS) { service.fetch(url.toString()) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return direct
        val bounded = markdown.boundedUtf8(maxBytes)
        return buildJsonObject {
            put("ok", true)
            put("statusCode", HTTP_OK)
            put("contentType", "text/markdown")
            put("bytesRead", bounded.bytes)
            put("truncated", bounded.truncated)
            put("textExtracted", true)
            put("via", service.label)
            put("title", title)
            put("body", bounded.text)
        }.toString()
    }

    private suspend fun fetchDirect(url: HttpUrl, maxBytes: Int): String {
        return try {
            withTimeoutOrNull(fetchDeadlineMillis) {
                fetchFollowingRedirects(url, maxBytes)
            } ?: errorResult(
                    RuntimeErrorCode.NETWORK_FETCH_FAILED.wireCode,
                    "Web fetch deadline exceeded.",
                    retryable = true,
                )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (error.hasDeniedNetworkAddressCause()) {
                privateAddressDeniedResult()
            } else {
                errorResult(
                    RuntimeErrorCode.NETWORK_FETCH_FAILED.wireCode,
                    error.message ?: "Network fetch failed.",
                    retryable = true,
                )
            }
        }
    }

    private suspend fun fetchFollowingRedirects(initialUrl: HttpUrl, maxBytes: Int): String {
        var currentUrl = initialUrl
        var redirectsFollowed = 0
        while (true) {
            if (currentUrl.host.isDeniedLiteralHost()) return privateAddressDeniedResult()
            val response = transport.execute(
                WebFetchRequest(
                    url = currentUrl.toString(),
                    headers = WEB_FETCH_REQUEST_HEADERS,
                    maxTransferBytes = MAX_WEB_FETCH_TRANSFER_BYTES,
                ),
            )
            val location = response.redirectLocation()
            if (!response.isRedirect() || location == null) return successResult(response, maxBytes, currentUrl)
            if (redirectsFollowed >= MAX_WEB_FETCH_REDIRECTS) {
                return fetchPolicyError("Redirect limit exceeded.")
            }
            val nextUrl = normalizedRedirect(currentUrl, location)
                ?: return invalidUrlResult("Redirect URL must be absolute or relative http/https.")
            redirectPolicyError(currentUrl, nextUrl)?.let { return it }
            currentUrl = nextUrl
            redirectsFollowed += 1
        }
    }

    private fun redirectPolicyError(currentUrl: HttpUrl, nextUrl: HttpUrl): String? {
        if (nextUrl.host.isDeniedLiteralHost()) return privateAddressDeniedResult()
        if (currentUrl.isHttps && !nextUrl.isHttps) {
            return fetchPolicyError("HTTPS to HTTP redirects are denied.")
        }
        if (currentUrl.host != nextUrl.host) {
            return fetchPolicyError(
                "Cross-host redirect to ${nextUrl.host} is denied; call web_fetch for that host explicitly.",
            )
        }
        return null
    }

    private fun successResult(response: WebFetchResponse, maxBytes: Int, page: HttpUrl): String {
        val contentType = response.contentType()
        val textExtracted = contentType.contains("text/html", ignoreCase = true)
        val decoded = response.body.decodeCompleteUtf8Prefix()
        val text = if (textExtracted) {
            extractHtmlText(decoded.text)
        } else {
            decoded.text
        }
        val bounded = text.boundedUtf8(maxBytes)
        return buildJsonObject {
            put("ok", true)
            put("statusCode", response.statusCode)
            put("contentType", contentType)
            put("bytesRead", bounded.bytes)
            put("truncated", response.transferTruncated || decoded.trailingDropped || bounded.truncated)
            put("textExtracted", textExtracted)
            if (textExtracted) webPageLeadImage(decoded.text, page)?.let { put("image", it) }
            put("body", bounded.text)
        }.toString()
    }

    private fun parseFetchTarget(argumentsJson: String): FetchTarget {
        val arguments = parseObject(argumentsJson)
            ?: return FetchTarget(errorResult = errorResult(RuntimeErrorCode.NETWORK_URL_INVALID.wireCode, "web_fetch arguments must be a JSON object."))
        val url = arguments.stringField("url")?.trim().orEmpty()
        val maxBytes = arguments.intField("max_bytes") ?: DEFAULT_WEB_FETCH_MAX_BYTES
        if (maxBytes <= 0 || maxBytes > MAX_WEB_FETCH_BYTES) {
            return FetchTarget(errorResult = errorResult(RuntimeErrorCode.READ_BUDGET_EXCEEDED.wireCode, "max_bytes out of range."))
        }
        val normalizedUrl = normalizedUrl(url)
            ?: return FetchTarget(errorResult = errorResult(RuntimeErrorCode.NETWORK_URL_INVALID.wireCode, "URL must be absolute http or https."))
        val host = normalizedUrl.host
        if (host.isDeniedLiteralHost()) {
            return FetchTarget(host = host, errorResult = privateAddressDeniedResult())
        }
        return FetchTarget(url = normalizedUrl, host = host, maxBytes = maxBytes)
    }

    private fun normalizedUrl(url: String): HttpUrl? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
        val canonicalHost = parsed.host.trimEnd('.')
        if (canonicalHost.isBlank()) return null
        return parsed.newBuilder().host(canonicalHost).fragment(null).build()
    }

    private fun normalizedRedirect(base: HttpUrl, location: String): HttpUrl? =
        base.resolve(location)?.let { normalizedUrl(it.toString()) }
}

/** Candidate API: controls whether [NetworkToolBackend] gates web_fetch per host. */
enum class NetworkToolConfirmationPolicy {
    /** Default: each new host in a run requires explicit confirmation before transport. */
    REQUIRE_PER_HOST,

    /** Skip host-level confirmation entirely; any non-denied fetch proceeds directly. */
    ALLOW_ALL_HOSTS,
}

private data class FetchTarget(
    val url: HttpUrl? = null,
    val host: String? = null,
    val maxBytes: Int = DEFAULT_WEB_FETCH_MAX_BYTES,
    val errorResult: String? = null,
)

internal fun extractHtmlText(html: String): String = LinearHtmlTextExtractor(html).extract()

private class LinearHtmlTextExtractor(
    private val html: String,
) {
    private val output = NormalizedHtmlText()
    private var index = 0
    private var hiddenTag: String? = null

    fun extract(): String {
        while (index < html.length) {
            val tagStart = html.indexOf('<', index)
            if (tagStart < 0) {
                appendVisibleText(index, html.length)
                break
            }
            appendVisibleText(index, tagStart)
            if (html.startsWith("<!--", tagStart)) {
                skipComment(tagStart)
                continue
            }
            val tag = parseHtmlTag(html, tagStart)
            if (tag == null) {
                appendVisibleText(tagStart, html.length)
                break
            }
            handleTag(tag)
            index = tag.endExclusive
        }
        return output.finish()
    }

    private fun appendVisibleText(start: Int, end: Int) {
        if (hiddenTag == null) output.append(html, start, end)
        index = end
    }

    private fun skipComment(start: Int) {
        if (hiddenTag == null) output.appendSpace()
        val end = html.indexOf("-->", startIndex = start + HTML_COMMENT_OPEN.length)
        index = if (end < 0) html.length else end + HTML_COMMENT_CLOSE.length
    }

    private fun handleTag(tag: HtmlTag) {
        val hidden = hiddenTag
        if (hidden != null) {
            closeHiddenTag(tag, hidden)
            return
        }
        if (!tag.closing && !tag.selfClosing && tag.name in HTML_INVISIBLE_TAGS) {
            output.appendSpace()
            hiddenTag = tag.name
            return
        }
        if (tag.name in HTML_BLOCK_BREAK_TAGS) output.lineBreak() else output.appendSpace()
    }

    private fun closeHiddenTag(tag: HtmlTag, hidden: String) {
        if (tag.name == hidden && tag.closing) {
            hiddenTag = null
            output.appendSpace()
        }
    }
}

private class NormalizedHtmlText {
    private val output = StringBuilder()
    private val line = StringBuilder()
    private var pendingSpace = false

    fun append(source: String, start: Int, end: Int) {
        var cursor = start
        while (cursor < end) {
            val entity = decodeBasicHtmlEntity(source, cursor, end)
            if (entity != null) {
                appendDecoded(entity.value)
                cursor = entity.endExclusive
                continue
            }
            cursor += appendCharacter(source, cursor, end)
        }
    }

    fun appendSpace() {
        if (line.isNotEmpty()) pendingSpace = true
    }

    fun lineBreak() {
        flushLine()
    }

    fun finish(): String {
        flushLine()
        return output.toString()
    }

    private fun appendCharacter(source: String, index: Int, end: Int): Int =
        when (val char = source[index]) {
            '\r' -> {
                lineBreak()
                if (index + 1 < end && source[index + 1] == '\n') 2 else 1
            }
            '\n' -> {
                lineBreak()
                1
            }
            ' ', '\t', '\u00A0' -> {
                appendSpace()
                1
            }
            else -> {
                appendCharacter(char)
                1
            }
        }

    private fun appendDecoded(value: String) {
        if (value == " ") {
            appendSpace()
            return
        }
        if (pendingSpace && line.isNotEmpty()) line.append(' ')
        pendingSpace = false
        line.append(value)
    }

    private fun appendCharacter(value: Char) {
        if (pendingSpace && line.isNotEmpty()) line.append(' ')
        pendingSpace = false
        line.append(value)
    }

    private fun flushLine() {
        if (line.isNotEmpty()) {
            if (output.isNotEmpty()) output.append('\n')
            output.append(line)
            line.clear()
        }
        pendingSpace = false
    }
}

private data class HtmlTag(
    val name: String,
    val closing: Boolean,
    val selfClosing: Boolean,
    val endExclusive: Int,
)

private data class DecodedHtmlEntity(
    val value: String,
    val endExclusive: Int,
)

private fun parseHtmlTag(html: String, start: Int): HtmlTag? {
    val end = findHtmlTagEnd(html, start + 1)
    if (end < 0) return null
    var cursor = start + 1
    while (cursor < end && html[cursor].isWhitespace()) cursor += 1
    val closing = cursor < end && html[cursor] == '/'
    if (closing) cursor += 1
    while (cursor < end && html[cursor].isWhitespace()) cursor += 1
    val nameStart = cursor
    while (cursor < end && html[cursor].isHtmlTagNameCharacter()) cursor += 1
    val name = html.substring(nameStart, cursor).lowercase()
    var suffix = end - 1
    while (suffix > start && html[suffix].isWhitespace()) suffix -= 1
    return HtmlTag(
        name = name,
        closing = closing,
        selfClosing = html[suffix] == '/',
        endExclusive = end + 1,
    )
}

private fun findHtmlTagEnd(html: String, start: Int): Int {
    var quote: Char? = null
    for (index in start until html.length) {
        val char = html[index]
        if (quote == null && (char == '\'' || char == '"')) {
            quote = char
        } else if (quote == char) {
            quote = null
        } else if (quote == null && char == '>') {
            return index
        }
    }
    return -1
}

private fun decodeBasicHtmlEntity(source: String, start: Int, end: Int): DecodedHtmlEntity? {
    if (source[start] != '&') return null
    for ((encoded, decoded) in BASIC_HTML_ENTITIES) {
        if (start + encoded.length <= end && source.regionMatches(start, encoded, 0, encoded.length)) {
            return DecodedHtmlEntity(decoded, start + encoded.length)
        }
    }
    return null
}

private fun Char.isHtmlTagNameCharacter(): Boolean = isLetterOrDigit() || this == '-' || this == ':'

private const val HTML_COMMENT_OPEN = "<!--"
private const val HTML_COMMENT_CLOSE = "-->"
private val HTML_INVISIBLE_TAGS = setOf("script", "style", "noscript", "svg", "head")
private val HTML_BLOCK_BREAK_TAGS = setOf(
    "p", "div", "br", "li", "ul", "ol", "tr", "table", "h1", "h2", "h3", "h4", "h5", "h6",
    "section", "article", "header", "footer",
)
private val BASIC_HTML_ENTITIES = listOf(
    "&nbsp;" to " ",
    "&lt;" to "<",
    "&gt;" to ">",
    "&quot;" to "\"",
    "&#39;" to "'",
    "&apos;" to "'",
    "&amp;" to "&",
)

private fun parseObject(value: String): JsonObject? =
    try {
        Json.parseToJsonElement(value) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

private fun JsonObject.stringField(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.intField(name: String): Int? =
    this[name]?.jsonPrimitive?.intOrNull

private fun WebFetchResponse.contentType(): String =
    headers.entries.firstOrNull { it.key.equals("content-type", ignoreCase = true) }
        ?.value
        ?.firstOrNull()
        .orEmpty()

private fun WebFetchResponse.redirectLocation(): String? =
    headers.entries.firstOrNull { it.key.equals("location", ignoreCase = true) }
        ?.value
        ?.firstOrNull()
        ?.takeIf { it.isNotBlank() }

private fun WebFetchResponse.isRedirect(): Boolean = statusCode in WEB_FETCH_REDIRECT_STATUS_CODES

private fun String.isDeniedLiteralHost(): Boolean {
    val isIpv6Literal = contains(':')
    val isIpv4Literal = isNotEmpty() && all { it.isDigit() || it == '.' }
    if (!isIpv6Literal && !isIpv4Literal) return false
    return runCatching { InetAddress.getByName(this).isDeniedNetworkTarget() }.getOrDefault(false)
}

private fun Throwable.hasDeniedNetworkAddressCause(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is NetworkAddressDeniedException) return true
        current = current.cause
    }
    return false
}

private fun privateAddressDeniedResult(): String =
    errorResult(
        RuntimeErrorCode.NETWORK_PRIVATE_ADDRESS_DENIED.wireCode,
        "Private, local, or non-routable network targets are denied.",
    )

private fun invalidUrlResult(message: String): String =
    errorResult(RuntimeErrorCode.NETWORK_URL_INVALID.wireCode, message)

/** The device's own rule refused the request (a redirect it does not follow); the fetch fallback must not get round it. */
private fun fetchPolicyError(message: String): String =
    errorResult(RuntimeErrorCode.NETWORK_FETCH_FAILED.wireCode, message, refusedByPolicy = true)

/** A failed request, or a site that refused or broke on the device's request (not a page that is simply missing). */
private fun String.fetchFailedOrRefused(): Boolean {
    val result = parseObject(this) ?: return false
    if (result["ok"]?.jsonPrimitive?.contentOrNull == "false") {
        // A cross-host or downgrading redirect is the device's refusal: fetched elsewhere, its target would skip the
        // per-host confirmation.
        return result["code"]?.jsonPrimitive?.contentOrNull == RuntimeErrorCode.NETWORK_FETCH_FAILED.wireCode &&
            result[REFUSED_BY_POLICY]?.jsonPrimitive?.contentOrNull != "true"
    }
    val status = result.intField("statusCode") ?: return false
    return (status in HTTP_CLIENT_ERRORS && status !in MISSING_STATUS_CODES) || status >= HTTP_SERVER_ERROR
}

private fun errorResult(code: String, message: String, retryable: Boolean = false, refusedByPolicy: Boolean = false): String =
    buildJsonObject {
        put("ok", false)
        put("code", code)
        put("message", message)
        put("retryable", retryable)
        if (refusedByPolicy) put(REFUSED_BY_POLICY, true)
    }.toString()

private const val REFUSED_BY_POLICY = "refusedByPolicy"

private data class DecodedUtf8Prefix(
    val text: String,
    val trailingDropped: Boolean,
)

private data class BoundedUtf8Text(
    val text: String,
    val bytes: Int,
    val truncated: Boolean,
)

private fun ByteArray.decodeCompleteUtf8Prefix(): DecodedUtf8Prefix {
    val completeBytes = completeUtf8PrefixLength()
    return DecodedUtf8Prefix(
        text = copyOf(completeBytes).toString(Charsets.UTF_8),
        trailingDropped = completeBytes < size,
    )
}

private fun ByteArray.completeUtf8PrefixLength(): Int {
    var index = 0
    var complete = 0
    while (index < size) {
        val sequenceBytes = utf8SequenceBytes(this[index])
        if (sequenceBytes == 1 || sequenceBytes == 0) {
            index += 1
            complete = index
            continue
        }
        if (index + sequenceBytes > size) return complete
        if ((1 until sequenceBytes).any { offset -> !this[index + offset].isUtf8Continuation() }) {
            index += 1
            complete = index
            continue
        }
        index += sequenceBytes
        complete = index
    }
    return complete
}

private fun String.boundedUtf8(maxBytes: Int): BoundedUtf8Text {
    val output = StringBuilder()
    var byteCount = 0
    var index = 0
    while (index < length) {
        val codePoint = codePointAt(index)
        val chunk = String(Character.toChars(codePoint))
        val chunkBytes = chunk.toByteArray(Charsets.UTF_8).size
        if (byteCount + chunkBytes > maxBytes) {
            return BoundedUtf8Text(output.toString(), byteCount, truncated = true)
        }
        output.append(chunk)
        byteCount += chunkBytes
        index += Character.charCount(codePoint)
    }
    return BoundedUtf8Text(output.toString(), byteCount, truncated = false)
}

private fun utf8SequenceBytes(byte: Byte): Int = when (byte.toInt() and UTF8_BYTE_MASK) {
    in 0x00..0x7F -> 1
    in 0xC2..0xDF -> 2
    in 0xE0..0xEF -> 3
    in 0xF0..0xF4 -> 4
    else -> 0
}

private fun Byte.isUtf8Continuation(): Boolean =
    (toInt() and UTF8_CONTINUATION_MASK) == UTF8_CONTINUATION_PREFIX

private const val WEB_FETCH_TOOL_NAME = "web_fetch"
private const val WEB_SEARCH_TOOL_NAME = "web_search"
private const val FALLBACK_FETCH_TIMEOUT_MILLIS = 20_000L
private const val HTTP_OK = 200
private const val HTTP_SERVER_ERROR = 500
private val HTTP_CLIENT_ERRORS = 400..499
/** "No such page": another fetcher would get the same. Any other 4xx is a site refusing the device (携程 answers 432). */
private val MISSING_STATUS_CODES = setOf(404, 410)
private const val DEFAULT_WEB_FETCH_MAX_BYTES = 32 * 1024
private const val MAX_WEB_FETCH_BYTES = 128 * 1024
private const val MAX_WEB_FETCH_TRANSFER_BYTES = 512 * 1024
private const val MAX_WEB_FETCH_REDIRECTS = 5
private const val WEB_FETCH_TOTAL_TIMEOUT_MILLIS = 30_000L
private const val UTF8_BYTE_MASK = 0xFF
private const val UTF8_CONTINUATION_MASK = 0xC0
private const val UTF8_CONTINUATION_PREFIX = 0x80

private val WEB_FETCH_REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)

private val WEB_FETCH_REQUEST_HEADERS = mapOf(
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
)
