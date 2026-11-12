package cc.noharry.agent.androiduse.appserver

import android.content.Context
import cc.noharry.agent.androiduse.AndroidUseAccessibility
import cc.noharry.agent.androiduse.AndroidUseOptions
import cc.noharry.agent.androiduse.AndroidUseToolProvider
import cc.noharry.agent.appserver.protocol.AndroidUseView
import cc.noharry.agent.appserver.protocol.CapabilityRequests
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.ExperimentalAppServerApi
import cc.noharry.agent.appserver.protocol.ServerFeatures
import cc.noharry.agent.appserver.runtime.RunFacts
import cc.noharry.agent.appserver.server.AppServerPlugin
import cc.noharry.agent.appserver.server.Assistant
import cc.noharry.agent.appserver.server.ForegroundApp
import cc.noharry.agent.appserver.server.ForegroundApps
import cc.noharry.agent.appserver.server.PluginContext
import cc.noharry.agent.appserver.server.PreferencesSource
import cc.noharry.agent.appserver.server.RouteModule
import cc.noharry.agent.spi.ToolProvider
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * Operating the screen on the app server (id `android-use`): the model taps, swipes and types through this module's
 * accessibility service ([AndroidUseToolProvider]), shown on the assistant on screen, while the person's switch (on at
 * first) is on and the service is bound. An app that depends on both this module and `agent-app-server` gets it
 * without listing it: this module's manifest declares it, and the server loads it from there
 * ([cc.noharry.agent.appserver.server.PluginDiscovery]); `AppServerConfig.ignoredPlugins` leaves it out. Nothing else
 * in this module names it, so an app without the server never loads it.
 *
 * While it runs it offers [ServerFeatures.ANDROID_USE] and the switch's request ([androidUseRoutes]), publishes the
 * switch and the service's state, names the app in front for each run ([ForegroundApps]), and lists the service with
 * the system as the switch says where the app may write secure settings (a platform-signed app, or one granted
 * `WRITE_SECURE_SETTINGS`), repairing one the system lists but never binds ([repairAndroidUse]). Elsewhere the person
 * turns the service on in the system's accessibility settings ([AndroidUseAccessibility.settingsIntent]).
 */
@ExperimentalAppServerApi
object AndroidUsePlugin : AppServerPlugin {
    override val id: String = "android-use"

    /** Whether it runs: only then is the service listed by the server and are its tools mounted. */
    @Volatile
    internal var running: Boolean = false
        private set

    override fun apply(ctx: PluginContext) {
        val app = ctx.app
        running = true
        ctx.onStop { running = false }
        ctx.offer(ServerFeatures.ANDROID_USE)
        ctx.provide(ForegroundApps::class.java, ForegroundApps(::foreground))
        ctx.runTools { facts -> screenTools(facts, ctx.assistant) }
        // Its tools work the device a subagent only looks at by default, and using them is not using a service.
        ctx.ownToolServer(ANDROID_USE_TOOL_SERVER, service = false, worksDevice = true)
        ctx.routes(androidUseRoutes(app))
        ctx.publish {
            follow(PreferencesSource.changes.onStart { emit(Unit) }.map { androidUseView(app) }) { view ->
                copy(preferences = preferences.copy(androidUse = view))
            }
            // Turned off, the switch leaves the settings with it.
            ctx.onStop { update { copy(preferences = preferences.copy(androidUse = AndroidUseView())) } }
        }
        applyAndroidUseSwitch(app)
        scheduleAndroidUseRepair(app)
    }
}

/** The switch's request ([CapabilityRequests.SET_ANDROID_USE]), which the plugin answers while it runs. */
@ExperimentalAppServerApi
fun androidUseRoutes(app: Context): RouteModule = RouteModule {
    on(CapabilityRequests.SET_ANDROID_USE) { params ->
        saveAndroidUseEnabled(app, params.on)
        PreferencesSource.changed()
        Empty
    }
}

/**
 * The tools for a run: only while the switch is on and the service is bound, so the model never sees tools that
 * cannot work. A model that reads no pictures gets the screen's elements without screenshots.
 */
private fun screenTools(facts: RunFacts, assistant: Assistant): List<ToolProvider> =
    if (!androidUseReady(facts.context)) {
        emptyList()
    } else {
        listOf(AndroidUseToolProvider(facts.context, AppServerAndroidUsePresenter(assistant), AndroidUseOptions(screenshots = facts.imageInput)))
    }

/**
 * The id of the tool server operating the screen gives each run (`mcp__android_use__android_action`): a host's skill that
 * describes it names it ([cc.noharry.agent.appserver.skills.RuntimeSkill.requiresToolServer]).
 */
@ExperimentalAppServerApi
const val ANDROID_USE_TOOL_SERVER = "android_use"

private fun foreground(context: Context): ForegroundApp? =
    AndroidUseAccessibility.foregroundApp(context)?.let { ForegroundApp(it.label, it.packageName) }

private fun androidUseView(context: Context): AndroidUseView = AndroidUseView(loadAndroidUseEnabled(context), androidUseState(context))
