@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.model

import android.content.Context
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.server.RouteModule
import cc.noharry.agent.runtime.WebFetchFallback
import cc.noharry.agent.runtime.WebSearchProvider

/**
 * What a model service offers beyond chat, for the profiles that reach it: web search and page fetch APIs whose
 * results reach the model and the screens, its own history updates for runs, requests of its own (a balance, a check
 * of a key or a relay), and keys its preset profiles share. A plugin brings the adapters for its services
 * ([cc.noharry.agent.appserver.server.AppServerPlugin.providerAdapters]); without one a profile has chat alone.
 */
interface ProviderAdapter {
    /** Its search and fetch APIs through [profile] (its key, on its host or behind a relay it found them behind); null without. */
    fun lookup(context: Context, config: ProviderConfig, profile: ProviderProfile): WebLookup? = null

    /** Whether [profile] has a [lookup], asked often (each run, each settings view): cheap, with no client built. */
    fun hasLookup(context: Context, config: ProviderConfig, profile: ProviderProfile): Boolean = lookup(context, config, profile) != null

    /** Whether runs on [profile] may send the service's own history updates (system updates, deferred tools loaded natively). */
    fun nativeUpdates(context: Context, config: ProviderConfig, profile: ProviderProfile): Boolean = false

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
