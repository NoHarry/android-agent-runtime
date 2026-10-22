package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.transport.WebFetchRequest
import cc.noharry.agent.transport.WebFetchResponse
import cc.noharry.agent.transport.WebFetchTransport
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebFetchHtmlExtractionTest {
    @Test
    fun aPageThatNamesItsPictureHandsItBack() = runBlocking {
        val html = "<html><head><meta property=\"og:title\" content=\"市集\">" +
            "<meta content=\"/img/poster.jpg?w=800&amp;h=600\" property=\"og:image\"></head><body>周末市集</body></html>"
        val backend = NetworkToolBackend(transport = FixedHtmlTransport(html), confirmationPolicy = NetworkToolConfirmationPolicy.ALLOW_ALL_HOSTS)

        val result = Json.parseToJsonElement(backend.execute("web_fetch", """{"url":"https://example.com/events/1"}""")).jsonObject

        assertEquals("https://example.com/img/poster.jpg?w=800&h=600", result["image"]?.jsonPrimitive?.content)
    }

    @Test
    fun onlyHttpsPicturesAreHandedBack() {
        val page = okhttp3.HttpUrl.Builder().scheme("https").host("example.com").build()

        assertEquals(null, webPageLeadImage("<meta property=\"og:image\" content=\"http://cdn.example.com/a.jpg\">", page))
        assertEquals(null, webPageLeadImage("<p>no picture</p>", page))
        assertEquals("https://cdn.example.com/b.jpg", webPageLeadImage("<meta name=\"twitter:image\" content=\"https://cdn.example.com/b.jpg\">", page))
    }

    @Test
    fun htmlBodyIsReducedToTextBeforeByteBudget() = runBlocking {
        val html = buildString {
            append("<!DOCTYPE html><html><head><title>ignored</title>")
            append("<script>var boilerplate='x'.repeat(9999);</script>")
            append("<style>.a{color:red}</style></head><body>")
            append("<!-- comment --><div>Search Result One &amp; Two</div>")
            append("<p>王菲 主题曲 结果</p></body></html>")
        }
        val backend = NetworkToolBackend(
            transport = FixedHtmlTransport(html),
            confirmationPolicy = NetworkToolConfirmationPolicy.ALLOW_ALL_HOSTS,
        )

        val result = backend.execute("web_fetch", """{"url":"https://example.com/search","max_bytes":5000}""")

        assertTrue(result.contains(""""ok":true"""))
        assertTrue(result.contains(""""textExtracted":true"""))
        assertTrue(result.contains("Search Result One & Two"))
        assertTrue(result.contains("王菲 主题曲 结果"))
        assertFalse(result.contains("boilerplate"))
        assertFalse(result.contains("<div>"))
        assertFalse(result.contains("color:red"))
    }

    @Test
    fun byteBudgetAppliesToExtractedTextNotRawHtml() = runBlocking {
        val html = "<html><head><script>${"junk".repeat(3000)}</script></head>" +
            "<body><p>useful answer text</p></body></html>"
        val backend = NetworkToolBackend(
            transport = FixedHtmlTransport(html),
            confirmationPolicy = NetworkToolConfirmationPolicy.ALLOW_ALL_HOSTS,
        )

        val result = backend.execute("web_fetch", """{"url":"https://example.com/page","max_bytes":100}""")

        assertTrue(result.contains("useful answer text"))
        assertTrue(result.contains(""""truncated":false"""))
    }

    @Test
    fun nonHtmlBodyIsReturnedRaw() = runBlocking {
        val backend = NetworkToolBackend(
            transport = FixedHtmlTransport("""<rss><item>title</item></rss>""", contentType = "text/xml; charset=utf-8"),
            confirmationPolicy = NetworkToolConfirmationPolicy.ALLOW_ALL_HOSTS,
        )

        val result = backend.execute("web_fetch", """{"url":"https://example.com/feed"}""")

        assertTrue(result.contains(""""textExtracted":false"""))
        assertTrue(result.contains("<rss>"))
    }

    @Test
    fun webFetchSendsContentNegotiationHeadersWithoutUserAgentSpoof() = runBlocking {
        val transport = FixedHtmlTransport("<html><body>ok</body></html>")
        val backend = NetworkToolBackend(
            transport = transport,
            confirmationPolicy = NetworkToolConfirmationPolicy.ALLOW_ALL_HOSTS,
        )

        backend.execute("web_fetch", """{"url":"https://example.com/page"}""")

        val headers = requireNotNull(transport.lastRequestHeaders)
        assertTrue(headers.getValue("Accept").contains("text/html"))
        assertTrue(headers.containsKey("Accept-Language"))
        assertFalse(headers.containsKey("User-Agent"))
    }

    @Test
    fun byteBudgetKeepsOnlyCompleteUtf8CodePoints() = runBlocking {
        val backend = NetworkToolBackend(
            transport = FixedHtmlTransport("你好吗", contentType = "text/plain; charset=utf-8"),
            confirmationPolicy = NetworkToolConfirmationPolicy.ALLOW_ALL_HOSTS,
        )

        val result = backend.execute("web_fetch", """{"url":"https://example.com/text","max_bytes":5}""")
        val json = Json.parseToJsonElement(result).jsonObject

        assertEquals("你", json.getValue("body").jsonPrimitive.content)
        assertEquals(3, json.getValue("bytesRead").jsonPrimitive.int)
        assertEquals("true", json.getValue("truncated").jsonPrimitive.content)
        assertFalse(result.contains('\uFFFD'))
    }

    @Test
    fun controlCharactersAreSerializedAsValidJson() = runBlocking {
        val body = "a\tb\u0000c\b\u000Cd"
        val backend = NetworkToolBackend(
            transport = FixedHtmlTransport(body, contentType = "text/plain"),
            confirmationPolicy = NetworkToolConfirmationPolicy.ALLOW_ALL_HOSTS,
        )

        val result = backend.execute("web_fetch", """{"url":"https://example.com/control"}""")

        assertEquals(body, Json.parseToJsonElement(result).jsonObject.getValue("body").jsonPrimitive.content)
    }

    @Test
    fun extractHtmlTextKeepsLineStructureAndDecodesEntities() {
        val text = extractHtmlText(
            "<div>First&nbsp;line</div><li>item &lt;a&gt;</li><span>same   line</span>",
        )

        assertEquals("First line\nitem <a>\nsame line", text)
    }

    @Test
    fun hiddenRawTextClosesAtFirstMatchingEndTag() {
        val text = extractHtmlText(
            """<script>const markup = "<script>";</script><p>visible</p>""",
        )

        assertEquals("visible", text)
    }

    @Test(timeout = 2_000L)
    fun malformedHiddenTagsAreProcessedInLinearTime() {
        val html = buildString {
            repeat(MALFORMED_HTML_TAG_COUNT) { append("<script>") }
        }

        assertEquals("", extractHtmlText(html))
    }

    @Test(timeout = 2_000L)
    fun redirectChainUsesOneEndToEndDeadline() = runBlocking {
        val transport = SlowRedirectTransport()
        val backend = NetworkToolBackend(
            transport = transport,
            confirmationPolicy = NetworkToolConfirmationPolicy.ALLOW_ALL_HOSTS,
            fetchDeadlineMillis = TEST_FETCH_DEADLINE_MILLIS,
        )

        val result = backend.execute("web_fetch", """{"url":"https://example.com/loop"}""")

        assertTrue(result.contains(RuntimeErrorCode.NETWORK_FETCH_FAILED.wireCode))
        assertTrue(result.contains("Web fetch deadline exceeded."))
        assertTrue(transport.callCount < MAX_REDIRECT_CHAIN_CALLS_WITHOUT_DEADLINE)
    }

    @Test
    fun callerTimeoutCancellationIsNotConvertedToNetworkFailure() = runBlocking {
        val backend = NetworkToolBackend(
            transport = SlowRedirectTransport(),
            confirmationPolicy = NetworkToolConfirmationPolicy.ALLOW_ALL_HOSTS,
            fetchDeadlineMillis = 5_000L,
        )
        var cancellation: TimeoutCancellationException? = null

        try {
            withTimeout(TEST_CALLER_TIMEOUT_MILLIS) {
                backend.execute("web_fetch", """{"url":"https://example.com/loop"}""")
            }
        } catch (error: TimeoutCancellationException) {
            cancellation = error
        }

        assertTrue(cancellation != null)
    }
}

