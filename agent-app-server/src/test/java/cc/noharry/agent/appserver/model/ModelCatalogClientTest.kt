package cc.noharry.agent.appserver.model
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelCatalogClientTest {
    @Test
    fun parsesOpenAiCompatibleModels() {
        val models = parseModelIds(
            """
                {
                  "object": "list",
                  "data": [
                    {"id": "deepseek-v4-pro", "object": "model"},
                    {"id": "deepseek-chat", "object": "model"},
                    {"id": "deepseek-v4-pro", "object": "model"}
                  ]
                }
            """.trimIndent(),
        )

        assertEquals(listOf("deepseek-chat", "deepseek-v4-pro"), models)
    }

    @Test
    fun parsesAnthropicModels() {
        val models = parseModelIds(
            """
                {
                  "data": [
                    {"id": "claude-3-5-haiku-latest", "display_name": "Claude Haiku"},
                    {"id": "claude-sonnet-4-20250514", "display_name": "Claude Sonnet"}
                  ],
                  "has_more": false
                }
            """.trimIndent(),
        )

        assertEquals(listOf("claude-3-5-haiku-latest", "claude-sonnet-4-20250514"), models)
    }
}
