@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.HookContext
import cc.noharry.agent.kernel.api.HookDecision
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.RuntimeHook
import cc.noharry.agent.kernel.api.ToolSource
import cc.noharry.agent.kernel.api.ToolUse
import cc.noharry.agent.kernel.api.CancelPendingConfirmationRequest
import cc.noharry.agent.kernel.api.CompletionImpact
import cc.noharry.agent.kernel.api.RuntimeEffectKind
import cc.noharry.agent.kernel.api.RuntimeTurnObserver
import cc.noharry.agent.kernel.api.TurnInputObservation
import cc.noharry.agent.kernel.api.TurnObservationReceipt
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.mcp.McpClientProvider
import cc.noharry.agent.kernel.api.ToolDescriptor
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionSummary
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import cc.noharry.agent.kernel.core.*

class NativeToolLoopCompletionObligationTest {
    @Test
    fun requiredSuccessThenFinishIsSucceeded() = runBlocking {
        val result = runMutatingBackend(
            firstResult = """{"ok":true,"committed":true}""",
            finishArguments = """{"response":"done"}""",
        )
        assertTrue(RuntimeCompletionSummary.firstPartyPass(result.status, result.completion))
        assertEquals(RuntimeCompletionOutcome.SUCCEEDED, result.completion.outcome)
        assertEquals(0, result.completion.requiredUnresolved)
    }

    @Test
    fun requiredFailureThenSuccessFinishIsNotSucceeded() = runBlocking {
        val result = runMutatingBackend(
            firstResult = """{"ok":false,"code":"WRITE_FAILED"}""",
            finishArguments = """{"response":"done"}""",
            extraRound = listOf(
                ModelEvent.ToolCall("f2", "finish", """{"response":"still done"}"""),
                ModelEvent.Completed,
            ),
        )
        assertFalse(RuntimeCompletionSummary.firstPartyPass(result.status, result.completion))
        assertNotEquals(RuntimeCompletionOutcome.SUCCEEDED, result.completion.outcome)
    }

    @Test
    fun requiredNotCommittedIsUnresolved() = runBlocking {
        val result = runMutatingBackend(
            firstResult = """{"ok":true,"committed":false}""",
            finishArguments = """{"response":"done"}""",
            extraRound = listOf(
                ModelEvent.ToolCall("f2", "finish", """{"response":"still done"}"""),
                ModelEvent.Completed,
            ),
        )
        assertFalse(RuntimeCompletionSummary.firstPartyPass(result.status, result.completion))
        assertTrue(result.completion.requiredUnresolved >= 1)
    }

    @Test
    fun nestedInnerFailureStaysUnresolved() = runBlocking {
        val result = runMutatingBackend(
            firstResult = """{"ok":false,"code":"NESTED"}""",
            finishArguments = """{"response":"done"}""",
            extraRound = listOf(
                ModelEvent.ToolCall("f2", "finish", """{"response":"still done"}"""),
                ModelEvent.Completed,
            ),
        )
        assertFalse(RuntimeCompletionSummary.firstPartyPass(result.status, result.completion))
    }

    @Test
    fun equivalentRetrySatisfiesSameObligation() = runBlocking {
        val result = runMutatingBackend(
            firstResult = """{"ok":false,"code":"TEMP"}""",
            finishArguments = """{"response":"done"}""",
            extraRound = listOf(
                ModelEvent.ToolCall("t2", qualifiedMcpTool("remember_fact"), """{"value":"home"}"""),
                ModelEvent.Completed,
            ),
            thirdRound = listOf(
                ModelEvent.ToolCall("f2", "finish", """{"response":"done"}"""),
                ModelEvent.Completed,
            ),
            secondResult = """{"ok":true,"committed":true}""",
        )
        assertEquals(RuntimeCompletionOutcome.SUCCEEDED, result.completion.outcome)
        assertEquals(0, result.completion.requiredUnresolved)
    }

