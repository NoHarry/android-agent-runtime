package cc.noharry.agent.model

import cc.noharry.agent.kernel.api.model.ModelRef
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class DeepSeekOutputBudgetTest {
    @Test
    fun deepSeekAliasesSendOwnerLimitAcrossProtocolsVariantsAndStreamingModes() {
        for (model in listOf("deepseek-flash", "cline-pass/deepseek-v4.1-flash", "DeepSeek-V4-Pro")) {
            for (variant in listOf(null, "none", "low", "medium", "high", "max")) {
                for (stream in listOf(false, true)) {
                    val bodies = bodies(model, variant, stream)
                    assertEquals(393_216, bodies[0].getValue("max_tokens").jsonPrimitive.int)
                    assertEquals(393_216, bodies[1].getValue("max_tokens").jsonPrimitive.int)
                    assertEquals(393_216, bodies[2].getValue("max_output_tokens").jsonPrimitive.int)
                    assertFalse(bodies[2].containsKey("max_tokens"))
                    assertFalse(bodies[0].containsKey("max_output_tokens"))
                }
            }
        }
    }

    @Test
    fun aHostsLimitReplacesTheModelsOwnOnEveryOpenAiEndpoint() {
        val request = contractModelRequest()
        val chat = openAiRequest(CONTRACT_API_KEY, CONTRACT_BASE_URL, "deepseek-flash", request, maxOutputTokens = 8_000).contractBodyJson()
        val responses = openAiResponsesRequest(CONTRACT_API_KEY, CONTRACT_BASE_URL, "deepseek-flash", request, maxOutputTokens = 8_000).contractBodyJson()
        assertEquals(8_000, chat.getValue("max_tokens").jsonPrimitive.int)
        assertEquals(8_000, responses.getValue("max_output_tokens").jsonPrimitive.int)
        assertEquals(4_096, openAiRequest(CONTRACT_API_KEY, CONTRACT_BASE_URL, "gpt-5", request, maxOutputTokens = 4_096).contractBodyJson().getValue("max_tokens").jsonPrimitive.int)
        // OpenAI's own API takes max_completion_tokens, which its reasoning models need.
        val openAi = openAiRequest(CONTRACT_API_KEY, "https://api.openai.com/v1", "o3", request, maxOutputTokens = 4_096).contractBodyJson()
        assertEquals(4_096, openAi.getValue("max_completion_tokens").jsonPrimitive.int)
        assertFalse(openAi.containsKey("max_tokens"))
        assertFalse(openAiRequest(CONTRACT_API_KEY, "https://api.openai.com/v1", "o3", request).contractBodyJson().containsKey("max_completion_tokens"))
    }

    @Test
    fun theClientsPassTheirLimitOnAndRefuseANonPositiveOne() {
        assertThrows(IllegalArgumentException::class.java) { OpenAiModelClient({ "k" }, "gpt-5", maxOutputTokens = 0) }
        assertThrows(IllegalArgumentException::class.java) { OpenAiModelClient({ "k" }, "gpt-5", endpoint = "responses", maxOutputTokens = -1) }
    }

    @Test
    fun outputLimitUsesActualClientModelEvenWithoutAnExplicitModelRef() {
        val request = contractModelRequest().copy(modelRef = null)
        val body = anthropicRequest(CONTRACT_API_KEY, CONTRACT_BASE_URL, "deepseek-flash", request).contractBodyJson()
        assertEquals(393_216, body.getValue("max_tokens").jsonPrimitive.int)
    }

    @Test
    fun otherModelsGetTheDefaultWithAnOlderClaudesThinkingBudgetOnTop() {
        val budgeted = bodies("claude-sonnet-4-5", "max", stream = true)
        assertEquals(16_000 + 8_192, budgeted[0].getValue("max_tokens").jsonPrimitive.int)
        assertFalse(budgeted[1].containsKey("max_tokens"))
        assertFalse(budgeted[2].containsKey("max_output_tokens"))
        // An adaptive Claude has no budget: its thinking shares the limit.
        assertEquals(16_000, bodies("claude-sonnet-5-5", "max", stream = true)[0].getValue("max_tokens").jsonPrimitive.int)
        val defaults = bodies("generic-model", null, stream = false)
        assertEquals(16_000, defaults[0].getValue("max_tokens").jsonPrimitive.int)
        assertFalse(defaults[1].containsKey("max_tokens"))
        assertFalse(defaults[2].containsKey("max_output_tokens"))
    }

    @Test
    fun aHostSetsTheOutputLimitAndAThinkingBudgetStillGetsRoom() {
        fun maxTokens(model: String, variant: String?, limit: Int) = anthropicRequest(
            CONTRACT_API_KEY, CONTRACT_BASE_URL, model,
            contractModelRequest().copy(modelRef = ModelRef("anthropic", model, variant)), maxOutputTokens = limit,
        ).contractBodyJson().getValue("max_tokens").jsonPrimitive.int

        assertEquals(32_000, maxTokens("claude-sonnet-5-5", "max", 32_000))
        assertEquals(2_000, maxTokens("deepseek-flash", null, 2_000))
        // An older Claude's "max" thinks on 8192 tokens: a smaller limit would leave no answer.
        assertEquals(8_192 + 1_024, maxTokens("claude-sonnet-4-5", "max", 4_000))
        assertEquals(20_000, maxTokens("claude-sonnet-4-5", "max", 20_000))
    }

    private fun bodies(model: String, variant: String?, stream: Boolean): List<JsonObject> {
        val base = contractModelRequest()
        val anthropic = base.copy(modelRef = ModelRef("anthropic", model, variant))
        val openai = base.copy(modelRef = ModelRef("openai", model, variant))
        return listOf(
            anthropicRequest(CONTRACT_API_KEY, CONTRACT_BASE_URL, model, anthropic, stream).contractBodyJson(),
            openAiRequest(CONTRACT_API_KEY, CONTRACT_BASE_URL, model, openai, stream).contractBodyJson(),
            openAiResponsesRequest(CONTRACT_API_KEY, CONTRACT_BASE_URL, model, openai, stream).contractBodyJson(),
        )
    }
}
