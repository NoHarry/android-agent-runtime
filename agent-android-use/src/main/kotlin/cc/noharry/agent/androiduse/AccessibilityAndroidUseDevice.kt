package cc.noharry.agent.androiduse

import android.accessibilityservice.AccessibilityService
import android.annotation.TargetApi
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** [AndroidUseDevice] over the connected [AndroidUseAccessibilityService]. */
internal class AccessibilityAndroidUseDevice(private val context: Context) : AndroidUseDevice {
    private val reader = AccessibilityScreenReader(context.packageName)

    private val service: AndroidUseAccessibilityService?
        get() = AndroidUseServiceHolder.service

    override fun isConnected(): Boolean = service != null

    override fun ownPackage(): String = context.packageName

    override suspend fun readScreen(displayId: Int): ScreenState {
        val connected = checkNotNull(service) { "Android Use service disconnected." }
        return withContext(Dispatchers.IO) { reader.read(connected, displayId) }
    }

    override suspend fun capture(displayId: Int, maxPixels: Int): ScreenCapture? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val connected = service ?: return null
        // takeScreenshot refuses calls closer together than its minimum interval; wait once and retry.
        val bitmap = takeScreenshot(connected, displayId) ?: run {
            delay(SCREENSHOT_RETRY_MILLIS)
            takeScreenshot(connected, displayId)
        } ?: return null
        return try {
            withContext(Dispatchers.Default) { encode(bitmap, maxPixels) }
        } finally {
            bitmap.recycle()
        }
    }

    override suspend fun click(handle: Int): Boolean {
        val node = reader.node(handle) ?: throw StaleNodeException(handle)
        return withContext(Dispatchers.IO) { clickChain(node, SystemClock.uptimeMillis() + CLICK_TIMEOUT_MILLIS) }
    }

    override suspend fun stroke(displayId: Int, stroke: Stroke): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val connected = service ?: return false
        return dispatch(connected, gestureOf(displayId, stroke))
    }

    override suspend fun setText(handle: Int?, text: String): Boolean = withContext(Dispatchers.IO) {
        val node = if (handle != null) {
            reader.node(handle) ?: throw StaleNodeException(handle)
        } else {
            service?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return@withContext false
        }
        if (!node.refresh() || !node.isEditable) return@withContext false
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    override suspend fun setProgress(handle: Int, value: Float): Boolean = withContext(Dispatchers.IO) {
        val node = reader.node(handle) ?: throw StaleNodeException(handle)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || !node.refresh()) return@withContext false
        val arguments = Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value) }
        node.performAction(android.R.id.accessibilityActionSetProgress, arguments)
    }

    override suspend fun pressKey(key: GlobalKey): Boolean {
        val connected = service ?: return false
        val action = when (key) {
            GlobalKey.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            GlobalKey.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            GlobalKey.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            GlobalKey.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
        }
        return connected.performGlobalAction(action)
    }

    override suspend fun launchApp(app: String): LaunchOutcome = withContext(Dispatchers.IO) {
        val packageManager = context.packageManager
        packageManager.getLaunchIntentForPackage(app)?.let { return@withContext start(it) }
        val apps = packageManager.queryIntentActivities(launcherIntent(), 0).map { info ->
            LauncherApp(info.loadLabel(packageManager).toString(), info.activityInfo.packageName, info.activityInfo.name)
        }
        val matches = matchLauncherApps(apps, app)
        when {
            matches.isEmpty() -> LaunchOutcome.NotFound
            matches.size > 1 -> LaunchOutcome.Ambiguous(matches.map { it.label })
            else -> start(launcherIntent().setClassName(matches.single().packageName, matches.single().activityName))
        }
    }

    private fun start(intent: Intent): LaunchOutcome {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        (service ?: context).startActivity(intent)
        return LaunchOutcome.Launched(intent.component?.packageName ?: intent.`package`.orEmpty())
    }

    override suspend fun awaitSettled(quietMillis: Long, maxMillis: Long) {
        val start = SystemClock.uptimeMillis()
        delay(MIN_SETTLE_MILLIS)
        while (true) {
            val now = SystemClock.uptimeMillis()
            if (now - AndroidUseServiceHolder.lastEventAtMillis >= quietMillis || now - start >= maxMillis) return
            delay(SETTLE_POLL_MILLIS)
        }
    }

    /**
     * The 可见即可说 click: refresh, then click the node or its nearest clickable ancestor. Each step is a blocking
     * IPC that no coroutine timeout can interrupt, so the walk gives up (and the caller taps) once [deadline] passes.
     */
    private fun clickChain(start: AccessibilityNodeInfo, deadline: Long): Boolean {
        if (!start.refresh()) return false
        var current: AccessibilityNodeInfo? = start
        repeat(MAX_CLICK_ANCESTORS) {
            val node = current ?: return false
            if (SystemClock.uptimeMillis() > deadline) return false
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = node.parent
        }
        return false
    }

    @TargetApi(Build.VERSION_CODES.N)
    private fun gestureOf(displayId: Int, stroke: Stroke): GestureDescription {
        val path = Path().apply {
            moveTo(stroke.fromX.toFloat(), stroke.fromY.toFloat())
            if (stroke.toX != stroke.fromX || stroke.toY != stroke.fromY) lineTo(stroke.toX.toFloat(), stroke.toY.toFloat())
        }
        val builder = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, stroke.durationMs.coerceAtLeast(1)))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) builder.setDisplayId(displayId)
        return builder.build()
    }

    @TargetApi(Build.VERSION_CODES.N)
    private suspend fun dispatch(service: AccessibilityService, gesture: GestureDescription): Boolean =
        suspendCancellableCoroutine { continuation ->
            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }
            if (!service.dispatchGesture(gesture, callback, null) && continuation.isActive) continuation.resume(false)
        }

    /**
     * The callback runs on a background executor: copying the hardware buffer into a software bitmap reads the
     * whole display back from the GPU, which must not stall the host main thread and its overlay animations.
     */
    @TargetApi(Build.VERSION_CODES.R)
    private suspend fun takeScreenshot(service: AccessibilityService, displayId: Int): Bitmap? =
        suspendCancellableCoroutine { continuation ->
            service.takeScreenshot(displayId, screenshotExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    val bitmap = softwareCopy(screenshot)
                    if (continuation.isActive) continuation.resume(bitmap) else bitmap?.recycle()
                }

                override fun onFailure(errorCode: Int) {
                    if (continuation.isActive) continuation.resume(null)
                }
            })
        }

    /** A software copy of [screenshot]; the hardware buffer and its wrapper are released either way. */
    @TargetApi(Build.VERSION_CODES.R)
    private fun softwareCopy(screenshot: AccessibilityService.ScreenshotResult): Bitmap? {
        val buffer = screenshot.hardwareBuffer
        return try {
            val hardware = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace) ?: return null
            try {
                hardware.copy(Bitmap.Config.ARGB_8888, false)
            } finally {
                hardware.recycle()
            }
        } catch (_: RuntimeException) {
            null
        } finally {
            buffer.close()
        }
    }

    private fun encode(bitmap: Bitmap, maxPixels: Int): ScreenCapture {
        val scale = screenshotScale(bitmap.width, bitmap.height, maxPixels)
        val scaled = if (scale < 1.0) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1), (bitmap.height * scale).roundToInt().coerceAtLeast(1), true)
        } else {
            bitmap
        }
        try {
            val bytes = ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
            return ScreenCapture(bytes, MEDIA_TYPE_JPEG, scaled.width, scaled.height, signature(bitmap))
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    /** Grayscale thumbnail of the frame, compared between observations to see visual-only changes. */
    private fun signature(bitmap: Bitmap): ByteArray {
        val tiny = Bitmap.createScaledBitmap(bitmap, SIGNATURE_WIDTH, SIGNATURE_HEIGHT, true)
        val pixels = IntArray(SIGNATURE_WIDTH * SIGNATURE_HEIGHT)
        try {
            tiny.getPixels(pixels, 0, SIGNATURE_WIDTH, 0, 0, SIGNATURE_WIDTH, SIGNATURE_HEIGHT)
        } finally {
            if (tiny !== bitmap) tiny.recycle()
        }
        return ByteArray(pixels.size) { index ->
            val color = pixels[index]
            val luma = (Color.red(color) * LUMA_RED + Color.green(color) * LUMA_GREEN + Color.blue(color) * LUMA_BLUE) / LUMA_SCALE
            luma.toByte()
        }
    }

    private fun launcherIntent(): Intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
}

/** Screenshot callbacks copy the frame off the main thread, on a shared dispatcher rather than a thread of our own. */
private val screenshotExecutor = Dispatchers.Default.asExecutor()
private const val SCREENSHOT_RETRY_MILLIS = 350L
private const val CLICK_TIMEOUT_MILLIS = 500L
private const val MAX_CLICK_ANCESTORS = 20
// Media and settings apps redraw after a service round trip; a shorter wait screenshots the old icon.
private const val MIN_SETTLE_MILLIS = 500L
private const val SETTLE_POLL_MILLIS = 50L
private const val JPEG_QUALITY = 80
private const val LUMA_RED = 299
private const val LUMA_GREEN = 587
private const val LUMA_BLUE = 114
private const val LUMA_SCALE = 1000
