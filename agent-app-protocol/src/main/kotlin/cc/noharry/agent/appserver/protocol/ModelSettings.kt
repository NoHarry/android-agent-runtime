package cc.noharry.agent.appserver.protocol

import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import kotlinx.serialization.Serializable

/** A context window as typed: `128000`, `128K` or `1M`; null when it is not a positive whole number of tokens. */
fun parseContextWindow(text: String): Long? {
    val value = text.trim().uppercase()
    val multiplier = when (value.lastOrNull()) { 'K' -> 1_000L; 'M' -> 1_000_000L; else -> 1L }
    val number = if (multiplier == 1L) value else value.dropLast(1)
    return runCatching { number.toBigDecimal().multiply(multiplier.toBigDecimal()).longValueExact() }
        .getOrNull()?.takeIf { it > 0 }
}

/** Model IDs trimmed, blanks dropped, each once, in their order. */
fun normalizeProviderModels(models: List<String>): List<String> =
    models.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/**
 * One model of a profile: the context window set for it (null: its default), the default, whether it takes images, the
 * output tokens its requests ask for set for it ([outputOverride], null: [ModelFacts.requestOutputTokens], else the wire
 * client's default), and what is known of it ([facts]: its service's, with the host's own over them; read only, not
 * saved; its reasoning efforts are those the runtime's variant menu can send).
 */
@Serializable
data class ModelSettingsView(
    val model: String,
    val windowOverride: Long? = null,
    val defaultWindow: Long = 0,
    val imageInput: Boolean = true,
    val facts: ModelFacts = ModelFacts(),
    val outputOverride: Long? = null,
)

/** The profile (as [ProviderEditState] names it) and the models whose settings are asked for. */
@Serializable
data class ModelSettingsParams(val profile: ProviderEditState, val models: List<String>)

@Serializable
data class ModelSettingsResult(val models: List<ModelSettingsView>)

/**
 * Each model's window ([ModelSettingsView.windowOverride], null for the default), image input and output tokens
 * ([ModelSettingsView.outputOverride], null for the default), saved for [profile].
 */
@Serializable
data class ModelSettingsSaveParams(val profile: ProviderEditState, val models: List<ModelSettingsView>)

/**
 * Web search and the fetch fallback for the active profile: whether it searches through a service's search API, whether it
 * has a search of its own, whether pages refused by the device go through a service's fetch API, and whether any
 * profile has a key for those ([lookupKey]).
 */
@Serializable
data class WebLookupView(
    val profileId: String = "",
    val profileLabel: String = "",
    val searchApi: Boolean = false,
    val ownSearch: Boolean = true,
    val fetchFallback: Boolean = true,
    val lookupKey: Boolean = false,
)

/** Model and service settings beyond the profile itself. */
object ModelSettingsRequests {
    val MODELS_READ = RequestMethod("modelSettings/read", ModelSettingsParams.serializer(), ModelSettingsResult.serializer())
    val MODELS_SAVE = RequestMethod("modelSettings/save", ModelSettingsSaveParams.serializer(), Empty.serializer())
    val LOOKUP_READ = RequestMethod("webLookup/read", Empty.serializer(), WebLookupView.serializer())
    val LOOKUP_SET_SEARCH_API = RequestMethod("webLookup/setSearchApi", FlagParams.serializer(), WebLookupView.serializer())
    val LOOKUP_SET_FETCH_FALLBACK = RequestMethod("webLookup/setFetchFallback", FlagParams.serializer(), WebLookupView.serializer())
}
