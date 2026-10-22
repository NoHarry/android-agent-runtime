package cc.noharry.agent.a2a.official

import cc.noharry.agent.a2a.A2aClientError
import cc.noharry.agent.a2a.ExperimentalA2aClientApi
import cc.noharry.agent.a2a.RemoteAgentRef
import java.net.URI
import java.util.Locale

@OptIn(ExperimentalA2aClientApi::class)
internal object OfficialUriPolicy {
    fun validate(remote: RemoteAgentRef, allowLoopbackHttp: Boolean): A2aClientError? {
        validate(remote.endpoint, allowLoopbackHttp)?.let { return it }
        remote.cardUrl?.let { validate(it, allowLoopbackHttp)?.let { error -> return error } }
        return null
    }

    fun validate(uri: URI, allowLoopbackHttp: Boolean): A2aClientError? {
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT)
        if (
            uri.userInfo != null ||
            uri.fragment != null ||
            host == null ||
            uri.port > MAX_PORT ||
            (scheme != HTTPS_SCHEME && scheme != HTTP_SCHEME)
        ) {
            return A2aClientError.Configuration("invalid_remote_uri")
        }
        if (scheme == HTTP_SCHEME && !(allowLoopbackHttp && isLoopback(host))) {
            return A2aClientError.Configuration("insecure_remote_uri")
        }
        return null
    }

    fun canonicallyMatches(configured: URI, advertised: URI): Boolean =
        canonical(configured) == canonical(advertised)

    private fun canonical(uri: URI): CanonicalUri {
        val scheme = uri.scheme.lowercase(Locale.ROOT)
        val port = when {
            uri.port >= 0 -> uri.port
            scheme == HTTPS_SCHEME -> HTTPS_DEFAULT_PORT
            else -> HTTP_DEFAULT_PORT
        }
        val path = uri.rawPath.orEmpty().ifEmpty { ROOT_PATH }.removeSingleTrailingSlash()
        return CanonicalUri(
            scheme = scheme,
            host = uri.host.lowercase(Locale.ROOT),
            port = port,
            path = path,
            query = uri.rawQuery,
        )
    }

    private fun String.removeSingleTrailingSlash(): String =
        if (length > 1 && endsWith(ROOT_PATH)) dropLast(1) else this

    private fun isLoopback(host: String): Boolean =
        host == "localhost" || host == "::1" || host == "[::1]" || host.isIpv4Loopback()

    private fun String.isIpv4Loopback(): Boolean {
        val octets = split('.')
        return octets.size == IPV4_OCTET_COUNT &&
            octets.first() == LOOPBACK_FIRST_OCTET &&
            octets.all { octet ->
                octet.toIntOrNull()?.let { it in MIN_IPV4_OCTET..MAX_IPV4_OCTET } == true
            }
    }

    private data class CanonicalUri(
        val scheme: String,
        val host: String,
        val port: Int,
        val path: String,
        val query: String?,
    )

    private const val HTTP_SCHEME = "http"
    private const val HTTPS_SCHEME = "https"
    private const val HTTP_DEFAULT_PORT = 80
    private const val HTTPS_DEFAULT_PORT = 443
    private const val MAX_PORT = 65_535
    private const val ROOT_PATH = "/"
    private const val IPV4_OCTET_COUNT = 4
    private const val LOOPBACK_FIRST_OCTET = "127"
    private const val MIN_IPV4_OCTET = 0
    private const val MAX_IPV4_OCTET = 255
}
