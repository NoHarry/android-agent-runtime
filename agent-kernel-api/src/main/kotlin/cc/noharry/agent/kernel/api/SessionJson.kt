package cc.noharry.agent.kernel.api

/**
 * Dependency-free JSON tree for lossless SessionEvent v2 structured fields.
 *
 * Number literals keep their original text so integers and decimals round-trip.
 */
@ExperimentalAgentRuntimeApi
sealed class SessionJson {
    data object Null : SessionJson()

    data class Bool(val value: Boolean) : SessionJson()

    data class Number(val literal: String) : SessionJson()

    data class Text(val value: String) : SessionJson()

    data class Array(val values: List<SessionJson>) : SessionJson()

    data class Object(val values: Map<String, SessionJson>) : SessionJson()
}

@ExperimentalAgentRuntimeApi
data class SessionAttachmentRef(
    val sha256: String,
    val mediaType: String = "",
    val byteSize: Long = 0,
    val logicalName: String = "",
)
