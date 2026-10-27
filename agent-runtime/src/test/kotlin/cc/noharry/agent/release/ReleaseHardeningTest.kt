package cc.noharry.agent.release

import cc.noharry.agent.kernel.api.AgentRuntime
import cc.noharry.agent.runtime.reliability.ANDROID_RELIABILITY_STORAGE_MIN_SDK
import cc.noharry.agent.runtime.reliability.ReliabilityStorageEncryptionMode
import cc.noharry.agent.runtime.reliability.ReliabilityStorageFileLockMode
import cc.noharry.agent.runtime.reliability.ReliabilityStorageHardeningPolicy
import cc.noharry.agent.runtime.reliability.ReliabilityStorageRootKind
import cc.noharry.agent.transport.OkHttpHttpTransport
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseHardeningTest {
    @Test
    fun consumerRulesKeepPublicApiAndMetadata() {
        val rules = File("src/main/resources/META-INF/proguard/agent-runtime.pro").readText()

        assertTrue(rules.contains("-keep class cc.noharry.agent.kernel.api.** { *; }"))
        assertTrue(rules.contains("-keep class cc.noharry.agent.kernel.core.plugin.KernelRuntimeBuilder { *; }"))
        assertTrue(rules.contains("-keep class cc.noharry.agent.kernel.core.plugin.CoreAgentRuntime { *; }"))
        assertTrue(rules.contains("-keep class cc.noharry.agent.runtime.AgentRuntimeBuilder { *; }"))
        assertTrue(rules.contains("-keep class cc.noharry.agent.runtime.RuntimeWorkspaceRef { *; }"))
        assertTrue(rules.contains("-keep class cc.noharry.agent.runtime.HostSkill { *; }"))
        assertTrue(rules.contains("-keep class cc.noharry.agent.runtime.NetworkToolConfirmationPolicy { *; }"))
        assertTrue(rules.contains("-keep class cc.noharry.agent.runtime.eventlog.DurableSessionEventLogPlugin { *; }"))
        assertTrue(rules.contains("-keep class cc.noharry.agent.dream.** { *; }"))
        assertTrue(rules.contains("-keep class cc.noharry.agent.mcp.** { *; }"))
        assertTrue(rules.contains("-keep class cc.noharry.agent.model.** { *; }"))
        assertTrue(rules.contains("-keep class cc.noharry.agent.policy.** { *; }"))
        assertTrue(rules.contains("-keep class cc.noharry.agent.transport.** { *; }"))
        assertTrue(rules.contains("-keep class cc.noharry.agent.vfs.** { *; }"))
        assertTrue(rules.contains("-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature"))
        assertFalse(rules.contains("\$\$serializer"))
    }

    @Test
    fun agentRuntimeBuilderConstructorIsNotPublic() {
        val publicConstructors = Class.forName("cc.noharry.agent.runtime.AgentRuntimeBuilder")
            .declaredConstructors
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
        assertTrue("Public AgentRuntimeBuilder constructors leaked: $publicConstructors", publicConstructors.isEmpty())
    }

    @Test
    fun minSdkAlignsWithCandidateReliabilityStoragePolicy() {
        val buildFile = File("../agent-runtime-android/build.gradle.kts").readText()

        assertEquals(23, ANDROID_RELIABILITY_STORAGE_MIN_SDK)
        assertTrue(buildFile.contains("minSdk = $ANDROID_RELIABILITY_STORAGE_MIN_SDK"))
    }

    @Test
    fun candidateReliabilityStoragePolicyIsExplicit() {
        val policy = ReliabilityStorageHardeningPolicy()

        assertEquals(ReliabilityStorageRootKind.APP_PRIVATE_NO_BACKUP, policy.rootKind)
        assertEquals(ReliabilityStorageFileLockMode.PER_OPERATION_ADVISORY, policy.fileLockMode)
        assertEquals(
            ReliabilityStorageEncryptionMode.HOST_APP_PRIVATE_STORAGE_REQUIRED,
            policy.encryptionMode,
        )
    }

    @Test
    fun publicRuntimeApiDoesNotExposeRawDebugTraceAccess() {
        val methodNames = AgentRuntime::class.java.methods
            .map { it.name.lowercase() }
            .filter { it.contains("raw") || it.contains("debug") }

        assertTrue("Public raw/debug trace API leaked: $methodNames", methodNames.isEmpty())
    }

    @Test
    fun publicTransportApiDoesNotExposeOkHttpTypes() {
        val publicConstructors = OkHttpHttpTransport::class.java.constructors
        val publicMethods = OkHttpHttpTransport::class.java.methods.filter(::isPublicDeclaredMethod)
        val exposedTypes = publicConstructors.flatMap { it.parameterTypes.toList() } +
            publicMethods.flatMap { method -> method.parameterTypes.toList() + method.returnType }

        assertFalse(exposedTypes.any { it.name.startsWith("okhttp3.") })
    }

    @Test
    fun candidatePublicApiClassRootsAreLoadable() {
        val missing = CANDIDATE_PUBLIC_API_CLASS_NAMES.filter { name ->
            runCatching { Class.forName(name) }.isFailure
        }
        assertTrue("Missing candidate public API classes: $missing", missing.isEmpty())
    }

    @Test
    fun kernelApiSourcePublicRootsMatchConfiguredInventory() {
        val sourceRoot = File("../agent-kernel-api/src/main/kotlin")
        assertTrue("kernel-api source root must exist: ${sourceRoot.absolutePath}", sourceRoot.isDirectory)
        val declared = sourcePublicRoots(sourceRoot).filter {
            it.startsWith("cc.noharry.agent.kernel.api.")
        }.toSet()
        val configured = CANDIDATE_PUBLIC_API_CLASS_NAMES
            .filter { it.startsWith("cc.noharry.agent.kernel.api.") }
            .toSet()
        val missing = (declared - configured).sorted()
        val stale = (configured - declared).sorted()
        assertTrue("kernel-api roots missing from ReleaseHardening inventory: $missing", missing.isEmpty())
        assertTrue("stale kernel-api roots in ReleaseHardening inventory: $stale", stale.isEmpty())
    }

    @Test
    fun sanctionedHostApiSourcePublicRootsMatchInventory() {
        val sourceRoot = File("src/main/kotlin")
        assertTrue("runtime source root must exist: ${sourceRoot.absolutePath}", sourceRoot.isDirectory)
        val declared = sourcePublicRoots(sourceRoot).filter { name ->
            SANCTIONED_HOST_API_PREFIXES.any(name::startsWith)
        }.toSet()
        val configured = CANDIDATE_PUBLIC_API_CLASS_NAMES.filter { name ->
            SANCTIONED_HOST_API_PREFIXES.any(name::startsWith)
        }.toSet()
        val missing = (declared - configured).sorted()
        val stale = (configured - declared).sorted()
        assertTrue("sanctioned host API roots missing from ReleaseHardening inventory: $missing", missing.isEmpty())
        assertTrue("stale sanctioned host API roots in ReleaseHardening inventory: $stale", stale.isEmpty())
    }

    @Test
    fun candidatePublicApiSurfaceDoesNotExposeForbiddenImplementationTypes() {
        val forbidden = candidatePublicApiRoots()
            .flatMap(::withNestedPublicClasses)
            .flatMap(::publicSignatureTypes)
            .map { it.normalizedTypeName() }
            .filter { typeName -> FORBIDDEN_PUBLIC_API_PREFIXES.any(typeName::startsWith) }
            .distinct()
            .sorted()

        assertTrue("Forbidden public API types leaked: $forbidden", forbidden.isEmpty())
    }

    private fun isPublicDeclaredMethod(method: java.lang.reflect.Method): Boolean =
        method.declaringClass == OkHttpHttpTransport::class.java &&
            Modifier.isPublic(method.modifiers)

    private fun candidatePublicApiRoots(): List<Class<*>> =
        CANDIDATE_PUBLIC_API_CLASS_NAMES.map { Class.forName(it) }

    private fun withNestedPublicClasses(type: Class<*>): List<Class<*>> =
        listOf(type) + type.declaredClasses
            .filter { Modifier.isPublic(it.modifiers) }
            .flatMap(::withNestedPublicClasses)

    private fun publicSignatureTypes(type: Class<*>): List<Class<*>> =
        publicConstructors(type).flatMap { it.parameterTypes.toList() } +
            publicMethods(type).flatMap { it.parameterTypes.toList() + it.returnType } +
            publicFields(type).map { it.type }

    private fun publicConstructors(type: Class<*>): List<Constructor<*>> =
        type.declaredConstructors.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }

    private fun publicMethods(type: Class<*>): List<Method> =
        type.declaredMethods.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }

    private fun publicFields(type: Class<*>): List<Field> =
        type.declaredFields.filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }

    private fun Class<*>.normalizedTypeName(): String =
        when {
            isArray -> componentType.normalizedTypeName()
            else -> name
        }
}

