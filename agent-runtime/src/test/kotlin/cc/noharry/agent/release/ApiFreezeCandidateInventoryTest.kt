@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.release

import cc.noharry.agent.kernel.api.AgentCapabilities
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentPlugin
import cc.noharry.agent.kernel.api.AgentPluginDescriptor
import cc.noharry.agent.kernel.api.AgentPluginScope
import cc.noharry.agent.kernel.api.AgentProfile
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.AgentRuntime
import cc.noharry.agent.kernel.api.SessionRunConflictPolicy
import cc.noharry.agent.kernel.api.CapabilityCardinality
import cc.noharry.agent.kernel.api.ExecEngine
import cc.noharry.agent.kernel.api.ExecRequest
import cc.noharry.agent.kernel.api.ExecResult
import cc.noharry.agent.kernel.api.ExecToolBridge
import cc.noharry.agent.kernel.api.HookContext
import cc.noharry.agent.kernel.api.HookDecision
import cc.noharry.agent.kernel.api.HookFailurePolicy
import cc.noharry.agent.kernel.api.KernelApiRange
import cc.noharry.agent.kernel.api.KernelApiVersion
import cc.noharry.agent.kernel.api.PluginVersion
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.ProviderConcurrencyMode
import cc.noharry.agent.kernel.api.CapabilityKey
import cc.noharry.agent.kernel.api.RuntimeHook
import cc.noharry.agent.kernel.api.RuntimeHookRegistration
import cc.noharry.agent.kernel.api.SessionCapabilityContext
import cc.noharry.agent.kernel.api.SessionCapabilityFactory
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.ToolCallTrace
import cc.noharry.agent.kernel.api.ToolCatalogCacheIdentity
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.ToolInitializeContext
import cc.noharry.agent.kernel.api.ToolInitializeResult
import cc.noharry.agent.kernel.api.ToolMatcher
import cc.noharry.agent.kernel.api.ToolPromptDescriptor
import cc.noharry.agent.kernel.api.ToolProvider
import cc.noharry.agent.kernel.api.ToolProviderStatus
import cc.noharry.agent.kernel.api.ToolResourceDescriptor
import cc.noharry.agent.kernel.api.ToolSource
import cc.noharry.agent.kernel.api.ToolUse
import cc.noharry.agent.kernel.api.ToolUseResult
import cc.noharry.agent.kernel.api.WorkspaceCallContext
import cc.noharry.agent.kernel.api.WorkspaceExecutionResult
import cc.noharry.agent.kernel.api.WorkspaceExecutionTelemetry
import cc.noharry.agent.kernel.api.WorkspacePort
import cc.noharry.agent.kernel.api.CancelPendingResult
import cc.noharry.agent.kernel.api.ConfirmationResumeResult
import cc.noharry.agent.kernel.api.PostUserInputResult
import cc.noharry.agent.kernel.api.ProtocolRequestCloseLoopResult
import cc.noharry.agent.kernel.api.SessionEvent
import cc.noharry.agent.kernel.api.SessionEventAppend
import cc.noharry.agent.kernel.api.SessionEventLog
import cc.noharry.agent.kernel.api.SessionEventLogDurability
import cc.noharry.agent.kernel.api.SessionEventLogHealth
import cc.noharry.agent.kernel.api.SessionEventLogHealthStatus
import cc.noharry.agent.kernel.api.SessionAttachmentRef
import cc.noharry.agent.kernel.api.SessionEventPayload
import cc.noharry.agent.kernel.api.SessionEventQuery
import cc.noharry.agent.kernel.api.SessionEventType
import cc.noharry.agent.kernel.api.SessionJson
import cc.noharry.agent.kernel.api.mcp.McpClientProvider
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelImage
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.policy.PolicyAction
import cc.noharry.agent.policy.PolicyContext
import cc.noharry.agent.policy.PolicyDecision
import cc.noharry.agent.policy.PolicyRule
import cc.noharry.agent.protocol.AgentProtocolCatalog
import cc.noharry.agent.kernel.api.reliability.RuntimeEvidenceType
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.reliability.RuntimeProtocolRequestStatus
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunPhase
import cc.noharry.agent.kernel.api.reliability.RuntimeRunSnapshot
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.api.reliability.SuggestedNextAction
import cc.noharry.agent.runtime.AgentRuntimeBuilder
import cc.noharry.agent.runtime.RuntimeWorkspaceRef
import cc.noharry.agent.transport.HttpEvent
import cc.noharry.agent.vfs.VfsEntry
import cc.noharry.agent.vfs.VfsReadResult
import cc.noharry.agent.vfs.VfsSource
import cc.noharry.agent.vfs.VfsStat
import cc.noharry.agent.vfs.VfsStatResult
import cc.noharry.agent.vfs.VfsWriteMode
import cc.noharry.agent.vfs.VfsWriteResult
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Modifier

