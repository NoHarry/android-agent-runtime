package cc.noharry.agent.appserver.model

import cc.noharry.agent.appserver.AppServerPluginApi
import cc.noharry.agent.appserver.protocol.ModelFacts
import cc.noharry.agent.appserver.protocol.ModelPrice
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.appserver.protocol.ServiceEndpointView
import cc.noharry.agent.appserver.protocol.ServiceView
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.model.ModelVariantRegistry
import cc.noharry.agent.spi.reliability.RuntimeErrorCode
import java.time.LocalDate

/**
 * A model service an adapter knows ([ProviderAdapter.services]): who it is ([id], [label]); how a profile reaches it
 * ([endpoints], the first its default: a profile whose base URL is on one of their hosts, or on one of [hosts], is on
 * it; a host of [hosts] that starts with a dot takes every host ending with it); where a person gets a key ([keyHint],
 * plain text); its models ([models], each with what is known of it and its prices) and the one a profile that names
 * none takes ([defaultModel]); and, as data, what the server's generic clients need to know of it: where it lists its
 * models ([modelsUrl], an OpenAI-compatible list asked with a bearer key wherever the profile's endpoint is; null lists
 * at `<base URL>/models` with the profile's wire, and [listsModels] false offers [models] alone), a prefix its list puts
 * before ids that requests leave out ([listedIdPrefix]), the usage fields it reports cached input in ([usageFields]),
 * how its failures read ([errors]) and how often a failed call is tried again ([retry]). [hostRegions] names the region
 * of hosts of [hosts] (a host, or a suffix starting with a dot), for a service whose prices differ by region.
 * [balance] and [relayCheck] say that its adapter answers [ProviderAdapter.balance] and [ProviderAdapter.check].
 * [source] and [asOf] say where its facts and prices were read and on which day.
 */
@AppServerPluginApi
class ModelService(
    val id: String,
    val label: String,
    val endpoints: List<ServiceEndpoint>,
    val hosts: Set<String> = emptySet(),
    val keyHint: String = "",
    val models: List<ServiceModel> = emptyList(),
    val modelsUrl: String? = null,
    val listsModels: Boolean = true,
    val listedIdPrefix: String = "",
    val usageFields: UsageFields? = null,
    val errors: List<ErrorRule> = emptyList(),
    val retry: RetryPolicy? = null,
    val balance: Boolean = false,
    val relayCheck: Boolean = false,
    val source: String = "",
    val asOf: String = "",
    val defaultModel: String? = null,
    val hostRegions: Map<String, String> = emptyMap(),
) {
    init {
        require(id.isNotBlank() && label.isNotBlank()) { "A model service needs an id and a label." }
        require(endpoints.isNotEmpty()) { "Model service $id names no endpoint." }
        require(defaultModel == null || models.any { it.id == defaultModel }) { "Model service $id's default model $defaultModel is not among its models." }
    }

    /** Every host a profile on it may be on: its endpoints' and [hosts]. */
    val allHosts: Set<String> = hosts.map { it.lowercase() }.toSet() + endpoints.mapNotNull { it.baseUrl.urlHost() }

    /** The region of its first endpoint: the list a model is priced from where the region is not known (a relay). */
    val defaultRegion: String get() = endpoints.first().region

    /** Whether a profile at [baseUrl] is on this service: its host is one of [allHosts], or ends with one that starts with a dot. */
    fun serves(baseUrl: String): Boolean = baseUrl.urlHost()?.let { host ->
        host in allHosts || allHosts.any { it.startsWith('.') && host.endsWith(it) }
    } == true

    /**
     * The region a profile at [baseUrl] is in: its endpoint's on the same host, else [hostRegions]'; null when neither
     * says (a host of [hosts] whose region is not known, or one not on this service).
     */
    fun regionOf(baseUrl: String): String? {
        val host = baseUrl.urlHost() ?: return null
        endpoints.firstOrNull { it.baseUrl.urlHost() == host }?.let { return it.region }
        return hostRegions.entries.firstOrNull { (named, _) -> host == named.lowercase() || named.startsWith('.') && host.endsWith(named.lowercase()) }?.value
    }

    /** [id]'s entry in [models] (its id or an alias, ignoring case); null when it lists no such model. */
    fun model(id: String): ServiceModel? = models.firstOrNull { it.id == id } ?: models.firstOrNull { it.isNamed(id) }

    /** Where a profile of [provider]'s wire reaches it, preferring [endpoint]; null when it speaks none of that wire. */
    fun endpointFor(provider: ModelProvider, endpoint: ProviderEndpoint? = null): ServiceEndpoint? =
        endpoints.firstOrNull { it.endpoint == endpoint } ?: defaultEndpointFor(provider)

    /** Its default endpoint for [provider]'s wire: the first of [endpoints] that speaks it; null when none does. */
    fun defaultEndpointFor(provider: ModelProvider): ServiceEndpoint? = endpoints.firstOrNull { it.endpoint.provider == provider }

    /** The service as a client sees it ([cc.noharry.agent.appserver.protocol.ServiceRequests.LIST]). */
    fun view(): ServiceView = ServiceView(
        id = id,
        label = label,
        endpoints = endpoints.map { ServiceEndpointView(it.endpoint, it.baseUrl, it.region) },
        keyHint = keyHint,
        models = models.map { it.id },
        balance = balance,
        relayCheck = relayCheck,
        defaultModel = defaultModel.orEmpty(),
    )

    override fun toString(): String = "ModelService($id)"
}

