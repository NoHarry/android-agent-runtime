package cc.noharry.agent.appserver.model
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.appserver.runtime.DEFAULT_STREAM_ENABLED
import cc.noharry.agent.model.ModelVariantRegistry

internal fun providerProfiles(values: Map<String, String>): List<ProviderProfile> {
    val explicit = values[PROFILES_KEY].csvValues()
        .mapNotNull { profileFromValues(values, it) }
    if (explicit.isNotEmpty()) return explicit.distinctBy { it.id }.withSharedKeys()
    val legacy = ModelProvider.entries.map { provider -> legacyProfile(values, provider) }
    return legacy.distinctBy { it.id }
}

internal fun resolveProviderSelection(
    values: Map<String, String>,
    profiles: List<ProviderProfile>,
    manualProfileEnabled: Boolean,
    selectedProfileId: String?,
): ProviderSelection {
    val defaultId = requestedDefaultProfileId(values, profiles) ?: profiles.first().id
    val selectedId = selectedProfileId.matchProfileId(profiles)
    val activeId = if (manualProfileEnabled && selectedId != null) selectedId else defaultId
    val activeProfile = profiles.firstOrNull { it.id == activeId } ?: profiles.first()
    return ProviderSelection(defaultId, activeProfile)
}

internal fun resolveStreamEnabled(values: Map<String, String>): Boolean =
    values["AGENT_RUNTIME_LIVE_PROVIDER_STREAM"].toStreamOverride()
        ?: values["AGENT_RUNTIME_MODEL_PROVIDER_STREAM"].toStreamOverride()
        ?: DEFAULT_STREAM_ENABLED

internal data class SavedProfileState(
    val selectedProfileId: String?,
    val manualProfileEnabled: Boolean,
    val overrides: Map<String, ProviderProfileOverride>,
) {
    val hasSavedState: Boolean =
        manualProfileEnabled || !selectedProfileId.isNullOrBlank() || overrides.isNotEmpty()
}

internal data class ProviderProfileOverride(
    val label: String? = null,
    val provider: ModelProvider? = null,
    val endpoint: String? = null,
    val apiKey: String? = null,
    val model: String? = null,
    val baseUrl: String? = null,
    val variant: String? = null,
    val serverSideSearch: String? = null,
    val searchCapability: String? = null,
)

private fun profileFromValues(values: Map<String, String>, rawId: String): ProviderProfile? {
    val id = rawId.normalizedProfileId().takeIf { it.isNotBlank() } ?: return null
    val prefix = "$PROFILE_ENV_PREFIX${id.envProfileKey()}_"
    val provider = ModelProvider.from(values["${prefix}PROVIDER"] ?: values["${prefix}KIND"])
    val endpoint = ProviderEndpoint.from(values["${prefix}ENDPOINT"], provider)
    val baseUrl = values["${prefix}BASE_URL"].orDefault(defaultBaseUrl(provider))
    return ProviderProfile(
        id = id,
        label = values["${prefix}LABEL"].orDefault(id),
        provider = provider,
        apiKey = values["${prefix}API_KEY"].orEmpty(),
        model = values["${prefix}MODEL"].orDefault(defaultModel(provider)),
        baseUrl = baseUrl,
        endpoint = endpoint,
        variant = values["${prefix}VARIANT"].orEmpty(),
        streamEnabled = values["${prefix}STREAM"].toStreamOverride(),
        serverSideSearch = values["${prefix}SERVER_SIDE_SEARCH"].toBooleanOverride()
            ?: values["${prefix}SERVER_SIDE_WEB_SEARCH"].toBooleanOverride()
            ?: true,
        searchCapability = values["${prefix}SEARCH_CAPABILITY"]
            ?: values["${prefix}SERVER_SIDE_SEARCH_CAPABILITY"]
            ?: defaultSearchCapability(provider, endpoint),
    )
}

