@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.release

import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.policy.PolicyAction
import cc.noharry.agent.policy.PolicyContext
import cc.noharry.agent.policy.PolicyDecision
import cc.noharry.agent.policy.PolicyRule
import cc.noharry.agent.runtime.AgentRuntimeBuilder
import cc.noharry.agent.kernel.api.HookContext
import cc.noharry.agent.kernel.api.HookDecision
import cc.noharry.agent.kernel.api.RuntimeHook
import cc.noharry.agent.kernel.api.ToolSource
import cc.noharry.agent.kernel.api.ToolUse
import cc.noharry.agent.kernel.api.ToolUseResult
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Modifier

class ApiFreezeCandidateM4InventoryTest {
    @Test
    fun modelDrivenHarnessCandidateSurfaceIsExplicit() {
        assertEquals(CANDIDATE_BUILDER_METHODS, publicMethods(AgentRuntimeBuilder::class.java))
        assertEquals(CANDIDATE_RUNTIME_HOOK_METHODS, publicMethods(RuntimeHook::class.java))
        assertEquals(CANDIDATE_TOOL_USE_FIELDS, publicDataFields(ToolUse::class.java))
        assertEquals(CANDIDATE_TOOL_SOURCE_TOKENS, ToolSource.entries.map { it.name })
        assertEquals(CANDIDATE_TOOL_USE_RESULT_FIELDS, publicDataFields(ToolUseResult::class.java))
        assertEquals(CANDIDATE_HOOK_CONTEXT_FIELDS, publicDataFields(HookContext::class.java))
        assertEquals(CANDIDATE_HOOK_DECISION_VARIANTS, publicNestedNames(HookDecision::class.java))
        assertEquals(CANDIDATE_HOOK_DECISION_FIELDS, publicNestedFieldSignatures(HookDecision::class.java))
        assertEquals(CANDIDATE_POLICY_RULE_METHODS, publicMethods(PolicyRule::class.java))
        assertEquals(CANDIDATE_POLICY_ACTION_VARIANTS, publicNestedNames(PolicyAction::class.java))
        assertEquals(CANDIDATE_POLICY_ACTION_FIELDS, publicNestedFieldSignatures(PolicyAction::class.java))
        assertEquals(CANDIDATE_POLICY_CONTEXT_FIELDS, publicDataFields(PolicyContext::class.java))
        assertEquals(CANDIDATE_POLICY_DECISION_VARIANTS, publicNestedNames(PolicyDecision::class.java))
        assertEquals(CANDIDATE_POLICY_DECISION_FIELDS, publicNestedFieldSignatures(PolicyDecision::class.java))
        assertEquals(CANDIDATE_MODEL_REQUEST_FIELDS, publicDataFields(ModelRequest::class.java))
    }

    private fun publicMethods(type: Class<*>): List<String> =
        type.methods
            .filter { it.declaringClass == type && Modifier.isPublic(it.modifiers) && "$" !in it.name }
            .map { it.name }
            .sorted()

    private fun publicDataFields(type: Class<*>): List<String> =
        type.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .map { it.name }
            .sorted()

    private fun publicNestedNames(type: Class<*>): List<String> =
        type.declaredClasses
            .filter { Modifier.isPublic(it.modifiers) }
            .map { it.simpleName }
            .sorted()

    private fun publicNestedFieldSignatures(type: Class<*>): List<String> =
        type.declaredClasses
            .filter { Modifier.isPublic(it.modifiers) }
            .mapNotNull { nested ->
                val fields = publicDataFields(nested)
                if (fields.isEmpty()) null else "${nested.simpleName}:${fields.joinToString(",")}"
            }
            .sorted()
}

private val CANDIDATE_BUILDER_METHODS = listOf(
    "agentsMd",
    "build",
    "buildAsync",
    "contextProvider",
    "contextWindow",
    "create",
    "defaultWorkspace",
    "deferredBackendTools",
    "disabledNativeTools",
    "execEngine",
    "hook",
    "hookTimeoutMillis",
    "imageInput",
    "loopBudget",
    "modelClient",
    "modelConcurrency",
    "modelRetry",
    "nativeDeferredTools",
    "nativeWebFetchEnabled",
    "networkConfirmationPolicy",
    "nowEpochMillis",
    "plugin",
    "policyConfig",
    "policyConfigJson",
    "policyConfigPath",
    "policyRule",
    "profile",
    "runContext",
    "runContextAsSystem",
    "runtimeEvidence",
    "sceneAgentsMd",
    "sessionActivationInheritance",
    "sessionConcurrency",
    "sessionEventLog",
    "sessionEventLogRoot",
    "sessionHistory",
    "sessionStore",
    "sessionToolActivations",
    "skill",
    "subagents",
    "systemPrompt",
    "toolConcurrency",
    "toolProvider",
    "toolProviders",
    "turnObserver",
    "virtualWorkspace",
    "webFetchFallback",
    "webSearch",
    "workspace",
    "workspaceRoot",
)

private val CANDIDATE_RUNTIME_HOOK_METHODS = listOf(
    "onPostToolUse",
    "onPreToolUse",
    "onRunFinished",
    "onRunStarted",
)

private val CANDIDATE_TOOL_USE_FIELDS = listOf("argumentsJson", "source", "toolCallId", "toolName")

private val CANDIDATE_TOOL_SOURCE_TOKENS = listOf(
    "INTERACTION",
    "NATIVE_READ",
    "NATIVE_WRITE",
    "NETWORK",
    "EXEC",
    "NATIVE_TODO",
    "BACKEND",
    "TERMINAL",
)

private val CANDIDATE_TOOL_USE_RESULT_FIELDS = listOf("code", "ok", "resultJson", "retryable")

private val CANDIDATE_HOOK_CONTEXT_FIELDS = listOf("approvedConfirmation", "requestId", "runId", "sessionId")

private val CANDIDATE_HOOK_DECISION_VARIANTS = listOf("Allow", "BlockAndContinue", "Deny", "RequireConfirmation")

private val CANDIDATE_HOOK_DECISION_FIELDS = listOf(
    "Allow:updatedArgumentsJson",
    "BlockAndContinue:prompt,reason",
    "Deny:code,reason",
    "RequireConfirmation:prompt,reason",
)

private val CANDIDATE_POLICY_RULE_METHODS = listOf("check")

private val CANDIDATE_POLICY_ACTION_VARIANTS = listOf("Call")

private val CANDIDATE_POLICY_ACTION_FIELDS = listOf("Call:arguments,source,toolName")

private val CANDIDATE_POLICY_CONTEXT_FIELDS =
    listOf("approvedConfirmation", "approvedStepIndex", "requestId", "runId", "sessionId")

private val CANDIDATE_POLICY_DECISION_VARIANTS = listOf("Allow", "Deny", "RequireConfirmation")

private val CANDIDATE_POLICY_DECISION_FIELDS = listOf("Deny:code,reason", "RequireConfirmation:prompt,reason")

private val CANDIDATE_MODEL_REQUEST_FIELDS =
    listOf("messages", "modelRef", "promptCacheKey", "system", "toolChoice", "tools")
