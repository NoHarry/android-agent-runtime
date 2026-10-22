package cc.noharry.agent.kernel.core

/** Observe-only probe emission seam. Named methods match the protocol loop. */
interface LoopProbeSink {
    val casePackId: String

    fun emitRunStart()

    fun emitRequestConfig()

    fun emitSessionIsolate(session: String, memory: String, cache: String)

    fun emitCaseBound(casePackId: String)

    fun emitEmptyCompletion(reason: String)

    fun emitModelRoundStart(roundIndex: Int)

    fun emitModelRoundEnd(roundIndex: Int, status: String)

    fun emitToolBatchPlan(width: Int, roundIndex: Int? = null)

    fun emitToolPrepare(
        toolName: String,
        callId: String,
        source: String? = null,
        roundIndex: Int? = null,
    )

    fun emitToolExecuteStart(
        toolName: String,
        callId: String,
        source: String? = null,
        roundIndex: Int? = null,
        argsDigest: String? = null,
    )

    fun emitToolExecuteEnd(
        toolName: String,
        callId: String,
        ok: Boolean,
        code: String? = null,
        source: String? = null,
        roundIndex: Int? = null,
    )

    fun emitNetworkFetch(host: String)

    companion object {
        fun noop(): LoopProbeSink = NoOpLoopProbeSink
    }
}

private object NoOpLoopProbeSink : LoopProbeSink {
    override val casePackId: String = "none"

    override fun emitRunStart() = Unit

    override fun emitRequestConfig() = Unit

    override fun emitSessionIsolate(session: String, memory: String, cache: String) = Unit

    override fun emitCaseBound(casePackId: String) = Unit

    override fun emitEmptyCompletion(reason: String) = Unit

    override fun emitModelRoundStart(roundIndex: Int) = Unit

    override fun emitModelRoundEnd(roundIndex: Int, status: String) = Unit

    override fun emitToolBatchPlan(width: Int, roundIndex: Int?) = Unit

    override fun emitToolPrepare(
        toolName: String,
        callId: String,
        source: String?,
        roundIndex: Int?,
    ) = Unit

    override fun emitToolExecuteStart(
        toolName: String,
        callId: String,
        source: String?,
        roundIndex: Int?,
        argsDigest: String?,
    ) = Unit

    override fun emitToolExecuteEnd(
        toolName: String,
        callId: String,
        ok: Boolean,
        code: String?,
        source: String?,
        roundIndex: Int?,
    ) = Unit

    override fun emitNetworkFetch(host: String) = Unit
}
