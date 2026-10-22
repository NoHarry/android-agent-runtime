package cc.noharry.agent.model


import cc.noharry.agent.kernel.api.model.ModelRef
import cc.noharry.agent.kernel.api.model.ModelVariant
/** Candidate model variant registry. Provider-specific request options remain internal. */
object ModelVariantRegistry {
    fun variants(providerId: String, modelId: String): List<ModelVariant> =
        providerProfile(providerId, modelId).variants

    fun defaultVariant(providerId: String, modelId: String): String? =
        providerProfile(providerId, modelId).defaultVariant

    fun resolve(
        explicit: ModelRef?,
        sessionRecent: ModelRef? = null,
        modelPreference: ModelRef? = null,
        agentDefault: ModelRef? = null,
        providerDefault: ModelRef? = null,
    ): ModelRef? =
        listOf(explicit, sessionRecent, modelPreference, agentDefault, providerDefault)
            .mapNotNull { it?.normalized() }
            .firstOrNull { it.providerId.isNotBlank() && it.modelId.isNotBlank() }
            ?.withValidVariant()
}

internal data class ProviderRequestOptions(
    val reasoningEffort: String? = null,
    val reasoningSummary: String? = null,
    val includeReasoning: Boolean = false,
    val thinkingBudgetTokens: Int? = null,
    /**
     * DeepSeek thinking-mode switch (`thinking.type`): `enabled` | `disabled`.
     * See https://api-docs.deepseek.com/zh-cn/guides/thinking_mode
     * Null means the provider wire does not send a thinking switch (Claude budget path, etc.).
     */
    val thinkingType: String? = null,
)

internal fun ModelRef?.providerOptions(providerId: String, modelId: String): ProviderRequestOptions =
    (this?.normalized()?.takeIf {
        it.providerId.equals(providerId, ignoreCase = true) &&
            it.modelId.equals(modelId, ignoreCase = true)
    }?.variant ?: ModelVariantRegistry.defaultVariant(providerId, modelId))
        ?.let { variantOptions(providerId, modelId, it) }
        ?: ProviderRequestOptions()

private data class ProviderProfile(
    val variants: List<ModelVariant>,
    val defaultVariant: String? = null,
)

private fun ModelRef.withValidVariant(): ModelRef {
    val activeVariant = variant?.takeIf { selected ->
        ModelVariantRegistry.variants(providerId, modelId).any { it.id == selected }
    }
    return copy(variant = activeVariant)
}

private fun providerProfile(providerId: String, modelId: String): ProviderProfile {
    val provider = providerId.lowercase()
    val model = modelId.lowercase()
    return when {
        provider.contains("anthropic") -> anthropicProfile(model)
        provider.contains("openai") || provider.contains("deepseek") -> openAiCompatibleProfile(model)
        provider.contains("gemini") || provider.contains("google") -> googleProfile()
        else -> ProviderProfile(emptyList(), null)
    }
}

private fun openAiCompatibleProfile(model: String): ProviderProfile {
    val ids = when {
        model.contains("deep-research") -> listOf("medium")
        // DeepSeek thinking mode: none=disabled; low/medium map to high on the wire.
        isDeepSeekModel(model) -> listOf("none") + WIDELY_SUPPORTED_EFFORTS + "max"
        model.contains("gpt-5") && model.contains("codex") -> gpt5CodexEfforts(model)
        model.contains("gpt-5") && model.contains("chat") -> listOf("medium")
        model.contains("gpt-5.2") && model.contains("pro") -> listOf("medium", "high", "xhigh")
        model.contains("gpt-5.3") && model.contains("pro") -> listOf("medium", "high", "xhigh")
        model.contains("gpt-5") && model.contains("pro") -> listOf("high")
        model.contains("gpt-5.1") -> listOf("none") + WIDELY_SUPPORTED_EFFORTS
        model.contains("gpt-5.2") || model.contains("gpt-5.3") -> listOf("none") + WIDELY_SUPPORTED_EFFORTS + "xhigh"
        model.contains("gpt-5") -> listOf("minimal") + WIDELY_SUPPORTED_EFFORTS
        model.startsWith("o1") || model.startsWith("o3") || model.startsWith("o4") -> WIDELY_SUPPORTED_EFFORTS
        else -> emptyList()
    }
    return ProviderProfile(
        variants = ids.toVariants(),
        defaultVariant = ids.defaultOpenAiVariant(model),
    )
}

private fun List<String>.defaultOpenAiVariant(model: String): String? =
    when {
        // Prefer thinking off by default for lower latency; hosts can switch to low/high/max.
        isDeepSeekModel(model) && "none" in this -> "none"
        else -> null
    }

private fun anthropicProfile(model: String): ProviderProfile {
    // Claude: Anthropic budget_tokens. DeepSeek Anthropic proxy: thinking.type + output_config.effort.
    val ids = when {
        isDeepSeekModel(model) -> listOf("none") + WIDELY_SUPPORTED_EFFORTS + "max"
        model.contains("claude") && (model.contains("opus") || model.contains("sonnet")) ->
            listOf("high", "max")
        else -> emptyList()
    }
    return ProviderProfile(
        variants = ids.toVariants(),
        defaultVariant = when {
            isDeepSeekModel(model) && "none" in ids -> "none"
            else -> null
        },
    )
}