internal fun legacyProfile(values: Map<String, String>, provider: ModelProvider): ProviderProfile {
    val model = values[providerModelName(provider)].orDefault(defaultModel(provider))
    val endpoint = ProviderEndpoint.from(values[providerEndpointName(provider)], provider)
    val baseUrl = values[providerBaseUrlName(provider)].orDefault(defaultBaseUrl(provider))
    return ProviderProfile(
        id = provider.wireName,
        label = provider.label,
        provider = provider,
        apiKey = values[providerKeyName(provider)].orEmpty(),
        model = model,
        baseUrl = baseUrl,
        endpoint = endpoint,
        variant = variant(values, provider, model),
        streamEnabled = null,
        serverSideSearch = values[providerServerSideSearchName(provider)].toBooleanOverride() ?: true,
        searchCapability = defaultSearchCapability(provider, endpoint),
    )
}

internal fun List<ProviderProfile>.withSavedProfiles(
    overrides: Map<String, ProviderProfileOverride>,
): List<ProviderProfile> =
    (map { profile -> profile.withOverride(overrides[profile.id.preferenceKey()] ?: overrides[profile.id]) } +
        overrides.filterKeys { id -> none { it.id.preferenceKey() == id.preferenceKey() } }
            .mapNotNull { (id, override) -> override.toProfile(id) })
        .distinctBy { it.id }

private fun ProviderProfile.withOverride(override: ProviderProfileOverride?): ProviderProfile {
    if (override == null) return this
    val activeProvider = override.provider ?: provider
    val activeEndpoint = ProviderEndpoint.from(override.endpoint, activeProvider)
    val activeBaseUrl = override.baseUrl ?: baseUrl
    val profileShapeChanged = override.provider != null || override.endpoint != null || override.baseUrl != null
    return copy(
        label = override.label ?: label,
        provider = activeProvider,
        endpoint = activeEndpoint,
        apiKey = override.apiKey ?: apiKey,
        model = override.model ?: model,
        baseUrl = activeBaseUrl,
        variant = override.variant ?: variant,
        serverSideSearch = override.serverSideSearch.toBooleanOverride() ?: serverSideSearch,
        searchCapability = override.searchCapability
            ?: if (profileShapeChanged) defaultSearchCapability(activeProvider, activeEndpoint) else searchCapability,
    )
}

private fun ProviderProfileOverride.toProfile(id: String): ProviderProfile? {
    val provider = provider ?: return null
    val model = model ?: defaultModel(provider)
    val baseUrl = baseUrl?.takeIf { it.isNotBlank() } ?: return null
    return ProviderProfile(
        id = id.normalizedProfileId().takeIf { it.isNotBlank() } ?: return null,
        label = label?.takeIf { it.isNotBlank() } ?: id,
        provider = provider,
        apiKey = apiKey.orEmpty(),
        model = model,
        baseUrl = baseUrl,
        endpoint = ProviderEndpoint.from(endpoint, provider),
        variant = variant.orEmpty(),
        serverSideSearch = serverSideSearch.toBooleanOverride() ?: true,
        searchCapability = searchCapability
            ?: defaultSearchCapability(provider, ProviderEndpoint.from(endpoint, provider)),
    )
}

internal fun defaultSearchCapability(
    provider: ModelProvider,
    endpoint: ProviderEndpoint,
): String =
    when (provider) {
        ModelProvider.ANTHROPIC -> "anthropic-server-tool"
        ModelProvider.OPENAI -> when {
            endpoint == ProviderEndpoint.RESPONSES -> "openai-responses-tool"
            else -> "openai-chat-options"
        }
    }

private fun requestedDefaultProfileId(
    values: Map<String, String>,
    profiles: List<ProviderProfile>,
): String? =
    listOf(
        values["AGENT_RUNTIME_ACTIVE_PROFILE"],
        values["AGENT_RUNTIME_DEFAULT_PROFILE"],
        values["AGENT_RUNTIME_LIVE_PROVIDER"],
        values["AGENT_RUNTIME_MODEL_PROVIDER"],
    ).firstNotNullOfOrNull { requested ->
        requested.matchProfileId(profiles)
            ?: profiles.firstOrNull { it.provider.wireName == requested?.trim()?.lowercase() }?.id
    }

/** The id among [profiles] that [this] names, as a selection reads it: ignoring case, then by its preference key. */
internal fun String?.matchProfileId(profiles: List<ProviderProfile>): String? {
    val requested = this?.normalizedProfileId() ?: return null
    return profiles.firstOrNull { it.id.equals(requested, ignoreCase = true) }?.id
        ?: profiles.firstOrNull { it.id.preferenceKey() == requested.preferenceKey() }?.id
}

