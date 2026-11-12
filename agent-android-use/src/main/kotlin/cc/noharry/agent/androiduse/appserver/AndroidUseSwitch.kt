package cc.noharry.agent.androiduse.appserver

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import cc.noharry.agent.androiduse.AndroidUseAccessibility
import cc.noharry.agent.appserver.protocol.AndroidUseState
import cc.noharry.agent.appserver.protocol.ExperimentalAppServerApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The person's switch for operating the screen ([AndroidUsePlugin]), kept on the device; on until they turn it off.
 * The tools also need the accessibility service bound, which the system does once the service is on in its settings.
 */
@ExperimentalAppServerApi
fun loadAndroidUseEnabled(context: Context): Boolean =
    context.getSharedPreferences(ANDROID_USE_PREFS, Context.MODE_PRIVATE).getBoolean(ANDROID_USE_ENABLED, true)

/** Saves the person's switch, and lists or drops the service when the plugin runs. */
@ExperimentalAppServerApi
fun saveAndroidUseEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(ANDROID_USE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(ANDROID_USE_ENABLED, enabled).apply()
    applyAndroidUseSwitch(context, enabled)
}

/**
 * Keeps the accessibility service in step with the switch where the app may write secure settings; runs when the plugin
 * starts and on every toggle. Without the plugin running ([AndroidUsePlugin]) the server never lists the service.
 */
internal fun applyAndroidUseSwitch(context: Context, enabled: Boolean = loadAndroidUseEnabled(context)): Boolean = when {
    !AndroidUsePlugin.running -> false
    enabled -> AndroidUseAccessibility.enable(context)
    else -> AndroidUseAccessibility.disable(context)
}

/** The tools are mounted only while the plugin and the switch are on and the service is bound, so they always work. */
internal fun androidUseReady(context: Context): Boolean =
    AndroidUsePlugin.running && loadAndroidUseEnabled(context) && AndroidUseAccessibility.isConnected()

internal fun androidUseState(context: Context): AndroidUseState = when {
    !AndroidUsePlugin.running || !loadAndroidUseEnabled(context) -> AndroidUseState.OFF
    AndroidUseAccessibility.isConnected() -> AndroidUseState.CONNECTED
    AndroidUseAccessibility.isEnabled(context) -> AndroidUseState.WAITING
    else -> AndroidUseState.FAILED
}

/**
 * Brings the service back when the switch is on but the system never binds it. On some Android 11 devices a force-stop
 * drops the service from `enabled_accessibility_services` and marks it crashed; listing it again at start
 * ([applyAndroidUseSwitch]) is not enough, as the system binds a crashed service only once it has seen it leave the
 * list. Taking this entry out and, [gapMillis] later, putting it back does that, for an app that may write secure
 * settings. True once the service is connected, within [waitMillis] of putting it back.
 */
@ExperimentalAppServerApi
suspend fun repairAndroidUse(
    context: Context,
    gapMillis: Long = REBIND_GAP_MS,
    waitMillis: Long = CONNECT_WAIT_MS,
): Boolean = repairLock.withLock {
    if (AndroidUseAccessibility.isConnected()) return@withLock true
    if (AndroidUseAccessibility.isEnabled(context) && !AndroidUseAccessibility.disable(context)) return@withLock false
    // The system must see the entry gone before it comes back, or it reads the same list and the service stays crashed.
    delay(gapMillis)
    if (!AndroidUseAccessibility.enable(context)) return@withLock false
    withTimeoutOrNull(waitMillis) {
        while (!AndroidUseAccessibility.isConnected()) delay(CONNECT_POLL_MS)
        true
    } ?: false
}

/**
 * At start the system binds a listed service within a second or two. Where the app may write secure settings, one the
 * switch wants that is still not connected after [STARTUP_CHECK_MS] is repaired once ([repairAndroidUse]); an app that
 * may not leaves the service to the person, and nothing is tried.
 */
internal fun scheduleAndroidUseRepair(context: Context) {
    val app = context.applicationContext
    if (app.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
    // On the main looper, not a timer of its own: unit tests create the application once per test, and their main
    // looper only moves when a test moves it, so no test's repair fires in the middle of another.
    Handler(Looper.getMainLooper()).postDelayed({
        if (!loadAndroidUseEnabled(app) || AndroidUseAccessibility.isConnected()) return@postDelayed
        Log.w(TAG, "android use: switched on but not connected ${STARTUP_CHECK_MS / 1000} s after start; re-enabling its service")
        repairScope.launch { Log.i(TAG, "android use repair: ${if (repairAndroidUse(app)) "connected" else "still not connected"}") }
    }, STARTUP_CHECK_MS)
}

private val repairScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
private val repairLock = Mutex()
private const val TAG = "AndroidUse"
private const val STARTUP_CHECK_MS = 8_000L
/** On a test device a gap of one second was enough for the system to drop the crashed entry. */
private const val REBIND_GAP_MS = 1_000L
private const val CONNECT_WAIT_MS = 5_000L
private const val CONNECT_POLL_MS = 200L
private const val ANDROID_USE_PREFS = "app_server_android_use"
private const val ANDROID_USE_ENABLED = "enabled"
