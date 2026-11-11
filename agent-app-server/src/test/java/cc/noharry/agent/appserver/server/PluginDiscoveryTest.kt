package cc.noharry.agent.appserver.server

import android.app.Application
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A library's manifest names its plugins by class (`<meta-data android:name="<class>" android:value="cc.noharry.agent.appserver.plugin"/>`):
 * a Kotlin object and a class made with no arguments are loaded; an entry of another value, a class that is not a plugin
 * or that cannot be found is left out; an id twice is loaded once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PluginDiscoveryTest {
    @Test
    fun theManifestsPluginEntriesAreLoadedAndAnythingElseIsLeftOut() {
        val metaData = Bundle().apply {
            putString(ObjectPlugin::class.java.name, PluginDiscovery.MANIFEST_VALUE)
            putString(ClassPlugin::class.java.name, PluginDiscovery.MANIFEST_VALUE)
            putString(SameIdPlugin::class.java.name, PluginDiscovery.MANIFEST_VALUE)
            putString(NotAPlugin::class.java.name, PluginDiscovery.MANIFEST_VALUE)
            putString("com.example.Missing", PluginDiscovery.MANIFEST_VALUE)
            putString(OtherEntry::class.java.name, "androidx.startup")
            putInt("com.example.number", 3)
        }

        val found = PluginDiscovery.declared(metaData, javaClass.classLoader!!)

        assertEquals(listOf("class-plugin", "object-plugin"), found.map { it.id }.sorted())
        assertEquals(emptyList<AppServerPlugin>(), PluginDiscovery.declared(null, javaClass.classLoader!!))
    }

    object ObjectPlugin : AppServerPlugin {
        override val id = "object-plugin"
        override fun apply(ctx: PluginContext) = Unit
    }

    class ClassPlugin : AppServerPlugin {
        override val id = "class-plugin"
        override fun apply(ctx: PluginContext) = Unit
    }

    /** Sorted after [ClassPlugin] by name, with its id: left out. */
    class SameIdPlugin : AppServerPlugin {
        override val id = "class-plugin"
        override fun apply(ctx: PluginContext) = Unit
    }

    class NotAPlugin

    object OtherEntry
}
