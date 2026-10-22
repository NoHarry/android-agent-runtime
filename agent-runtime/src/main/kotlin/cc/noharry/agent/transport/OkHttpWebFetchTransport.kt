package cc.noharry.agent.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import okio.BufferedSource
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import cc.noharry.agent.kernel.core.failed

internal class OkHttpWebFetchTransport(
    private val callFactory: Call.Factory = buildWebFetchClient(GuardedNetworkDns()),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : WebFetchTransport {
    override suspend fun execute(request: WebFetchRequest): WebFetchResponse = withContext(ioDispatcher) {
        require(request.maxTransferBytes > 0) { "maxTransferBytes must be positive." }
        executeCancellable(request)
    }

    private suspend fun executeCancellable(request: WebFetchRequest): WebFetchResponse =
        suspendCancellableCoroutine { continuation ->
            val call = callFactory.newCall(request.toOkHttpRequest())
            continuation.invokeOnCancellation { call.cancel() }
            val checkCancellation = {
                if (!continuation.isActive) {
                    throw CancellationException("web_fetch was cancelled.")
                }
            }
            try {
                val fetched = call.execute().use { response ->
                    checkCancellation()
                    // OkHttp transparently decompresses gzip before exposing ResponseBody.source(),
                    // so this cap applies to the bytes that would otherwise be retained in memory.
                    val boundedBody = if (response.code in HTTP_REDIRECT_STATUS_RANGE) {
                        EMPTY_WEB_FETCH_BODY
                    } else {
                        response.body?.source()?.let { source ->
                            readBoundedBody(source, request.maxTransferBytes, checkCancellation)
                        } ?: EMPTY_WEB_FETCH_BODY
                    }
                    WebFetchResponse(
                        statusCode = response.code,
                        headers = response.headers.toMultimap(),
                        body = boundedBody.bytes,
                        transferTruncated = boundedBody.truncated,
                    )
                }
                if (continuation.isActive) continuation.resume(fetched)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
}

internal class GuardedNetworkDns(
    private val delegate: Dns = Dns.SYSTEM,
    private val nat64PrefixResolver: Nat64PrefixResolver = Dns64PrefixResolver(delegate),
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val resolved = try {
            delegate.lookup(hostname)
        } catch (error: UnknownHostException) {
            throw error
        } catch (error: Exception) {
            throw UnknownHostException("DNS lookup failed for $hostname").apply { initCause(error) }
        }
        if (resolved.isEmpty()) {
            throw UnknownHostException("DNS lookup returned no addresses for $hostname")
        }
        val nat64Resolution = if (resolved.any { it.address.size == IPV6_ADDRESS_BYTES }) {
            resolveNat64Prefix()
        } else {
            Nat64PrefixResolution.Absent
        }
        val allowed = resolved.filterNot { it.isDeniedNetworkTarget(nat64Resolution) }
        if (allowed.isEmpty()) {
            throw NetworkAddressDeniedException(hostname)
        }
        return allowed
    }

    private fun resolveNat64Prefix(): Nat64PrefixResolution = try {
        nat64PrefixResolver.resolve()
    } catch (_: Exception) {
        Nat64PrefixResolution.Unknown
    }
}

internal fun interface Nat64PrefixResolver {
    fun resolve(): Nat64PrefixResolution
}

internal sealed class Nat64PrefixResolution {
    internal object Absent : Nat64PrefixResolution()

    internal object Unknown : Nat64PrefixResolution()

    internal class Present(val prefixes: List<Nat64Prefix>) : Nat64PrefixResolution() {
        constructor(prefix: Nat64Prefix) : this(listOf(prefix))

        init {
            require(prefixes.isNotEmpty()) { "At least one NAT64 prefix is required." }
        }
    }
}

internal class Nat64Prefix(address: ByteArray, val prefixLength: Int) {
    val address: ByteArray = address.normalizedPrefix(prefixLength)

    init {
        require(address.size == IPV6_ADDRESS_BYTES) { "NAT64 prefix must be an IPv6 address." }
        require(prefixLength in RFC6052_PREFIX_LENGTHS) { "Unsupported RFC 6052 prefix length: $prefixLength" }
    }

    override fun equals(other: Any?): Boolean =
        other is Nat64Prefix && prefixLength == other.prefixLength && address.contentEquals(other.address)

    override fun hashCode(): Int = 31 * prefixLength + address.contentHashCode()
}

internal class Dns64PrefixResolver(
    private val delegate: Dns,
) : Nat64PrefixResolver {
    override fun resolve(): Nat64PrefixResolution {
        val answers = try {
            delegate.lookup(DNS64_DISCOVERY_HOST)
        } catch (_: Exception) {
            return Nat64PrefixResolution.Unknown
        }
        val ipv6Answers = answers.filter { it.address.size == IPV6_ADDRESS_BYTES }
        if (ipv6Answers.isEmpty()) return Nat64PrefixResolution.Absent
        val prefixes = ipv6Answers.flatMap { it.address.discoverNat64Prefixes() }.distinct()
        return if (prefixes.isNotEmpty()) {
            Nat64PrefixResolution.Present(prefixes)
        } else {
            Nat64PrefixResolution.Unknown
        }
    }
}

internal fun buildWebFetchClient(dns: Dns): OkHttpClient =
    OkHttpClient.Builder()
        .dns(dns)
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(WEB_FETCH_CALL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        .build()

internal fun InetAddress.isDeniedNetworkTarget(): Boolean =
    isDeniedNetworkTarget(Nat64PrefixResolution.Absent)

internal fun InetAddress.isDeniedNetworkTarget(nat64Resolution: Nat64PrefixResolution): Boolean {
    if (
        isAnyLocalAddress ||
        isLoopbackAddress ||
        isLinkLocalAddress ||
        isSiteLocalAddress ||
        isMulticastAddress
    ) {
        return true
    }
    val bytes = address
    val embeddedIpv4 = bytes.embeddedIpv4Address()
    if (embeddedIpv4 != null) {
        return embeddedIpv4.isDeniedIpv4Target()
    }
    if (bytes.size != IPV6_ADDRESS_BYTES) return true
    if ((bytes[0].unsigned() and IPV6_ULA_MASK) == IPV6_ULA_PREFIX) return true
    bytes.translatedIpv4Address()?.let { return it.isDeniedIpv4Target() }
    if (bytes.isDeniedIpv6SpecialTarget()) return true
    if (nat64Resolution is Nat64PrefixResolution.Unknown) return true
    val networkTranslatedIpv4 = when (nat64Resolution) {
        Nat64PrefixResolution.Absent -> null
        Nat64PrefixResolution.Unknown -> error("Unknown NAT64 resolution was handled above.")
        is Nat64PrefixResolution.Present -> nat64Resolution.prefixes
            .sortedByDescending(Nat64Prefix::prefixLength)
            .firstNotNullOfOrNull(bytes::translatedIpv4Address)
    }
    return networkTranslatedIpv4?.isDeniedIpv4Target() == true
}

internal data class BoundedWebFetchBody(
    val bytes: ByteArray,
    val truncated: Boolean,
)

internal fun readBoundedBody(
    source: BufferedSource,
    maxBytes: Int,
    checkCancellation: () -> Unit = {},
): BoundedWebFetchBody {
    require(maxBytes > 0) { "maxBytes must be positive." }
    val sink = Buffer()
    var remaining = maxBytes.toLong() + OVERFLOW_SENTINEL_BYTES
    while (remaining > 0L) {
        checkCancellation()
        val read = source.read(sink, minOf(WEB_FETCH_READ_CHUNK_BYTES, remaining))
        if (read == -1L) break
        remaining -= read
    }
    val truncated = sink.size > maxBytes.toLong()
    val bytesToReturn = minOf(sink.size, maxBytes.toLong())
    return BoundedWebFetchBody(
        bytes = sink.readByteArray(bytesToReturn),
        truncated = truncated,
    )
}

private fun WebFetchRequest.toOkHttpRequest(): Request {
    val builder = Request.Builder().url(url).get()
    headers.forEach { (name, value) -> builder.header(name, value) }
    return builder.build()
}

private fun ByteArray.embeddedIpv4Address(): ByteArray? {
    if (size == IPV4_ADDRESS_BYTES) return this
    if (size != IPV6_ADDRESS_BYTES) return null
    val mapped = take(IPV4_MAPPED_PREFIX_ZERO_BYTES).all { it == 0.toByte() } &&
        this[IPV4_MAPPED_FF_FIRST_INDEX].unsigned() == BYTE_MAX &&
        this[IPV4_MAPPED_FF_SECOND_INDEX].unsigned() == BYTE_MAX
    val compatible = take(IPV4_COMPATIBLE_PREFIX_BYTES).all { it == 0.toByte() }
    return if (mapped || compatible) copyOfRange(IPV4_COMPATIBLE_PREFIX_BYTES, size) else null
}

private fun ByteArray.isDeniedIpv4Target(): Boolean {
    if (size != IPV4_ADDRESS_BYTES) return true
    val first = this[0].unsigned()
    val second = this[1].unsigned()
    return first == IPV4_UNSPECIFIED_PREFIX ||
        first == IPV4_LOOPBACK_PREFIX ||
        first == IPV4_LINK_LOCAL_PREFIX && second == IPV4_LINK_LOCAL_SECOND ||
        first == IPV4_PRIVATE_A_PREFIX ||
        first == IPV4_PRIVATE_B_PREFIX && second in IPV4_PRIVATE_B_SECOND_RANGE ||
        first == IPV4_PRIVATE_C_PREFIX && second == IPV4_PRIVATE_C_SECOND ||
        first == IPV4_CGNAT_PREFIX && second in IPV4_CGNAT_SECOND_RANGE ||
        first == IPV4_PROTOCOL_ASSIGNMENTS_PREFIX && second == 0 && this[2].unsigned() == 0 ||
        first == IPV4_PROTOCOL_ASSIGNMENTS_PREFIX && second == 0 && this[2].unsigned() == 2 ||
        first == IPV4_PROTOCOL_ASSIGNMENTS_PREFIX && second == 88 && this[2].unsigned() == 99 ||
        first == IPV4_BENCHMARK_PREFIX && second in IPV4_BENCHMARK_SECOND_RANGE ||
        first == IPV4_BENCHMARK_PREFIX && second == 51 && this[2].unsigned() == 100 ||
        first == IPV4_DOCUMENTATION_PREFIX && second == 0 && this[2].unsigned() == 113 ||
        first >= IPV4_MULTICAST_PREFIX
}

private fun ByteArray.translatedIpv4Address(): ByteArray? {
    if (size != IPV6_ADDRESS_BYTES) return null
    val wellKnownNat64 = matchesPrefix(IPV6_NAT64_WELL_KNOWN_PREFIX)
    if (wellKnownNat64) return copyOfRange(IPV4_COMPATIBLE_PREFIX_BYTES, size)
    val sixToFour = this[0].unsigned() == 0x20 && this[1].unsigned() == 0x02
    return if (sixToFour) copyOfRange(2, 6) else null
}

private fun ByteArray.translatedIpv4Address(prefix: Nat64Prefix): ByteArray? {
    if (size != IPV6_ADDRESS_BYTES || !matchesPrefix(prefix)) return null
    val prefixBytes = prefix.prefixLength / Byte.SIZE_BITS
    if (prefix.prefixLength == RFC6052_96_PREFIX_LENGTH) {
        return copyOfRange(prefixBytes, prefixBytes + IPV4_ADDRESS_BYTES)
    }
    if (this[RFC6052_U_BYTE_INDEX].unsigned() != 0) return null
    val bytesBeforeU = minOf(RFC6052_U_BYTE_INDEX - prefixBytes, IPV4_ADDRESS_BYTES)
    val ipv4 = ByteArray(IPV4_ADDRESS_BYTES)
    copyInto(ipv4, endIndex = prefixBytes + bytesBeforeU, startIndex = prefixBytes)
    if (bytesBeforeU < IPV4_ADDRESS_BYTES) {
        copyInto(
            destination = ipv4,
            destinationOffset = bytesBeforeU,
            startIndex = RFC6052_U_BYTE_INDEX + 1,
            endIndex = RFC6052_U_BYTE_INDEX + 1 + IPV4_ADDRESS_BYTES - bytesBeforeU,
        )
    }
    return ipv4
}

private fun ByteArray.discoverNat64Prefixes(): List<Nat64Prefix> =
    RFC6052_PREFIX_LENGTHS.mapNotNull { prefixLength ->
        val candidate = Nat64Prefix(this, prefixLength)
        val translated = translatedIpv4Address(candidate) ?: return@mapNotNull null
        candidate.takeIf { DNS64_DISCOVERY_IPV4_ADDRESSES.any(translated::contentEquals) }
    }

private fun ByteArray.normalizedPrefix(prefixLength: Int): ByteArray {
    require(size == IPV6_ADDRESS_BYTES) { "NAT64 prefix must be an IPv6 address." }
    require(prefixLength in RFC6052_PREFIX_LENGTHS) { "Unsupported RFC 6052 prefix length: $prefixLength" }
    return copyOf().also { normalized ->
        normalized.fill(0, fromIndex = prefixLength / Byte.SIZE_BITS)
    }
}

private fun ByteArray.matchesPrefix(prefix: Nat64Prefix): Boolean {
    val prefixBytes = prefix.prefixLength / Byte.SIZE_BITS
    return size == IPV6_ADDRESS_BYTES &&
        (0 until prefixBytes).all { index -> this[index] == prefix.address[index] }
}

private fun ByteArray.isDeniedIpv6SpecialTarget(): Boolean {
    if (matchesPrefix(IPV6_DISCARD_ONLY_PREFIX)) return true
    if (matchesPrefix(IPV6_NAT64_LOCAL_PREFIX)) return true
    if (matchesPrefix(IPV6_TEREDO_PREFIX)) return true
    if (matchesPrefix(IPV6_BENCHMARK_PREFIX)) return true
    if (matchesPrefix(IPV6_DOCUMENTATION_PREFIX)) return true
    if (matchesPrefix(IPV6_DEPRECATED_6BONE_PREFIX)) return true
    return matchesOrchidPrefix(0x10) || matchesOrchidPrefix(0x20)
}

private fun ByteArray.matchesPrefix(prefix: IntArray): Boolean =
    size >= prefix.size && prefix.indices.all { index -> this[index].unsigned() == prefix[index] }

private fun ByteArray.matchesOrchidPrefix(fourthByteHighNibble: Int): Boolean =
    size == IPV6_ADDRESS_BYTES &&
        this[0].unsigned() == 0x20 &&
        this[1].unsigned() == 0x01 &&
        this[2].unsigned() == 0x00 &&
        (this[3].unsigned() and 0xF0) == fourthByteHighNibble

private fun Byte.unsigned(): Int = toInt() and BYTE_MAX

private const val WEB_FETCH_READ_CHUNK_BYTES = 8192L
internal const val WEB_FETCH_CALL_TIMEOUT_MILLIS = 30_000L
private val HTTP_REDIRECT_STATUS_RANGE = 300..399
private val EMPTY_WEB_FETCH_BODY = BoundedWebFetchBody(ByteArray(0), truncated = false)
private const val OVERFLOW_SENTINEL_BYTES = 1L
private const val BYTE_MAX = 0xFF
private const val IPV4_ADDRESS_BYTES = 4
private const val IPV6_ADDRESS_BYTES = 16
private const val IPV4_MAPPED_PREFIX_ZERO_BYTES = 10
private const val IPV4_MAPPED_FF_FIRST_INDEX = 10
private const val IPV4_MAPPED_FF_SECOND_INDEX = 11
private const val IPV4_COMPATIBLE_PREFIX_BYTES = 12
private const val IPV6_ULA_MASK = 0xFE
private const val IPV6_ULA_PREFIX = 0xFC
private const val IPV4_UNSPECIFIED_PREFIX = 0
private const val IPV4_PRIVATE_A_PREFIX = 10
private const val IPV4_LOOPBACK_PREFIX = 127
private const val IPV4_LINK_LOCAL_PREFIX = 169
private const val IPV4_LINK_LOCAL_SECOND = 254
private const val IPV4_PRIVATE_B_PREFIX = 172
private val IPV4_PRIVATE_B_SECOND_RANGE = 16..31
private const val IPV4_PRIVATE_C_PREFIX = 192
private const val IPV4_PRIVATE_C_SECOND = 168
private const val IPV4_PROTOCOL_ASSIGNMENTS_PREFIX = 192
private const val IPV4_BENCHMARK_PREFIX = 198
private val IPV4_BENCHMARK_SECOND_RANGE = 18..19
private const val IPV4_DOCUMENTATION_PREFIX = 203
private const val IPV4_CGNAT_PREFIX = 100
private val IPV4_CGNAT_SECOND_RANGE = 64..127
private const val IPV4_MULTICAST_PREFIX = 224
private const val DNS64_DISCOVERY_HOST = "ipv4only.arpa"
private const val RFC6052_U_BYTE_INDEX = 8
private const val RFC6052_96_PREFIX_LENGTH = 96
private val RFC6052_PREFIX_LENGTHS = setOf(32, 40, 48, 56, 64, RFC6052_96_PREFIX_LENGTH)
private val DNS64_DISCOVERY_IPV4_ADDRESSES = listOf(
    byteArrayOf(192.toByte(), 0, 0, 170.toByte()),
    byteArrayOf(192.toByte(), 0, 0, 171.toByte()),
)
private val IPV6_DISCARD_ONLY_PREFIX = intArrayOf(0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
private val IPV6_NAT64_WELL_KNOWN_PREFIX = intArrayOf(0x00, 0x64, 0xFF, 0x9B, 0, 0, 0, 0, 0, 0, 0, 0)
private val IPV6_NAT64_LOCAL_PREFIX = intArrayOf(0x00, 0x64, 0xFF, 0x9B, 0x00, 0x01)
private val IPV6_TEREDO_PREFIX = intArrayOf(0x20, 0x01, 0x00, 0x00)
private val IPV6_BENCHMARK_PREFIX = intArrayOf(0x20, 0x01, 0x00, 0x02, 0x00, 0x00)
private val IPV6_DOCUMENTATION_PREFIX = intArrayOf(0x20, 0x01, 0x0D, 0xB8)
private val IPV6_DEPRECATED_6BONE_PREFIX = intArrayOf(0x3F, 0xFE)
