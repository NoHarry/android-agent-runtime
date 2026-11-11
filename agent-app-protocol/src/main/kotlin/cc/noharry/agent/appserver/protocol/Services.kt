package cc.noharry.agent.appserver.protocol

import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import kotlinx.serialization.Serializable

/**
 * What is known of one model: its [contextWindow] and [maxOutputTokens] in tokens, whether it reads pictures
 * ([imageInput]), calls tools ([toolCalls]) and reasons before it answers ([reasoning]), and the reasoning efforts it
 * takes ([reasoningEfforts], as its service names them). Null where nothing is known: the server then keeps its
 * conservative default (a 128K window, pictures on).
 */
@Serializable
data class ModelFacts(
    val contextWindow: Long? = null,
    val maxOutputTokens: Long? = null,
    val imageInput: Boolean? = null,
    val toolCalls: Boolean? = null,
    val reasoning: Boolean? = null,
    val reasoningEfforts: List<String> = emptyList(),
) {
    /** These facts where they say something, [base]'s elsewhere. */
    fun over(base: ModelFacts): ModelFacts = ModelFacts(
        contextWindow = contextWindow ?: base.contextWindow,
        maxOutputTokens = maxOutputTokens ?: base.maxOutputTokens,
        imageInput = imageInput ?: base.imageInput,
        toolCalls = toolCalls ?: base.toolCalls,
        reasoning = reasoning ?: base.reasoning,
        reasoningEfforts = reasoningEfforts.ifEmpty { base.reasoningEfforts },
    )
}

/**
 * One way a model service is reached: the [endpoint] kind and the base URL a profile on it uses, in [region] where the
 * service has hosts of its own per region (`global`, `cn`; blank when it has one).
 */
@Serializable
data class ServiceEndpointView(val endpoint: ProviderEndpoint, val baseUrl: String, val region: String = "")

/**
 * A model service an adapter of the server knows: its [id] and [label], the [endpoints] it speaks (the first is its
 * default), where a person gets a key ([keyHint], plain text), the models its list names ([models]), and whether it
 * answers [ServiceRequests.BALANCE] ([balance]) and [ServiceRequests.CHECK] ([relayCheck], for a relay that may have it
 * behind).
 */
@Serializable
data class ServiceView(
    val id: String,
    val label: String,
    val endpoints: List<ServiceEndpointView> = emptyList(),
    val keyHint: String = "",
    val models: List<String> = emptyList(),
    val balance: Boolean = false,
    val relayCheck: Boolean = false,
)

@Serializable
data class ServiceListResult(val services: List<ServiceView> = emptyList())

/** The connection a service request is about, as filled in: saved or not; a blank [apiKey] is the one saved for [profileId]. */
@Serializable
data class ProviderConnection(
    val profileId: String,
    val label: String = "",
    val provider: ModelProvider = ModelProvider.OPENAI,
    val endpoint: ProviderEndpoint = ProviderEndpoint.defaultFor(ModelProvider.OPENAI),
    val apiKey: String = "",
    val model: String = "",
    val baseUrl: String = "",
)

/**
 * [service]'s balance for the key typed in ([apiKey]), else the one saved for [connection]; or, with [connection] and
 * [url], a relay's at [url] (where a check found it), with the key the server resolves for that connection. A key goes
 * in only; it never comes back.
 */
@Serializable
data class ServiceBalanceParams(
    val service: String,
    val apiKey: String = "",
    val connection: ProviderConnection? = null,
    val url: String? = null,
)

/** The balance, or why it could not be read. */
@Serializable
data class ServiceBalanceResult(val balance: BalanceView? = null, val failure: Finding? = null)

/** [service]'s check of [connection]: what of that service's own API the connection reaches. */
@Serializable
data class ServiceCheckParams(val service: String, val connection: ProviderConnection)

/** One part of a service check: passed, with the [url] that worked, or failed; [note] says what came back. */
@Serializable
data class ServiceCheckPart(val passed: Boolean, val note: Finding, val url: String? = null)

/**
 * What a check found of a service's own API through a connection (a relay, say), as stored: whether there is one
 * ([checked]), whether it was made for the connection as now filled in ([current]), whether that connection has a key
 * at all ([hasKey]), and, when current, its parts: the service's balance query, its search and page fetch APIs
 * ([lookup]), and whether its own history updates pass ([nativeUpdates], checked on the Messages endpoint only).
 */
@Serializable
data class ServiceCheckView(
    val checked: Boolean = false,
    val current: Boolean = false,
    val hasKey: Boolean = false,
    val balance: ServiceCheckPart? = null,
    val lookup: ServiceCheckPart? = null,
    val nativeUpdates: ServiceCheckPart? = null,
)

/**
 * The model services the server's adapters know, and what some of them answer beyond chat. Without an adapter for a
 * service, a balance or a check of it is refused (invalid params).
 */
object ServiceRequests {
    val LIST = RequestMethod("services/list", Empty.serializer(), ServiceListResult.serializer())
    val BALANCE = RequestMethod("services/balance", ServiceBalanceParams.serializer(), ServiceBalanceResult.serializer())

    /** The stored check of the connection's profile, measured against the connection as filled in. */
    val CHECKED = RequestMethod("services/checked", ServiceCheckParams.serializer(), ServiceCheckView.serializer())

    /** Checks the connection as filled in and keeps the result for its profile. */
    val CHECK = RequestMethod("services/check", ServiceCheckParams.serializer(), ServiceCheckView.serializer())
}
