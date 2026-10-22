@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.HookContext
import cc.noharry.agent.kernel.api.HookDecision
import cc.noharry.agent.kernel.api.HookFailurePolicy
import cc.noharry.agent.kernel.api.RuntimeHook
import cc.noharry.agent.kernel.api.RuntimeHookRegistration
import cc.noharry.agent.kernel.api.ToolUse
import cc.noharry.agent.kernel.api.ToolUseResult
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.policy.PolicyAction
import cc.noharry.agent.policy.PolicyContext
import cc.noharry.agent.policy.PolicyDecision
import cc.noharry.agent.policy.PolicyEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import cc.noharry.agent.kernel.core.HookChainPort
import cc.noharry.agent.kernel.core.ParsedToolArguments
import cc.noharry.agent.kernel.core.PreToolUseOutcome
import cc.noharry.agent.kernel.core.failed
import cc.noharry.agent.kernel.core.parseToolArguments

internal class RuntimeHookChain(
    private val hooks: List<RuntimeHookRegistration>,
    private val observationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : HookChainPort {
    suspend fun preToolUse(call: ToolUse, context: HookContext): HookDecision {
        return when (val outcome = preToolUseOutcome(call, context)) {
            is PreToolUseOutcome.Allowed -> HookDecision.Allow(
                updatedArgumentsJson = outcome.toolUse.argumentsJson.takeIf { it != call.argumentsJson },
            )
            is PreToolUseOutcome.Denied -> HookDecision.Deny(outcome.code, outcome.reason)
            is PreToolUseOutcome.RequiresConfirmation -> HookDecision.RequireConfirmation(
                reason = outcome.reason,
                prompt = outcome.prompt,
            )
            is PreToolUseOutcome.BlockedAndContinue -> HookDecision.BlockAndContinue(
                reason = outcome.reason,
                prompt = outcome.prompt,
            )
        }
    }

    override suspend fun preToolUseOutcome(call: ToolUse, context: HookContext): PreToolUseOutcome {
        val arguments = when (val parsed = parseToolArguments(call.toolName, call.argumentsJson)) {
            is ParsedToolArguments.Invalid -> return PreToolUseOutcome.Denied(
                code = RuntimeErrorCode.SCHEMA_VALIDATION_FAILED.wireCode,
                reason = "Tool ${call.toolName} arguments must be a JSON object.",
            )
            is ParsedToolArguments.Valid -> parsed.arguments
        }
        return preToolUseOutcome(call, context, arguments)
    }

    internal suspend fun preToolUseOutcome(
        call: ToolUse,
        context: HookContext,
        arguments: JsonObject,
    ): PreToolUseOutcome {
        var currentCall = call
        var currentArguments = arguments
        var rewritten = false
        val confirmations = mutableListOf<HookDecision.RequireConfirmation>()
        for (registration in hooks) {
            if (!registration.matcher.matches(currentCall)) continue
            val decision = registration.preToolUseSafely(currentCall, context, currentArguments)
            when (decision) {
                is HookDecision.Allow -> {
                    val updatedArgumentsJson = decision.updatedArgumentsJson ?: continue
                    when (val parsed = parseToolArguments(currentCall.toolName, updatedArgumentsJson)) {
                        is ParsedToolArguments.Invalid -> return invalidUpdatedArguments(currentCall.toolName)
                        is ParsedToolArguments.Valid -> {
                            currentCall = currentCall.copy(argumentsJson = updatedArgumentsJson)
                            currentArguments = parsed.arguments
                            rewritten = true
                        }
                    }
                }
                is HookDecision.Deny -> return PreToolUseOutcome.Denied(decision.code, decision.reason)
                is HookDecision.RequireConfirmation -> confirmations += decision
                is HookDecision.BlockAndContinue -> return PreToolUseOutcome.BlockedAndContinue(
                    toolUse = currentCall,
                    reason = decision.reason,
                    prompt = decision.prompt,
                )
            }
        }
        if (rewritten) {
            val policyHook = hooks.firstOrNull { it.hook is PolicyHookAdapter }
            if (policyHook != null) {
                when (val decision = policyHook.preToolUseSafely(currentCall, context, currentArguments)) {
                    is HookDecision.Allow -> Unit
                    is HookDecision.Deny -> return PreToolUseOutcome.Denied(decision.code, decision.reason)
                    is HookDecision.RequireConfirmation -> confirmations += decision
                    is HookDecision.BlockAndContinue -> return PreToolUseOutcome.BlockedAndContinue(
                        toolUse = currentCall,
                        reason = decision.reason,
                        prompt = decision.prompt,
                    )
                }
            }
        }
        val confirmation = confirmations.aggregatedConfirmation()
        return if (confirmation == null) {
            PreToolUseOutcome.Allowed(currentCall)
        } else {
            PreToolUseOutcome.RequiresConfirmation(
                toolUse = currentCall,
                reason = confirmation.reason,
                prompt = confirmation.prompt,
            )
        }
    }

    override suspend fun postToolUse(call: ToolUse, result: ToolUseResult, context: HookContext) {
        hooks.forEach { registration ->
            if (!registration.matcher.matches(call)) return@forEach
            observationScope.launch {
                registration.observeSafely { onPostToolUse(call, result, context) }
            }
        }
    }

    override suspend fun onRunStarted(context: HookContext) {
        hooks.forEach { registration ->
            registration.observeSafely { onRunStarted(context) }
        }
    }

    override suspend fun onRunFinished(result: RuntimeResult, context: HookContext) {
        hooks.forEach { registration ->
            registration.observeSafely { onRunFinished(result, context) }
        }
    }

    private suspend fun RuntimeHookRegistration.preToolUseSafely(
        call: ToolUse,
        context: HookContext,
        arguments: JsonObject,
    ): HookDecision = try {
        withTimeout(timeoutMillis) {
            val currentHook = hook
            if (currentHook is PolicyHookAdapter) {
                currentHook.onPreToolUse(call, context, arguments)
            } else {
                currentHook.onPreToolUse(call, context)
            }
        }
    } catch (error: TimeoutCancellationException) {
        failureDecision(error.message ?: "Runtime hook timed out.")
    } catch (error: CancellationException) {
        throw error
    } catch (error: RuntimeException) {
        failureDecision(error.message ?: "Runtime hook failed.")
    } catch (error: Exception) {
        failureDecision(error.message ?: "Runtime hook failed.")
    }

    private fun RuntimeHookRegistration.failureDecision(reason: String): HookDecision =
        when (failurePolicy) {
            HookFailurePolicy.FAIL_CLOSED -> HookDecision.Deny(
                code = RuntimeErrorCode.HOOK_FAILED.wireCode,
                reason = reason,
            )
            HookFailurePolicy.FAIL_OPEN -> HookDecision.Allow()
        }

    private suspend fun RuntimeHookRegistration.observeSafely(block: suspend RuntimeHook.() -> Unit) {
        try {
            withTimeout(timeoutMillis) { hook.block() }
        } catch (_: TimeoutCancellationException) {
        } catch (error: CancellationException) {
            throw error
        } catch (_: RuntimeException) {
        } catch (_: Exception) {
        }
    }

    companion object {
        fun empty(): RuntimeHookChain = RuntimeHookChain(emptyList())
    }
}

private fun invalidUpdatedArguments(toolName: String): PreToolUseOutcome.Denied =
    PreToolUseOutcome.Denied(
        code = RuntimeErrorCode.SCHEMA_VALIDATION_FAILED.wireCode,
        reason = "Tool $toolName updated arguments must be a JSON object.",
    )

private fun List<HookDecision.RequireConfirmation>.aggregatedConfirmation(): HookDecision.RequireConfirmation? =
    when (size) {
        0 -> null
        1 -> first()
        else -> HookDecision.RequireConfirmation(
            reason = map { it.reason }.nonBlankDistinct().joinToString("; ")
                .ifBlank { "Multiple confirmations required." },
            prompt = map { it.prompt }.nonBlankDistinct().joinToString(CONFIRMATION_PROMPT_SEPARATOR)
                .ifBlank { "Approve this action?" },
        )
    }

private fun List<String>.nonBlankDistinct(): List<String> =
    map { it.trim() }.filter { it.isNotBlank() }.distinct()

private const val CONFIRMATION_PROMPT_SEPARATOR = "\n\n"

internal class PolicyHookAdapter(
    private val policyEngine: PolicyEngine,
) : RuntimeHook {
    override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision =
        when (val parsed = parseToolArguments(call.toolName, call.argumentsJson)) {
            is ParsedToolArguments.Invalid -> HookDecision.Deny(
                code = RuntimeErrorCode.SCHEMA_VALIDATION_FAILED.wireCode,
                reason = "Tool ${call.toolName} arguments must be a JSON object.",
            )
            is ParsedToolArguments.Valid -> onPreToolUse(call, context, parsed.arguments)
        }

    internal fun onPreToolUse(call: ToolUse, context: HookContext, arguments: JsonObject): HookDecision =
        when (
            val decision = policyEngine.evaluate(
                action = PolicyAction.Call(
                    toolName = call.toolName,
                    arguments = arguments,
                    source = call.source.name,
                ),
                context = PolicyContext(
                    sessionId = context.sessionId,
                    runId = context.runId,
                    requestId = context.requestId,
                    approvedConfirmation = context.approvedConfirmation,
                ),
            )
        ) {
            PolicyDecision.Allow -> HookDecision.Allow()
            is PolicyDecision.Deny -> HookDecision.Deny(decision.code, decision.reason)
            is PolicyDecision.RequireConfirmation -> HookDecision.RequireConfirmation(
                reason = decision.reason,
                prompt = decision.prompt,
            )
        }
}

internal const val DEFAULT_HOOK_TIMEOUT_MILLIS = 5_000L
