package cc.noharry.agent.appserver.models

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.appserver.runtime.ProviderPresets
import cc.noharry.agent.appserver.server.AppServerConfig
import cc.noharry.agent.appserver.server.AppServerTesting
import cc.noharry.agent.appserver.server.PluginAdditions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A profile named after a service with nothing else takes the service's default endpoint and its default model, the
 * general model that calls tools there (not the first of its list); what a host gave stays; and a `deepseek` profile
 * with no base URL takes DeepSeek's host first, so it shares the key of a profile there. The keys are stand-ins.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ServiceDefaultsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun reset() = AppServerTesting.reset()

    @Test
    fun aProfileWithOnlyAServicesIdTakesItsDefaultEndpointAndDefaultModel() {
        val profiles = load(
            """{"profiles":[{"id":"openai","provider":"openai","apiKey":""},{"id":"anthropic","provider":"anthropic","apiKey":""},
            {"id":"gemini","provider":"openai","apiKey":""}]}""",
        )
        val openAi = profiles.getValue("openai")
        assertEquals(ProviderEndpoint.RESPONSES, openAi.endpoint)
        assertEquals("https://api.openai.com/v1", openAi.baseUrl)
        assertEquals("gpt-6.1-sol", openAi.model)
        assertEquals("openai-responses-tool", openAi.searchCapability)
        assertEquals("claude-sonnet-5-5", profiles.getValue("anthropic").model)
        assertEquals("gemini-3.8-flash", profiles.getValue("gemini").model)
    }

    @Test
    fun whatTheHostGaveStays() {
        val profiles = load(
            """{"profiles":[{"id":"openai","provider":"openai","endpoint":"chat_completions","apiKey":""},
            {"id":"anthropic","provider":"anthropic","model":"claude-opus-5-5","apiKey":""}]}""",
        )
        val openAi = profiles.getValue("openai")
        assertEquals(ProviderEndpoint.CHAT_COMPLETIONS, openAi.endpoint)
        // Its default model calls tools only through Responses: on Chat Completions none is chosen for the person.
        assertEquals("", openAi.model)
        assertEquals("claude-opus-5-5", profiles.getValue("anthropic").model)
    }

    @Test
    fun aDeepSeekProfileWithNoBaseUrlSharesTheKeyOfOneOnDeepSeeksHost() {
        val profiles = load(
            """{"profiles":[{"id":"deepseek","provider":"openai","apiKey":""},
            {"id":"deepseek-anthropic","provider":"anthropic","baseUrl":"https://api.deepseek.com/anthropic","model":"deepseek-flash","apiKey":""}]}""",
            "AGENT_RUNTIME_PROVIDER_PROFILE_DEEPSEEK_ANTHROPIC_API_KEY=sk-deepseek-stand-in",
        )
        val deepSeek = profiles.getValue("deepseek")
        assertEquals("https://api.deepseek.com", deepSeek.baseUrl)
        assertEquals("deepseek-flash", deepSeek.model)
        assertEquals("sk-deepseek-stand-in", deepSeek.apiKey)
    }

    @Test
    fun everyServicesDefaultModelCallsToolsAndSaysWhy() {
        val data = ServiceCatalog.load()
        ServiceCatalog.read(data).forEach { service ->
            val default = service.defaultModel ?: return@forEach
            assertEquals(service.id, true, service.model(default)?.facts?.toolCalls)
            val why = data.services.single { it.id == service.id }.defaultModelReason
            assertTrue(service.id, why.contains("https://"))
        }
        assertEquals(listOf("openrouter"), ServiceCatalog.services.filter { it.defaultModel == null }.map { it.id })
    }

    private fun load(profiles: String, local: String = "") =
        run {
            context.getSharedPreferences("app_server_provider_config", Context.MODE_PRIVATE).edit().clear().commit()
            AppServerTesting.install(AppServerConfig(name = "test", presets = ProviderPresets(configText = profiles, localOverridesText = local)))
            AppServerTesting.running(PluginAdditions(providerAdapters = ModelsPlugin.adapters()))
            ProviderConfig.load(context).profiles.associateBy { it.id }
        }
}