class ApiFreezeCandidateInventoryTest {
    @Test
    fun candidateApiFreezeTokenInventoryIsExplicit() {
        assertEquals(expected("AgentRuntime methods"), publicMethodNames(AgentRuntime::class.java))
        assertEquals(expected("AgentRuntimeBuilder methods"), publicMethodNames(AgentRuntimeBuilder::class.java))
        assertEquals(expected("AgentRequest fields"), publicDataFields(AgentRequest::class.java))
        assertEquals(expected("SessionRunConflictPolicy tokens"), SessionRunConflictPolicy.entries.map { it.name })
        assertEquals(expected("RuntimeWorkspaceRef fields"), publicDataFields(RuntimeWorkspaceRef::class.java))
        assertKernelPluginAndEventLogSurface()
        assertExecSurface()
        assertHookSurface()
        assertPolicySurface()
        assertModelTransportSurface()
        assertRuntimeRecordSurface()
        assertVfsSurface()
    }

    private fun assertKernelPluginAndEventLogSurface() {
        assertEquals(expected("AgentPlugin methods"), publicMethodNames(AgentPlugin::class.java))
        assertEquals(expected("AgentPluginScope methods"), publicMethodNames(AgentPluginScope::class.java))
        assertEquals(expected("CapabilityCardinality tokens"), CapabilityCardinality.entries.map { it.name })
        assertEquals(expected("CapabilityKey fields"), publicDataFields(CapabilityKey::class.java))
        assertEquals(expected("AgentCapabilities fields"), publicObjectFields(AgentCapabilities::class.java))
        assertEquals(expected("WorkspacePort methods"), publicMethodNames(WorkspacePort::class.java))
        assertEquals(expected("WorkspaceCallContext fields"), publicDataFields(WorkspaceCallContext::class.java))
        assertEquals(expected("WorkspaceExecutionResult fields"), publicDataFields(WorkspaceExecutionResult::class.java))
        assertEquals(
            expected("WorkspaceExecutionTelemetry fields"),
            publicDataFields(WorkspaceExecutionTelemetry::class.java),
        )
        assertEquals(
            expected("ModelClientException fields"),
            publicDataFields(cc.noharry.agent.kernel.api.model.ModelClientException::class.java),
        )
        assertEquals(expected("AgentProfile fields"), publicDataFields(AgentProfile::class.java))
        assertEquals(expected("AgentPluginDescriptor fields"), publicDataFields(AgentPluginDescriptor::class.java))
        assertEquals(expected("PluginVersion fields"), publicDataFields(PluginVersion::class.java))
        assertEquals(expected("KernelApiVersion fields"), publicDataFields(KernelApiVersion::class.java))
        assertEquals(expected("KernelApiRange fields"), publicDataFields(KernelApiRange::class.java))
        assertEquals(
            expected("KernelApiRange methods"),
            publicMethodNames(KernelApiRange::class.java).filter { it == "contains" },
        )
        assertEquals(expected("SessionCapabilityContext fields"), publicDataFields(SessionCapabilityContext::class.java))
        assertEquals(
            expected("SessionCapabilityFactory methods"),
            publicMethodNames(SessionCapabilityFactory::class.java),
        )
        assertEquals(expected("SessionEventLog methods"), publicMethodNames(SessionEventLog::class.java))
        assertEquals(expected("SessionEventLogHealth fields"), publicDataFields(SessionEventLogHealth::class.java))
        assertEquals(expected("SessionEventLogHealthStatus tokens"), SessionEventLogHealthStatus.entries.map { it.name })
        assertEquals(expected("SessionEventLogDurability tokens"), SessionEventLogDurability.entries.map { it.name })
        assertEquals(expected("SessionEvent fields"), publicDataFields(SessionEvent::class.java))
        assertEquals(expected("SessionEventAppend fields"), publicDataFields(SessionEventAppend::class.java))
        assertEquals(expected("SessionEventQuery fields"), publicDataFields(SessionEventQuery::class.java))
        assertEquals(expected("SessionEventPayload kinds"), publicNestedNames(SessionEventPayload::class.java))
        assertEquals(expected("SessionEventPayload fields"), publicNestedFieldSignatures(SessionEventPayload::class.java))
        assertEquals(expected("SessionJson kinds"), publicNestedNames(SessionJson::class.java))
        assertEquals(expected("SessionJson fields"), publicNestedFieldSignatures(SessionJson::class.java))
        assertEquals(expected("SessionAttachmentRef fields"), publicDataFields(SessionAttachmentRef::class.java))
        assertEquals(expected("SessionEventType tokens"), SessionEventType.entries.map { it.name })
    }

