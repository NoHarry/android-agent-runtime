package cc.noharry.agent.a2a.server

/**
 * Candidate inbound text request. IDs are server-owned and safe as opaque session identifiers.
 * [metadata] holds the message's top-level scalar metadata as text (bounded); hosts must treat it as
 * untrusted caller hints, never as authorization.
 */
data class A2aRequest(
    val taskId: String,
    val contextId: String,
    val text: String,
    val metadata: Map<String, String> = emptyMap(),
)
