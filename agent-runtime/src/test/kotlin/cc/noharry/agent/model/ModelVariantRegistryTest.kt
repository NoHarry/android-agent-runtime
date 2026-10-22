package cc.noharry.agent.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.model.ModelRef
import cc.noharry.agent.kernel.api.model.ModelMessage

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
