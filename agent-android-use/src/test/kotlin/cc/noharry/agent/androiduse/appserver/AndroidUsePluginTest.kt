package cc.noharry.agent.androiduse.appserver

import android.app.Application
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.protocol.AndroidUseState
import cc.noharry.agent.appserver.protocol.CapabilityRequests
import cc.noharry.agent.appserver.protocol.ServerFeatures
import cc.noharry.agent.appserver.runtime.RunFacts
import cc.noharry.agent.appserver.server.AppServerConfig
import cc.noharry.agent.appserver.server.AppServerPlugin
import cc.noharry.agent.appserver.server.AppServerTesting
import cc.noharry.agent.appserver.server.ForegroundApps
import cc.noharry.agent.appserver.server.PluginDiscovery
import cc.noharry.agent.appserver.server.PluginHost
import cc.noharry.agent.appserver.server.PluginSwitches
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Operating the screen on the app server comes only with its plugin, which the module's manifest brings to an app that
 * has the server: the service, the switch's request, its tools, the app in front.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AndroidUsePluginTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @After
    fun bare() = AppServerTesting.reset()

    @Test
    fun theManifestBringsThePluginUnlessTheHostLeavesItOut() {
        assertEquals(listOf<AppServerPlugin>(AndroidUsePlugin), PluginDiscovery.find(app, AppServerConfig(name = "test")))
        assertEquals(emptyList<AppServerPlugin>(), PluginDiscovery.find(app, AppServerConfig(name = "test", ignoredPlugins = setOf("android-use"))))
    }

    @Test
    fun withoutThePluginTheAccessibilityServiceIsNeverListed() {
        assertFalse(applyAndroidUseSwitch(app, enabled = true))
        assertEquals(null, enabledServices())
        assertEquals(AndroidUseState.OFF, androidUseState(app))
    }

    @Test
    fun runningItListsTheServiceAndBringsWhatItAdds() {
        val host = PluginHost(app, listOf(AndroidUsePlugin), AllOn)
        host.startAll()
        val additions = host.additions
        assertTrue(enabledServices().orEmpty().contains("AndroidUseAccessibilityService"))
        assertTrue(ServerFeatures.ANDROID_USE in additions.offers)
        assertTrue(CapabilityRequests.SET_ANDROID_USE.name in additions.routes)
        assertNotNull(additions.service(ForegroundApps::class.java))
        // Nothing binds the service under Robolectric, so no run gets tools that cannot work.
        assertEquals(1, additions.runTools.size)
        assertTrue(additions.runTools.single()(facts()).isEmpty())
        assertEquals(AndroidUseState.WAITING, androidUseState(app))
        // Turned off, it takes all of it back.
        host.setEnabled(AndroidUsePlugin.id, false)
        assertFalse(AndroidUsePlugin.running)
        assertTrue(host.additions.runTools.isEmpty() && host.additions.routes.isEmpty())
        assertEquals(AndroidUseState.OFF, androidUseState(app))
    }

    private fun enabledServices(): String? = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)

    private fun facts() = RunFacts(app as Context, "", child = false, webSearch = false, subagentTypes = emptySet())

    private object AllOn : PluginSwitches {
        private val off = mutableSetOf<String>()

        override fun isOn(id: String): Boolean = id !in off

        override fun set(id: String, on: Boolean) {
            if (on) off -= id else off += id
        }
    }
}
