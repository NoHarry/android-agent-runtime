package cc.noharry.agent.appserver.model
import android.content.Context
import cc.noharry.agent.appserver.protocol.ProviderEditState
import cc.noharry.agent.appserver.runtime.AppHost
import java.security.MessageDigest

/**
 * The model's context window, for the usage shown and a run's automatic compaction at 80 percent: the one the person set
 * for it, else its default ([defaultContextWindow]).
 */
internal fun loadContextWindow(context: Context, profile: ProviderEditState): Long =
    contextWindowOverride(context, profile) ?: defaultContextWindow(profile.baseUrl, profile.model)

@cc.noharry.agent.appserver.AppServerPluginApi
fun saveContextWindow(context: Context, profile: ProviderEditState, tokens: Long?) {
    require(tokens == null || tokens > 0) { "Context window must be positive." }
    context.getSharedPreferences(CONTEXT_WINDOW_PREFS, Context.MODE_PRIVATE)
        .edit().apply {
            if (tokens == null) remove(modelIdentityKey(profile)) else putLong(modelIdentityKey(profile), tokens)
        }.apply()
}

/**
 * [model]'s window on a profile at [baseUrl]: the one a model the host runs itself is started with, else the one its
 * facts give ([modelFacts]: the host's, else its service's), else 128K tokens.
 */
internal fun defaultContextWindow(baseUrl: String, model: String): Long =
    AppHost.models.contextTokens(model) ?: modelFacts(baseUrl, model).contextWindow ?: DEFAULT_CONTEXT_TOKENS

/** One saved model: profile, provider, endpoint, base URL and model ID. Keys every per-model setting. */
internal fun modelIdentityKey(profile: ProviderEditState): String {
    val identity = listOf(profile.profileId, profile.provider.wireName, profile.endpoint.wireName, profile.baseUrl, profile.model)
        .joinToString(separator = "\u0000")
    return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal const val CONTEXT_WINDOW_PREFS = "app_server_context_windows"
private const val DEFAULT_CONTEXT_TOKENS = 128_000L

internal fun contextWindowOverride(context: Context, profile: ProviderEditState): Long? =
    context.getSharedPreferences(CONTEXT_WINDOW_PREFS, Context.MODE_PRIVATE)
        .getLong(modelIdentityKey(profile), 0L).takeIf { it > 0 }