private val KERNEL_API_TYPE_DECL = Regex(
    """^(internal|private|protected)?\s*(?:(?:expect|actual|data|sealed|enum|value|fun|annotation|inner|open|final|abstract)\s+)*(?:class|interface|object)\s+([A-Za-z_][A-Za-z0-9_]*)\b""",
)
private val KERNEL_API_PACKAGE = Regex("""(?m)^package\s+([A-Za-z_][A-Za-z0-9_.]*)\s*$""")

private val SANCTIONED_HOST_API_PREFIXES = listOf(
    "cc.noharry.agent.dream.",
    "cc.noharry.agent.mcp.",
    "cc.noharry.agent.model.",
    "cc.noharry.agent.policy.",
    "cc.noharry.agent.transport.",
)

private fun sourcePublicRoots(sourceRoot: File): Set<String> {
    val roots = linkedSetOf<String>()
    val files = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }
    fileLoop@ for (file in files) {
        val text = file.readText()
        val packageName = KERNEL_API_PACKAGE.find(text)?.groupValues?.get(1) ?: continue@fileLoop
        lineLoop@ for (raw in text.lineSequence()) {
            val line = raw.substringBefore("//").trimEnd()
            if (line.isEmpty() || line.first().isWhitespace() || line.startsWith("@")) {
                continue@lineLoop
            }
            val match = KERNEL_API_TYPE_DECL.find(line) ?: continue@lineLoop
            if (match.groupValues[1].isNotEmpty()) {
                continue@lineLoop
            }
            roots += "$packageName.${match.groupValues[2]}"
        }
    }
    return roots
}