    @Test
    fun distinctArgumentsDoNotSatisfyEachOther() = runBlocking {
        val result = runMutatingBackend(
            firstResult = """{"ok":false,"code":"TEMP"}""",
            finishArguments = """{"response":"done"}""",
            extraRound = listOf(
                ModelEvent.ToolCall("t2", qualifiedMcpTool("remember_fact"), """{"value":"other"}"""),
                ModelEvent.Completed,
            ),
            thirdRound = listOf(
                ModelEvent.ToolCall("f2", "finish", """{"response":"done"}"""),
                ModelEvent.Completed,
            ),
            secondResult = """{"ok":true,"committed":true}""",
        )
        assertFalse(RuntimeCompletionSummary.firstPartyPass(result.status, result.completion))
        assertTrue(result.completion.requiredUnresolved >= 1)
    }

    @Test
    fun observerRequiredReceiptCreatesObligation() = runBlocking {
        val observer = RecordingObserver()
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(ModelEvent.ToolCall("f1", "finish", """{"response":"done"}"""), ModelEvent.Completed),
                        listOf(ModelEvent.ToolCall("f2", "finish", """{"response":"done"}"""), ModelEvent.Completed),
                    ),
                ),
            )
            .turnObserver(observer)
            .build()
        val events = runtime.run(
            AgentRequest("request-1", "session-1", "remember this"),
        ).toList()
        val result = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result
        assertTrue(observer.seen)
        assertFalse(RuntimeCompletionSummary.firstPartyPass(result.status, result.completion))
    }

    @Test
    fun partialAndBlockedAreNeverFirstPartyPass() = runBlocking {
        val partial = runPlainFinish("""{"response":"missing media","outcome":"partial"}""")
        val blocked = runPlainFinish("""{"response":"need permission","outcome":"blocked"}""")
        assertEquals(RuntimeRunStatus.COMPLETED, partial.status)
        assertEquals(RuntimeCompletionOutcome.PARTIAL, partial.completion.outcome)
        assertEquals(RuntimeCompletionOutcome.BLOCKED, blocked.completion.outcome)
        assertFalse(RuntimeCompletionSummary.firstPartyPass(partial.status, partial.completion))
        assertFalse(RuntimeCompletionSummary.firstPartyPass(blocked.status, blocked.completion))
    }

    @Test
    fun legacyFinishWithoutOutcomeIsSuccessRequest() = runBlocking {
        val result = runPlainFinish("""{"response":"done"}""")
        assertTrue(RuntimeCompletionSummary.firstPartyPass(result.status, result.completion))
    }

    @Test
    fun legacyResultDecodeWithoutCompletionIsUnknown() {
        val decoded = ReliabilityJsonCodecs.decodeResult(
            """{"runId":"run-1","status":"COMPLETED","response":"old","recoverable":true}""",
        )
        assertEquals(RuntimeCompletionOutcome.UNKNOWN, decoded.completion.outcome)
        assertFalse(RuntimeCompletionSummary.firstPartyPass(decoded.status, decoded.completion))
    }

    @Test
    fun cancelAfterRequiredObserverIsNotSucceeded() = runBlocking {
        val qualified = qualifiedMcpTool("remember_fact")
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("obligation-cancel-").toFile())
            .turnObserver(RecordingObserver())
            .policyRule(confirmUnlessApproved())
            .toolProvider(MutatingMcpProvider(mutableListOf("""{"ok":true,"committed":true}""")))
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(
                            ModelEvent.ToolCall("inspect", "read_file", """{"path":"/mcp/tools/$qualified/schema.json"}"""),
                            ModelEvent.Completed,
                        ),
                        listOf(ModelEvent.ToolCall("t1", qualified, """{"value":"home"}"""), ModelEvent.Completed),
                    ),
                ),
            )
            .build()
        val pending = runtime.run(AgentRequest("request-1", "session-1", "remember home")).toList()
            .filterIsInstance<AgentEvent.ConfirmationRequired>()
            .single()
            .pending
        runtime.cancelPendingConfirmation(
            CancelPendingConfirmationRequest(pending.pendingId, "cancel-1", "host cancelled"),
        )
        val result = runtime.getRuntimeResult("request-1")!!
        assertEquals(RuntimeRunStatus.CANCELLED, result.status)
        assertNotEquals(RuntimeCompletionOutcome.SUCCEEDED, result.completion.outcome)
        assertFalse(RuntimeCompletionSummary.firstPartyPass(result.status, result.completion))
        assertTrue(result.completion.requiredUnresolved >= 1)
    }

    @Test
    fun rebuildResumeKeepsUnresolvedObserverObligation() = runBlocking {
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("obligation-ws-").toFile())
            .turnObserver(RecordingObserver())
            .toolProvider(LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature"))))
            .hook(confirmUntilApproved())
            .modelClient(
                FakeModelClient(schemaThenBackendRounds(finish = true)),
            )
            .build()
        val pending = runtime.run(AgentRequest("request-1", "session-1", "remember this")).toList()
            .filterIsInstance<cc.noharry.agent.kernel.api.AgentEvent.ConfirmationRequired>()
            .single()
        runtime.resumeConfirmation(approve(pending.pending.pendingId))
        val result = requireNotNull(runtime.getRuntimeResult("request-1"))
        assertFalse(RuntimeCompletionSummary.firstPartyPass(result.status, result.completion))
        assertTrue(result.completion.requiredUnresolved >= 1)
        assertNotEquals(RuntimeCompletionOutcome.SUCCEEDED, result.completion.outcome)
    }

    @Test
    fun processRestartRebuildResumeIsUnsupportedWhileObligationRemainsUnresolved() = runBlocking {
        val root = Files.createTempDirectory("obligation-rebuild-").toFile()
        val first = AgentRuntimeBuilder.create()
            .reliabilityStorageRoot(root)
            .workspaceRoot(Files.createTempDirectory("obligation-restart-ws-").toFile())
            .turnObserver(RecordingObserver())
            .toolProvider(LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature"))))
            .hook(confirmUntilApproved())
            .modelClient(
                FakeModelClient(schemaThenBackendRounds()),
            )
            .build()
        val pending = first.run(AgentRequest("request-1", "session-1", "remember this")).toList()
            .filterIsInstance<cc.noharry.agent.kernel.api.AgentEvent.ConfirmationRequired>()
            .single()
        val seen = mutableListOf<ModelRequest>()
        val restored = AgentRuntimeBuilder.create()
            .reliabilityStorageRoot(root)
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(ModelEvent.ToolCall("f1", "finish", """{"response":"done"}"""), ModelEvent.Completed),
                    ),
                    seenRequests = seen,
                ),
            )
            .build()
        val resume = restored.resumeConfirmation(approve(pending.pending.pendingId))
        assertTrue(
            resume is cc.noharry.agent.kernel.api.ConfirmationResumeResult.Rejected ||
                resume is cc.noharry.agent.kernel.api.ConfirmationResumeResult.NotFound,
        )
        assertTrue(seen.isEmpty())
    }

    @Test
    fun narrationRetryDoesNotConsumeObligationCorrectionBudget() = runBlocking {
        val seenRequests = mutableListOf<cc.noharry.agent.kernel.api.model.ModelRequest>()
        val result = runMutatingBackend(
            firstResult = """{"ok":false,"code":"WRITE_FAILED"}""",
            finishArguments = """{"response":"All tasks completed! Let me update the todo and provide the final response."}""",
            extraRound = listOf(
                ModelEvent.ToolCall("f2", "finish", """{"response":"remembered home"}"""),
                ModelEvent.Completed,
            ),
            thirdRound = listOf(
                ModelEvent.ToolCall("t2", qualifiedMcpTool("remember_fact"), """{"value":"home"}"""),
                ModelEvent.Completed,
            ),
            fourthRound = listOf(
                ModelEvent.ToolCall("f3", "finish", """{"response":"remembered home"}"""),
                ModelEvent.Completed,
            ),
            secondResult = """{"ok":true,"committed":true}""",
            seenRequests = seenRequests,
        )
        val finishResults = seenRequests.flatMap { request ->
            request.messages.filter { it.role == "tool" && it.toolName == "finish" }.map { it.content }
        }
        assertTrue(finishResults.any { it.contains(INVALID_FINISH_RESPONSE_CODE) })
        assertTrue(finishResults.any { it.contains(REQUIRED_ACTION_UNRESOLVED) })
        assertEquals(RuntimeCompletionOutcome.SUCCEEDED, result.completion.outcome)
        assertEquals(0, result.completion.requiredUnresolved)
    }

    @Test
    fun obligationCorrectionDoesNotConsumeNarrationRetryBudget() = runBlocking {
        val seenRequests = mutableListOf<cc.noharry.agent.kernel.api.model.ModelRequest>()
        val result = runMutatingBackend(
            firstResult = """{"ok":false,"code":"WRITE_FAILED"}""",
            finishArguments = """{"response":"remembered home"}""",
            extraRound = listOf(
                ModelEvent.ToolCall(
                    "f2",
                    "finish",
                    """{"response":"All tasks completed! Let me update the todo and provide the final response."}""",
                ),
                ModelEvent.Completed,
            ),
            thirdRound = listOf(
                ModelEvent.ToolCall("t2", qualifiedMcpTool("remember_fact"), """{"value":"home"}"""),
                ModelEvent.Completed,
            ),
            fourthRound = listOf(
                ModelEvent.ToolCall("f3", "finish", """{"response":"remembered home"}"""),
                ModelEvent.Completed,
            ),
            secondResult = """{"ok":true,"committed":true}""",
            seenRequests = seenRequests,
        )
        val finishResults = seenRequests.flatMap { request ->
            request.messages.filter { it.role == "tool" && it.toolName == "finish" }.map { it.content }
        }
        assertTrue(finishResults.any { it.contains(REQUIRED_ACTION_UNRESOLVED) })
        assertTrue(finishResults.any { it.contains(INVALID_FINISH_RESPONSE_CODE) })
        assertEquals(RuntimeCompletionOutcome.SUCCEEDED, result.completion.outcome)
        assertEquals(0, result.completion.requiredUnresolved)
    }

    @Test
    fun readOnlyHintDoesNotBatchRequiredMutatingActionPastLedger() = runBlocking {
        val hintSchema =
            """{"type":"object","annotations":{"readOnlyHint":true},"required":["value"],"properties":{"value":{"type":"string"}}}"""
        val entry = DynamicToolEntry(
            modelTool = cc.noharry.agent.kernel.api.model.ModelTool(qualifiedMcpTool("remember_fact"), hintSchema),
            source = DynamicToolSource.BACKEND,
            backendToolName = qualifiedMcpTool("remember_fact"),
            action = RuntimeActionDescriptor(
                providerId = TEST_MCP_SERVER_ID,
                actionName = "remember_fact",
                effectKind = RuntimeEffectKind.MUTATING,
                impact = CompletionImpact.REQUIRED_ON_CALL,
            ),
        )
        val toolSet = DynamicToolSet(listOf(entry))
        assertFalse(toolSet.isReadOnlyBackendTool(qualifiedMcpTool("remember_fact")))
        assertTrue(toolSet.requiresObligationLedger(qualifiedMcpTool("remember_fact")))

        val result = runMutatingBackend(
            firstResult = """{"ok":false,"code":"WRITE_FAILED"}""",
            finishArguments = """{"response":"done"}""",
            extraRound = listOf(
                ModelEvent.ToolCall("f2", "finish", """{"response":"still done"}"""),
                ModelEvent.Completed,
            ),
            schemaJson = hintSchema,
        )
        assertFalse(RuntimeCompletionSummary.firstPartyPass(result.status, result.completion))
        assertTrue(result.completion.requiredUnresolved >= 1)
    }

    @Test
    fun requiredDeniedIsUnresolved() = runBlocking {
        val result = runMutatingBackend(
            firstResult = """{"ok":true,"committed":true}""",
            finishArguments = """{"response":"done"}""",
            extraRound = listOf(
                ModelEvent.ToolCall("f2", "finish", """{"response":"still done"}"""),
                ModelEvent.Completed,
            ),
            policyConfigJson =
                """{"rules":[{"match":{"tools":["mcp__default__remember_*"],"sources":["BACKEND"]},"decision":"deny","userMessage":"policy blocked"}]}""",
        )
        assertFalse(RuntimeCompletionSummary.firstPartyPass(result.status, result.completion))
        assertTrue(result.completion.requiredUnresolved >= 1)
    }

    @Test
    fun requiredBlockedIsUnresolved() = runBlocking {
        val result = runMutatingBackend(
            firstResult = """{"ok":true,"committed":true}""",
            finishArguments = """{"response":"done"}""",
            extraRound = listOf(
                ModelEvent.ToolCall("f2", "finish", """{"response":"still done"}"""),
                ModelEvent.Completed,
            ),
            hook = object : RuntimeHook {
                override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision =
                    if (call.source == ToolSource.BACKEND) {
                        HookDecision.BlockAndContinue("blocked required write", "blocked")
                    } else {
                        HookDecision.Allow()
                    }
            },
        )
        assertFalse(RuntimeCompletionSummary.firstPartyPass(result.status, result.completion))
        assertTrue(result.completion.requiredUnresolved >= 1)
    }

    @Test
    fun requiredMissingProviderIsUnresolved() = runBlocking {
        val activationStore = ToolActivationStore()
        val dummy = MutatingMcpProvider(mutableListOf("""{"ok":true,"committed":true}"""))
        val qualified = qualifiedMcpTool("remember_fact")
        activationStore.activate(
            runId = "request-1",
            tool = McpToolRef(
                provider = dummy,
                serverId = TEST_MCP_SERVER_ID,
                normalizedServerId = TEST_MCP_SERVER_ID,
                originalToolName = "remember_fact",
                normalizedToolName = "remember_fact",
                qualifiedName = qualified,
                descriptor = cc.noharry.agent.kernel.api.ToolDescriptor(
                    name = "remember_fact",
                    inputSchemaJson = """{"type":"object","required":["value"],"properties":{"value":{"type":"string"}}}""",
                    effectKind = RuntimeEffectKind.MUTATING.name,
                    completionImpact = CompletionImpact.REQUIRED_ON_CALL.name,
                ),
            ),
            schemaJson = """{"type":"object","required":["value"],"properties":{"value":{"type":"string"}}}""",
        )
        val loop = NativeToolLoop(
            modelClient = FakeModelClient(
                eventsByRound = listOf(
                    listOf(ModelEvent.ToolCall("t1", qualified, """{"value":"home"}"""), ModelEvent.Completed),
                    listOf(ModelEvent.ToolCall("f1", "finish", """{"response":"done"}"""), ModelEvent.Completed),
                    listOf(ModelEvent.ToolCall("f2", "finish", """{"response":"still done"}"""), ModelEvent.Completed),
                ),
            ),
            mcpRegistry = ToolProviderRegistry(),
            workspace = null,
            activationStore = activationStore,
        )
        val outcome = loop.run(AgentRequest("request-1", "session-1", "remember home"), "request-1")
        val result = (outcome as NativeLoopOutcome.Terminal).result
        assertFalse(RuntimeCompletionSummary.firstPartyPass(result.status, result.completion))
        assertTrue(result.completion.requiredUnresolved >= 1)
    }

    @Test
    fun requiredTransportErrorIsUnresolved() = runBlocking {
        val result = runMutatingBackend(
            firstResult = """{"ok":true,"committed":true}""",
            finishArguments = """{"response":"done"}""",
            extraRound = listOf(
                ModelEvent.ToolCall("f2", "finish", """{"response":"still done"}"""),
                ModelEvent.Completed,
            ),
            onCall = { throw RuntimeException("connection reset") },
        )
        assertFalse(RuntimeCompletionSummary.firstPartyPass(result.status, result.completion))
        assertTrue(result.completion.requiredUnresolved >= 1)
    }

    @Test
    fun requiredTimeoutReceiptIsUnresolved() = runBlocking {
        val result = runMutatingBackend(
            firstResult = """{"ok":false,"code":"TIMEOUT","retryable":true}""",
            finishArguments = """{"response":"done"}""",
            extraRound = listOf(
                ModelEvent.ToolCall("f2", "finish", """{"response":"still done"}"""),
                ModelEvent.Completed,
            ),
        )
        assertFalse(RuntimeCompletionSummary.firstPartyPass(result.status, result.completion))
        assertTrue(result.completion.requiredUnresolved >= 1)
    }

    @Test
    fun requiredCancelReceiptIsUnresolved() = runBlocking {
        val result = runMutatingBackend(
            firstResult = cancelledToolResult("remember_fact"),
            finishArguments = """{"response":"done"}""",
            extraRound = listOf(
                ModelEvent.ToolCall("f2", "finish", """{"response":"still done"}"""),
                ModelEvent.Completed,
            ),
        )
        assertFalse(RuntimeCompletionSummary.firstPartyPass(result.status, result.completion))
        assertTrue(result.completion.requiredUnresolved >= 1)
    }

    @Test
    fun requiredCancellationExceptionLeavesLedgerUnresolved() = runBlocking {
        val history = InMemoryLoopHistoryStore()
        val activationStore = ToolActivationStore()
        val workspaceRoot = Files.createTempDirectory("obligation-ce-").toFile()
        val vfs = cc.noharry.agent.vfs.VfsFileSystem.singleFileRoot(workspaceRoot)
        val mcpRegistry = ToolProviderRegistry(listOf(CancellingRequiredMcpProvider()))
        val workspace = VirtualWorkspace(
            fileSystem = vfs,
            materializer = VirtualFileMaterializer(mcpRegistry = mcpRegistry, activationStore = activationStore),
            mcpRegistry = mcpRegistry,
            activationStore = activationStore,
            runId = "request-1",
        )
        val qualified = qualifiedMcpTool("remember_fact")
        val loop = NativeToolLoop(
            modelClient = FakeModelClient(
                eventsByRound = listOf(
                    listOf(
                        ModelEvent.ToolCall("inspect", "read_file", """{"path":"/mcp/tools/$qualified/schema.json"}"""),
                        ModelEvent.Completed,
                    ),
                    listOf(ModelEvent.ToolCall("t1", qualified, """{"value":"home"}"""), ModelEvent.Completed),
                ),
            ),
            mcpRegistry = mcpRegistry,
            workspace = workspace,
            loopHistoryStore = history,
            activationStore = activationStore,
        )
        try {
            loop.run(AgentRequest("request-1", "session-1", "remember home"), "request-1")
            org.junit.Assert.fail("expected CancellationException")
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            assertEquals("backend cancelled", cancelled.message)
        }
        val encoded = history.read("request-1")?.obligationLedger
        assertFalse(encoded.isNullOrBlank())
        val ledger = RuntimeObligationLedger.decode(encoded)
        val summary = ledger.summarize(RuntimeCompletionOutcome.SUCCEEDED)
        assertTrue(summary.requiredUnresolved >= 1)
        assertNotEquals(RuntimeCompletionOutcome.SUCCEEDED, summary.outcome)
    }

    @Test
    fun noChangeRequiredSatisfiesObligation() = runBlocking {
        val result = runMutatingBackend(
            firstResult = """{"ok":true,"commitStatus":"NO_CHANGE_REQUIRED"}""",
            finishArguments = """{"response":"already present"}""",
        )
        assertTrue(RuntimeCompletionSummary.firstPartyPass(result.status, result.completion))
        assertEquals(RuntimeCompletionOutcome.SUCCEEDED, result.completion.outcome)
        assertEquals(0, result.completion.requiredUnresolved)
    }

    @Test
    fun satisfiedOutcomeIsNotOverwrittenByLaterFailure() {
        val ledger = RuntimeObligationLedger("secret")
        val descriptor = RuntimeActionDescriptor(
            providerId = "default",
            actionName = "remember_fact",
            effectKind = RuntimeEffectKind.MUTATING,
            impact = CompletionImpact.REQUIRED_ON_CALL,
        )
        val declared = ledger.declareRequiredMutating("run-1", descriptor, """{"value":"home"}""")!!
        assertFalse(declared.isSatisfied())
        ledger.recordOutcome(
            cc.noharry.agent.kernel.api.RuntimeActionOutcome(
                obligationId = declared.obligationId,
                actionName = "remember_fact",
                status = cc.noharry.agent.kernel.api.RuntimeActionStatus.SUCCEEDED,
                commitStatus = cc.noharry.agent.kernel.api.RuntimeCommitStatus.NO_CHANGE_REQUIRED,
            ),
        )
        assertTrue(ledger.snapshot().single().isSatisfied())
        ledger.recordOutcome(
            cc.noharry.agent.kernel.api.RuntimeActionOutcome(
                obligationId = declared.obligationId,
                actionName = "remember_fact",
                status = cc.noharry.agent.kernel.api.RuntimeActionStatus.FAILED,
                commitStatus = cc.noharry.agent.kernel.api.RuntimeCommitStatus.NOT_COMMITTED,
                safeCode = "WRITE_FAILED",
            ),
        )
        val kept = ledger.snapshot().single()
        assertTrue(kept.isSatisfied())
        assertEquals(cc.noharry.agent.kernel.api.RuntimeCommitStatus.NO_CHANGE_REQUIRED, kept.commitStatus)
        assertEquals(0, ledger.summarize(RuntimeCompletionOutcome.SUCCEEDED).requiredUnresolved)
    }

}

