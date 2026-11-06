package cc.noharry.agent.a2a.server

import java.security.MessageDigest

/**
 * Who may send the server messages and read its tasks, by a request's headers (names in lower case). The Agent Card stays
 * readable by anyone, so callers learn how to authenticate. Without one, anyone who reaches the server may: on the
 * loopback address that is every app on the device.
 */
fun interface A2aAuthorizer {
    fun allows(headers: Map<String, String>): Boolean

    companion object {
        /** Callers that send `Authorization: Bearer <token>`; the Agent Card says so. [token]: 16 or more printable ASCII characters, no spaces. */
        fun bearer(token: String): A2aAuthorizer = BearerAuthorizer(token)
    }
}

internal class BearerAuthorizer(token: String) : A2aAuthorizer {
    private val expected: ByteArray

    init {
        require(token.length >= MIN_TOKEN_CHARS) { "A bearer token needs at least $MIN_TOKEN_CHARS characters" }
        // Headers are read as ASCII and trimmed: a token outside printable ASCII could never match.
        require(token.all { it in '!'..'~' }) { "A bearer token is printable ASCII without spaces" }
        expected = token.toByteArray(Charsets.UTF_8)
    }

    override fun allows(headers: Map<String, String>): Boolean {
        val value = headers["authorization"] ?: return false
        if (!value.startsWith(SCHEME, ignoreCase = true)) return false
        // Compared in constant time, so the answer's timing tells nothing about the token.
        return MessageDigest.isEqual(value.substring(SCHEME.length).trim().toByteArray(Charsets.UTF_8), expected)
    }

    private companion object {
        const val SCHEME = "Bearer "
        const val MIN_TOKEN_CHARS = 16
    }
}
