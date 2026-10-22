package cc.noharry.agent.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

class OkHttpWebFetchTransportTest {
    @Test
    fun guardedDnsReturnsOnlyValidatedConnectionAddresses() {
        val publicAddress = InetAddress.getByName("8.8.8.8")
        val privateAddress = InetAddress.getByName("10.0.0.8")
        val dns = GuardedNetworkDns(fixedDns(privateAddress, publicAddress))

        val resolved = dns.lookup("example.com")

        assertEquals(listOf(publicAddress), resolved)
    }

    @Test
    fun guardedDnsFailsClosedForEmptyOrDeniedResults() {
        assertUnknownHost { GuardedNetworkDns(fixedDns()).lookup("empty.example") }
        val denied = try {
            GuardedNetworkDns(fixedDns(InetAddress.getByName("127.0.0.1"))).lookup("local.example")
            null
        } catch (error: NetworkAddressDeniedException) {
            error
        }

        assertTrue(denied != null)
    }

    @Test
    fun addressPolicyRejectsAllRequiredNonPublicRanges() {
        val denied = listOf(
            "0.0.0.0",
            "10.0.0.1",
            "100.64.0.1",
            "127.0.0.1",
            "169.254.1.1",
            "172.16.0.1",
            "192.0.0.1",
            "192.0.2.1",
            "192.88.99.1",
            "192.168.0.1",
            "198.18.0.1",
            "198.51.100.1",
            "203.0.113.1",
            "224.0.0.1",
            "::",
            "::1",
            "100::1",
            "64:ff9b::a00:1",
            "64:ff9b:1::1",
            "2001::1",
            "2001:2::1",
            "2001:db8::1",
            "2002:a00:1::1",
            "3ffe::1",
            "fc00::1",
            "fd00::1",
            "fe80::1",
            "ff02::1",
        ).map(InetAddress::getByName) + mappedIpv6Address(127, 0, 0, 1)

        assertTrue(denied.all(InetAddress::isDeniedNetworkTarget))
        assertFalse(InetAddress.getByName("8.8.8.8").isDeniedNetworkTarget())
        assertFalse(InetAddress.getByName("2001:4860:4860::8888").isDeniedNetworkTarget())
        assertFalse(InetAddress.getByName("64:ff9b::808:808").isDeniedNetworkTarget())
    }

    @Test
    fun networkSpecificNat64PolicySupportsEveryRfc6052PrefixLength() {
        RFC6052_TEST_PREFIX_LENGTHS.forEach { prefixLength ->
            val prefix = testNat64Prefix(prefixLength)
            val resolution = Nat64PrefixResolution.Present(prefix)
            val privateTarget = rfc6052Address(prefix, byteArrayOf(10, 0, 0, 8))
            val publicTarget = rfc6052Address(prefix, byteArrayOf(8, 8, 8, 8))

            assertTrue("private target for /$prefixLength", privateTarget.isDeniedNetworkTarget(resolution))
            assertFalse("public target for /$prefixLength", publicTarget.isDeniedNetworkTarget(resolution))
        }
    }

    @Test
    fun guardedDnsDiscoversAndAppliesActiveNetworkNat64Prefix() {
        RFC6052_TEST_PREFIX_LENGTHS.forEach { prefixLength ->
            val prefix = testNat64Prefix(prefixLength)
            val privateTarget = rfc6052Address(prefix, byteArrayOf(192.toByte(), 168.toByte(), 1, 7))
            val publicIpv6 = InetAddress.getByName("2606:4700:4700::1111")
            val delegate = mappingDns(
                "target.example" to listOf(privateTarget, publicIpv6),
                "ipv4only.arpa" to listOf(
                    rfc6052Address(prefix, byteArrayOf(192.toByte(), 0, 0, 170.toByte())),
                    rfc6052Address(prefix, byteArrayOf(192.toByte(), 0, 0, 171.toByte())),
                ),
            )

            val resolved = GuardedNetworkDns(delegate).lookup("target.example")

            assertEquals("active /$prefixLength prefix", listOf(publicIpv6), resolved)
        }
    }

