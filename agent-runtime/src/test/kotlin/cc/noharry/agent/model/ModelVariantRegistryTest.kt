package cc.noharry.agent.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import cc.noharry.agent.spi.model.ModelRequest
import cc.noharry.agent.spi.model.ModelRef
import cc.noharry.agent.spi.model.ModelMessage

class ModelVariantRegistryTest {
    @Test
    fun resolvesExplicitVariantBeforeDefaults() {
        val ref = ModelVariantRegistry.resolve(
            explicit = ModelRef("openai", "gpt-5", "high"),
            providerDefault = ModelRef("openai", "gpt-5", "minimal"),
        )

        assertEquals(ModelRef("openai", "gpt-5", "high"), ref)
    }

    @Test
    fun defaultSelectionDoesNotForceVariant() {
        val ref = ModelVariantRegistry.resolve(
            explicit = ModelRef("openai", "gpt-5"),
        )

        assertNull(ref?.variant)
    }

    @Test
    fun nonReasoningOpenAiModelHasNoVariants() {
        val variants = ModelVariantRegistry.variants("openai", "gpt-4o-mini").map { it.id }

        assertEquals(emptyList<String>(), variants)
    }

    @Test
    fun gpt5ModelHasModelSpecificEfforts() {
        val variants = ModelVariantRegistry.variants("openai", "gpt-5").map { it.id }

        assertEquals(listOf("minimal", "low", "medium", "high"), variants)
    }

    @Test
    fun deepSeekV4OpenAiCompatibleModelHasNoneAndEffortLadder() {
        val variants = ModelVariantRegistry.variants("openai", "deepseek-v4-flash").map { it.id }

        assertEquals(listOf("none", "low", "medium", "high", "max"), variants)
    }