private suspend fun runPlainFinish(finishArguments: String): RuntimeResult {
    val runtime = AgentRuntimeBuilder.create()
        .modelClient(
            FakeModelClient(
                eventsByRound = listOf(
                    listOf(ModelEvent.ToolCall("f1", "finish", finishArguments), ModelEvent.Completed),
                ),
            ),
        )
        .build()
    return runtime.run(AgentRequest("request-1", "session-1", "hello")).toList()
        .filterIsInstance<AgentEvent.ResultAvailable>().single().result
}

private suspend fun runMutatingBackend(
    firstResult: String,
    finishArguments: String,
    extraRound: List<ModelEvent>? = listOf(
        ModelEvent.ToolCall("f2", "finish", """{"response":"retry done"}"""),
        ModelEvent.Completed,
    ),
    thirdRound: List<ModelEvent>? = null,
    fourthRound: List<ModelEvent>? = null,
    secondResult: String = firstResult,
    schemaJson: String = """{"type":"object","required":["value"],"properties":{"value":{"type":"string"}}}""",
    policyConfigJson: String? = null,
    hook: RuntimeHook? = null,
    onCall: (() -> Unit)? = null,
    seenRequests: MutableList<ModelRequest>? = null,
): RuntimeResult {
    val provider = MutatingMcpProvider(mutableListOf(firstResult, secondResult), schemaJson, onCall)
    val qualified = qualifiedMcpTool("remember_fact")
    val rounds = mutableListOf(
        listOf(
            ModelEvent.ToolCall("inspect", "read_file", """{"path":"/mcp/tools/$qualified/schema.json"}"""),
            ModelEvent.Completed,
        ),
        listOf(
            ModelEvent.ToolCall("t1", qualified, """{"value":"home"}"""),
            ModelEvent.Completed,
        ),
        listOf(ModelEvent.ToolCall("f1", "finish", finishArguments), ModelEvent.Completed),
    )
    extraRound?.let(rounds::add)
    thirdRound?.let(rounds::add)
    fourthRound?.let(rounds::add)
    val builder = AgentRuntimeBuilder.create()
        .workspaceRoot(Files.createTempDirectory("obligation-").toFile())
        .toolProvider(provider)
        .modelClient(FakeModelClient(eventsByRound = rounds, seenRequests = seenRequests ?: mutableListOf()))
    policyConfigJson?.let(builder::policyConfigJson)
    hook?.let(builder::hook)
    return builder.build().run(AgentRequest("request-1", "session-1", "remember home")).toList()
        .filterIsInstance<AgentEvent.ResultAvailable>().single().result
}

