package cc.noharry.agent.androiduse

/** Candidate: where an action lands, in display pixels. A tap has `to` equal to `from`. Not frozen. */
data class AndroidUseGesturePreview(
    val kind: Kind,
    val displayId: Int,
    val x: Int,
    val y: Int,
    val toX: Int = x,
    val toY: Int = y,
    val durationMs: Long = 0,
) {
    /** The kind of action being shown. */
    enum class Kind { TAP, LONG_PRESS, SWIPE, TYPE }
}