private val FORBIDDEN_PUBLIC_API_PREFIXES = listOf(
    "okhttp3.",
)

private val CANDIDATE_PUBLIC_API_CLASS_NAMES = listOf(
    "cc.noharry.agent.kernel.api.AgentRuntime",
    "cc.noharry.agent.kernel.api.AgentRequest",
    "cc.noharry.agent.kernel.api.AgentEvent",
    "cc.noharry.agent.kernel.api.AgentCapabilities",
    "cc.noharry.agent.kernel.api.AgentPlugin",
    "cc.noharry.agent.kernel.api.AgentPluginDescriptor",
    "cc.noharry.agent.kernel.api.AgentPluginScope",
    "cc.noharry.agent.kernel.api.AgentProfile",
    "cc.noharry.agent.kernel.api.CapabilityCardinality",
    "cc.noharry.agent.kernel.api.CapabilityKey",
    "cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi",
    "cc.noharry.agent.kernel.api.CompletionImpact",
    "cc.noharry.agent.kernel.api.ContextStability",
    "cc.noharry.agent.kernel.api.PluginContractMessages",
    "cc.noharry.agent.kernel.api.PostUserInputRequest",
    "cc.noharry.agent.kernel.api.PostUserInputResult",
    "cc.noharry.agent.kernel.api.ProbeEmitterRegistration",
    "cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration",
    "cc.noharry.agent.kernel.api.ProviderConcurrencyMode",
    "cc.noharry.agent.kernel.api.RuntimeActionOutcome",
    "cc.noharry.agent.kernel.api.RuntimeActionStatus",
    "cc.noharry.agent.kernel.api.RuntimeCommitStatus",
    "cc.noharry.agent.kernel.api.RuntimeCompletedTurn",
    "cc.noharry.agent.kernel.api.RuntimeContextBlock",
    "cc.noharry.agent.kernel.api.RuntimeContextContribution",
    "cc.noharry.agent.kernel.api.RuntimeContextDatum",
    "cc.noharry.agent.kernel.api.RuntimeContextDatumKind",
    "cc.noharry.agent.kernel.api.RuntimeContextProvider",
    "cc.noharry.agent.kernel.api.RuntimeContextRequest",
    "cc.noharry.agent.kernel.api.RuntimeContextScalar",
    "cc.noharry.agent.kernel.api.RuntimeEffectKind",
    "cc.noharry.agent.kernel.api.RuntimeEvidenceConfig",
    "cc.noharry.agent.kernel.api.RuntimeObserverActionPreflight",
    "cc.noharry.agent.kernel.api.RuntimeProbeEmitterHub",
    "cc.noharry.agent.kernel.api.RuntimeProbeStageEmitter",
    "cc.noharry.agent.kernel.api.RuntimeTurnInput",
    "cc.noharry.agent.kernel.api.RuntimeTurnObserver",
    "cc.noharry.agent.kernel.api.SessionEvent",
    "cc.noharry.agent.kernel.api.SessionEventAppend",
    "cc.noharry.agent.kernel.api.SessionEventLog",
    "cc.noharry.agent.kernel.api.SessionEventLogDurability",
    "cc.noharry.agent.kernel.api.SessionEventLogHealth",
    "cc.noharry.agent.kernel.api.SessionEventLogHealthStatus",
    "cc.noharry.agent.kernel.api.SessionEventPayload",
    "cc.noharry.agent.kernel.api.SessionEventQuery",
    "cc.noharry.agent.kernel.api.SessionEventType",
    "cc.noharry.agent.kernel.api.SessionJson",
    "cc.noharry.agent.kernel.api.SessionAttachmentRef",
    "cc.noharry.agent.kernel.api.SessionRunConflictPolicy",
    "cc.noharry.agent.kernel.api.TurnInputObservation",
    "cc.noharry.agent.kernel.api.TurnObservationReceipt",
    "cc.noharry.agent.kernel.api.ConfirmationDecision",
    "cc.noharry.agent.kernel.api.ConfirmationChoice",
    "cc.noharry.agent.kernel.api.PendingConfirmation",
    "cc.noharry.agent.kernel.api.CancelPendingConfirmationRequest",
    "cc.noharry.agent.kernel.api.ConfirmationResumeResult",
    "cc.noharry.agent.kernel.api.CancelPendingResult",
    "cc.noharry.agent.kernel.api.ExecEngine",
    "cc.noharry.agent.kernel.api.ExecRequest",
    "cc.noharry.agent.kernel.api.ExecResult",
    "cc.noharry.agent.kernel.api.ExecToolBridge",
    "cc.noharry.agent.kernel.api.HookContext",
    "cc.noharry.agent.kernel.api.HookDecision",
    "cc.noharry.agent.kernel.api.HookFailurePolicy",
    "cc.noharry.agent.kernel.api.KernelApiRange",
    "cc.noharry.agent.kernel.api.KernelApiVersion",
    "cc.noharry.agent.kernel.api.KernelApiVersions",
    "cc.noharry.agent.kernel.api.PluginVersion",
    "cc.noharry.agent.kernel.api.ProtocolRequestResolution",
    "cc.noharry.agent.kernel.api.ProtocolRequestRejection",
    "cc.noharry.agent.kernel.api.ProtocolRequestCancellation",
    "cc.noharry.agent.kernel.api.ProtocolRequestCloseLoopResult",
    "cc.noharry.agent.kernel.api.RuntimeHook",
    "cc.noharry.agent.kernel.api.RuntimeHookRegistration",
    "cc.noharry.agent.kernel.api.SessionCapabilityContext",
    "cc.noharry.agent.kernel.api.SessionCapabilityFactory",
    "cc.noharry.agent.kernel.api.ToolCallContext",
    "cc.noharry.agent.kernel.api.ToolCallResult",
    "cc.noharry.agent.kernel.api.ToolCallTrace",
    "cc.noharry.agent.kernel.api.ToolCatalogCacheIdentity",
    "cc.noharry.agent.kernel.api.ToolDescriptor",
    "cc.noharry.agent.kernel.api.ToolPrefetchProvider",
    "cc.noharry.agent.kernel.api.ToolPrefetchRequest",
    "cc.noharry.agent.kernel.api.ToolPrefetchTrace",
    "cc.noharry.agent.kernel.api.ToolInitializeContext",
    "cc.noharry.agent.kernel.api.ToolInitializeResult",
    "cc.noharry.agent.kernel.api.ToolMatcher",
    "cc.noharry.agent.kernel.api.ToolPromptDescriptor",
    "cc.noharry.agent.kernel.api.ToolProvider",
    "cc.noharry.agent.kernel.api.ToolProviderStatus",
    "cc.noharry.agent.kernel.api.ToolResourceDescriptor",
    "cc.noharry.agent.kernel.api.ToolSource",
    "cc.noharry.agent.kernel.api.ToolUse",
    "cc.noharry.agent.kernel.api.ToolUseResult",
    "cc.noharry.agent.kernel.api.WorkspaceCallContext",
    "cc.noharry.agent.kernel.api.WorkspaceExecutionResult",
    "cc.noharry.agent.kernel.api.WorkspaceExecutionTelemetry",
    "cc.noharry.agent.kernel.api.WorkspacePort",
    "cc.noharry.agent.dream.DreamWorker",
    "cc.noharry.agent.dream.DreamRequest",
    "cc.noharry.agent.dream.DreamMode",
    "cc.noharry.agent.dream.DreamResult",
    "cc.noharry.agent.dream.NoOpDreamWorker",
    "cc.noharry.agent.dream.MemoryIndexRebuilder",
    "cc.noharry.agent.policy.PolicyRule",
    "cc.noharry.agent.policy.PolicyAction",
    "cc.noharry.agent.policy.PolicyContext",
    "cc.noharry.agent.policy.PolicyDecision",
    "cc.noharry.agent.kernel.api.mcp.McpClientProvider",
    "cc.noharry.agent.kernel.api.model.ModelClient",
    "cc.noharry.agent.kernel.api.model.ModelClientException",
    "cc.noharry.agent.kernel.api.model.ModelImage",
    "cc.noharry.agent.kernel.api.model.ModelRef",
    "cc.noharry.agent.kernel.api.model.ModelRequest",
    "cc.noharry.agent.kernel.api.model.ModelResponseMetadata",
    "cc.noharry.agent.kernel.api.model.ModelMessage",
    "cc.noharry.agent.kernel.api.model.ModelTool",
    "cc.noharry.agent.kernel.api.model.ModelEvent",
    "cc.noharry.agent.kernel.api.model.ModelVariant",
    "cc.noharry.agent.kernel.api.model.ServerSideSearchCapability",
    "cc.noharry.agent.mcp.McpClientMessages",
    "cc.noharry.agent.mcp.McpConnectResult",
    "cc.noharry.agent.mcp.McpHttpClient",
    "cc.noharry.agent.mcp.McpRpcClient",
    "cc.noharry.agent.mcp.McpRpcResponse",
    "cc.noharry.agent.mcp.McpStatus",
    "cc.noharry.agent.mcp.McpToolCatalog",
    "cc.noharry.agent.mcp.McpToolProvider",
    "cc.noharry.agent.mcp.McpToolsListing",
    "cc.noharry.agent.model.OpenAiModelClient",
    "cc.noharry.agent.model.AnthropicModelClient",
    "cc.noharry.agent.model.ModelVariantRegistry",
    "cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus",
    "cc.noharry.agent.kernel.api.reliability.RuntimeRunSnapshot",
    "cc.noharry.agent.kernel.api.reliability.RuntimeRunEnvelope",
    "cc.noharry.agent.kernel.api.reliability.RuntimeRunPhase",
    "cc.noharry.agent.kernel.api.reliability.RuntimeWorkpad",
    "cc.noharry.agent.kernel.api.reliability.CompletionItem",
    "cc.noharry.agent.kernel.api.reliability.RuntimeHandoff",
    "cc.noharry.agent.kernel.api.reliability.RuntimeResult",
    "cc.noharry.agent.kernel.api.reliability.RuntimeBlocker",
    "cc.noharry.agent.kernel.api.reliability.OpenConfusion",
    "cc.noharry.agent.kernel.api.reliability.RuntimeProtocolRequest",
    "cc.noharry.agent.kernel.api.reliability.RuntimeProtocolRequestStatus",
    "cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode",
    "cc.noharry.agent.kernel.api.reliability.RuntimeEvidence",
    "cc.noharry.agent.kernel.api.reliability.RuntimeEvidenceType",
    "cc.noharry.agent.kernel.api.reliability.RuntimeTraceEvent",
    "cc.noharry.agent.kernel.api.reliability.SuggestedNextAction",
    "cc.noharry.agent.kernel.api.reliability.RuntimeCandidate",
    "cc.noharry.agent.kernel.api.reliability.RuntimeCandidateType",
    "cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome",
    "cc.noharry.agent.kernel.api.reliability.RuntimeCompletionSummary",
    "cc.noharry.agent.runtime.AgentRuntimeBuilder",
    "cc.noharry.agent.runtime.RuntimeWorkspaceRef",
    "cc.noharry.agent.runtime.HostSkill",
    "cc.noharry.agent.runtime.NetworkToolConfirmationPolicy",
    "cc.noharry.agent.runtime.eventlog.DurableSessionEventLogPlugin",
    "cc.noharry.agent.kernel.core.plugin.KernelRuntimeBuilder",
    "cc.noharry.agent.kernel.core.plugin.CoreAgentRuntime",
    "cc.noharry.agent.vfs.VfsFileSystem",
    "cc.noharry.agent.transport.HttpTransport",
    "cc.noharry.agent.transport.HttpRequest",
    "cc.noharry.agent.transport.HttpResponse",
    "cc.noharry.agent.transport.HttpEvent",
    "cc.noharry.agent.transport.OkHttpHttpTransport",
)
