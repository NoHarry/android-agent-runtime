package cc.noharry.agent.androiduse

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * Candidate accessibility service behind Android Use. Not frozen.
 *
 * The host enables it ([AndroidUseAccessibility.enable]); [AndroidUseToolProvider] drives it. Window
 * events only time the "screen settled" wait after an action; nothing is read until a tool asks.
 */
class AndroidUseAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        AndroidUseServiceHolder.attach(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        AndroidUseServiceHolder.markEvent(SystemClock.uptimeMillis())
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        AndroidUseServiceHolder.detach(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AndroidUseServiceHolder.detach(this)
        super.onDestroy()
    }
}

/** The connected service, if any, and when the screen last changed. */
internal object AndroidUseServiceHolder {
    @Volatile
    var service: AndroidUseAccessibilityService? = null
        private set

    @Volatile
    var lastEventAtMillis: Long = 0L
        private set

    fun attach(connected: AndroidUseAccessibilityService) {
        service = connected
    }

    fun detach(disconnected: AndroidUseAccessibilityService) {
        if (service === disconnected) service = null
    }

    fun markEvent(uptimeMillis: Long) {
        lastEventAtMillis = uptimeMillis
    }
}
