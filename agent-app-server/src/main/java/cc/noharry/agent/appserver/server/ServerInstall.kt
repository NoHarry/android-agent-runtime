@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.server

import android.app.Application
import cc.noharry.agent.appserver.http.appServerHttpClient
import cc.noharry.agent.appserver.mcp.McpToolCatalogs
import cc.noharry.agent.appserver.packages.PackageInstaller
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.RuntimeSession
import cc.noharry.agent.appserver.session.SessionSettings
import cc.noharry.agent.appserver.skills.AppSubagents
import cc.noharry.agent.appserver.usage.UsageStore

/**
 * What runs in the app's process from its start, before any front end connects: the session settings, then each of
 * the host's [plugins] that are on (its voice, its own models, plans, A2A), then the packages a person installed
 * ([packages]), then stores, subagents and tools.
 */
internal fun installServer(app: Application, plugins: PluginHost, packages: PackageInstaller) {
    SessionSettings.init(app)
    plugins.startAll()
    packages.loadInstalled()
    // The trust store (system roots plus the host's extra ones, which some devices lack) is loaded once, here off the
    // main thread, before the first wake or request needs it.
    Thread({ runCatching { appServerHttpClient(app) } }, "trust-roots").start()
    UsageStore.init(app)
    AppSubagents.install(app) { report -> RuntimeSession.deliverSubagentReport(app, report) }
    AppSubagents.onLifecycle(RuntimeSession::noteSubagent)
    McpToolCatalogs.refreshOnAppStart(app)
}
