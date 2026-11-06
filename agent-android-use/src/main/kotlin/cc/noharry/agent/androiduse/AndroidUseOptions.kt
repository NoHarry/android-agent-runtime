package cc.noharry.agent.androiduse

/** Android Use settings. */
data class AndroidUseOptions(
    /** False for a text-only model: observations then carry elements only. */
    val screenshots: Boolean = true,
    /** Screenshots are scaled to at most this many pixels; 640k suits the image budgets of common vision models. */
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
    /**
     * True describes the tools as a fallback: the model should look for a dedicated tool (search_tools) before opening
     * an app or a page by hand. False describes them as the way to use the screen.
     */
    val fallbackOnly: Boolean = true,
    /** True keeps the status bar and dock out of reach: their elements are left out and presses there refused. */
    val protectSystemBars: Boolean = true,
    /**
     * True lets the agent see and work the host's own app windows too (a host that runs its own screens); its overlays
     * over other apps, such as the presenter's, stay out. By default every window of the host is left out.
     */
    val observeOwnApp: Boolean = false,
    /**
     * True reads a contentDescription that is a JSON object as some apps' voice-control protocol, keeping only its
     * `action` label; false passes such descriptions on as they are.
     */
    val jsonDescriptionActions: Boolean = true,
    /** How long a run must leave the screen alone before a run of another conversation may take it over. */
    val leaseIdleMillis: Long = DEFAULT_LEASE_IDLE_MILLIS,
) {
    init {
        require(maxScreenshotPixels > 0) { "maxScreenshotPixels must be positive." }
        require(leaseIdleMillis >= 0) { "leaseIdleMillis must not be negative." }
        require(maxElements > 0) { "maxElements must be positive." }
        require(presenterTimeoutMillis > 0 && runStartTimeoutMillis > 0) { "Presenter timeouts must be positive." }
    }
}

const val DEFAULT_MAX_SCREENSHOT_PIXELS = 640_000
const val DEFAULT_MAX_ELEMENTS = 80
const val RUN_START_TIMEOUT_MILLIS = 1_500L
const val DEFAULT_LEASE_IDLE_MILLIS = 10_000L
