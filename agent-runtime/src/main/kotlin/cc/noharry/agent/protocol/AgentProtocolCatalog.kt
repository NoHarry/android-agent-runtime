package cc.noharry.agent.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Candidate single source for the current agent-runtime API and wire token inventory.
 *
 * This catalog is mechanical evidence only. It is not an API-freeze approval, and
 * any change to these groups still requires owner review before freeze closure.
 */
internal object AgentProtocolCatalog {
    const val SCHEMA_VERSION: Int = 1
    const val CATALOG_ID: String = "agent-runtime-protocol-candidate-v1"

    val ownerReviewNotes: List<String> = listOf(
        "This report is candidate evidence only; it is not API freeze or owner sign-off.",
        "ProtocolRequest close-loop method and DTO names still require SDK Owner approval.",
        "AgentEvent.Failed.code and RuntimeBlocker.code remain string error-code surfaces.",
        "RuntimeErrorCode is a candidate taxonomy; owner review is still required before API freeze.",
        "AgentRequest, hook/policy, ModelRequest, ModelEvent and RuntimeResult fields are candidate surfaces; drift requires owner review.",
        "ModelRequest.promptCacheKey and ModelRequest.toolChoice are experimental candidate fields; they are not frozen stable API.",
        "Kernel plugin, Minimal Agent, and SessionEventLog surfaces are Phase 0 candidates; host-packaged code only and no API freeze is implied.",
    )

