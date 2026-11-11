package cc.noharry.agent.appserver.sample.notes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import cc.noharry.agent.appserver.protocol.ClientInfo
import cc.noharry.agent.appserver.protocol.ClientRequests
import cc.noharry.agent.appserver.protocol.ServerNotifications
import cc.noharry.agent.appserver.protocol.TurnStartParams
import cc.noharry.agent.appserver.protocol.client.ServerClient
import cc.noharry.agent.appserver.server.AppServer
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// guide:start service
/**
 * The assistant with no screen: a foreground service that takes one request per start (extra `prompt`) and writes the
 * answer to the log under the tag NotesAgent. It answers no approval itself: a call that asks waits for the screen, if
 * one is open, and is not done when nobody answers within a minute.
 *
 *     adb shell am start-foreground-service -n <package>/.AgentService --es prompt "note that the meeting is at 3"
 */
class AgentService : Service() {
    private val scope = MainScope()
    private var client: ServerClient? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION, notification())
        val prompt = intent?.getStringExtra("prompt")?.trim().orEmpty()
        scope.launch {
            val answer = runCatching { answer(prompt) }.getOrElse { "failed: ${it.message}" }
            Log.i(TAG, "answer: $answer")
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private suspend fun answer(prompt: String): String {
        if (prompt.isEmpty()) return "no prompt"
        val client = client ?: ServerClient(AppServer.connect(this), ClientInfo(name = "notes-service", version = "0.1"), scope = scope)
            .also { it.connect(); client = it }
        // Listening before the turn starts, so its end cannot pass unseen.
        val ended = scope.async(start = CoroutineStart.UNDISPATCHED) {
            client.notifications.first { it.method == ServerNotifications.TURN_COMPLETED.name }
        }
        client.request(ClientRequests.TURN_START, TurnStartParams(prompt))
        ended.await()
        return client.state.value.lastAnswer() ?: "no answer"
    }

    override fun onDestroy() {
        client?.close()
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.service_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return builder.setContentTitle(getString(R.string.service_working)).setSmallIcon(android.R.drawable.stat_notify_sync).build()
    }

    private companion object {
        const val TAG = "NotesAgent"
        const val CHANNEL = "requests"
        const val NOTIFICATION = 1
    }
}
// guide:end service
