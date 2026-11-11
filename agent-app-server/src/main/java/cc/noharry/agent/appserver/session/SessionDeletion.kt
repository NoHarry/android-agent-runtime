package cc.noharry.agent.appserver.session
/** Bounded per-target outcome; names are stable labels, never absolute paths. */
internal data class SessionDeletionResult(
    val writerStopped: Boolean,
    val eventLogDeleted: Boolean,
    val artifactsDeleted: Boolean,
) {
    val ok: Boolean get() = writerStopped && eventLogDeleted && artifactsDeleted

    val failedTargets: List<String>
        get() = buildList {
            if (!writerStopped) add("run did not stop")
            if (!eventLogDeleted) add("trajectory event log")
            if (!artifactsDeleted) add("artifacts")
        }
}

/**
 * Fixed deletion order: cancel and join the exact run (which also suppresses late persist),
 * then drop the Trajectory state and its whole EventLog root, then remove the persisted
 * session and artifacts.
 *
 * When the run does not confirm cancellation the EventLog root is left in place: a writer that
 * ignored cancellation can still append to or recreate the tree we just deleted, which would
 * resurrect a "deleted" session as a corrupt one.
 */
internal suspend fun deleteRuntimeSession(
    sessionId: String,
    deleteActiveSession: suspend (String) -> Boolean,
    dropTrajectory: suspend (String) -> Boolean,
    deletePersistedSession: (String) -> Unit,
    deleteArtifacts: (String) -> Boolean,
): SessionDeletionResult {
    val writerStopped = deleteActiveSession(sessionId)
    val eventLogDeleted = if (writerStopped) dropTrajectory(sessionId) else false
    deletePersistedSession(sessionId)
    return SessionDeletionResult(
        writerStopped = writerStopped,
        eventLogDeleted = eventLogDeleted,
        artifactsDeleted = deleteArtifacts(sessionId),
    )
}
