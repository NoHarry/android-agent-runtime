package cc.noharry.agent.release

import cc.noharry.agent.AgentRuntime
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

        assertTrue(rules.contains("-keep class cc.noharry.agent.spi.** { *; }"))
        assertTrue(rules.contains("-keep class cc.noharry.agent.runtime.AgentRuntimeBuilder { *; }"))
        assertTrue(rules.contains("-keep class cc.noharry.agent.runtime.RuntimeInspection { *; }"))
        assertTrue(rules.contains("-keep class cc.noharry.agent.runtime.RuntimeWorkspaceRef { *; }"))
        assertTrue(rules.contains("-keep class cc.noharry.agent.runtime.HostSkill { *; }"))
        assertTrue(rules.contains("-keep class cc.noharry.agent.runtime.NetworkToolConfirmationPolicy { *; }"))
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
        val sourceRoot = File("../agent-api/src/main/kotlin")
        assertTrue("kernel-api source root must exist: ${sourceRoot.absolutePath}", sourceRoot.isDirectory)
        val declared = sourcePublicRoots(sourceRoot).filter {
            it.startsWith("cc.noharry.agent.spi.") && !it.startsWith("cc.noharry.agent.spi.reliability.")
        }.toSet()
        val configured = CANDIDATE_PUBLIC_API_CLASS_NAMES
            .filter { it.startsWith("cc.noharry.agent.spi.") && !it.startsWith("cc.noharry.agent.spi.reliability.") }
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
    "cc.noharry.agent.AgentRuntime",
    "cc.noharry.agent.AgentThread",
    "cc.noharry.agent.Approval",
    "cc.noharry.agent.ApprovalPolicy",
    "cc.noharry.agent.ApprovalScope",
    "cc.noharry.agent.ApprovalState",
    "cc.noharry.agent.InputPart",
    "cc.noharry.agent.ItemStatus",
    "cc.noharry.agent.MessagePhase",
    "cc.noharry.agent.NetworkActivity",
    "cc.noharry.agent.NoticeKind",
    "cc.noharry.agent.PendingRequest",
    "cc.noharry.agent.Question",
    "cc.noharry.agent.RespondResult",
    "cc.noharry.agent.RuntimeDiagnostics",
    "cc.noharry.agent.SteerResult",
    "cc.noharry.agent.ThreadEvent",
    "cc.noharry.agent.ThreadInfo",
    "cc.noharry.agent.ThreadItem",
    "cc.noharry.agent.ThreadOptions",
    "cc.noharry.agent.ThreadStatus",
    "cc.noharry.agent.ToolCatalogDomain",
    "cc.noharry.agent.ToolCatalogEntry",
    "cc.noharry.agent.ToolCatalogSnapshot",
    "cc.noharry.agent.TurnError",
    "cc.noharry.agent.TurnInput",
    "cc.noharry.agent.TurnOptions",
    "cc.noharry.agent.TurnResult",
    "cc.noharry.agent.TurnStatus",
    "cc.noharry.agent.Usage",
    "cc.noharry.agent.mcp.McpClientMessages",
    "cc.noharry.agent.mcp.McpConnectResult",
    "cc.noharry.agent.mcp.McpHttpClient",
    "cc.noharry.agent.mcp.McpRpcClient",
    "cc.noharry.agent.mcp.McpRpcResponse",
    "cc.noharry.agent.mcp.McpStatus",
    "cc.noharry.agent.mcp.McpToolCatalog",
    "cc.noharry.agent.mcp.McpToolProvider",
    "cc.noharry.agent.mcp.McpToolsListing",
    "cc.noharry.agent.model.AnthropicModelClient",
    "cc.noharry.agent.model.ModelVariantRegistry",
    "cc.noharry.agent.model.OpenAiModelClient",
    "cc.noharry.agent.model.ServerSideSearchCapability",
    "cc.noharry.agent.policy.PolicyAction",
    "cc.noharry.agent.policy.PolicyContext",
    "cc.noharry.agent.policy.PolicyDecision",
    "cc.noharry.agent.policy.PolicyRule",
    "cc.noharry.agent.runtime.AgentRuntimeBuilder",
    "cc.noharry.agent.runtime.BackendTools",
    "cc.noharry.agent.runtime.DefaultTraceRedactor",
    "cc.noharry.agent.runtime.FunctionToolsBuilder",
    "cc.noharry.agent.runtime.HostSkill",
    "cc.noharry.agent.runtime.LoopLimits",
    "cc.noharry.agent.runtime.LoopSettings",
    "cc.noharry.agent.runtime.MemoryTopic",
    "cc.noharry.agent.runtime.NetworkToolConfirmationPolicy",
    "cc.noharry.agent.runtime.ProtocolRules",
    "cc.noharry.agent.runtime.RuntimeConcurrency",
    "cc.noharry.agent.runtime.RuntimeInspection",
    "cc.noharry.agent.runtime.RuntimeMemoryPolicy",
    "cc.noharry.agent.runtime.RuntimeMemoryRetriever",
    "cc.noharry.agent.runtime.RuntimeWorkspaceRef",
    "cc.noharry.agent.runtime.SessionHistory",
    "cc.noharry.agent.runtime.SessionToolActivations",
    "cc.noharry.agent.runtime.StandardTraceRedactor",
    "cc.noharry.agent.runtime.SubagentConfig",
    "cc.noharry.agent.runtime.SubagentLauncher",
    "cc.noharry.agent.runtime.SubagentReport",
    "cc.noharry.agent.runtime.SubagentSpec",
    "cc.noharry.agent.runtime.SubagentState",
    "cc.noharry.agent.runtime.SubagentStatus",
    "cc.noharry.agent.runtime.SubagentType",
    "cc.noharry.agent.runtime.Subagents",
    "cc.noharry.agent.runtime.ToolDomainNaming",
    "cc.noharry.agent.runtime.ToolDomainTool",
    "cc.noharry.agent.runtime.TraceRedactor",
    "cc.noharry.agent.runtime.WebFallbackPage",
    "cc.noharry.agent.runtime.WebFetchFallback",
    "cc.noharry.agent.runtime.WebSearchProvider",
    "cc.noharry.agent.runtime.WebSearchResult",
    "cc.noharry.agent.spi.CompletionImpact",
    "cc.noharry.agent.spi.ContextStability",
    "cc.noharry.agent.spi.ExecEngine",
    "cc.noharry.agent.spi.ExecRequest",
    "cc.noharry.agent.spi.ExecResult",
    "cc.noharry.agent.spi.ExecToolBridge",
    "cc.noharry.agent.spi.ExperimentalAgentRuntimeApi",
    "cc.noharry.agent.spi.HookContext",
    "cc.noharry.agent.spi.HookDecision",
    "cc.noharry.agent.spi.HookFailurePolicy",
    "cc.noharry.agent.spi.InternalAgentRuntimeApi",
    "cc.noharry.agent.spi.NarrationCheck",
    "cc.noharry.agent.spi.ProviderConcurrencyDeclaration",
    "cc.noharry.agent.spi.ProviderConcurrencyMode",
    "cc.noharry.agent.spi.RuntimeActionOutcome",
    "cc.noharry.agent.spi.RuntimeActionStatus",
    "cc.noharry.agent.spi.RuntimeCommitStatus",
    "cc.noharry.agent.spi.RuntimeCompletedTurn",
    "cc.noharry.agent.spi.RuntimeContextBlock",
    "cc.noharry.agent.spi.RuntimeContextContribution",
    "cc.noharry.agent.spi.RuntimeContextDatum",
    "cc.noharry.agent.spi.RuntimeContextDatumKind",
    "cc.noharry.agent.spi.RuntimeContextProvider",
    "cc.noharry.agent.spi.RuntimeContextRequest",
    "cc.noharry.agent.spi.RuntimeContextScalar",
    "cc.noharry.agent.spi.RuntimeEffectKind",
    "cc.noharry.agent.spi.RuntimeEvidenceConfig",
    "cc.noharry.agent.spi.RuntimeHook",
    "cc.noharry.agent.spi.RuntimeObserverActionPreflight",
    "cc.noharry.agent.spi.RuntimeTurnInput",
    "cc.noharry.agent.spi.RuntimeTurnObserver",
    "cc.noharry.agent.spi.ServiceAnswerCheck",
    "cc.noharry.agent.spi.SessionAttachmentRef",
    "cc.noharry.agent.spi.SessionEvent",
    "cc.noharry.agent.spi.SessionEventAppend",
    "cc.noharry.agent.spi.SessionEventLog",
    "cc.noharry.agent.spi.SessionEventLogDurability",
    "cc.noharry.agent.spi.SessionEventLogHealth",
    "cc.noharry.agent.spi.SessionEventLogHealthStatus",
    "cc.noharry.agent.spi.SessionEventPayload",
    "cc.noharry.agent.spi.SessionEventQuery",
    "cc.noharry.agent.spi.SessionEventType",
    "cc.noharry.agent.spi.SessionJson",
    "cc.noharry.agent.spi.SessionRunConflictPolicy",
    "cc.noharry.agent.spi.ToolCallContext",
    "cc.noharry.agent.spi.ToolCallResult",
    "cc.noharry.agent.spi.ToolCallTrace",
    "cc.noharry.agent.spi.ToolCatalogCacheIdentity",
    "cc.noharry.agent.spi.ToolDescriptor",
    "cc.noharry.agent.spi.ToolInitializeContext",
    "cc.noharry.agent.spi.ToolInitializeResult",
    "cc.noharry.agent.spi.ToolMatcher",
    "cc.noharry.agent.spi.ToolPrefetchProvider",
    "cc.noharry.agent.spi.ToolPrefetchRequest",
    "cc.noharry.agent.spi.ToolPrefetchTrace",
    "cc.noharry.agent.spi.ToolPromptDescriptor",
    "cc.noharry.agent.spi.ToolProvider",
    "cc.noharry.agent.spi.ToolProviderStatus",
    "cc.noharry.agent.spi.ToolResourceDescriptor",
    "cc.noharry.agent.spi.ToolSource",
    "cc.noharry.agent.spi.ToolUse",
    "cc.noharry.agent.spi.ToolUseResult",
    "cc.noharry.agent.spi.TurnInputObservation",
    "cc.noharry.agent.spi.TurnObservationReceipt",
    "cc.noharry.agent.spi.mcp.McpClientProvider",
    "cc.noharry.agent.spi.model.ModelClient",
    "cc.noharry.agent.spi.model.ModelClientException",
    "cc.noharry.agent.spi.model.ModelEvent",
    "cc.noharry.agent.spi.model.ModelImage",
    "cc.noharry.agent.spi.model.ModelMessage",
    "cc.noharry.agent.spi.model.ModelRef",
    "cc.noharry.agent.spi.model.ModelRequest",
    "cc.noharry.agent.spi.model.ModelResponseMetadata",
    "cc.noharry.agent.spi.model.ModelTool",
    "cc.noharry.agent.spi.model.ModelVariant",
    "cc.noharry.agent.spi.reliability.RuntimeCompletionOutcome",
    "cc.noharry.agent.spi.reliability.RuntimeCompletionSummary",
    "cc.noharry.agent.spi.reliability.RuntimeErrorCode",
    "cc.noharry.agent.spi.reliability.RuntimeEvidence",
    "cc.noharry.agent.spi.reliability.RuntimeEvidenceType",
    "cc.noharry.agent.spi.reliability.RuntimeHandoff",
    "cc.noharry.agent.spi.reliability.RuntimeResult",
    "cc.noharry.agent.spi.reliability.RuntimeRunSnapshot",
    "cc.noharry.agent.spi.reliability.RuntimeRunStatus",
    "cc.noharry.agent.spi.reliability.RuntimeTraceEvent",
    "cc.noharry.agent.spi.reliability.SuggestedNextAction",
    "cc.noharry.agent.transport.HttpEvent",
    "cc.noharry.agent.transport.HttpRequest",
    "cc.noharry.agent.transport.HttpResponse",
    "cc.noharry.agent.transport.HttpTransport",
    "cc.noharry.agent.transport.OkHttpHttpTransport",
    "cc.noharry.agent.vfs.VfsFileSystem",
)