private class MutatingMcpProvider(
    private val results: MutableList<String>,
    private val schemaJson: String =
        """{"type":"object","required":["value"],"properties":{"value":{"type":"string"}}}""",
    private val onCall: (() -> Unit)? = null,
) : McpClientProvider {
    override val serverId: String = TEST_MCP_SERVER_ID
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()

    override suspend fun listTools(): List<ToolDescriptor> = listOf(
        ToolDescriptor(
            name = "remember_fact",
            inputSchemaJson = schemaJson,
            effectKind = RuntimeEffectKind.MUTATING.name,
            completionImpact = CompletionImpact.REQUIRED_ON_CALL.name,
        ),
    )

    override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult {
        onCall?.invoke()
        val payload = if (results.isEmpty()) """{"ok":true}""" else results.removeAt(0)
        return ToolCallResult(success = !payload.contains("\"ok\":false"), resultJson = payload)
    }
}

private class CancellingRequiredMcpProvider : McpClientProvider {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    override val serverId: String = TEST_MCP_SERVER_ID

    override suspend fun listTools(): List<ToolDescriptor> = listOf(
        ToolDescriptor(
            name = "remember_fact",
            inputSchemaJson = """{"type":"object","required":["value"],"properties":{"value":{"type":"string"}}}""",
            effectKind = RuntimeEffectKind.MUTATING.name,
            completionImpact = CompletionImpact.REQUIRED_ON_CALL.name,
        ),
    )

