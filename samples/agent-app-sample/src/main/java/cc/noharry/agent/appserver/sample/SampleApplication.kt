package cc.noharry.agent.appserver.sample

import android.app.Application
import cc.noharry.agent.appserver.server.AppServer
import cc.noharry.agent.appserver.server.AppServerConfig

/** Starts the app server with nothing of its own: no features, the generic persona, no voice. */
class SampleApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppServer.install(this, AppServerConfig(name = "agent-app-sample"))
    }
}
