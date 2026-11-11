package cc.noharry.agent.appserver.model

import cc.noharry.agent.appserver.protocol.ProviderEndpoint

/**
 * A model service a host's build carries ([cc.noharry.agent.appserver.server.AppServerConfig.model]): [baseUrl] (an
 * OpenAI-compatible `…/v1`, or Anthropic's), the [model]'s name, its [apiKey] (blank for a server on this device) and
 * the [endpoint] kind (Chat Completions by default; [ProviderEndpoint.RESPONSES], or [ProviderEndpoint.MESSAGES] for
 * Anthropic). It is a profile named [label] among the person's, the one a new installation answers with; the profile
 * the person picks later wins, and they may edit this one's key and model. The key is never shown, logged or published.
 */
class ModelPreset(
    val baseUrl: String,
    val model: String,
    val apiKey: String = "",
    val endpoint: ProviderEndpoint = ProviderEndpoint.CHAT_COMPLETIONS,
    val label: String = model,
) {
    init {
        require(baseUrl.isNotBlank() && model.isNotBlank()) { "A model preset needs a base URL and a model." }
    }

    override fun toString(): String = "ModelPreset($label: ${endpoint.wireName} $model at $baseUrl)"
}

/**
 * [this] build's values with [preset] as one more profile, first in the list and the default, unless they name a
 * default profile themselves (`activeProfile`, `defaultProfile`).
 */
internal fun Map<String, String>.withModelPreset(preset: ModelPreset?): Map<String, String> {
    if (preset == null) return this
    val prefix = "$PROFILE_ENV_PREFIX${MODEL_PRESET_PROFILE_ID.envProfileKey()}_"
    val listed = this[PROFILES_KEY].csvValues().filterNot { it.normalizedProfileId() == MODEL_PRESET_PROFILE_ID }
    val namesDefault = this[ACTIVE_PROFILE_KEY].orEmpty().isNotBlank() || this[PRESET_DEFAULT_PROFILE_KEY].orEmpty().isNotBlank()
    return this + mapOf(
        PROFILES_KEY to (listOf(MODEL_PRESET_PROFILE_ID) + listed).joinToString(","),
        "${prefix}LABEL" to preset.label,
        "${prefix}PROVIDER" to preset.endpoint.provider.wireName,
        "${prefix}ENDPOINT" to preset.endpoint.wireName,
        "${prefix}BASE_URL" to preset.baseUrl.trim(),
        "${prefix}MODEL" to preset.model.trim(),
        "${prefix}API_KEY" to preset.apiKey.trim(),
    ) + if (namesDefault) emptyMap() else mapOf(PRESET_DEFAULT_PROFILE_KEY to MODEL_PRESET_PROFILE_ID)
}

/** The profile id of the host's [ModelPreset]. */
internal const val MODEL_PRESET_PROFILE_ID = "preset"
private const val ACTIVE_PROFILE_KEY = "AGENT_RUNTIME_ACTIVE_PROFILE"
private const val PRESET_DEFAULT_PROFILE_KEY = "AGENT_RUNTIME_DEFAULT_PROFILE"