internal fun ProviderProfile.toActiveValues(): Map<String, String> =
    buildMap {
        put("AGENT_RUNTIME_LIVE_PROVIDER", provider.wireName)
        put(providerKeyName(provider), apiKey)
        put(providerModelName(provider), model)
        put(providerBaseUrlName(provider), baseUrl)
        put(providerEndpointName(provider), endpoint.wireName)
        if (variant.isNotBlank()) put(providerVariantName(provider), variant)
        streamEnabled?.let { put("AGENT_RUNTIME_LIVE_PROVIDER_STREAM", it.toString()) }
        if (serverSideSearch) put(providerServerSideSearchName(provider), "true")
        if (searchCapability.isNotBlank()) put(providerSearchCapabilityName(provider), searchCapability)
    }

internal fun providerFromOrNull(value: String?): ModelProvider? =
    value?.let { raw -> ModelProvider.entries.firstOrNull { it.wireName == raw.trim().lowercase() } }

internal fun manualProfileId(label: String, provider: ModelProvider): String {
    val base = label.normalizedProfileId().ifBlank { provider.wireName }
    return "$MANUAL_PROFILE_ID_PREFIX-$base-${System.currentTimeMillis()}"
}

internal fun providerKeyName(provider: ModelProvider): String =
    when (provider) {
        ModelProvider.ANTHROPIC -> "ANTHROPIC_API_KEY"
        ModelProvider.OPENAI -> "OPENAI_API_KEY"
    }

internal fun providerModelName(provider: ModelProvider): String =
    when (provider) {
        ModelProvider.ANTHROPIC -> "ANTHROPIC_MODEL"
        ModelProvider.OPENAI -> "OPENAI_MODEL"
    }

internal fun providerBaseUrlName(provider: ModelProvider): String =
    when (provider) {
        ModelProvider.ANTHROPIC -> "ANTHROPIC_BASE_URL"
        ModelProvider.OPENAI -> "OPENAI_BASE_URL"
    }

internal fun providerEndpointName(provider: ModelProvider): String =
    when (provider) {
        ModelProvider.ANTHROPIC -> "ANTHROPIC_ENDPOINT"
        ModelProvider.OPENAI -> "OPENAI_ENDPOINT"
    }

internal fun providerVariantName(provider: ModelProvider): String =
    when (provider) {
        ModelProvider.ANTHROPIC -> "ANTHROPIC_VARIANT"
        ModelProvider.OPENAI -> "OPENAI_VARIANT"
    }

internal fun providerServerSideSearchName(provider: ModelProvider): String =
    when (provider) {
        ModelProvider.ANTHROPIC -> "ANTHROPIC_SERVER_SIDE_SEARCH"
        ModelProvider.OPENAI -> "OPENAI_SERVER_SIDE_SEARCH"
    }

internal fun providerSearchCapabilityName(provider: ModelProvider): String =
    when (provider) {
        ModelProvider.ANTHROPIC -> "ANTHROPIC_SEARCH_CAPABILITY"
        ModelProvider.OPENAI -> "OPENAI_SEARCH_CAPABILITY"
    }

private fun variant(values: Map<String, String>, provider: ModelProvider, model: String): String {
    val configured = values[providerModelVariantName(provider, model)]
        ?: values[providerVariantName(provider)]?.takeIf { it.isNotBlank() }
    val variants = ModelVariantRegistry.variants(provider.wireName, model).map { it.id }
    return configured?.takeIf { it in variants }.orEmpty()
}

private fun providerModelVariantName(provider: ModelProvider, model: String): String =
    "${providerVariantName(provider)}$MODEL_VARIANT_KEY_SEPARATOR${model.preferenceKey()}"

private fun defaultModel(provider: ModelProvider): String =
    when (provider) {
        ModelProvider.ANTHROPIC -> DEFAULT_ANTHROPIC_MODEL
        ModelProvider.OPENAI -> DEFAULT_OPENAI_MODEL
    }

private fun defaultBaseUrl(provider: ModelProvider): String =
    when (provider) {
        ModelProvider.ANTHROPIC -> DEFAULT_ANTHROPIC_BASE_URL
        ModelProvider.OPENAI -> DEFAULT_OPENAI_BASE_URL
    }
