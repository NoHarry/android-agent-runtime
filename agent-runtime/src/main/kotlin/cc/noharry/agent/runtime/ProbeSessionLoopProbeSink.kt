package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.core.LoopProbeSink
import cc.noharry.agent.runtime.probe.ProbeSession

internal class ProbeSessionLoopProbeSink(
    private val session: ProbeSession,
) : LoopProbeSink {
    override val casePackId: String
        get() = session.casePackId

    override fun emitRunStart() {
        session.emitRunStart()
    }

    override fun emitRequestConfig() {
        session.emitRequestConfig()
    }

    override fun emitSessionIsolate(session: String, memory: String, cache: String) {
        this.session.emitSessionIsolate(session, memory, cache)
    }

    override fun emitCaseBound(casePackId: String) {
        session.emitCaseBound(casePackId)
    }

    override fun emitEmptyCompletion(reason: String) {
        session.emitEmptyCompletion(reason)
    }

    override fun emitModelRoundStart(roundIndex: Int) {
        session.emitModelRoundStart(roundIndex)
    }

    override fun emitModelRoundEnd(roundIndex: Int, status: String) {
        session.emitModelRoundEnd(roundIndex, status)
    }

    override fun emitToolBatchPlan(width: Int, roundIndex: Int?) {
        session.emitToolBatchPlan(width, roundIndex)
    }

    override fun emitToolPrepare(
        toolName: String,
        callId: String,
        source: String?,
        roundIndex: Int?,
    ) {
        session.emitToolPrepare(toolName, callId, source, roundIndex)
    }

    override fun emitToolExecuteStart(
        toolName: String,
        callId: String,
        source: String?,
        roundIndex: Int?,
        argsDigest: String?,
    ) {
        session.emitToolExecuteStart(
            toolName = toolName,
            callId = callId,
            source = source,
            roundIndex = roundIndex,
            argsDigest = argsDigest,
        )
    }

    override fun emitToolExecuteEnd(
        toolName: String,
        callId: String,
        ok: Boolean,
        code: String?,
        source: String?,
        roundIndex: Int?,
    ) {
        session.emitToolExecuteEnd(toolName, callId, ok, code, source, roundIndex)
    }

    override fun emitNetworkFetch(host: String) {
        session.emitNetworkFetch(host)
    }
}