    val inventory: Map<String, List<String>> = linkedMapOf(
        "AgentRuntime methods" to tokens(
            """
            cancelPendingConfirmation cancelRuntimeProtocolRequest close getOpenConfusion getPendingConfirmation
            getPendingProtocolRequests getRunState getRuntimeEvidence getRuntimeHandoff getRuntimeResult
            getRuntimeTraceEvents getSessionEventLogHealth postUserInput querySessionEvents rejectRuntimeProtocolRequest replaySessionEvents
            resolveRuntimeProtocolRequest resumeConfirmation resumeUserInput run subscribeSessionEvents
            """,
        ),
        "AgentRuntimeBuilder methods" to tokens(
            """
            agentsMd build buildAsync contextProvider contextWindow create defaultWorkspace deferredBackendTools disabledNativeTools execEngine hook hookTimeoutMillis imageInput loopBudget
            modelClient modelConcurrency modelRetry nativeDeferredTools nativeWebFetchEnabled networkConfirmationPolicy nowEpochMillis
            plugin policyConfig policyConfigJson policyConfigPath policyRule profile runContext runContextAsSystem runtimeEvidence sceneAgentsMd
            sessionActivationInheritance sessionConcurrency sessionEventLog sessionEventLogRoot sessionHistory sessionStore sessionToolActivations skill subagents systemPrompt toolConcurrency toolProvider toolProviders turnObserver virtualWorkspace webFetchFallback webSearch workspace
            workspaceRoot
            """,
        ),
        "AgentPlugin methods" to tokens("getDescriptor getId install"),
        "AgentPluginScope methods" to tokens("own provide resolve resolveAll"),
        "CapabilityCardinality tokens" to tokens("SINGLE MULTIPLE"),
        "CapabilityKey fields" to tokens("cardinality name"),
        "AgentCapabilities fields" to tokens(
            "EXEC_ENGINE MODEL_CLIENT RUNTIME_CONTEXT_PROVIDER RUNTIME_HOOK RUNTIME_TURN_OBSERVER SESSION_EVENT_LOG TOOL_PROVIDER WORKSPACE",
        ),
        "AgentProfile fields" to tokens("id kernelApiVersion"),
        "AgentPluginDescriptor fields" to tokens("id kernelApiRange optionalRequires pluginVersion provides requires"),
        "PluginVersion fields" to tokens("major minor patch"),
        "KernelApiVersion fields" to tokens("major minor"),
        "KernelApiRange fields" to tokens("maxInclusive min"),
        "KernelApiRange methods" to tokens("contains"),
        "SessionCapabilityContext fields" to tokens("sessionId"),
        "SessionCapabilityFactory methods" to tokens("create"),
        "SessionEventLog methods" to tokens("append durability health putAttachment query readAttachment replay subscribe"),
        "SessionEventLogHealth fields" to tokens(
            "appendedCount attemptedCount backendDurability failedCount firstFailureCode lastAcceptedSequence lastPersistedSequence recorderCoverageVersion status",
        ),
        "SessionEventLogHealthStatus tokens" to tokens("COMPLETE DEGRADED UNAVAILABLE VOLATILE UNKNOWN"),
        "SessionEventLogDurability tokens" to tokens("DURABLE VOLATILE UNKNOWN"),
        "SessionEvent fields" to tokens(
            "eventId payload recordedAtEpochMillis requestId runId schemaVersion sequence sessionId sourceEventSequences type",
        ),
        "SessionEventAppend fields" to tokens("payload requestId runId sessionId sourceEventSequences type"),
        "SessionEventQuery fields" to tokens("afterSequenceExclusive limit requestId runId sessionId types"),
        "SessionEventPayload kinds" to tokens(
            "AssistantChunk AssistantMessage Empty ModelCallFinished ModelRequest ModelResponseMetadata RunLifecycle ToolCall ToolResult Usage UserInteraction UserMessage",
        ),
        "SessionEventPayload fields" to tokens(
            """
            AssistantChunk:kind,metadata,signature,text
            AssistantMessage:finishReason,sourceChunkSequences,text,toolCalls,usage
            ModelCallFinished:callId,durationMs,errorCode,eventCount,messageCount,modelRef,source,status,toolCount
            ModelRequest:attachments,messages,modelRef,options,promptCacheKey,systemPrompt,toolChoice,tools
            ModelResponseMetadata:fields
            RunLifecycle:errorCode,modelRef,phase,scene,status,todoCompletion
            ToolCall:arguments,name,schemaRef,source,todoCompletion,toolCallId
            ToolResult:attachments,code,name,ok,result,retryable,source,status,todoCompletion,toolCallId
            Usage:cacheReadTokens,cacheWriteTokens,extras,inputTokens,outputTokens,reasoningTokens
            UserInteraction:inputRequestId,kind,options,questionId,reason,state,text
            UserMessage:attachments,text
            """,
        ),
        "SessionJson kinds" to tokens("Array Bool Null Number Object Text"),
        "SessionJson fields" to tokens("Array:values Bool:value Number:literal Object:values Text:value"),
        "SessionAttachmentRef fields" to tokens("byteSize logicalName mediaType sha256"),
        "SessionEventType tokens" to tokens(
            "RUN_STARTED USER_MESSAGE USER_INTERACTION MODEL_REQUEST ASSISTANT_CHUNK ASSISTANT_MESSAGE MODEL_TOOL_CALL TOOL_STARTED TOOL_RESULT USAGE MODEL_RESPONSE_METADATA MODEL_CALL_FINISHED RUN_STATUS RUN_FINISHED",
        ),
        "MinimalAgent methods" to tokens("build builder"),
        "AgentRequest fields" to tokens("currentStateKeys input modelRef requestId resumeRunId scene sessionId sessionRunConflictPolicy workspaceId"),
        "SessionRunConflictPolicy tokens" to tokens("RESUME_CURRENT QUEUE_NEW CANCEL_AND_REPLACE"),
        "RuntimeWorkspaceRef fields" to tokens("fileSystem workspaceId"),
        "ExperimentalAgentRuntimeApi annotations" to tokens(
            """
            AgentCapabilities AgentPlugin AgentPluginDescriptor AgentPluginScope AgentProfile
            AgentRuntime.getSessionEventLogHealth AgentRuntime.querySessionEvents AgentRuntime.replaySessionEvents
            AgentRuntime.subscribeSessionEvents
            AgentRuntimeBuilder.contextProvider AgentRuntimeBuilder.deferredBackendTools AgentRuntimeBuilder.execEngine AgentRuntimeBuilder.hook
            AgentRuntimeBuilder.hookTimeoutMillis AgentRuntimeBuilder.nativeDeferredTools AgentRuntimeBuilder.nowEpochMillis
            AgentRuntimeBuilder.plugin AgentRuntimeBuilder.profile AgentRuntimeBuilder.runContext
            AgentRuntimeBuilder.runContextAsSystem AgentRuntimeBuilder.runtimeEvidence
            AgentRuntimeBuilder.sessionEventLog AgentRuntimeBuilder.sessionEventLogRoot
            AgentRuntimeBuilder.sessionHistory AgentRuntimeBuilder.sessionStore AgentRuntimeBuilder.sessionToolActivations AgentRuntimeBuilder.subagents
            AgentRuntimeBuilder.turnObserver AgentRuntimeBuilder.webFetchFallback AgentRuntimeBuilder.webSearch
            CapabilityCardinality CapabilityKey ExecEngine ExecRequest ExecResult ExecToolBridge HookDecision
            HookFailurePolicy KernelApiRange KernelApiVersion KernelApiVersions MinimalAgent
            ModelMessage.assistantText ModelMessage.toolAdditions ModelRequest.promptCacheKey ModelRequest.toolChoice ModelTool.deferred ModelTool.description PluginVersion
            ProbeEmitterRegistration
            ProviderConcurrencyDeclaration ProviderConcurrencyMode
            RuntimeContextProvider RuntimeHook RuntimeHookRegistration RuntimeProbeEmitterHub RuntimeProbeStageEmitter
            RuntimeObserverActionPreflight RuntimeTurnObserver SessionAttachmentRef SessionCapabilityContext
            SessionCapabilityFactory SessionEvent SessionEventAppend SessionEventLog SessionEventLogDurability
            SessionEventLogHealth SessionEventLogHealthStatus SessionEventPayload SessionEventQuery
            SessionEventType SessionJson ToolMatcher
            WorkspaceCallContext WorkspaceExecutionResult WorkspaceExecutionTelemetry WorkspacePort
            """,
        ),
        "WorkspacePort methods" to tokens("execute"),
        "WorkspaceCallContext fields" to tokens("modelRef requestId runId scene sessionId"),
        "WorkspaceExecutionResult fields" to tokens("resultJson telemetry"),
        "WorkspaceExecutionTelemetry fields" to tokens(
            """
            batchResultWaitDurationMs batchWaitDurationMs executeDurationMs materializeDurationMs
            prepareDurationMs publicFinishDurationMs resolveDurationMs scanDurationMs schemaCacheHit
            serializeDurationMs summaryDurationMs
            """,
        ),
        "ModelClientException fields" to tokens("code message recoverable"),
        // Public hub surface is exact (runtimeId, runId) lookup plus install/uninstall.
        // No runId-only latest map — emitterFor requires (runtimeId, runId).
        "RuntimeProbeEmitterHub methods" to tokens(
            "emitterFor install uninstall uninstallAllForRuntime",
        ),
        "ProbeEmitterRegistration fields" to tokens("runId runtimeId"),
        "RuntimeContextRequest fields" to
            tokens(
                "currentStateKeys deadlineEpochMillis input referenceTimeEpochMillis requestId runId runtimeInstanceId scene sessionId",
            ),
        "RuntimeTurnInput fields" to
            tokens("deadlineEpochMillis input referenceTimeEpochMillis requestId runId runtimeInstanceId scene sessionId"),
        "RuntimeTurnObserver methods" to tokens("close onTurnFinished onTurnInput preflightTurnInput"),
        "RuntimeObserverActionPreflight fields" to
            tokens("actionName argumentDigest completionImpact effectKind effectKeyDigest observerId"),
        "RuntimeCompletedTurn fields" to
            tokens(
                "completionOutcome inputDigest referenceTimeEpochMillis requestId runId runtimeInstanceId scene sessionId terminalStatus",
            ),
        "ExecEngine methods" to tokens("getProviderConcurrency run"),
        "ModelClient methods" to tokens("complete getProviderConcurrency"),
        "McpClientProvider methods" to tokens("getProviderId getServerId"),
        "ToolProvider methods" to tokens(
            "callTool getDisplayName getProviderConcurrency getProviderId initialize listPrompts listResources listTools",
        ),
        "ToolCatalogCacheIdentity methods" to tokens("getCatalogCacheIdentity"),
        "ToolProviderStatus tokens" to tokens("INITIALIZING CONNECTED DISCONNECTED FAILED"),
        "ToolInitializeContext fields" to tokens("requestId runId sessionId"),
        "ToolInitializeResult fields" to tokens("message status"),
        "ToolDescriptor fields" to tokens("completionImpact description effectKind inputSchemaJson name outputSchemaJson title"),
        "ToolCallContext fields" to tokens("requestId runId sessionId"),
        "ToolCallResult fields" to tokens("images resultJson retryable success trace"),
        "ToolCallTrace fields" to tokens("validationCode validationMessage validationOk validationSchemaPath"),
        "ToolResourceDescriptor fields" to tokens("description name uri"),
        "ToolPromptDescriptor fields" to tokens("description name"),
        "ProviderConcurrencyMode tokens" to tokens(
            "RUNTIME_SHARED_THREAD_SAFE RUNTIME_SHARED_SERIALIZED SESSION_FACTORY",
        ),
        "ProviderConcurrencyDeclaration fields" to tokens("maxConcurrentCalls mode"),
        "ProviderConcurrencyDeclaration methods" to tokens(
            "serialized sessionFactory threadSafe",
        ),
        "ExecRequest fields" to tokens("code outputTokenBudget runId timeoutMillis"),
        "ExecToolBridge methods" to tokens("callTool emitOutput"),
        "ExecResult fields" to tokens("errorCode message ok output"),
        "RuntimeHook methods" to tokens("onPostToolUse onPreToolUse onRunFinished onRunStarted"),
        "RuntimeHookRegistration fields" to tokens("failurePolicy hook matcher timeoutMillis"),
        "HookFailurePolicy tokens" to tokens("FAIL_CLOSED FAIL_OPEN"),
        "ToolMatcher methods" to tokens("matches"),
        "ToolUse fields" to tokens("argumentsJson source toolCallId toolName"),
        "ToolSource tokens" to tokens("INTERACTION NATIVE_READ NATIVE_WRITE NETWORK EXEC NATIVE_TODO BACKEND TERMINAL"),
        "ToolUseResult fields" to tokens("code ok resultJson retryable"),
        "HookContext fields" to tokens("approvedConfirmation requestId runId sessionId"),
        "HookDecision variants" to tokens("Allow BlockAndContinue Deny RequireConfirmation"),
        "HookDecision fields" to tokens(
            "Allow:updatedArgumentsJson BlockAndContinue:prompt,reason Deny:code,reason RequireConfirmation:prompt,reason",
        ),
        "PolicyRule methods" to tokens("check"),
        "PolicyAction variants" to tokens("Call"),
        "PolicyAction fields" to tokens("Call:arguments,source,toolName"),
        "PolicyContext fields" to tokens("approvedConfirmation approvedStepIndex requestId runId sessionId"),
        "PolicyDecision variants" to tokens("Allow Deny RequireConfirmation"),
        "PolicyDecision fields" to tokens("Deny:code,reason RequireConfirmation:prompt,reason"),
        "ModelRequest fields" to tokens("messages modelRef promptCacheKey system toolChoice tools"),
        "AgentEvent variants" to tokens(
            """
            BudgetWarning CompactionApplied ConfirmationRequired EmptyCompletionObserved ExecOutputDelta Failed Finished
            NativeToolCallFinished NativeToolCallStarted NetworkToolActivity ResultAvailable RetryAttempted
            RuntimeBlocked RuntimeProtocolRequestCreated SearchActivity Started SteeringAccepted TextDelta ThinkingDelta
            TokenUsageDelta UserInteraction WorkspaceChanged WorkspaceFileChanged
            """,
        ),
        "AgentEvent fields" to tokens(
            """
            BudgetWarning:roundsRemaining,runId,toolCallsRemaining
            CompactionApplied:afterMessageCount,beforeMessageCount,droppedCount,runId,summaryBytes
            ConfirmationRequired:pending
            EmptyCompletionObserved:continueCount,continued,hadStateAdvancingWork,kind,modelRound,runId,successfulBackendToolCount,textChars,thinkingChars
            ExecOutputDelta:runId,text Failed:code,message,recoverable,runId Finished:runId
            NativeToolCallFinished:result,runId,toolCallId,toolName
            NativeToolCallStarted:arguments,runId,toolCallId,toolName
            NetworkToolActivity:decision,domain,runId,statusCode,toolCallId,toolName,truncated
            ResultAvailable:result
            RetryAttempted:attempt,delayMillis,fallbackModelRef,modelRef,runId RuntimeBlocked:blocker
            RuntimeProtocolRequestCreated:protocolRequest SearchActivity:provider,queryPreview,resultCount,runId Started:runId
            SteeringAccepted:requestId,runId,textPreview TextDelta:runId,text
            ThinkingDelta:runId,text
            TokenUsageDelta:cacheReadTokens,cacheWriteTokens,inputTokens,outputTokens,reasoningTokens,runId
            UserInteraction:interaction,runId
            WorkspaceChanged:runId,workspaceId
            WorkspaceFileChanged:addedLines,bytes,diffTimedOut,path,removedLines,replacements,runId,toolCallId,toolName
            """,
        ),
        "ModelMessage fields" to tokens("assistantText content images role thinkingSignature toolAdditions toolArgumentsJson toolCallId toolName"),
        "ModelImage fields" to tokens("bytes detail mediaType sourcePath"),
        "ModelEvent variants" to tokens("Completed SearchActivity TextDelta ThinkingDelta ToolCall UsageDelta"),
        "ModelEvent fields" to tokens(
            """
            SearchActivity:provider,queryPreview,resultCount
            TextDelta:text ThinkingDelta:signature,text ToolCall:argumentsJson,id,name
            UsageDelta:cacheReadTokens,cacheWriteTokens,inputTokens,outputTokens,reasoningTokens
            """,
        ),
        "HttpEvent variants" to tokens("Data Done Response"),
        "HttpEvent fields" to tokens("Data:bytes Response:headers,statusCode"),
        "ConfirmationResumeResult tokens" to tokens("Accepted AlreadyConsumed Expired NotFound Rejected"),
        "CancelPendingResult tokens" to tokens("AlreadyConsumed Cancelled Expired NotFound"),
        "PostUserInputResult tokens" to tokens("Accepted Duplicate NotFound Rejected RunNotActive"),
        "ProtocolRequestCloseLoopResult tokens" to tokens(
            "AlreadyApplied AlreadyTerminal Expired InvalidState NotFound Updated",
        ),
        "RuntimeRunStatus tokens" to tokens(
            "CREATED QUEUED RUNNING WAITING_CONFIRMATION WAITING_USER_INPUT WAITING_PROTOCOL_REQUEST BLOCKED COMPLETED FAILED CANCELLED",
        ),
        "RuntimeResult fields" to tokens("completion errorCode evidenceIds recoverable response runId status"),
        "RuntimeCompletionOutcome tokens" to tokens("SUCCEEDED PARTIAL BLOCKED FAILED UNKNOWN"),
        "RuntimeCompletionSummary fields" to tokens(
            "outcome projectionDigest requiredSucceeded requiredTotal requiredUnresolved unresolvedCodes",
        ),
        "RuntimeEffectKind tokens" to tokens("READ_ONLY MUTATING CONTROL UNKNOWN"),
        "CompletionImpact tokens" to tokens("REQUIRED_ON_CALL ADVISORY"),
        "RuntimeActionStatus tokens" to tokens("DECLARED STARTED SUCCEEDED FAILED DENIED BLOCKED CANCELLED UNKNOWN"),
        "RuntimeCommitStatus tokens" to tokens("COMMITTED NO_CHANGE_REQUIRED NOT_COMMITTED UNKNOWN"),
        "RuntimeActionOutcome fields" to tokens("actionName commitStatus obligationId retryable safeCode status"),
        "RuntimeRunSnapshot fields" to tokens(
            "pendingConfirmationId runId sessionId status suggestedNextAction updatedAtEpochMillis",
        ),
        "RuntimeRunPhase tokens" to tokens(
            "QUEUED MODEL_RUNNING WAITING_CONFIRMATION WAITING_USER_INPUT WAITING_PROTOCOL_REQUEST TERMINAL",
        ),
        "RuntimeProtocolRequestStatus tokens" to tokens("CREATED WAITING RESOLVED REJECTED CANCELLED EXPIRED"),
        "RuntimeEvidenceType tokens" to tokens(
            "PROTOCOL_REQUEST_RESOLUTION RUN_CREATED WORKPAD_UPDATED HANDOFF_WRITTEN BLOCKER_RECORDED RESULT_WRITTEN MCP_DEBUG_CALL",
        ),
        "SuggestedNextAction tokens" to tokens(
            "ASK_USER RESUME_CONFIRMATION START_NEW_AGENT_REQUEST RESOLVE_PROTOCOL_REQUEST INSPECT_BLOCKER",
        ),
        "RuntimeErrorCode tokens" to tokens(
            """
            CAPABILITY_BACKEND_NOT_CONFIGURED SCHEMA_INSPECT_FAILED INVALID_TOOL_SCHEMA
            SCHEMA_VALIDATION_FAILED POLICY_DENIED BACKEND_CALL_FAILED MODEL_LOOP_FAILED PROVIDER_AUTH_FAILED
            PROVIDER_RATE_LIMITED PROVIDER_SERVER_ERROR PROVIDER_HTTP_ERROR PROVIDER_TRANSPORT_FAILED
            PROVIDER_RESPONSE_MALFORMED MODEL_LOOP_BUDGET_EXCEEDED MODEL_LOOP_TIMEOUT
            MODEL_FINISHED_WITHOUT_RUNTIME_FINISH WORKSPACE_NOT_CONFIGURED WORKSPACE_WRITE_DENIED
            WORKSPACE_READ_REQUIRED WORKSPACE_FULL_READ_REQUIRED WORKSPACE_WRITE_BUDGET_EXCEEDED
            WORKSPACE_WRITE_CONFLICT WORKSPACE_EDIT_NO_MATCH HOOK_DENIED HOOK_FAILED
            POLICY_CONFIG_INVALID PENDING_CONFIRMATION_EXISTS WAITING_USER_INPUT
            INVALID_WORKSPACE_PATH READ_BUDGET_EXCEEDED BINARY_FILE_UNSUPPORTED NOT_FOUND
            INVALID_GREP_PATTERN GREP_BUDGET_EXCEEDED NETWORK_URL_INVALID NETWORK_PRIVATE_ADDRESS_DENIED
            NETWORK_FETCH_FAILED EXEC_ENGINE_NOT_CONFIGURED EXEC_FAILED EXEC_TIMEOUT
            UNSUPPORTED_NATIVE_TOOL INACTIVE_BACKEND_TOOL UNKNOWN_BACKEND_TOOL
            SCHEMA_MATERIALIZE_BUDGET_EXCEEDED SCHEMA_MATERIALIZE_TIMEOUT CAPABILITY_CATALOG_TIMEOUT
            RELIABILITY_STORAGE_ROOT_MUST_BE_DIRECTORY
            RELIABILITY_STORAGE_LOCK_UNAVAILABLE RAW_TRACE_WORKSPACE_FORBIDDEN_IN_RELEASE
            DEBUG_RAW_TRACE_FORBIDDEN_IN_RELEASE DEBUG_RAW_TRACE_ACCESS_NOT_ENABLED
            SESSION_QUEUE_FULL RUNTIME_BUSY RUNTIME_CLOSED ADMISSION_POLICY_REQUIRED RUN_REPLACED
            DUPLICATE_RUN_ID PROCESS_RESTART_ADMISSION_UNSUPPORTED
            """,
        ),
        "VfsEntry fields" to tokens("modifiedAtMillis name revision sizeBytes type"),
        "VfsReadResult variants" to tokens("BinaryUnsupported Error Found InvalidPath NotFound ReadDenied"),
        "VfsReadResult fields" to tokens(
            """
            Error:code,message,retryable
            Found:attributes,bytesRead,modifiedAtMillis,revision,sizeBytes,text,totalBytes
            """,
        ),
        "VfsStat fields" to tokens("modifiedAtMillis revision sizeBytes type"),
        "VfsStatResult variants" to tokens("Error Found InvalidPath NotFound ReadDenied"),
        "VfsStatResult fields" to tokens("Error:code,message,retryable Found:stat"),
        "VfsWriteMode tokens" to tokens("CREATE OVERWRITE APPEND ATOMIC_REPLACE"),
        "VfsWriteResult variants" to tokens(
            "BinaryUnsupported Conflict Error InvalidPath NotFound WriteDenied Written",
        ),
        "VfsWriteResult fields" to tokens(
            "Error:code,message,retryable Written:bytes,modifiedAtMillis,revision,sizeBytes",
        ),
        "VfsSource methods" to tokens("deleteTree list read search stat write"),
    )

    fun requireGroup(name: String): List<String> =
        inventory[name] ?: error("Protocol catalog group is missing: $name")

    fun canonicalJson(): String {
        val root = buildJsonObject {
            put("schemaVersion", JsonPrimitive(SCHEMA_VERSION))
            put("catalogId", JsonPrimitive(CATALOG_ID))
            put("ownerReviewNotes", ownerReviewNotes.toJsonArray())
            put(
                "inventory",
                buildJsonObject {
                    for ((group, values) in inventory) {
                        put(group, values.toJsonArray())
                    }
                },
            )
        }
        return JSON.encodeToString(root) + "\n"
    }
}

@OptIn(ExperimentalSerializationApi::class)
private val JSON = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

private fun tokens(value: String): List<String> =
    value.trim().split(Regex("\\s+")).filter { it.isNotBlank() }

private fun List<String>.toJsonArray(): JsonArray =
    JsonArray(map(::JsonPrimitive))
