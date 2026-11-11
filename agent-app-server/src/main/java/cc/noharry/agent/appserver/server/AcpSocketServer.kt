@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.server

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import android.util.Log
import cc.noharry.agent.acp.AcpAgent
import cc.noharry.agent.acp.AcpAgentConfig
import cc.noharry.agent.acp.StreamAcpTransport
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.evidence.appDiagnostics
import cc.noharry.agent.appserver.runtime.RuntimeSession
import cc.noharry.agent.appserver.runtime.appWorkspaceRoot
import cc.noharry.agent.appserver.runtime.buildRuntime
import cc.noharry.agent.appserver.trajectory.appSessionEventLogRoot
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The agent over the Agent Client Protocol, for an editor or another ACP client on a PC: an abstract local socket
 * ([name]), reached with `adb forward tcp:<port> localabstract:<name>`. Like the protocol socket, only this app and the
 * shell may connect, and it listens only when the host sets [AppServerConfig.debugSockets], whatever the build (a host
 * sets it in its debug builds). Each turn runs on a runtime built when it starts, as the app's own turns do
 * ([RuntimePerTurn]): the model, the tool servers, the plugins and the settings of that moment, so a plugin
 * switched off is gone from the next prompt and one switched on is there. Its sessions are kept apart from the app's
 * own, and the server's rules on asking hold in them as in the app (a plugin's tool that acts asks in every mode).
 */
internal class AcpSocketServer(private val app: android.content.Context, private val name: String) {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(): Boolean {
        val listening = try {
            LocalServerSocket(name)
        } catch (taken: IOException) {
            Log.w(TAG, "ACP socket not opened: ${taken.message}")
            return false
        }
        io.launch { accept(listening) }
        Log.i(TAG, "ACP socket listening on localabstract:$name")
        return true
    }

    private fun accept(listening: LocalServerSocket) {
        while (true) {
            val peer = try { listening.accept() } catch (_: IOException) { return }
            val uid = runCatching { peer.peerCredentials.uid }.getOrNull()
            if (uid == Process.myUid() || uid == SHELL_UID || uid == ROOT_UID) io.launch { serve(peer) } else runCatching { peer.close() }
        }
    }

    @Suppress("TooGenericExceptionCaught") // One client's failure must not stop the others.
    private suspend fun serve(peer: LocalSocket) {
        try {
            val runtime = RuntimePerTurn { acpRuntime(app) }
            try {
                val about = AcpAgentConfig(name = name, title = AppHost.persona.name, version = "debug")
                AcpAgent(runtime, about).serve(StreamAcpTransport(peer.inputStream, peer.outputStream))
            } finally {
                runtime.close()
            }
        } catch (error: Exception) {
            Log.w(TAG, "ACP connection ended: ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            runCatching { peer.close() }
        }
    }

    private companion object {
        const val TAG = "AppServerAcp"
        const val SHELL_UID = 2000
        const val ROOT_UID = 0
    }
}

/** The model and tool servers chosen now, the app's workspace, and a conversation store apart from the app's. */
private fun acpRuntime(app: android.content.Context): cc.noharry.agent.AgentRuntime {
    val (config, mcpProviders) = RuntimeSession.chosen(app)
    return buildRuntime(
        app, config, config.activeProfile, streamEnabled = true, useSimulatedModel = false,
        workspaceRoot = appWorkspaceRoot(app),
        sessionEventLogRoot = appSessionEventLogRoot(app, ACP_SESSIONS),
        sessionContext = "",
        evidenceRecorder = null,
        diagnostics = appDiagnostics(app, ACP_SESSIONS, onEntry = {}, onTokenUsage = {}),
        mcpProviders = mcpProviders,
    )
}

private const val ACP_SESSIONS = "acp"
