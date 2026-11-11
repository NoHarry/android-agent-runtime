package cc.noharry.agent.appserver.models

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.model.ErrorRule
import cc.noharry.agent.appserver.model.ModelFailure
import cc.noharry.agent.appserver.model.ModelFailureKind
import cc.noharry.agent.appserver.model.ModelService
import cc.noharry.agent.appserver.model.ProviderAdapter
import cc.noharry.agent.appserver.model.ServiceEndpoint
import cc.noharry.agent.appserver.model.ServiceModel
import cc.noharry.agent.appserver.model.modelFacts
import cc.noharry.agent.appserver.model.modelPrice
import cc.noharry.agent.appserver.protocol.ModelFacts
import cc.noharry.agent.appserver.protocol.ModelPrice
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.appserver.server.AppServerPlugin
import cc.noharry.agent.appserver.server.AppServerTesting
import cc.noharry.agent.appserver.server.PluginContext
import cc.noharry.agent.appserver.server.PluginHost
import cc.noharry.agent.spi.reliability.RuntimeErrorCode
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The host guide's own adapter ("Model services"), as the guide quotes it: a plugin of it makes the server know the service. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class OwnAdapterExampleTest {
    @After
    fun reset() = AppServerTesting.reset()

    @Test
    fun theGuidesAdapterMakesTheServerKnowItsServiceItsModelAndItsFailures() {
        val plugins = PluginHost(ApplicationProvider.getApplicationContext(), listOf(ExamplePlugin), main = Dispatchers.Unconfined)
        plugins.startAll()
        AppServerTesting.running(plugins.additions)

        assertEquals(256_000L, modelFacts("https://api.example.com/v1", "example-chat").contextWindow)
        assertEquals(0.5, modelPrice("example-chat")!!.input, 0.0)
        val dry = ModelFailure(403, """{"error":{"code":"credit_exhausted"}}""", RuntimeErrorCode.PROVIDER_AUTH_FAILED)
        assertEquals(ModelFailureKind.QUOTA, ExampleAdapter.classify(ExampleAdapter.services.single(), dry))
    }
}

// guide:start own-adapter
/** A service the server should know: where it is, its model with its facts and price, how it says a key ran dry. */
object ExampleAdapter : ProviderAdapter {
    override val services = listOf(
        ModelService(
            id = "example",
            label = "Example AI",
            endpoints = listOf(ServiceEndpoint(ProviderEndpoint.CHAT_COMPLETIONS, "https://api.example.com/v1")),
            keyHint = "Create a key at https://example.com/keys",
            models = listOf(
                ServiceModel(
                    id = "example-chat",
                    facts = ModelFacts(contextWindow = 256_000, maxOutputTokens = 32_000, imageInput = true, toolCalls = true),
                    price = ModelPrice(input = 0.5, cacheRead = 0.05, output = 2.0, source = "example", asOf = "2026-11-11"),
                ),
            ),
            errors = listOf(ErrorRule(ModelFailureKind.QUOTA, status = 403, pattern = "credit_exhausted")),
        ),
    )
}

/** Its plugin, which a person can turn off and on like any other. */
object ExamplePlugin : AppServerPlugin {
    override val id = "example-service"

    override fun apply(ctx: PluginContext) = ctx.providerAdapter(ExampleAdapter)
}
// guide:end own-adapter
