package cc.noharry.agent.a2a.server

/** Candidate host acknowledgement for input to an existing task; acceptance is not model consumption. */
data class A2aInputReceipt(val accepted: Boolean, val duplicate: Boolean = false, val code: String = "")
