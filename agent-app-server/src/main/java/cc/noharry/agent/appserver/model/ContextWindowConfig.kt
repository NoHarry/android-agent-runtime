package cc.noharry.agent.appserver.model
import android.content.Context
import cc.noharry.agent.appserver.protocol.ProviderEditState
import cc.noharry.agent.appserver.runtime.AppHost
import java.security.MessageDigest

/** Shared model capacity for usage display and next-run automatic compaction at80percent. */
internal fun loadContextWindow(context: Context, profile: ProviderEditState): Long? {
    val stored = context.getSharedPreferences(CONTEXT_WINDOW_PREFS, Context.MODE_PRIVATE)
        .getLong(modelIdentityKey(profile), 0L)
    return stored.takeIf { it > 0 } ?: defaultContextWindow(profile.model)
}

@cc.noharry.agent.appserver.AppServerPluginApi
fun saveContextWindow(context: Context, profile: ProviderEditState, tokens: Long?) {
    require(tokens == null || tokens > 0) { "Context window must be positive." }
    context.getSharedPreferences(CONTEXT_WINDOW_PREFS, Context.MODE_PRIVATE)
        .edit().apply {
            if (tokens == null) remove(modelIdentityKey(profile)) else putLong(modelIdentityKey(profile), tokens)
        }.apply()
}

internal fun defaultContextWindow(model: String): Long =
    // A model the host runs itself (an on-device server) is started with its window.
    AppHost.models.contextTokens(model)
        // These are the current deployment aliases with a confirmed 1M window.
        // Do not infer the same capacity for older DeepSeek models or other providers.
        ?: if (model.substringAfterLast('/').lowercase() in ONE_MILLION_DEEPSEEK_MODELS) DEEPSEEK_CONTEXT_TOKENS else DEFAULT_CONTEXT_TOKENS

/** One saved model: profile, provider, endpoint, base URL and model ID. Keys every per-model setting. */
internal fun modelIdentityKey(profile: ProviderEditState): String {
    val identity = listOf(profile.profileId, profile.provider.wireName, profile.endpoint.wireName, profile.baseUrl, profile.model)
        .joinToString(separator = "\u0000")
    return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal const val CONTEXT_WINDOW_PREFS = "app_server_context_windows"
private const val DEFAULT_CONTEXT_TOKENS = 128_000L
private const val DEEPSEEK_CONTEXT_TOKENS = 1_000_000L
private val ONE_MILLION_DEEPSEEK_MODELS = setOf("deepseek-flash", "deepseek-v4.1-flash")

internal fun contextWindowOverride(context: Context, profile: ProviderEditState): Long? =
    context.getSharedPreferences(CONTEXT_WINDOW_PREFS, Context.MODE_PRIVATE)
        .getLong(modelIdentityKey(profile), 0L).takeIf { it > 0 }
