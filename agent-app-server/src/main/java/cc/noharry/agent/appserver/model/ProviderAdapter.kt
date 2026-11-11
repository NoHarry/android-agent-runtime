@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.model

import android.content.Context
import cc.noharry.agent.appserver.protocol.BalanceView
import cc.noharry.agent.appserver.protocol.ModelFacts
import cc.noharry.agent.appserver.protocol.ModelPrice
import cc.noharry.agent.appserver.protocol.ServiceCheckView
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.server.RouteModule
import cc.noharry.agent.runtime.WebFetchFallback
import cc.noharry.agent.runtime.WebSearchProvider

/**
 * What the server knows of model services beyond the wire protocols it speaks, from a plugin that brings it
 * ([cc.noharry.agent.appserver.server.PluginContext.providerAdapter]). For the profiles on its [services]: who the
 * service is and how it is reached, its models with their facts and prices, how its failures read, its model list; and
 * beyond chat, web search and page fetch APIs whose results reach the model and the screens, its own history updates
 * for runs, its balance, a check of a relay that may have it behind, requests of its own, and keys its preset profiles
 * share. Every member has a default: without an adapter a profile has chat alone, with conservative defaults.
 */
interface ProviderAdapter {
    /** The model services it knows. */
    val services: List<ModelService> get() = emptyList()

    /** The service of [services] a profile at [baseUrl] is on; by default the one with an endpoint on its host. */
    fun serviceOf(baseUrl: String): ModelService? = services.firstOrNull { it.serves(baseUrl) }

    /** What is known of [model] at [service]; by default its entry in the service's list, null when it has none. */
    fun facts(service: ModelService, model: String): ModelFacts? = service.model(model)?.facts

    /** [model]'s price, wherever it ran; by default its entry in one of [services]' lists, null when none has it. */
    fun price(model: String): ModelPrice? = services.firstNotNullOfOrNull { it.model(model)?.price }

    /**
     * The models [service] offers through [profile] with [apiKey], fetched by the adapter; null (the default) has the
     * server list them from the service's models endpoint ([ModelService.modelsUrl]).
     */
    suspend fun listModels(context: Context, service: ModelService, profile: ProviderProfile, apiKey: String): List<String>? = null

    /** What a failed call to [service] was; by default as its [ModelService.errors] say, null for the server's own reading. */
    fun classify(service: ModelService, failure: ModelFailure): ModelFailureKind? = failure.kindBy(service.errors)

    /** Its search and fetch APIs through [profile] (its key, on its host or behind a relay it found them behind); null without. */
    fun lookup(context: Context, config: ProviderConfig, profile: ProviderProfile): WebLookup? = null

    /** Whether [profile] has a [lookup], asked often (each run, each settings view): cheap, with no client built. */
    fun hasLookup(context: Context, config: ProviderConfig, profile: ProviderProfile): Boolean = lookup(context, config, profile) != null

    /** Whether runs on [profile] may send the service's own history updates (system updates, deferred tools loaded natively). */
    fun nativeUpdates(context: Context, config: ProviderConfig, profile: ProviderProfile): Boolean = false

    /**
     * [service]'s balance for [apiKey], at the service itself or, with [relayUrl], at a relay's address a [check] found it
     * behind; null when the service has no balance query ([ModelService.balance] false). A failure the screens can name
     * is a [cc.noharry.agent.appserver.FoundException].
     */
    suspend fun balance(context: Context, service: ModelService, apiKey: String, relayUrl: String?): BalanceView? = null

    /**
     * The check of what of [service]'s own API [profile] (a relay, say) reaches that was last made for it, measured
     * against [profile] and [apiKey] as they are now; null when the service has no such check ([ModelService.relayCheck]).
     */
    fun checked(context: Context, service: ModelService, profile: ProviderProfile, apiKey: String): ServiceCheckView? = null

    /** Checks what of [service]'s own API [profile] reaches with [apiKey], and keeps the result for it; null without a check. */
    suspend fun check(context: Context, service: ModelService, profile: ProviderProfile, apiKey: String): ServiceCheckView? = null

    /** Requests of its own, for its settings. */
    fun routes(app: Context): List<RouteModule> = emptyList()

    /** [profiles] with keys filled in where the service's own profiles share one. */
    fun shareKeys(profiles: List<ProviderProfile>): List<ProviderProfile> = profiles
}

/** A service's web search and page fetch, for web_search and for pages that refuse the device. */
interface WebLookup : WebSearchProvider, WebFetchFallback

/** A lookup through [profile]'s key, else through another profile's, from the host's adapters; null without one. */
@cc.noharry.agent.appserver.AppServerPluginApi
fun ProviderConfig.webLookup(context: Context, profile: ProviderProfile): WebLookup? =
    (listOf(profile) + profiles).firstNotNullOfOrNull { candidate ->
        AppHost.providerAdapters.firstNotNullOfOrNull { it.lookup(context, this, candidate) }
    }

/** Whether any profile has a lookup, which some subagent types need ([cc.noharry.agent.appserver.skills.SubagentKind.requiresWebLookup]). */
internal fun ProviderConfig.hasWebLookup(context: Context): Boolean =
    profiles.any { profile -> AppHost.providerAdapters.any { it.hasLookup(context, this, profile) } }

/** Whether runs on [profile] send a service's own history updates ([ProviderAdapter.nativeUpdates]). */
internal fun ProviderConfig.nativeUpdates(context: Context, profile: ProviderProfile): Boolean =
    AppHost.providerAdapters.any { it.nativeUpdates(context, this, profile) }

/** [this] with the keys the host's adapters share between profiles ([ProviderAdapter.shareKeys]). */
internal fun List<ProviderProfile>.withSharedKeys(): List<ProviderProfile> =
    AppHost.providerAdapters.fold(this) { profiles, adapter -> adapter.shareKeys(profiles) }

/**
 * [this] with what a profile with no base URL leaves to its service: a profile whose id is a known service's
 * ([ModelService.id]) takes the base URL of that service's endpoint for its wire (its own endpoint kind if the service
 * speaks it) and, without a model, the first of the service's models.
 */
internal fun List<ProviderProfile>.withServiceDefaults(): List<ProviderProfile> = map { profile ->
    if (profile.baseUrl.isNotBlank() || AppHost.models.owns(profile.id)) return@map profile
    val service = serviceNamed(profile.id)?.second ?: return@map profile
    val endpoint = service.endpointFor(profile.provider, profile.endpoint) ?: return@map profile
    profile.copy(
        baseUrl = endpoint.baseUrl,
        endpoint = endpoint.endpoint,
        model = profile.model.ifBlank { service.models.firstOrNull()?.id.orEmpty() },
    )
}
