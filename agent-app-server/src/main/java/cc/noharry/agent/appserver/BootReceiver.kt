package cc.noharry.agent.appserver

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import cc.noharry.agent.appserver.runtime.AppHost

/**
 * Starts the app's process after a reboot, so the host's work resumes even if nobody opens the app, and after an
 * update: alarms set before it may name a receiver the update moved, and starting sets them anew. Tells the plugins that
 * asked when the clock or the zone changes (a plan's due times move with it). Off in the server's manifest; the server
 * turns it on only while a running plugin asks ([cc.noharry.agent.appserver.server.PluginContext.startOnBoot]), so no
 * app starts at boot just because it carries the server. A host that wants it may also turn it on in its own manifest,
 * so the update that first brings it starts the app too.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Boot itself starts the app, whose plugins check what they keep as they start.
        val clock = intent.action == Intent.ACTION_TIME_CHANGED || intent.action == Intent.ACTION_TIMEZONE_CHANGED
        if (clock) AppHost.additions.clockChanged.forEach { changed -> runCatching(changed).onFailure { Log.w(TAG, "a plugin failed on a clock change", it) } }
    }

    internal companion object {
        /** Turns the receiver on or off to match the running plugins; the system keeps the setting across restarts. */
        fun follow(context: Context, wanted: Boolean) {
            val component = ComponentName(context, BootReceiver::class.java)
            val state = if (wanted) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            runCatching {
                if (context.packageManager.getComponentEnabledSetting(component) != state) {
                    context.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
                }
            }.onFailure { Log.w(TAG, "could not turn ${if (wanted) "on" else "off"}: ${it.javaClass.simpleName}") }
        }

        private const val TAG = "BootReceiver"
    }
}
