package cc.noharry.agent.model

import cc.noharry.agent.kernel.api.model.ModelRef
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun outputLimitUsesActualClientModelEvenWithoutAnExplicitModelRef() {
        val request = contractModelRequest().copy(modelRef = null)
        val body = anthropicRequest(CONTRACT_API_KEY, CONTRACT_BASE_URL, "deepseek-flash", request).contractBodyJson()
        assertEquals(393_216, body.getValue("max_tokens").jsonPrimitive.int)
    }

    @Test
    fun otherModelsRetainTheirExistingOutputAndThinkingBudgets() {
        val bodies = bodies("claude-sonnet-test", "high", stream = true)
        assertEquals(5_120, bodies[0].getValue("max_tokens").jsonPrimitive.int)
        assertFalse(bodies[1].containsKey("max_tokens"))
        assertFalse(bodies[2].containsKey("max_output_tokens"))
        val defaults = bodies("generic-model", null, stream = false)
        assertEquals(1_024, defaults[0].getValue("max_tokens").jsonPrimitive.int)
        assertFalse(defaults[1].containsKey("max_tokens"))
        assertFalse(defaults[2].containsKey("max_output_tokens"))
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