    @Test
    fun unknownNat64PrefixFailsClosedForIpv6ButKeepsPublicIpv4Fallback() {
        val publicIpv4 = InetAddress.getByName("8.8.8.8")
        val publicIpv6 = InetAddress.getByName("2001:4860:4860::8888")
        val deniedWellKnownNat64 = InetAddress.getByName("64:ff9b::a00:8")
        val dns = GuardedNetworkDns(
            delegate = fixedDns(deniedWellKnownNat64, publicIpv6, publicIpv4),
            nat64PrefixResolver = Nat64PrefixResolver { Nat64PrefixResolution.Unknown },
        )

        assertEquals(listOf(publicIpv4), dns.lookup("public.example"))
    }

    @Test
    fun webFetchClientDisablesProxyAndAutomaticRedirects() {
        val dns = fixedDns(InetAddress.getByName("8.8.8.8"))
        val client = buildWebFetchClient(dns)

        assertSame(dns, client.dns)
        assertEquals(Proxy.NO_PROXY, client.proxy)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertEquals(WEB_FETCH_CALL_TIMEOUT_MILLIS.toInt(), client.callTimeoutMillis)
    }

    @Test
    fun boundedBodyStopsAfterOverflowSentinel() {
        val source = Buffer().write(ByteArray(1_024) { 7 })

        val result = readBoundedBody(source, maxBytes = 128)

        assertEquals(128, result.bytes.size)
        assertTrue(result.truncated)
        assertEquals(1_024L - 129L, source.size)
    }

    @Test
    fun boundedBodyDoesNotMarkExactLimitAsTruncated() {
        val source = Buffer().writeUtf8("hello")

        val result = readBoundedBody(source, maxBytes = 5)

        assertEquals("hello", result.bytes.toString(Charsets.UTF_8))
        assertFalse(result.truncated)
    }

    @Test
    fun boundedBodyPropagatesCancellationCheck() {
        val source = Buffer().write(ByteArray(64))
        var cancellation: CancellationException? = null

        try {
            readBoundedBody(source, maxBytes = 32) { throw CancellationException("cancel bounded read") }
        } catch (error: CancellationException) {
            cancellation = error
        }

        assertEquals("cancel bounded read", cancellation?.message)
    }

    @Test
    fun cancellingCoroutineCancelsBlockedOkHttpCallPromptly() = runBlocking {
        val blockedCall = BlockingCall()
        val transport = OkHttpWebFetchTransport(callFactory = Call.Factory { blockedCall })
        val execution = launch {
            transport.execute(
                WebFetchRequest(
                    url = "https://example.com/blocked",
                    maxTransferBytes = 128,
                ),
            )
        }
        blockedCall.started.await()

        execution.cancel()
        withTimeout(BLOCKED_CALL_CANCEL_TIMEOUT_MILLIS) { execution.cancelAndJoin() }

        assertTrue(blockedCall.cancelled.get())
    }

    @Test
    fun redirectResponseDoesNotReadBody() = runBlocking {
        val body = UnreadableResponseBody()
        val transport = OkHttpWebFetchTransport(
            callFactory = Call.Factory { request -> ImmediateResponseCall(request, 302, body) },
        )

        val response = transport.execute(
            WebFetchRequest(
                url = "https://example.com/redirect",
                maxTransferBytes = 128,
            ),
        )

        assertEquals(302, response.statusCode)
        assertArrayEquals(ByteArray(0), response.body)
        assertFalse(response.transferTruncated)
        assertFalse(body.readRequested.get())
    }
}

private fun fixedDns(vararg addresses: InetAddress): Dns = object : Dns {
    override fun lookup(hostname: String): List<InetAddress> = addresses.toList()
}

private fun mappingDns(vararg entries: Pair<String, List<InetAddress>>): Dns {
    val answers = mapOf(*entries)
    return object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            answers[hostname] ?: throw UnknownHostException(hostname)
    }
}

