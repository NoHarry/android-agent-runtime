package cc.noharry.agent.appserver.runtime
import android.content.Context
import cc.noharry.agent.appserver.model.ModelOptionsController
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.model.ProviderProfile
import cc.noharry.agent.appserver.protocol.DEFAULT_DEBUG_VARIANT_ID
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.protocol.ProviderEditState
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.model.ModelVariantRegistry

internal fun saveSelectedModel(
    context: Context,
    profile: ProviderProfile,
    config: ProviderConfig,
    model: String,
): ProviderConfig? {
    val selectedModel = model.trim()
    if (selectedModel.isEmpty()) return null
    val editor = config.editState(profile)
    if (editor.model == selectedModel) return null
    ProviderConfig.saveProfileOverride(
        context,
        profile,
        apiKey = profile.apiKey,
        model = selectedModel,
        baseUrl = editor.baseUrl,
        endpoint = editor.endpoint,
    )
    return ProviderConfig.load(context)
}

internal fun saveSelectedVariant(
    context: Context,
    profile: ProviderProfile,
    config: ProviderConfig,
    variant: String,
): ProviderConfig? {
    val selectedVariant = variant.trim().takeUnless { it.equals(DEFAULT_DEBUG_VARIANT_ID, ignoreCase = true) }.orEmpty()
    val editor = config.editState(profile)
    if (editor.variant == selectedVariant) return null
    ProviderConfig.saveVariantOverride(context, profile.withModel(editor.model), selectedVariant)
    return ProviderConfig.load(context)
}

internal fun saveProviderOverrideChoice(
    context: Context,
    profile: ProviderProfile,
    config: ProviderConfig?,
    apiKey: String? = null,
    model: String? = null,
    baseUrl: String? = null,
    endpoint: ProviderEndpoint? = null,
    selectedProvider: ModelProvider? = null,
): ProviderConfig {
    val editor = config?.editState(profile) ?: ProviderEditState()
    val targetProvider = selectedProvider ?: profile.provider
    val targetEndpoint = (endpoint ?: editor.endpoint).takeIf { it.provider == targetProvider }
        ?: ProviderEndpoint.defaultFor(targetProvider)
    ProviderConfig.saveProfileOverride(
        context,
        profile.copy(provider = targetProvider),
        apiKey = apiKey?.takeIf { it.isNotBlank() } ?: profile.apiKey,
        model = model ?: editor.model,
        baseUrl = baseUrl ?: editor.baseUrl,
        endpoint = targetEndpoint,
    )
    return ProviderConfig.load(context)
}

internal fun appConfigEditor(
    profile: ProviderProfile?,
    config: ProviderConfig?,
    modelOptions: ModelOptionsController,
): ProviderEditState {
    val activeProfile = profile ?: config?.activeProfile
    val editor = activeProfile?.let { config?.editState(it) } ?: ProviderEditState()
    return editor.copy(
        modelOptions = activeProfile?.let { modelOptions.options(it, editor.model) }.orEmpty(),
        variantOptions = activeProfile
            ?.let { ModelVariantRegistry.variants(it.provider.wireName, editor.model).map { variant -> variant.id } }
            .orEmpty(),
        defaultVariant = activeProfile?.let { ModelVariantRegistry.defaultVariant(it.provider.wireName, editor.model) },
    )
}

/** The key a model list is fetched with: [typed], else the one saved for [profileId], else a local server's stand-in. */
internal fun catalogKey(config: ProviderConfig?, profileId: String, typed: String, baseUrl: String): String =
    (config?.keyFor(profileId, typed) ?: typed).ifBlank { DEFAULT_TEST_API_KEY.takeIf { baseUrl.isLocalUrl() }.orEmpty() }

private fun String.isLocalUrl(): Boolean {
    val lower = trim().lowercase()
    return lower.startsWith("http://localhost") || lower.startsWith("http://127.0.0.1")
}

private const val DEFAULT_TEST_API_KEY = "local"