/**
 * How a model service is reached: the [endpoint] kind, at [baseUrl] (OpenAI-compatible `…/v1`, or the Messages API's),
 * in [region] when the service has hosts of its own per region (`global`, `cn`; blank when it has one).
 */
@AppServerPluginApi
class ServiceEndpoint(val endpoint: ProviderEndpoint, val baseUrl: String, val region: String = "")

/**
 * A model a service offers: its [id] as requests name it, what is known of it ([facts]), its [price] if known, and other
 * ids the service takes for the same model ([aliases]). [prices] holds more of its prices: one per region where the
 * service's lists differ by region ([ModelPrice.region]), and those it announced for a later day
 * ([ModelPrice.effectiveFrom]); [priceIn] picks the one that applies.
 */
@AppServerPluginApi
class ServiceModel(
    val id: String,
    val facts: ModelFacts = ModelFacts(),
    val price: ModelPrice? = null,
    val aliases: Set<String> = emptySet(),
    val prices: List<ModelPrice> = emptyList(),
) {
    init {
        (listOfNotNull(price) + prices).forEach { entry ->
            require(entry.effectiveFrom == null || runCatching { LocalDate.parse(entry.effectiveFrom) }.isSuccess) { "$id: a price's effectiveFrom is not a date." }
        }
    }

    /** Whether [id] names this model: its own id or an alias, ignoring case. */
    fun isNamed(id: String): Boolean = this.id.equals(id, ignoreCase = true) || aliases.any { it.equals(id, ignoreCase = true) }

    /**
     * Its price for a profile in [region] (null: a region the service's data does not know) on [day]: of its prices for
     * every region or for that one, the latest whose [ModelPrice.effectiveFrom] has come. Null when none applies, as for
     * a region whose list the data does not have.
     */
    fun priceIn(region: String?, day: LocalDate): ModelPrice? = (listOfNotNull(price) + prices)
        .filter { it.region.isNullOrEmpty() || it.region == region }
        .filter { entry -> entry.effectiveFrom?.let { !LocalDate.parse(it).isAfter(day) } ?: true }
        .maxWithOrNull(compareBy({ it.effectiveFrom.orEmpty() }, { !it.region.isNullOrEmpty() }))
}

/**
 * The usage fields a service reports cached input in where they are not the wire protocol's own: [cacheHit] (input read
 * from the cache) and [cacheMiss] (input that was not), both counts of tokens beside the request's input tokens.
 */
@AppServerPluginApi
class UsageFields(val cacheHit: String, val cacheMiss: String? = null)

/** How often a failed model call is tried again: [attempts] in all, waiting from [initialBackoffMillis] up to [maxBackoffMillis]. */
@AppServerPluginApi
class RetryPolicy(val attempts: Int, val initialBackoffMillis: Long, val maxBackoffMillis: Long) {
    init {
        require(attempts >= 1 && initialBackoffMillis >= 0 && maxBackoffMillis >= initialBackoffMillis) { "A retry policy needs one attempt or more." }
    }
}

/**
 * A failed call that reads as [kind]: one answered with [status] (any status, when null) whose answer has a match of
 * [pattern] (a regular expression, ignoring case; any answer, when null).
 */
@AppServerPluginApi
class ErrorRule(val kind: ModelFailureKind, val status: Int? = null, val pattern: String? = null) {
    private val regex = pattern?.let { Regex(it, RegexOption.IGNORE_CASE) }

    init {
        require(status != null || !pattern.isNullOrBlank()) { "An error rule needs a status or a pattern to match." }
    }

    fun matches(failure: ModelFailure): Boolean =
        (status == null || status == failure.status) && (regex == null || regex.containsMatchIn(failure.body))
}

