package cc.noharry.agent.a2a.server

import java.net.InetAddress

/**
 * Bounded server configuration, on the loopback address unless [bindAddress] says otherwise; use a trusted
 * tunnel for remote access. [description] and [skillDescription] go on the Agent Card; [messages] are the status texts
 * callers see. [authorizer] decides who may use the server (none: anyone who reaches it, on loopback every app on the
 * device). [bindAddress] is an IP address, not a name; one other than loopback needs an authorizer and an `https`
 * [publicUrl], since the server itself speaks plain HTTP and a token must not cross a network in clear (put it behind
 * a TLS tunnel or proxy). [publicUrl] is the address the Agent Card gives, else `http://<bindAddress>:<port>/a2a`;
 * [cardVersion] is the agent's version on it.
 */
data class A2aServerConfig(
    val port: Int = 8766,
    val name: String = "Android Runtime Agent",
    val maxActiveTasks: Int = 4,
    val maxRetainedTasks: Int = 64,
    val taskTimeoutMillis: Long = 600_000,
    val description: String = "Text requests, answered with brief tool progress and text.",
    val skillDescription: String = "Run a text request with the app's tools and answer in text.",
    val messages: A2aServerMessages = A2aServerMessages(),
    val authorizer: A2aAuthorizer? = null,
    val bindAddress: String = LOOPBACK,
    val publicUrl: String? = null,
    val cardVersion: String = "0.1.0",
) {
    init {
        require(bindAddress.isIpLiteral()) { "bindAddress must be an IP address: $bindAddress" }
        require(InetAddress.getByName(bindAddress).isLoopbackAddress || (authorizer != null && publicUrl?.startsWith("https://") == true)) {
            "Listening on $bindAddress, beyond this device, needs an authorizer and an https publicUrl"
        }
        require(publicUrl == null || publicUrl.startsWith("http://") || publicUrl.startsWith("https://"))
        require(cardVersion.isNotBlank())
        require(port in 0..65535)
        require(name.isNotBlank())
        require(maxActiveTasks in 1..MAX_ACTIVE_TASKS)
        require(maxRetainedTasks in maxActiveTasks..MAX_RETAINED_TASKS)
        require(taskTimeoutMillis > 0)
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val MAX_ACTIVE_TASKS = 16
        const val MAX_RETAINED_TASKS = 256
    }
}

/** An IPv4 or IPv6 literal, which InetAddress reads without a lookup. */
private fun String.isIpLiteral(): Boolean =
    IPV4.matches(this) && split('.').all { it.toInt() <= MAX_OCTET } || IPV6.matches(this) && count { it == ':' } >= 2

private val IPV4 = Regex("""^(?:\d{1,3}\.){3}\d{1,3}$""")
private val IPV6 = Regex("""^[0-9a-fA-F:]*(?::\d{1,3}(?:\.\d{1,3}){3})?$""")
private const val MAX_OCTET = 255

/** What the server tells a caller about its task: English unless the host words it in its own language. */
data class A2aServerMessages(
    val accepted: String = "Text request received",
    val started: String = "Started",
    val timedOut: String = "The task timed out, or its output was read too slowly",
    val canceled: String = "The task was canceled",
    val interrupted: String = "Interrupted by a new task",
    val failed: String = "The task failed; the device's log has why",
    val noResult: String = "The run ended without a final result",
    val deliveryFailed: String = "The output connection closed, or the output grew too large",
)
