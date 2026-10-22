package cc.noharry.agent.androiduse

/**
 * Candidate host hooks around Android Use, so the host can show where the agent acts. Not frozen.
 *
 * The provider bounds every call ([AndroidUseOptions.presenterTimeoutMillis], and
 * [AndroidUseOptions.runStartTimeoutMillis] for [onRunStarted]), so a slow host never stalls an action.
 * Calls arrive on a background dispatcher; hosts switch to their UI thread themselves.
 */
interface AndroidUsePresenter {
    /** A run used the screen for the first time. */
    suspend fun onRunStarted(runId: String) = Unit

    /** Right before a screenshot: hide anything that must not appear in it or cover the target. */
    suspend fun beforeCapture() = Unit

    /** The screenshot is taken. */
    suspend fun afterCapture() = Unit

    /** Right before a gesture or text input; [preview] is in display pixels. */
    suspend fun beforeGesture(preview: AndroidUseGesturePreview) = Unit

    /** The gesture or input finished. */
    suspend fun afterGesture() = Unit

    companion object {
        /** A presenter that shows nothing. */
        val None: AndroidUsePresenter = object : AndroidUsePresenter {}
    }
}

/** Default upper bound for each presenter call around a capture or gesture. */
const val PRESENTER_TIMEOUT_MILLIS = 600L