private class FixedHtmlTransport(
    private val body: String,
    private val contentType: String = "text/html; charset=utf-8",
) : WebFetchTransport {
    var lastRequestHeaders: Map<String, String>? = null

    override suspend fun execute(request: WebFetchRequest): WebFetchResponse {
        lastRequestHeaders = request.headers
        return WebFetchResponse(
            statusCode = 200,
            headers = mapOf("Content-Type" to listOf(contentType)),
            body = body.toByteArray(Charsets.UTF_8),
        )
    }
}

private class SlowRedirectTransport : WebFetchTransport {
    var callCount: Int = 0
        private set

    override suspend fun execute(request: WebFetchRequest): WebFetchResponse {
        callCount += 1
        delay(TEST_REDIRECT_DELAY_MILLIS)
        return WebFetchResponse(
            statusCode = 302,
            headers = mapOf("Location" to listOf("/loop")),
        )
    }
}

private const val MALFORMED_HTML_TAG_COUNT = 8_000
private const val TEST_FETCH_DEADLINE_MILLIS = 90L
private const val TEST_CALLER_TIMEOUT_MILLIS = 20L
private const val TEST_REDIRECT_DELAY_MILLIS = 50L
private const val MAX_REDIRECT_CHAIN_CALLS_WITHOUT_DEADLINE = 6
