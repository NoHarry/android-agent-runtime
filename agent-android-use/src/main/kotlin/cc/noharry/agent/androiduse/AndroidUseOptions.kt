package cc.noharry.agent.androiduse

/** Candidate Android Use settings. Not frozen. */
data class AndroidUseOptions(
    /** False for a text-only model: observations then carry elements only. */
    val screenshots: Boolean = true,
    /** Screenshots are scaled to at most this many pixels; 640k fits DeepSeek's vision budget. */
    val maxScreenshotPixels: Int = DEFAULT_MAX_SCREENSHOT_PIXELS,
    /** At most this many elements per observation, top to bottom. */
    val maxElements: Int = DEFAULT_MAX_ELEMENTS,
    /** Upper bound for each presenter call around a capture or gesture. */
    val presenterTimeoutMillis: Long = PRESENTER_TIMEOUT_MILLIS,
    /** Upper bound for [AndroidUsePresenter.onRunStarted], which may fold a host card first. */
    val runStartTimeoutMillis: Long = RUN_START_TIMEOUT_MILLIS,
    /**
     * True offers both tools from the first model round. A host whose tools sit behind `search_tools`
     * otherwise relies on the model searching for "screen" first, which it skips when a domain tool
     * already opened a page.
     */
    val preloadTools: Boolean = true,
) {
    init {
        require(maxScreenshotPixels > 0) { "maxScreenshotPixels must be positive." }
        require(maxElements > 0) { "maxElements must be positive." }
        require(presenterTimeoutMillis > 0 && runStartTimeoutMillis > 0) { "Presenter timeouts must be positive." }
    }
}

const val DEFAULT_MAX_SCREENSHOT_PIXELS = 640_000
const val DEFAULT_MAX_ELEMENTS = 80
const val RUN_START_TIMEOUT_MILLIS = 1_500L