    private fun assertPolicySurface() {
        assertEquals(expected("PolicyRule methods"), publicMethodNames(PolicyRule::class.java))
        assertEquals(expected("PolicyAction variants"), publicNestedNames(PolicyAction::class.java))
        assertEquals(expected("PolicyAction fields"), publicNestedFieldSignatures(PolicyAction::class.java))
        assertEquals(expected("PolicyContext fields"), publicDataFields(PolicyContext::class.java))
        assertEquals(expected("PolicyDecision variants"), publicNestedNames(PolicyDecision::class.java))
        assertEquals(expected("PolicyDecision fields"), publicNestedFieldSignatures(PolicyDecision::class.java))
    }

    private fun assertModelTransportSurface() {
        assertEquals(expected("ModelRequest fields"), publicDataFields(ModelRequest::class.java))
        assertEquals(expected("AgentEvent variants"), publicNestedNames(AgentEvent::class.java))
        assertEquals(expected("AgentEvent fields"), publicNestedFieldSignatures(AgentEvent::class.java))
        assertEquals(expected("ModelMessage fields"), publicDataFields(ModelMessage::class.java))
        assertEquals(expected("ModelImage fields"), publicDataFields(ModelImage::class.java))
        assertEquals(expected("ModelEvent variants"), publicNestedNames(ModelEvent::class.java))
        assertEquals(expected("ModelEvent fields"), publicNestedFieldSignatures(ModelEvent::class.java))
        assertEquals(expected("HttpEvent variants"), publicNestedNames(HttpEvent::class.java))
        assertEquals(expected("HttpEvent fields"), publicNestedFieldSignatures(HttpEvent::class.java))
        assertEquals(
            expected("ConfirmationResumeResult tokens"),
            publicNestedNames(ConfirmationResumeResult::class.java),
        )
        assertEquals(
            expected("CancelPendingResult tokens"),
            publicNestedNames(CancelPendingResult::class.java),
        )
        assertEquals(
            expected("PostUserInputResult tokens"),
            publicNestedNames(PostUserInputResult::class.java),
        )
        assertEquals(
            expected("ProtocolRequestCloseLoopResult tokens"),
            publicNestedNames(ProtocolRequestCloseLoopResult::class.java),
        )
    }

    private fun assertRuntimeRecordSurface() {
        assertEquals(expected("RuntimeRunStatus tokens"), RuntimeRunStatus.entries.map { it.name })
        assertEquals(expected("RuntimeResult fields"), publicDataFields(RuntimeResult::class.java))
        assertEquals(expected("RuntimeRunSnapshot fields"), publicDataFields(RuntimeRunSnapshot::class.java))
        assertEquals(expected("RuntimeRunPhase tokens"), RuntimeRunPhase.entries.map { it.name })
        assertEquals(
            expected("RuntimeProtocolRequestStatus tokens"),
            RuntimeProtocolRequestStatus.entries.map { it.name },
        )
        assertEquals(expected("RuntimeEvidenceType tokens"), RuntimeEvidenceType.entries.map { it.name })
        assertEquals(expected("SuggestedNextAction tokens"), SuggestedNextAction.entries.map { it.name })
        assertEquals(expected("RuntimeErrorCode tokens"), RuntimeErrorCode.entries.map { it.name })
        assertEquals(expected("RuntimeErrorCode tokens"), RuntimeErrorCode.entries.map { it.wireCode })
    }

    private fun assertHookSurface() {
        assertEquals(expected("RuntimeHook methods"), publicMethodNames(RuntimeHook::class.java))
        assertEquals(expected("RuntimeHookRegistration fields"), publicDataFields(RuntimeHookRegistration::class.java))
        assertEquals(expected("HookFailurePolicy tokens"), HookFailurePolicy.entries.map { it.name })
        assertEquals(expected("ToolMatcher methods"), publicMethodNames(ToolMatcher::class.java))
        assertEquals(expected("ToolUse fields"), publicDataFields(ToolUse::class.java))
        assertEquals(expected("ToolSource tokens"), ToolSource.entries.map { it.name })
        assertEquals(expected("ToolUseResult fields"), publicDataFields(ToolUseResult::class.java))
        assertEquals(expected("HookContext fields"), publicDataFields(HookContext::class.java))
        assertEquals(expected("HookDecision variants"), publicNestedNames(HookDecision::class.java))
        assertEquals(expected("HookDecision fields"), publicNestedFieldSignatures(HookDecision::class.java))
    }

