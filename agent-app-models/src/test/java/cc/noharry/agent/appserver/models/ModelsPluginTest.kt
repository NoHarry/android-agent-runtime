package cc.noharry.agent.appserver.models

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.model.ProviderConfig
import cc.noharry.agent.appserver.model.ProviderProfile
import cc.noharry.agent.appserver.model.webLookup
import cc.noharry.agent.appserver.models.deepseek.DeepSeekAdapter
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.appserver.protocol.ServiceRequests
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import cc.noharry.agent.appserver.server.AppServerConfig
import cc.noharry.agent.appserver.server.AppServerPlugin
import cc.noharry.agent.appserver.server.AppServerTesting
import cc.noharry.agent.appserver.server.PluginContext
import cc.noharry.agent.appserver.server.PluginDiscovery
import cc.noharry.agent.appserver.server.PluginHost
import cc.noharry.agent.appserver.server.RouteTable
import cc.noharry.agent.appserver.server.coreModules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The module's plugin is in every app that depends on the module, from its manifest, without the host listing it; the
 * host leaves it out by its id, and a plugin it lists with that id is loaded as listed. Running, it brings an adapter per
 * service and DeepSeek's extras; turned off, the server knows no service again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ModelsPluginTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @After
    fun reset() = AppServerTesting.reset()

    @Test
    fun theManifestBringsThePluginUnlessTheHostLeavesItOutOrListsItsOwn() {
        assertEquals(listOf(ModelsPlugin), PluginDiscovery.find(app, AppServerConfig(name = "test")))
        assertEquals(emptyList<AppServerPlugin>(), PluginDiscovery.find(app, AppServerConfig(name = "test", ignoredPlugins = setOf("models"))))
        val own = object : AppServerPlugin {
            override val id = "models"
            override fun apply(ctx: PluginContext) = Unit
        }
        assertEquals(emptyList<AppServerPlugin>(), PluginDiscovery.find(app, AppServerConfig(name = "test", plugins = listOf(own))))
    }

    @Test
    fun aDataFileMissingFromTheAppSaysSoRatherThanGivingNoServices() {
        val empty = object : ClassLoader(null) {}
        try {
            ServiceCatalog.load(empty)
            org.junit.Assert.fail("a missing data file gave services")
        } catch (missing: IllegalStateException) {
            assertTrue(missing.message.orEmpty(), missing.message.orEmpty().contains(ServiceCatalog.RESOURCE) && missing.message.orEmpty().contains("cannot start"))
        }
    }

    @Test
    fun runningItBringsEveryServiceAndTurnedOffTheServerKnowsNone() = runBlocking {
        val plugins = PluginHost(app, listOf(ModelsPlugin), main = Dispatchers.Unconfined)
        plugins.startAll()
        AppServerTesting.running(plugins.additions)
        assertEquals(ServiceCatalog.services.map { it.id }, services())
        assertSame(DeepSeekAdapter, plugins.additions.providerAdapters.first())

        val deepSeek = ProviderProfile("ds", "DeepSeek", ModelProvider.ANTHROPIC, "k", "deepseek-flash", "https://api.deepseek.com/anthropic", ProviderEndpoint.MESSAGES)
        val config = ProviderConfig.load(app).let { it.copy(profiles = it.profiles + deepSeek) }
        assertNotNull(config.webLookup(app, deepSeek))
        assertEquals("deepseek", config.editState(deepSeek).service)

        plugins.setEnabled("models", false)
        AppServerTesting.running(plugins.additions)
        assertTrue(plugins.additions.providerAdapters.isEmpty())
        assertEquals(emptyList<String>(), services())
        assertNull(config.webLookup(app, deepSeek))
        assertEquals("", config.editState(deepSeek).service)

        plugins.setEnabled("models", true)
        AppServerTesting.running(plugins.additions)
        assertEquals(ServiceCatalog.services.size, services().size)
    }

    private suspend fun services(): List<String> = call(ServiceRequests.LIST, Empty).services.map { it.id }

    private suspend fun <P, R> call(method: RequestMethod<P, R>, params: P): R {
        var answer: (suspend (P) -> R)? = null
        val table = object : RouteTable {
            @Suppress("UNCHECKED_CAST")
            override fun <A, B> on(route: RequestMethod<A, B>, block: suspend (A) -> B) {
                if (route.name == method.name) answer = block as suspend (P) -> R
            }
        }
        coreModules(app).forEach { module -> with(module) { table.install() } }
        return checkNotNull(answer)(params)
    }
}
