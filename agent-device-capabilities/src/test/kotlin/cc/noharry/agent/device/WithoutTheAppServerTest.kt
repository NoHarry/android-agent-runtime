package cc.noharry.agent.device

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The module as a host with the runtime alone has it. `testWithoutAppServer` runs this and every other test of the
 * module but its app server plugin's on a classpath without the app server, where the plugin's class cannot even load:
 * that they pass there shows that nothing else in the module loads it. In the usual run, with the server, it loads.
 */
class WithoutTheAppServerTest {
    @Test
    fun thePluginLoadsOnlyWhereTheAppServerIs() {
        val loader = javaClass.classLoader
        if (System.getProperty(WITHOUT_APP_SERVER) == "true") {
            assertThrows(ClassNotFoundException::class.java) { Class.forName(PLUGIN_API, false, loader) }
            assertThrows(NoClassDefFoundError::class.java) { Class.forName(PLUGIN, false, loader) }
        } else {
            assertTrue(Class.forName(PLUGIN_API, false, loader).isAssignableFrom(Class.forName(PLUGIN, false, loader)))
        }
    }

    private companion object {
        /** Set by testWithoutAppServer. */
        const val WITHOUT_APP_SERVER = "agent.withoutAppServer"
        const val PLUGIN_API = "cc.noharry.agent.appserver.server.AppServerPlugin"
        /** The plugin for the device's tools on the app server. */
        const val PLUGIN = "cc.noharry.agent.device.appserver.DeviceToolsPlugin"
    }
}