private fun testNat64Prefix(prefixLength: Int): Nat64Prefix =
    Nat64Prefix(InetAddress.getByName("2001:4860:abcd:ef12:3456:789a:bcde:f012").address, prefixLength)

private fun rfc6052Address(prefix: Nat64Prefix, ipv4: ByteArray): InetAddress {
    require(ipv4.size == 4)
    val bytes = prefix.address.copyOf()
    val prefixBytes = prefix.prefixLength / Byte.SIZE_BITS
    if (prefix.prefixLength == 96) {
        ipv4.copyInto(bytes, destinationOffset = prefixBytes)
    } else {
        val bytesBeforeU = minOf(8 - prefixBytes, ipv4.size)
        ipv4.copyInto(bytes, destinationOffset = prefixBytes, endIndex = bytesBeforeU)
        bytes[8] = 0
        if (bytesBeforeU < ipv4.size) {
            ipv4.copyInto(bytes, destinationOffset = 9, startIndex = bytesBeforeU)
        }
    }
    return Inet6Address.getByAddress(null, bytes, -1)
}

private fun mappedIpv6Address(first: Int, second: Int, third: Int, fourth: Int): InetAddress {
    val bytes = ByteArray(16)
    bytes[10] = 0xFF.toByte()
    bytes[11] = 0xFF.toByte()
    bytes[12] = first.toByte()
    bytes[13] = second.toByte()
    bytes[14] = third.toByte()
    bytes[15] = fourth.toByte()
    return Inet6Address.getByAddress(null, bytes, -1)
}

private fun assertUnknownHost(block: () -> Unit) {
    var failure: UnknownHostException? = null
    try {
        block()
    } catch (error: UnknownHostException) {
        failure = error
    }
    assertTrue(failure != null)
}

private class BlockingCall : Call {
    val started = CompletableDeferred<Unit>()
    val cancelled = AtomicBoolean(false)
    private val released = CountDownLatch(1)
    private val executed = AtomicBoolean(false)
    private val request = Request.Builder().url("https://example.com/blocked").build()

    override fun request(): Request = request

    override fun execute(): Response {
        executed.set(true)
        started.complete(Unit)
        released.await()
        throw IOException("cancelled=${cancelled.get()}")
    }

    override fun enqueue(responseCallback: Callback) {
        throw UnsupportedOperationException("BlockingCall only supports execute().")
    }

    override fun cancel() {
        cancelled.set(true)
        released.countDown()
    }

    override fun isExecuted(): Boolean = executed.get()

    override fun isCanceled(): Boolean = cancelled.get()

    override fun timeout(): Timeout = Timeout.NONE

    override fun clone(): Call = BlockingCall()
}

private class ImmediateResponseCall(
    private val request: Request,
    private val statusCode: Int,
    private val body: ResponseBody,
) : Call {
    private val executed = AtomicBoolean(false)

    override fun request(): Request = request

    override fun execute(): Response {
        executed.set(true)
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(statusCode)
            .message("test response")
            .body(body)
            .build()
    }

    override fun enqueue(responseCallback: Callback) =
        throw UnsupportedOperationException("ImmediateResponseCall only supports execute().")

    override fun cancel() = Unit

    override fun isExecuted(): Boolean = executed.get()

    override fun isCanceled(): Boolean = false

    override fun timeout(): Timeout = Timeout.NONE

    override fun clone(): Call = ImmediateResponseCall(request, statusCode, body)
}

private class UnreadableResponseBody : ResponseBody() {
    val readRequested = AtomicBoolean(false)
    private val unreadableSource = object : ForwardingSource(Buffer()) {
        override fun read(sink: Buffer, byteCount: Long): Long {
            readRequested.set(true)
            throw AssertionError("redirect response body must not be read")
        }
    }.buffer()

    override fun contentType(): MediaType? = null

    override fun contentLength(): Long = Long.MAX_VALUE

    override fun source(): BufferedSource = unreadableSource
}

private const val BLOCKED_CALL_CANCEL_TIMEOUT_MILLIS = 1_000L
private val RFC6052_TEST_PREFIX_LENGTHS = listOf(32, 40, 48, 56, 64, 96)
