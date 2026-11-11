package cc.noharry.agent.appserver.console
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.protocol.ProviderEditState
import cc.noharry.agent.appserver.protocol.variantChoices
import cc.noharry.agent.model.ModelVariantRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

/** The composer's 思考强度 menu: DeepSeek shows none chosen until another is picked; other models keep Default. */
class VariantChoicesTest {
    /** As the server sends it: the model's own default comes from the registry ([appConfigEditor]). */
    private fun editor(provider: ModelProvider, model: String, variant: String, options: List<String>) =
        ProviderEditState(
            model,
            variant,
            "https://example.test",
            provider = provider,
            variantOptions = options,
            defaultVariant = ModelVariantRegistry.defaultVariant(provider.wireName, model),
        )

    @Test
    fun deepSeekShowsNoneChosenWithoutASeparateDefault() {
        val options = listOf("none", "low", "medium", "high", "max")

        assertEquals(options to "none", editor(ModelProvider.ANTHROPIC, "deepseek-flash", "", options).variantChoices())
        assertEquals(options to "none", editor(ModelProvider.OPENAI, "deepseek-v4-pro", "", options).variantChoices())
        assertEquals(options to "high", editor(ModelProvider.ANTHROPIC, "deepseek-flash", "high", options).variantChoices())
    }

    @Test
    fun aModelWithoutADefaultOfItsOwnKeepsDefault() {
        val claude = listOf("high", "max")

        assertEquals(listOf("default", "high", "max") to "default", editor(ModelProvider.ANTHROPIC, "claude-sonnet-5", "", claude).variantChoices())
        assertEquals(listOf("default", "high", "max") to "max", editor(ModelProvider.ANTHROPIC, "claude-sonnet-5", "max", claude).variantChoices())
    }
}
