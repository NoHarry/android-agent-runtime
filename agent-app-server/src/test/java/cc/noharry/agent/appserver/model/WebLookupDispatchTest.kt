@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.model

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.protocol.ModelProvider
import cc.noharry.agent.appserver.protocol.ProviderEndpoint
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.server.PluginAdditions
import cc.noharry.agent.appserver.skills.SubagentSettings
import cc.noharry.agent.appserver.skills.loadSubagentSettings
import cc.noharry.agent.appserver.skills.saveSubagentSettings
import cc.noharry.agent.runtime.WebFallbackPage
import cc.noharry.agent.runtime.WebSearchResult
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A model service's extras beyond chat come only through an adapter a running plugin installs: its search when a
 * profile is set to it (or a subagent is told to), its page fetch while that switch is on, whether lookups are at hand
 * at all, and its own history updates. Here with an adapter of the test's own, keyed by one profile.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class WebLookupDispatchTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val keyed = profile("svc", "https://api.service.example/v1")
    private val other = profile("gw", "https://gateway.example/v1")
    private val config = ProviderConfig.load(context).let { it.copy(profiles = it.profiles + keyed + other) }

    @After
    fun noAdapters() = AppHost.install(additions = PluginAdditions.NONE)

    @Test
    fun withoutAnAdapterAProfileHasChatAlone() {
        SearchBackend.set(context, keyed.id, true)
        assertNull(appWebSearch(context, config, keyed, forceApi = true))
        assertNull(appWebFetchFallback(context, config, keyed))
        assertFalse(config.hasWebLookup(context))
        assertFalse(config.nativeUpdates(context, keyed))
        SearchBackend.set(context, keyed.id, false)
    }

    @Test
    fun aProfileSearchesThroughTheServiceOnlyWhenSetToOrWhenASubagentIsTold() {
        install()
        assertNull(appWebSearch(context, config, keyed))
        SearchBackend.set(context, keyed.id, true)
        assertSame(Lookup, appWebSearch(context, config, keyed))
        SearchBackend.set(context, keyed.id, false)
        assertNull(appWebSearch(context, config, keyed))
        // A subagent searches through it whatever the main agent uses, as its settings say.
        assertFalse(loadSubagentSettings(context).searchApi)
        saveSubagentSettings(context, SubagentSettings(searchApi = true))
        assertTrue(loadSubagentSettings(context).searchApi)
        assertSame(Lookup, appWebSearch(context, config, keyed, forceApi = true))
        saveSubagentSettings(context, SubagentSettings())
    }

    @Test
    fun theFetchFallbackIsItsOwnSwitchOnByDefaultAndAnyProfilesKeyServesAnother() {
        install()
        // The gateway keeps its own search and gets pages through the keyed profile's service.
        assertNull(appWebSearch(context, config, other))
        assertSame(Lookup, appWebFetchFallback(context, config, other))
        assertTrue(config.hasWebLookup(context))
        val unkeyed = config.copy(profiles = config.profiles - keyed)
        assertNull(appWebFetchFallback(context, unkeyed, other))
        assertFalse(unkeyed.hasWebLookup(context))
        FetchFallback.set(context, false)
        assertNull(appWebFetchFallback(context, config, keyed))
        FetchFallback.set(context, true)
    }

    @Test
    fun aServicesOwnHistoryUpdatesAreItsAdaptersToSay() {
        install()
        assertTrue(config.nativeUpdates(context, keyed))
        assertFalse(config.nativeUpdates(context, other))
    }

    private fun install() = AppHost.install(additions = PluginAdditions(providerAdapters = listOf(Adapter)))

    private fun profile(id: String, baseUrl: String) =
        ProviderProfile(id, id, ModelProvider.ANTHROPIC, "k", "chat-model", baseUrl, ProviderEndpoint.MESSAGES)

    /** A service whose API is reached with the key of its own profile ("svc"). */
    private object Adapter : ProviderAdapter {
        override fun lookup(context: Context, config: ProviderConfig, profile: ProviderProfile): WebLookup? =
            Lookup.takeIf { profile.id == "svc" }

        override fun nativeUpdates(context: Context, config: ProviderConfig, profile: ProviderProfile): Boolean = profile.id == "svc"
    }

    private object Lookup : WebLookup {
        override val label: String = "api.service.example"

        override suspend fun search(query: String, count: Int): List<WebSearchResult> = emptyList()

        override suspend fun fetch(url: String): WebFallbackPage? = null
    }
}