    private fun assertExecSurface() {
        assertEquals(expected("ExecEngine methods"), publicMethodNames(ExecEngine::class.java))
        assertEquals(expected("ModelClient methods"), publicMethodNames(ModelClient::class.java))
        assertEquals(expected("McpClientProvider methods"), publicMethodNames(McpClientProvider::class.java))
        assertEquals(expected("ToolProvider methods"), publicMethodNames(ToolProvider::class.java))
        assertEquals(
            expected("ToolCatalogCacheIdentity methods"),
            publicMethodNames(ToolCatalogCacheIdentity::class.java),
        )
        assertEquals(expected("ToolProviderStatus tokens"), ToolProviderStatus.entries.map { it.name })
        assertEquals(expected("ToolInitializeContext fields"), publicDataFields(ToolInitializeContext::class.java))
        assertEquals(expected("ToolInitializeResult fields"), publicDataFields(ToolInitializeResult::class.java))
        assertEquals(expected("ToolDescriptor fields"), publicDataFields(ToolDescriptor::class.java))
        assertEquals(expected("ToolCallContext fields"), publicDataFields(ToolCallContext::class.java))
        assertEquals(expected("ToolCallResult fields"), publicDataFields(ToolCallResult::class.java))
        assertEquals(expected("ToolCallTrace fields"), publicDataFields(ToolCallTrace::class.java))
        assertEquals(expected("ToolResourceDescriptor fields"), publicDataFields(ToolResourceDescriptor::class.java))
        assertEquals(expected("ToolPromptDescriptor fields"), publicDataFields(ToolPromptDescriptor::class.java))
        assertEquals(expected("ProviderConcurrencyMode tokens"), ProviderConcurrencyMode.entries.map { it.name })
        assertEquals(
            expected("ProviderConcurrencyDeclaration fields"),
            publicDataFields(ProviderConcurrencyDeclaration::class.java),
        )
        assertEquals(expected("ExecRequest fields"), publicDataFields(ExecRequest::class.java))
        assertEquals(expected("ExecToolBridge methods"), publicMethodNames(ExecToolBridge::class.java))
        assertEquals(expected("ExecResult fields"), publicDataFields(ExecResult::class.java))
    }

    private fun assertVfsSurface() {
        assertEquals(expected("VfsEntry fields"), publicDataFields(VfsEntry::class.java))
        assertEquals(expected("VfsReadResult variants"), publicNestedNames(VfsReadResult::class.java))
        assertEquals(expected("VfsReadResult fields"), publicNestedFieldSignatures(VfsReadResult::class.java))
        assertEquals(expected("VfsStat fields"), publicDataFields(VfsStat::class.java))
        assertEquals(expected("VfsStatResult variants"), publicNestedNames(VfsStatResult::class.java))
        assertEquals(expected("VfsStatResult fields"), publicNestedFieldSignatures(VfsStatResult::class.java))
        assertEquals(expected("VfsWriteMode tokens"), VfsWriteMode.entries.map { it.name })
        assertEquals(expected("VfsWriteResult variants"), publicNestedNames(VfsWriteResult::class.java))
        assertEquals(expected("VfsWriteResult fields"), publicNestedFieldSignatures(VfsWriteResult::class.java))
        assertEquals(expected("VfsSource methods"), publicMethodNames(VfsSource::class.java))
    }

    @Test
    fun protocolCatalogMatchesGoldenFixture() {
        assertEquals(
            resourceText("fixtures/protocol/agent_protocol_catalog.json"),
            AgentProtocolCatalog.canonicalJson(),
        )
    }

    private fun expected(group: String): List<String> =
        AgentProtocolCatalog.requireGroup(group)

    private fun publicMethodNames(type: Class<*>): List<String> =
        type.methods
            .filter { it.declaringClass == type }
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .map { it.name }
            .filterNot { "$" in it }
            .distinct()
            .sorted()

    private fun publicNestedNames(type: Class<*>): List<String> =
        type.declaredClasses
            .filter { Modifier.isPublic(it.modifiers) }
            .map { it.simpleName }
            .sorted()

    private fun publicDataFields(type: Class<*>): List<String> =
        type.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .map { it.name }
            .sorted()

    private fun publicObjectFields(type: Class<*>): List<String> =
        type.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .map { it.name }
            .filterNot { it == "INSTANCE" || "$" in it }
            .sorted()

    private fun publicNestedFieldSignatures(type: Class<*>): List<String> =
        type.declaredClasses
            .filter { Modifier.isPublic(it.modifiers) }
            .mapNotNull { nested ->
                val fields = publicDataFields(nested)
                if (fields.isEmpty()) null else "${nested.simpleName}:${fields.joinToString(",")}"
            }
            .sorted()

    private fun resourceText(path: String): String {
        val classLoader = requireNotNull(Thread.currentThread().contextClassLoader) {
            "Missing context class loader."
        }
        val stream = requireNotNull(classLoader.getResourceAsStream(path)) {
            "Missing test resource: $path"
        }
        return stream.bufferedReader().use { it.readText() }.replace("\r\n", "\n")
    }
}