private fun googleProfile(): ProviderProfile {
    val ids = listOf("minimal", "low", "medium", "high", "max")
    return ProviderProfile(ids.toVariants())
}

private fun variantOptions(providerId: String, modelId: String, variant: String): ProviderRequestOptions {
    val provider = providerId.lowercase()
    val model = modelId.lowercase()
    val normalized = variant.lowercase()
    // DeepSeek (OpenAI or Anthropic wire): thinking.type + effort — not Claude budget_tokens.
    if (isDeepSeekModel(model)) {
        return deepSeekOptions(normalized)
    }
    return when {
        provider.contains("anthropic") -> anthropicOptions(normalized)
        provider.contains("google") || provider.contains("gemini") -> googleOptions(normalized)
        provider.contains("openai") || provider.contains("deepseek") -> openAiOptions(modelId, normalized)
        else -> ProviderRequestOptions()
    }
}

/**
 * DeepSeek thinking mode (OpenAI + Anthropic-compatible endpoints).
 *
 * - Switch: `thinking.type` = enabled | disabled (`none` variant → disabled).
 * - Effort (thinking on): wire only `high` | `max` (low/medium → high, xhigh → max).
 * - OpenAI body uses `reasoning_effort`; Anthropic body uses `output_config.effort`.
 */
private fun deepSeekOptions(variant: String): ProviderRequestOptions =
    when (variant) {
        "none" -> ProviderRequestOptions(
            thinkingType = THINKING_TYPE_DISABLED,
            includeReasoning = false,
        )
        "low", "medium", "high", "max", "xhigh" -> ProviderRequestOptions(
            thinkingType = THINKING_TYPE_ENABLED,
            reasoningEffort = deepSeekWireEffort(variant),
            includeReasoning = true,
        )
        else -> ProviderRequestOptions()
    }

/** Docs: low/medium map to high; xhigh maps to max. */
private fun deepSeekWireEffort(variant: String): String =
    when (variant.lowercase()) {
        "max", "xhigh" -> "max"
        else -> "high"
    }

private fun openAiOptions(modelId: String, variant: String): ProviderRequestOptions =
    when (variant) {
        "none", "minimal", "low", "medium", "high", "xhigh", "max" -> ProviderRequestOptions(
            reasoningEffort = variant,
            reasoningSummary = if (modelId.lowercase().contains("gpt-5")) "auto" else null,
            includeReasoning = true,
        )
        else -> ProviderRequestOptions()
    }

private fun anthropicOptions(variant: String): ProviderRequestOptions =
    when (variant) {
        "low" -> ProviderRequestOptions(thinkingBudgetTokens = 1_024, includeReasoning = true)
        "medium" -> ProviderRequestOptions(thinkingBudgetTokens = 4_096, includeReasoning = true)
        "high" -> ProviderRequestOptions(thinkingBudgetTokens = 4_096, includeReasoning = true)
        "max" -> ProviderRequestOptions(thinkingBudgetTokens = 8_192, includeReasoning = true)
        else -> ProviderRequestOptions()
    }

internal fun isDeepSeekModel(modelId: String): Boolean {
    val model = modelId.lowercase()
    return model.contains("deepseek")
}

/** Owner-selected output budget, separate from the input context window and thinking effort. */
internal fun modelOutputTokenLimit(modelId: String): Int? =
    DEEPSEEK_MAX_OUTPUT_TOKENS.takeIf { isDeepSeekModel(modelId) }

private const val DEEPSEEK_MAX_OUTPUT_TOKENS = 393_216

internal const val THINKING_TYPE_ENABLED = "enabled"
internal const val THINKING_TYPE_DISABLED = "disabled"

private fun googleOptions(variant: String): ProviderRequestOptions =
    when (variant) {
        "minimal" -> ProviderRequestOptions(thinkingBudgetTokens = 256, includeReasoning = true)
        "low" -> ProviderRequestOptions(thinkingBudgetTokens = 1_024, includeReasoning = true)
        "medium" -> ProviderRequestOptions(thinkingBudgetTokens = 4_096, includeReasoning = true)
        "high", "max" -> ProviderRequestOptions(thinkingBudgetTokens = 8_192, includeReasoning = true)
        else -> ProviderRequestOptions()
    }

private fun String.variantLabel(): String =
    replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

private fun gpt5CodexEfforts(model: String): List<String> =
    when {
        model.contains("gpt-5.3") -> listOf("none") + WIDELY_SUPPORTED_EFFORTS + "xhigh"
        model.contains("gpt-5.2") || model.contains("codex-max") -> WIDELY_SUPPORTED_EFFORTS + "xhigh"
        else -> WIDELY_SUPPORTED_EFFORTS
    }

private fun List<String>.toVariants(): List<ModelVariant> =
    map { ModelVariant(it, it.variantLabel()) }

private val WIDELY_SUPPORTED_EFFORTS = listOf("low", "medium", "high")
