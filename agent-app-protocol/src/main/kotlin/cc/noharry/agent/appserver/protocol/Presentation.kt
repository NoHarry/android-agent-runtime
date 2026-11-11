package cc.noharry.agent.appserver.protocol

import cc.noharry.agent.appserver.protocol.jsonrpc.NotificationMethod
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The assistant on this device's screen (the overlay) as a client like any other. The client that presents it says so
 * in its capabilities ([ClientCapabilities.presentation]): the server asks it to bring the assistant up, open or fold
 * its card, take a wake, show a plan coming due and draw Android Use, and it tells the server how the assistant stands
 * ([PresentationState]). Without such a client none of it happens and the server goes on without a screen.
 */

/** How the assistant on screen stands, as the presenting client last told it. */
@Serializable
data class PresentationState(
    /** The assistant is up and takes wakes itself. */
    val ready: Boolean = false,
    /** Until when (epoch milliseconds) the person is reading a result on it: a report waits until then. */
    val readingUntilEpochMillis: Long = 0,
    /** A full-screen front end is in front, which takes a wake itself and keeps the assistant away. */
    val inFront: Boolean = false,
)

/** Opens the assistant's card, or folds it back. */
@Serializable
enum class OverlayCommand {
    @SerialName("show")
    SHOW,

    @SerialName("hide")
    HIDE,
}

@Serializable
data class PresentStartParams(val open: Boolean)

@Serializable
data class PresentCardParams(val command: OverlayCommand)

/**
 * Done, or [refusal] says why not (a missing permission, a start the system refused). [noResting]: this screen keeps no
 * assistant up between wakes (a phone), so the server listens for the wake word by itself.
 */
@Serializable
data class PresentResult(val refusal: String? = null, val noResting: Boolean = false)

/** [origin]: how the wake came, in the host's own words (the wake word, a tap on the assistant). */
@Serializable
data class PresentWakeParams(val origin: String)

/** Whether the assistant took it: false when none is up (a wake is then heard without it). */
@Serializable
data class TakenResult(val taken: Boolean)

/** [planId]'s controls are done: [outcome] replaces what they were doing while its entry is up. */
@Serializable
data class PlanRanParams(val planId: String, val outcome: Finding)

/** Android Use: the fingertip to ([x], [y]) in screen pixels; [press] ripples the target. Answered once it arrived. */
@Serializable
data class PointParams(val x: Int, val y: Int, val press: Boolean)

/** Android Use: a drag along a gesture. Answered once the fingertip is on its start. */
@Serializable
data class DragParams(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int, val durationMs: Long)

/** Requests the server sends the presenting client. */
object PresentationRequests {
    /** Bring the assistant up with its card closed. */
    val ENSURE_RUNNING = RequestMethod("presentation/ensureRunning", Empty.serializer(), PresentResult.serializer())
    val START = RequestMethod("presentation/start", PresentStartParams.serializer(), PresentResult.serializer())
    val CARD = RequestMethod("presentation/card", PresentCardParams.serializer(), PresentResult.serializer())
    val WAKE = RequestMethod("presentation/wake", PresentWakeParams.serializer(), TakenResult.serializer())

    /** Folds the card, lights the screen edges and shows the pointer; answered when ready. */
    val ANDROID_USE_BEGIN = RequestMethod("androidUse/begin", Empty.serializer(), Empty.serializer())
    val ANDROID_USE_POINT = RequestMethod("androidUse/point", PointParams.serializer(), Empty.serializer())
    val ANDROID_USE_DRAG = RequestMethod("androidUse/drag", DragParams.serializer(), Empty.serializer())

    /** Hides the glow and the pointer for a screenshot; answered once they are gone. */
    val ANDROID_USE_HIDE = RequestMethod("androidUse/hide", Empty.serializer(), Empty.serializer())
}

/** Notifications the server sends the presenting client. */
object PresentationNotifications {
    /** A plan came due: its entry in a small popup, the newest on top (the chime is the server's own). */
    val PLAN_DUE = NotificationMethod("presentation/planDue", PlanNoticeEntry.serializer())
    val PLAN_RAN = NotificationMethod("presentation/planRan", PlanRanParams.serializer())

    /** Android Use: the glow and the pointer back after a screenshot. */
    val ANDROID_USE_REVEAL = NotificationMethod("androidUse/reveal", Empty.serializer())
}

/** Notifications the presenting client sends the server. */
object PresentationReports {
    val STATE = NotificationMethod("presentation/state", PresentationState.serializer())
}
