package cc.noharry.agent.appserver.protocol

import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import kotlinx.serialization.Serializable

/**
 * What is known of one model: its [contextWindow] and [maxOutputTokens] (the documented ceiling of one answer) in
 * tokens, whether it reads pictures ([imageInput]), calls tools ([toolCalls]) and reasons before it answers
 * ([reasoning]), the reasoning efforts it takes ([reasoningEfforts], as its service names them), and the output a
 * request asks for ([requestOutputTokens]: sent as the request's output limit, where the service's own default would
 * cut answers short; null sends the wire client's default). Null where nothing is known: the server then keeps its
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
    val requestOutputTokens: Long? = null,
) {
    /** These facts where they say something, [base]'s elsewhere. */
    fun over(base: ModelFacts): ModelFacts = ModelFacts(
        contextWindow = contextWindow ?: base.contextWindow,
        maxOutputTokens = maxOutputTokens ?: base.maxOutputTokens,
        imageInput = imageInput ?: base.imageInput,
        toolCalls = toolCalls ?: base.toolCalls,
        reasoning = reasoning ?: base.reasoning,
        reasoningEfforts = reasoningEfforts.ifEmpty { base.reasoningEfforts },
        requestOutputTokens = requestOutputTokens ?: base.requestOutputTokens,
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
 * default), where a person gets a key ([keyHint], plain text), the models its list names ([models]) and the one a
 * profile that names none takes on its default endpoint ([defaultModel], blank for none), and whether it answers
 * [ServiceRequests.BALANCE] ([balance]) and [ServiceRequests.CHECK] ([relayCheck], for a relay that may have it behind).
 * [listsModels] false: the service has no model list, so [SettingsRequests.PROVIDER_FETCH_MODELS] answers with [models]
 * without asking it, and a list fetched says nothing of a key.
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
    val defaultModel: String = "",
    val listsModels: Boolean = true,
)

@Serializable
data class ServiceListResult(val services: List<ServiceView> = emptyList())

/**
 * The connection a service request is about, as filled in: saved or not; a blank [apiKey] is the one saved for
 * [profileId], which the server sends only to the host that saved profile points at, or to an address a check made
 * with the key typed found for it; for any other [baseUrl] the person types the key again.
 */
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
 * [service]'s balance for the key typed in ([apiKey], else [connection]'s), else the one saved for [connection]; or,
 * with [connection] and [url], a relay's at [url] (where a check found it). A saved key goes only where
 * [ProviderConnection] says, else the balance fails with `MODEL_KEY_TYPE_AGAIN`. A key goes in only; it never comes back.
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
 * [savedKeyRefused]: the key is the profile's saved one, which does not go to the connection's base URL (a host other
 * than the saved profile's): nothing is checked until the person types the key.
 */
@Serializable
data class ServiceCheckView(
    val checked: Boolean = false,
    val current: Boolean = false,
    val hasKey: Boolean = false,
    val balance: ServiceCheckPart? = null,
    val lookup: ServiceCheckPart? = null,
    val nativeUpdates: ServiceCheckPart? = null,
    val savedKeyRefused: Boolean = false,
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
