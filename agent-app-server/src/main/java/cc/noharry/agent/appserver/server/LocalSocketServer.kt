package cc.noharry.agent.appserver.server

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Process
import android.util.Log
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcCodec
import cc.noharry.agent.appserver.protocol.transport.LineMessageChannel
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The protocol from outside the app, the way `codex app-server` is reached over stdio: an abstract local socket
 * ([name]), one JSON message per line. Only this app and the shell (adb) may connect; anything else is closed at
 * once. A PC reaches it with `adb forward tcp:8791 localabstract:<name>` ([AppServerConfig.name]).
 */
internal class LocalSocketServer(
    private val hub: ServerHub,
    /** The abstract socket's name: the server's ([AppServerConfig.name]). */
    private val name: String,
    private val onError: (Throwable) -> Unit = {},
) {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var socket: LocalServerSocket? = null

    /** Starts listening; false when the name is taken (another process of this app listens already). */
    fun start(): Boolean {
        val listening = try {
            LocalServerSocket(name)
        } catch (taken: IOException) {
            Log.w(TAG, "protocol socket not opened: ${taken.message}")
            return false
        }
        socket = listening
        io.launch { accept(listening) }
        Log.i(TAG, "protocol socket listening on localabstract:$name")
        return true
    }

    private fun accept(listening: LocalServerSocket) {
        while (true) {
            val peer = try {
                listening.accept()
            } catch (closed: IOException) {
                onError(closed)
                return
            }
            if (allowed(peer)) serve(peer) else refuse(peer)
        }
    }

    private fun allowed(peer: LocalSocket): Boolean {
        val uid = runCatching { peer.peerCredentials.uid }.getOrNull() ?: return false
        return uid == Process.myUid() || uid == SHELL_UID || uid == ROOT_UID
    }

    private fun serve(peer: LocalSocket) {
        lateinit var channel: LineMessageChannel
        channel = LineMessageChannel(
            input = peer.inputStream,
            output = peer.outputStream,
            scope = io,
            // A line that is not a message is answered with an error, so its sender is not left waiting.
            onBadLine = { line, bad ->
                Log.w(TAG, "protocol socket skipped a line: ${bad.message}")
                JsonRpcCodec.answerTo(line, bad)?.let { answer -> io.launch { runCatching { channel.send(answer) } } }
            },
            onReadError = onError,
            onClosed = { runCatching { peer.close() } },
        )
        hub.connect(channel, queueLimit = SOCKET_QUEUE_LIMIT)
    }

    private fun refuse(peer: LocalSocket) {
        Log.w(TAG, "protocol socket refused a connection from uid ${runCatching { peer.peerCredentials.uid }.getOrNull()}")
        runCatching { peer.close() }
    }

    companion object {
        private const val TAG = "AppServerSocket"
        private const val SHELL_UID = 2000
        private const val ROOT_UID = 0

        /** A socket client this far behind stopped reading; a reading one keeps up with every run. */
        private const val SOCKET_QUEUE_LIMIT = 4_096
    }
}
