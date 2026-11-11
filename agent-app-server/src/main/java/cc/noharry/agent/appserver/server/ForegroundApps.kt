package cc.noharry.agent.appserver.server

import android.content.Context
import cc.noharry.agent.appserver.AppServerPluginApi
import cc.noharry.agent.appserver.runtime.AppHost

/**
 * The app in front of the person, as a plugin that sees the screen knows it (operating the screen offers it with
 * [PluginContext.provide]). Each run's context names it, so the model does not open it again, and the media tools send
 * a pause with nothing playing to it.
 */
@AppServerPluginApi
fun interface ForegroundApps {
    /** The app in front now, or null when it is not known. */
    fun current(context: Context): ForegroundApp?
}

/** An app in front: its name as the person reads it, and its package. */
@AppServerPluginApi
class ForegroundApp(val label: String, val packageName: String)

/** The app in front, as a running plugin knows it ([ForegroundApps]); null without such a plugin. */
@AppServerPluginApi
fun foregroundApp(context: Context): ForegroundApp? = AppHost.additions.service(ForegroundApps::class.java)?.current(context)
