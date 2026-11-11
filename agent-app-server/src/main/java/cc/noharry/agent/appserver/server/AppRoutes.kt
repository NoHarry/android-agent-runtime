package cc.noharry.agent.appserver.server

import android.content.Context
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.FeatureRequests
import cc.noharry.agent.appserver.runtime.RuntimeSession

/** The app around the screens: their language. */
internal class AppRoutes(private val app: Context) : RouteModule {
    override fun RouteTable.install() {
        on(FeatureRequests.LANGUAGE_CHANGED) {
            RuntimeSession.languageChanged(app)
            PreferencesSource.changed()
            Empty
        }
    }
}