    override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult {
        throw CancellationException("backend cancelled")
    }
}

private class RecordingObserver : RuntimeTurnObserver {
    var seen = false
    override val id: String = "required-observer"

    override fun preflightTurnInput(input: cc.noharry.agent.kernel.api.RuntimeTurnInput) =
        cc.noharry.agent.kernel.api.RuntimeObserverActionPreflight(
            observerId = id,
            actionName = id,
            effectKind = RuntimeEffectKind.MUTATING,
            completionImpact = CompletionImpact.REQUIRED_ON_CALL,
            effectKeyDigest = "observer-write",
        )

    override suspend fun onTurnInput(input: cc.noharry.agent.kernel.api.RuntimeTurnInput): TurnInputObservation {
        seen = true
        return TurnInputObservation(
            observerId = id,
            handled = true,
            writeCommitted = false,
            safeCode = "NOT_COMMITTED",
            effectKind = RuntimeEffectKind.MUTATING,
            completionImpact = CompletionImpact.REQUIRED_ON_CALL,
            commitStatus = cc.noharry.agent.kernel.api.RuntimeCommitStatus.NOT_COMMITTED,
            effectKeyDigest = "observer-write",
        )
    }

    override suspend fun onTurnFinished(turn: cc.noharry.agent.kernel.api.RuntimeCompletedTurn): TurnObservationReceipt =
        TurnObservationReceipt(id, enqueued = false)
}
