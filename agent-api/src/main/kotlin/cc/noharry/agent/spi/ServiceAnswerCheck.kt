package cc.noharry.agent.spi

/**
 * Whether a tool result that reports a problem (`"ok":false`, or MCP `isError`), as the service returned it, is the
 * service's answer in its own terms (no route found, out of stock) rather than a call that broke. Such a result reaches
 * the model as the service wrote it and does not count toward the failures that stop a tool being called; any other
 * failed result reaches it as the runtime's failure report, with the service's text inside. Results that do not report
 * a problem are never asked about. By default no failed result is taken for a service's answer; a host supplies the
 * check that fits its services (the runtime ships one for MCP results with a `structuredContent.status.name`).
 *
 * It is called for every failed tool result, on several threads at once when tools run in parallel: keep it quick,
 * without side effects and safe to call concurrently. One that throws is taken as no.
 */
@ExperimentalAgentRuntimeApi
fun interface ServiceAnswerCheck {
    fun isServiceAnswer(resultJson: String): Boolean

    companion object {
        /** Takes no failed result for a service's answer. */
        val NONE: ServiceAnswerCheck = ServiceAnswerCheck { false }
    }
}
