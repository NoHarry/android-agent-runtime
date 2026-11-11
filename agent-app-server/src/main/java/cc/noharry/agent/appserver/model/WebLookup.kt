@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.model
import android.content.Context
import cc.noharry.agent.appserver.runtime.AppHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How each provider profile searches: the provider's server-side search (default) or a service's search API
 * ([ProviderAdapter.lookup]). Kept per profile id, beside the profile settings rather than in them.
 */
@cc.noharry.agent.appserver.AppServerPluginApi
object SearchBackend {
    private val state = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** Profile id → whether it searches through the API, for the Settings page. */
    val usesApi: StateFlow<Map<String, Boolean>> = state.asStateFlow()

    fun load(context: Context): Map<String, Boolean> =
        context.getSharedPreferences(SEARCH_PREFS, Context.MODE_PRIVATE).all
            .mapNotNull { (id, value) -> (value as? Boolean)?.let { id to it } }.toMap()
            .also { state.value = it }

    fun usesApi(context: Context, profileId: String): Boolean = load(context)[profileId] == true

    fun set(context: Context, profileId: String, api: Boolean) {
        context.getSharedPreferences(SEARCH_PREFS, Context.MODE_PRIVATE).edit().putBoolean(profileId, api).apply()
        load(context)
    }
}

/**
 * The search for a run on [profile]: a service's search API when the profile is set to it (or the run is a subagent
 * and [forceApi]) and a key for one is at hand (the profile's own, else another profile's), otherwise null and the
 * provider's own search.
 */
internal fun appWebSearch(
    context: Context,
    config: ProviderConfig,
    profile: ProviderProfile,
    forceApi: Boolean = false,
): WebLookup? {
    if (!forceApi && !SearchBackend.usesApi(context, profile.id)) return null
    return config.webLookup(context, profile)
}

/**
 * Whether web_fetch gets a page through a service's fetch API when the site refused the device or the request failed.
 * One switch for the main agent and subagents, apart from how they search; on by default, since it costs only when a
 * page was refused (some sites answer a device's request with an error such as 432), and only
 * where an installed adapter has such an API ([ProviderAdapter.lookup]).
 */
@cc.noharry.agent.appserver.AppServerPluginApi
object FetchFallback {
    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun set(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FETCH_FALLBACK_PREFS, Context.MODE_PRIVATE)
}

/** The fetch fallback for a run on [profile]: a service's fetch API when switched on and a key for one is at hand. */
internal fun appWebFetchFallback(context: Context, config: ProviderConfig, profile: ProviderProfile): WebLookup? =
    if (FetchFallback.enabled(context)) config.webLookup(context, profile) else null

private const val SEARCH_PREFS = "app_server_search_backend"
private const val FETCH_FALLBACK_PREFS = "app_server_fetch_fallback"
private const val KEY_ENABLED = "enabled"
