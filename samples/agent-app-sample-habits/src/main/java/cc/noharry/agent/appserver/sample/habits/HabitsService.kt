package cc.noharry.agent.appserver.sample.habits

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.launch

/**
 * The app's only way in: a foreground service that takes one request per start and writes the answer to the log
 * under the tag HabitsAgent. Extras, all optional: `new_conversation` (true: start a new conversation first),
 * `plugin` (`on` or `off`: turn the habit list plugin on or off first), `text` (the request), `allow_reset` (true:
 * a reset the turn asks for is allowed; any other approval is refused).
 *
 *     adb shell am start-foreground-service -n cc.noharry.agent.appserver.sample.habits/.HabitsService --es text "log running"
 *     adb shell am start-foreground-service -n cc.noharry.agent.appserver.sample.habits/.HabitsService --es text "reset running" --ez allow_reset true
 *     adb shell am start-foreground-service -n cc.noharry.agent.appserver.sample.habits/.HabitsService --es plugin off --es text "list my habits"
 */
class HabitsService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION, notification())
        val text = intent?.getStringExtra("text")?.trim().orEmpty()
        val plugin = intent?.getStringExtra("plugin")
        val newConversation = intent?.getBooleanExtra("new_conversation", false) == true
        val allowReset = intent?.getBooleanExtra("allow_reset", false) == true
        HabitsAgent.scope.launch {
            val answer = runCatching {
                if (newConversation) HabitsAgent.newConversation(this@HabitsService)
                if (plugin != null) HabitsAgent.setPlugin(this@HabitsService, HabitListPlugin.id, plugin == "on")
                if (text.isEmpty()) "no text" else HabitsAgent.ask(this@HabitsService, text, allowReset)
            }.getOrElse { "failed: ${it.message}" }
            Log.i(HabitsAgent.TAG, "answer: $answer")
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
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
        const val CHANNEL = "requests"
        const val NOTIFICATION = 1
    }
}
