package cc.noharry.agent.appserver.model
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.protocol.ProviderEditState
import cc.noharry.agent.appserver.protocol.parseContextWindow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ContextWindowConfigTest {
    @Test fun aModelNothingIsKnownOfHasTheConservativeWindowAndTheHostsFactsWin() {
        assertEquals(128_000L, defaultContextWindow("https://api.example.com/v1", "example-large"))
        cc.noharry.agent.appserver.runtime.AppHost.install(modelFacts = mapOf("example-large" to cc.noharry.agent.appserver.protocol.ModelFacts(contextWindow = 1_000_000)))
        try {
            assertEquals(1_000_000L, defaultContextWindow("https://api.example.com/v1", "example-large"))
            // Behind a relay's prefix, the same model.
            assertEquals(1_000_000L, defaultContextWindow("https://relay.example.com/v1", "relay/example-large"))
            assertEquals(128_000L, defaultContextWindow("https://api.example.com/v1", "example-small"))
        } finally {
            cc.noharry.agent.appserver.runtime.AppHost.install(modelFacts = emptyMap())
        }
    }

    @Test fun aModelTheHostRunsItselfHasTheWindowItsServerStartsWith() {
        // As an on-device server started with -c 8192 for one of its models.
        val ownModels = object : cc.noharry.agent.appserver.runtime.HostModels {
            override fun contextTokens(model: String): Long? = 8_192L.takeIf { model == "local-small" }
        }
        cc.noharry.agent.appserver.runtime.AppHost.install(models = ownModels)
        try {
            assertEquals(8_192L, defaultContextWindow("http://127.0.0.1:8080/v1", "local-small"))
            assertEquals(128_000L, defaultContextWindow("http://127.0.0.1:8080/v1", "local-other"))
        } finally {
            cc.noharry.agent.appserver.runtime.AppHost.install(models = cc.noharry.agent.appserver.runtime.HostModels.NONE)
        }
    }

    @Test fun windowOverridePersistsPerProfileModelAndEndpoint() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("app_server_context_windows", Context.MODE_PRIVATE).edit().clear().commit()
        val profile = ProviderEditState("custom", "", "https://example.test", profileId = "test")
        assertEquals(128_000L, loadContextWindow(context, profile))
        saveContextWindow(context, profile, 475_000)
        assertEquals(475_000L, loadContextWindow(context, profile))
        assertEquals(128_000L, loadContextWindow(context, profile.copy(model = "another")))
        assertEquals(128_000L, loadContextWindow(context, profile.copy(profileId = "other")))
        assertEquals(128_000L, loadContextWindow(context, profile.copy(baseUrl = "https://other.test")))
        assertThrows(IllegalArgumentException::class.java) { saveContextWindow(context, profile, 0) }
        assertEquals(475_000L, loadContextWindow(context, profile))
    }
    @Test fun emptyOverrideRestoresDefaultAndCapacityParserRejectsInvalidValues() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val profile = ProviderEditState("example-large", "", "https://reset.test", profileId = "reset")
        saveContextWindow(context, profile, 64_000)
        saveContextWindow(context, profile, null)
        assertEquals(128_000L, loadContextWindow(context, profile))
        assertNull(contextWindowOverride(context, profile))
        assertEquals(128_000L, parseContextWindow("128K"))
        assertEquals(1_500_000L, parseContextWindow("1.5m"))
        listOf("", "0", "-1", "abc", "0.0001K", "999999999999999999999M").forEach { assertNull(parseContextWindow(it)) }
    }

}