/** What a failed model call was, as the runtime's stable codes say it. */
@AppServerPluginApi
enum class ModelFailureKind(val code: RuntimeErrorCode, val recoverable: Boolean) {
    AUTH(RuntimeErrorCode.PROVIDER_AUTH_FAILED, recoverable = false),
    QUOTA(RuntimeErrorCode.PROVIDER_QUOTA_EXCEEDED, recoverable = false),
    RATE_LIMIT(RuntimeErrorCode.PROVIDER_RATE_LIMITED, recoverable = true),
    CONTEXT_WINDOW(RuntimeErrorCode.PROVIDER_CONTEXT_WINDOW_EXCEEDED, recoverable = false),
    SERVER(RuntimeErrorCode.PROVIDER_SERVER_ERROR, recoverable = true),
    TIMEOUT(RuntimeErrorCode.PROVIDER_TIMEOUT, recoverable = true),
}

/**
 * A model call that failed: the HTTP [status] it was answered with (null when none came, as when the connection
 * failed), the start of the answer ([body], with keys taken out), the runtime's [code] for it, and whether it [timedOut].
 */
@AppServerPluginApi
class ModelFailure(val status: Int?, val body: String, val code: RuntimeErrorCode, val timedOut: Boolean = false)

/** The host and the running plugins' adapters with the service [baseUrl] is on, the first adapter that knows it; null for none. */
internal fun serviceAt(baseUrl: String): Pair<ProviderAdapter, ModelService>? =
    AppHost.providerAdapters.firstNotNullOfOrNull { adapter -> adapter.serviceOf(baseUrl)?.let { adapter to it } }

/** Every service the adapters know, each id once (the first adapter's). */
internal fun knownServices(): List<Pair<ProviderAdapter, ModelService>> =
    AppHost.providerAdapters.flatMap { adapter -> adapter.services.map { adapter to it } }.distinctBy { it.second.id }

/** The adapter and service whose id is [id]; null when no adapter knows it. */
internal fun serviceNamed(id: String): Pair<ProviderAdapter, ModelService>? = knownServices().firstOrNull { it.second.id == id }

/**
 * What is known of [model] on a profile at [baseUrl]: the host's facts ([cc.noharry.agent.appserver.server.AppServerConfig.modelFacts])
 * over its service's; on a host no adapter knows (a relay, say), over those of a service that lists the model by its
 * name, else by its name without a relay's prefix (`relay/`). Empty when nothing is known.
 */
@AppServerPluginApi
fun modelFacts(baseUrl: String, model: String): ModelFacts {
    val bare = model.bareModelId()
    val host = AppHost.modelFacts[model] ?: AppHost.modelFacts[bare]
    val atService = serviceAt(baseUrl)?.let { (adapter, service) -> adapter.facts(service, model) ?: adapter.facts(service, bare) }
    val byName = atService ?: listOf(model, bare).firstNotNullOfOrNull { name ->
        knownServices().firstNotNullOfOrNull { (adapter, service) -> adapter.facts(service, name) }
    }
    return (host ?: ModelFacts()).over(byName ?: ModelFacts())
}

/**
 * [model]'s price on [day]: the host's; else, for a profile at [baseUrl] on a service an adapter knows, that service's
 * for the profile's region ([ModelService.regionOf]: none where the data has no list for it); else the first an adapter
 * has for its name, else for its name without a relay's prefix (each name asked of every service before the next), from
 * the list of the service's first region. Null when none has one.
 */
@AppServerPluginApi
fun modelPrice(model: String, baseUrl: String? = null, day: LocalDate = LocalDate.now()): ModelPrice? {
    val bare = model.bareModelId()
    AppHost.modelPrices[model]?.let { return it }
    AppHost.modelPrices[bare]?.let { return it }
    baseUrl?.let(::serviceAt)?.let { (adapter, service) ->
        val region = service.regionOf(baseUrl)
        return adapter.price(service, model, region, day) ?: adapter.price(service, bare, region, day)
    }
    return listOf(model, bare).firstNotNullOfOrNull { name ->
        knownServices().firstNotNullOfOrNull { (adapter, service) -> adapter.price(service, name, service.defaultRegion, day) }
    }
}

/**
 * [this] with only the reasoning efforts the runtime's variant menu can send for [model] on [provider]'s wire
 * ([ModelVariantRegistry.variants]): one the data lists that the menu does not offer (`xhigh` where the wire has no
 * such variant) is not shown as one to choose.
 */
internal fun ModelFacts.selectableEfforts(provider: ModelProvider, model: String): ModelFacts {
    val offered = ModelVariantRegistry.variants(provider.wireName, model).map { it.id }.toSet()
    return copy(reasoningEfforts = reasoningEfforts.filter { it in offered })
}

/** The usage fields of every service an adapter knows, for reading a call's usage. */
internal fun knownUsageFields(): List<UsageFields> = knownServices().mapNotNull { it.second.usageFields }

/** The model's id without a relay's prefix (`relay/model` is `model`). */
internal fun String.bareModelId(): String = substringAfterLast('/')

/** The host of a URL, lower case; null when it has none. */
internal fun String.urlHost(): String? = runCatching { java.net.URI(trim()).host?.lowercase() }.getOrNull()
