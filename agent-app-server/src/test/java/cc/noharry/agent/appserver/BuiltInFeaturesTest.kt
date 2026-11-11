package cc.noharry.agent.appserver

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import cc.noharry.agent.appserver.protocol.MemoryRequests
import cc.noharry.agent.appserver.protocol.ServerFeatures
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.BuiltInFeatures
import cc.noharry.agent.appserver.server.LessonRoutes
import cc.noharry.agent.appserver.server.RouteMap
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The server's own features stay off until the host turns them on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class BuiltInFeaturesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun none() = AppHost.install(builtIns = BuiltInFeatures.NONE)

    @Test
    fun theBootReceiverFollowsTheRunningPlugins() {
        val component = ComponentName(context, BootReceiver::class.java)
        BootReceiver.follow(context, wanted = true)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, context.packageManager.getComponentEnabledSetting(component))
        BootReceiver.follow(context, wanted = false)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, context.packageManager.getComponentEnabledSetting(component))
    }

    @Test
    fun theServerItselfAnswersOnlyTheRuntimesLessonsAndOffersNoMemory() {
        // What the model remembers about the person is a plugin's (Bothy's agent-app-memory), its requests included.
        val methods = RouteMap().apply { with(LessonRoutes(context)) { install() } }.entries.map { it.key }.toSet()
        assertEquals(setOf(MemoryRequests.LESSONS.name), methods)
        assertFalse(ServerFeatures.MEMORY in BuiltInFeatures(fileWriting = true, web = true, scripts = true, subagents = true).offered())
        assertFalse(File(context.filesDir, "user-memory").exists())
    }
}
