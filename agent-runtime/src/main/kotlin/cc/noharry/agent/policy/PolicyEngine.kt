package cc.noharry.agent.policy

import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import kotlinx.serialization.json.JsonObject

internal class PolicyEngine(
    private val rules: List<PolicyRule> = emptyList(),
) {
    fun evaluate(action: PolicyAction, context: PolicyContext): PolicyDecision {
        var confirmation: PolicyDecision.RequireConfirmation? = null
        for (rule in rules) {
            when (val decision = rule.check(action, context)) {
                PolicyDecision.Allow -> Unit
                is PolicyDecision.Deny -> return decision
                is PolicyDecision.RequireConfirmation -> {
                    if (confirmation == null) {
                        confirmation = decision
                    }
                }
            }
        }
        return confirmation ?: PolicyDecision.Allow
    }

    internal companion object {
        fun allowAll(): PolicyEngine = PolicyEngine()
    }
}

/** Candidate host policy rule API. Not frozen until the API freeze boundary is signed. */
fun interface PolicyRule {
    /** Evaluates one runtime policy action in the supplied context. */
    fun check(action: PolicyAction, context: PolicyContext): PolicyDecision
}

/** Candidate policy action taxonomy. */
sealed interface PolicyAction {
    /** Candidate tool-call action. `toolName` is the final model-visible injected name. */
    data class Call(
        val toolName: String,
        val arguments: JsonObject,
        val source: String? = null,
    ) : PolicyAction
}

/** Candidate policy evaluation context. */
data class PolicyContext(
    val sessionId: String,
    val runId: String,
    val requestId: String,
    val approvedConfirmation: Boolean = false,
    val approvedStepIndex: Int? = null,
)

/** Candidate policy decision. */
sealed interface PolicyDecision {
    data object Allow : PolicyDecision

    data class Deny(
        val code: String = RuntimeErrorCode.POLICY_DENIED.wireCode,
        val reason: String,
    ) : PolicyDecision

    data class RequireConfirmation(
        val reason: String,
        val prompt: String,
    ) : PolicyDecision
}
