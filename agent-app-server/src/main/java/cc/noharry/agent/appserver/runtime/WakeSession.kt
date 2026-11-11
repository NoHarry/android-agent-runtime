package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.appserver.protocol.SessionPolicy
import cc.noharry.agent.appserver.protocol.SessionSummary
import cc.noharry.agent.appserver.session.SessionSettings

/** A conversation with subagents still working, or results not delivered yet, is kept so the results land in it. */
internal fun wakeStartsNewSession(
    active: SessionSummary?,
    nowMillis: Long,
    backgroundWork: Boolean = false,
    policy: SessionPolicy = SessionSettings.policy.value,
): Boolean =
    active != null && active.turnCount > 0 && !backgroundWork &&
        (policy.everyWake || nowMillis - active.updatedAtEpochMillis >= policy.idleMillis)