    @Test
    fun deepSeekV4DefaultsToDisabledThinkingNoneEffort() {
        val body = openAiRequest(
            apiKey = "key",
            baseUrl = "https://provider.test/v1",
            model = "deepseek-v4-flash",
            request = request(ModelRef("openai", "deepseek-v4-flash")),
        ).bodyObject()
        val thinking = body["thinking"]?.jsonObject

        assertEquals("deepseek-v4-flash", body["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals("disabled", thinking?.get("type")?.jsonPrimitive?.contentOrNull)
        assertNull(body["reasoning_effort"])
        assertEquals("none", ModelVariantRegistry.defaultVariant("openai", "deepseek-v4-flash"))
    }

    @Test
    fun deepSeekNoneVariantDisablesThinkingOnOpenAiWire() {
        val body = openAiRequest(
            apiKey = "key",
            baseUrl = "https://api.deepseek.com/v1",
            model = "deepseek-v4-flash",
            request = request(ModelRef("openai", "deepseek-v4-flash", "none")),
        ).bodyObject()
        val thinking = body["thinking"]?.jsonObject

        assertEquals("disabled", thinking?.get("type")?.jsonPrimitive?.contentOrNull)
        assertNull(body["reasoning_effort"])
    }

    @Test
    fun deepSeekLowMapsToHighEffortOnOpenAiWire() {
        val body = openAiRequest(
            apiKey = "key",
            baseUrl = "https://provider.test/v1",
            model = "deepseek-v4-flash",
            request = request(ModelRef("openai", "deepseek-v4-flash", "low")),
        ).bodyObject()

        assertEquals("enabled", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
        assertEquals("high", body["reasoning_effort"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun explicitDeepSeekV4MaxVariantUsesMaxEffort() {
        val body = openAiRequest(
            apiKey = "key",
            baseUrl = "https://provider.test/v1",
            model = "deepseek-v4-flash",
            request = request(ModelRef("openai", "deepseek-v4-flash", "max")),
        ).bodyObject()

        assertEquals("max", body["reasoning_effort"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun aVariantAppliesToItsModelWhateverTheHostCallsTheProvider() {
        val body = openAiRequest(
            apiKey = "key",
            baseUrl = "https://provider.test/v1",
            model = "deepseek-v4-flash",
            request = request(ModelRef("deepseek", "deepseek-v4-flash", "high")),
        ).bodyObject()
        assertEquals("high", body["reasoning_effort"]?.jsonPrimitive?.contentOrNull)
        assertEquals("enabled", body["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
        // A variant chosen for another model does not carry over.
        val other = openAiRequest(
            apiKey = "key",
            baseUrl = "https://provider.test/v1",
            model = "deepseek-v4-flash",
            request = request(ModelRef("my-relay", "deepseek-v4-pro", "high")),
        ).bodyObject()
        assertEquals("disabled", other["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
        // A reasoning model the registry does not list still gets the effort the host chose.
        val unlisted = openAiRequest(
            apiKey = "key",
            baseUrl = "https://provider.test/v1",
            model = "gpt-oss-120b",
            request = request(ModelRef("my-relay", "gpt-oss-120b", "high")),
        ).bodyObject()
        assertEquals("high", unlisted["reasoning_effort"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun deepSeekReasonerAlsoExposesThinkingSwitchVariants() {
        val variants = ModelVariantRegistry.variants("openai", "deepseek-reasoner").map { it.id }

        assertEquals(listOf("none", "low", "medium", "high", "max"), variants)
    }

    @Test
    fun openAiVariantMapsToReasoningEffort() {
        val body = openAiRequest(
            apiKey = "key",
            baseUrl = "https://provider.test/v1",
            model = "gpt-5",
            request = request(ModelRef("openai", "gpt-5", "high")),
        ).bodyObject()

        assertEquals("gpt-5", body["model"]?.jsonPrimitive?.contentOrNull)
        assertEquals("high", body["reasoning_effort"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun noneVariantEmitsReasoningEffortWhenSelected() {
        val body = openAiRequest(
            apiKey = "key",
            baseUrl = "https://provider.test/v1",
            model = "gpt-5.1",
            request = request(ModelRef("openai", "gpt-5.1", "none")),
        ).bodyObject()

        assertEquals("none", body["reasoning_effort"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun anthropicVariantMapsToThinkingBudget() {
        val body = anthropicRequest(
            apiKey = "key",
            baseUrl = "https://provider.test/v1",
            model = "claude-3-7-sonnet",
            request = request(ModelRef("anthropic", "claude-3-7-sonnet", "high")),
        ).bodyObject()
        val thinking = body["thinking"]?.jsonObject

        assertNotNull(thinking)
        assertEquals("enabled", thinking?.get("type")?.jsonPrimitive?.contentOrNull)
        assertEquals("4096", thinking?.get("budget_tokens")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun anAdaptiveClaudeThinksWithAnEffortAndNeverABudget() {
        assertEquals(listOf("low", "medium", "high", "max"), ModelVariantRegistry.variants("anthropic", "claude-sonnet-5-5").map { it.id })
        val body = anthropicRequest(
            apiKey = "key",
            baseUrl = "https://provider.test/v1",
            model = "claude-opus-5-5",
            request = request(ModelRef("anthropic", "claude-opus-5-5", "high")),
        ).bodyObject()
        val thinking = body["thinking"]?.jsonObject

        assertEquals("adaptive", thinking?.get("type")?.jsonPrimitive?.contentOrNull)
        assertNull(thinking?.get("budget_tokens"))
        // From 4.7 the thinking comes back only when a summary is asked for.
        assertEquals("summarized", thinking?.get("display")?.jsonPrimitive?.contentOrNull)
        assertEquals("high", body["output_config"]?.jsonObject?.get("effort")?.jsonPrimitive?.contentOrNull)
        // Some refuse turning thinking off: "none" is not offered, and an unknown variant sends nothing.
        val plain = anthropicRequest("key", "https://provider.test/v1", "claude-opus-5-5", request(ModelRef("anthropic", "claude-opus-5-5", "none"))).bodyObject()
        assertNull(plain["thinking"])
        assertNull(plain["output_config"])
        // 4.6 summarizes on its own and does not take the field.
        val opus46 = anthropicRequest("key", "https://provider.test/v1", "claude-opus-4-6", request(ModelRef("anthropic", "claude-opus-4-6", "max"))).bodyObject()
        assertEquals("adaptive", opus46["thinking"]?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
        assertNull(opus46["thinking"]?.jsonObject?.get("display"))
    }

    @Test
    fun anOlderClaudeThinksOnABudgetAndAnEarlierOneDoesNotThink() {
        for (model in listOf("claude-haiku-4-5", "claude-sonnet-4-5-20250929", "anthropic.claude-opus-4-1-20250805-v1:0", "claude-3-7-sonnet-latest")) {
            assertEquals(model, listOf("high", "max"), ModelVariantRegistry.variants("anthropic", model).map { it.id })
        }
        for (model in listOf("claude-3-opus-20240229", "claude-3-5-sonnet-latest", "anthropic.claude-v2:1")) {
            assertEquals(model, emptyList<String>(), ModelVariantRegistry.variants("anthropic", model).map { it.id })
        }
    }

    @Test
    fun claudeVersionsSplitAtFourSix() {
        val adaptive = listOf(
            "claude-opus-4-6", "claude-sonnet-4-6", "claude-sonnet-5", "claude-haiku-5-5", "claude-opus-5-5-20261101",
            "claude-fable-5-1", "anthropic/claude-sonnet-5-5", "anthropic/claude-opus-4.7", "claude-sonnet-4.6",
            "claude-opus-4-7@20260101", "global.anthropic.claude-opus-4-8-20260301-v1:0",
        )
        for (model in adaptive) assertTrue(model, isAdaptiveClaude(model))
        for (model in listOf(
            "claude-3-7-sonnet", "claude-3-5-haiku-latest", "claude-sonnet-4-20250514", "claude-opus-4-1-20250805",
            "claude-sonnet-4-5-20250929", "claude-haiku-4-5", "anthropic/claude-opus-4.5", "claude-sonnet-4@20250514",
            "anthropic.claude-v2:1", "deepseek-flash", "gpt-5",
        )) {
            assertFalse(model, isAdaptiveClaude(model))
        }
    }

    @Test
    fun deepSeekV4OnAnthropicWireHasNoneAndEffortVariants() {
        val variants = ModelVariantRegistry.variants("anthropic", "deepseek-v4-flash").map { it.id }

        assertEquals(listOf("none", "low", "medium", "high", "max"), variants)
        assertEquals("none", ModelVariantRegistry.defaultVariant("anthropic", "deepseek-v4-flash"))
    }

    @Test
    fun deepSeekV4AnthropicVariantMapsToOutputConfigEffortNotBudgetTokens() {
        val body = anthropicRequest(
            apiKey = "key",
            baseUrl = "https://api.deepseek.com/anthropic",
            model = "deepseek-v4-flash",
            request = request(ModelRef("anthropic", "deepseek-v4-flash", "max")),
        ).bodyObject()
        val thinking = body["thinking"]?.jsonObject
        val outputConfig = body["output_config"]?.jsonObject

        assertNotNull(thinking)
        assertEquals("enabled", thinking?.get("type")?.jsonPrimitive?.contentOrNull)
        assertNull(thinking?.get("budget_tokens"))
        assertEquals("max", outputConfig?.get("effort")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun deepSeekV4AnthropicDefaultDisablesThinking() {
        val body = anthropicRequest(
            apiKey = "key",
            baseUrl = "https://api.deepseek.com/anthropic",
            model = "deepseek-v4-flash",
            request = request(ModelRef("anthropic", "deepseek-v4-flash")),
        ).bodyObject()
        val thinking = body["thinking"]?.jsonObject

        assertEquals("disabled", thinking?.get("type")?.jsonPrimitive?.contentOrNull)
        assertNull(body["output_config"])
        assertNull(thinking?.get("budget_tokens"))
    }

    @Test
    fun deepSeekNoneVariantDisablesThinkingOnAnthropicWire() {
        val body = anthropicRequest(
            apiKey = "key",
            baseUrl = "https://api.deepseek.com/anthropic",
            model = "deepseek-v4-flash",
            request = request(ModelRef("anthropic", "deepseek-v4-flash", "none")),
        ).bodyObject()
        val thinking = body["thinking"]?.jsonObject

        assertEquals("disabled", thinking?.get("type")?.jsonPrimitive?.contentOrNull)
        assertNull(body["output_config"])
        assertNull(thinking?.get("budget_tokens"))
    }
}

private fun request(modelRef: ModelRef): ModelRequest =
    ModelRequest(messages = listOf(ModelMessage("user", "hello")), modelRef = modelRef)

private fun cc.noharry.agent.transport.HttpRequest.bodyObject() =
    Json.parseToJsonElement(requireNotNull(body).toString(Charsets.UTF_8)).jsonObject
