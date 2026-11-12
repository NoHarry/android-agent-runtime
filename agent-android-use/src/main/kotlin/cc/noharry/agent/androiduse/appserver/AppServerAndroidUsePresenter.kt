package cc.noharry.agent.androiduse.appserver

import cc.noharry.agent.androiduse.AndroidUseGesturePreview
import cc.noharry.agent.androiduse.AndroidUsePresenter
import cc.noharry.agent.appserver.protocol.DragParams
import cc.noharry.agent.appserver.protocol.Empty
import cc.noharry.agent.appserver.protocol.PointParams
import cc.noharry.agent.appserver.protocol.PresentationNotifications
import cc.noharry.agent.appserver.protocol.PresentationRequests
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import cc.noharry.agent.appserver.server.Assistant

/**
 * Shows operating the screen on the [assistant] on screen, over the protocol: the card folds, the screen edges glow, and
 * the assistant points at each tap and drags along each swipe. Without an assistant up every call returns at once; the
 * provider bounds each call anyway.
 */
internal class AppServerAndroidUsePresenter(private val assistant: Assistant) : AndroidUsePresenter {
    override suspend fun onRunStarted(runId: String) = show(PresentationRequests.ANDROID_USE_BEGIN, Empty)

    override suspend fun beforeCapture() = show(PresentationRequests.ANDROID_USE_HIDE, Empty)

    override suspend fun afterCapture() = assistant.tell(PresentationNotifications.ANDROID_USE_REVEAL, Empty)

    override suspend fun beforeGesture(preview: AndroidUseGesturePreview) {
        // The overlay covers the main display only.
        if (preview.displayId != MAIN_DISPLAY_ID) return
        when (preview.kind) {
            AndroidUseGesturePreview.Kind.SWIPE ->
                show(PresentationRequests.ANDROID_USE_DRAG, DragParams(preview.x, preview.y, preview.toX, preview.toY, preview.durationMs))
            AndroidUseGesturePreview.Kind.TAP, AndroidUseGesturePreview.Kind.LONG_PRESS ->
                show(PresentationRequests.ANDROID_USE_POINT, PointParams(preview.x, preview.y, press = true))
            AndroidUseGesturePreview.Kind.TYPE -> show(PresentationRequests.ANDROID_USE_POINT, PointParams(preview.x, preview.y, press = false))
        }
    }

    /** Returns once shown, or at once when no assistant is up. */
    private suspend fun <P> show(method: RequestMethod<P, Empty>, params: P) {
        if (assistant.ready) assistant.ask(method, params)
    }
}

private const val MAIN_DISPLAY_ID = 0
