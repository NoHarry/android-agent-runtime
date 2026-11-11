package cc.noharry.agent.appserver.model

import android.content.Context
import cc.noharry.agent.appserver.protocol.ProviderEditState

/**
 * The output tokens a request to [profile]'s model asks for: the person's setting for the model, else its facts'
 * ([cc.noharry.agent.appserver.protocol.ModelFacts.requestOutputTokens]: the host's over its service's); null leaves
 * the wire client's default (the Messages API's fallback, or no limit sent).
 */
internal fun requestOutputTokens(context: Context, profile: ProviderEditState): Long? =
    outputTokensOverride(context, profile) ?: modelFacts(profile.baseUrl, profile.model).requestOutputTokens

/** Sets or, with null, clears the person's output tokens for [profile]'s model. */
@cc.noharry.agent.appserver.AppServerPluginApi
fun saveOutputTokens(context: Context, profile: ProviderEditState, tokens: Long?) {
    require(tokens == null || tokens > 0) { "Output tokens must be positive." }
    context.getSharedPreferences(OUTPUT_TOKENS_PREFS, Context.MODE_PRIVATE)
        .edit().apply {
            if (tokens == null) remove(modelIdentityKey(profile)) else putLong(modelIdentityKey(profile), tokens)
        }.apply()
}

internal fun outputTokensOverride(context: Context, profile: ProviderEditState): Long? =
    context.getSharedPreferences(OUTPUT_TOKENS_PREFS, Context.MODE_PRIVATE)
        .getLong(modelIdentityKey(profile), 0L).takeIf { it > 0 }

internal const val OUTPUT_TOKENS_PREFS = "app_server_output_tokens"
