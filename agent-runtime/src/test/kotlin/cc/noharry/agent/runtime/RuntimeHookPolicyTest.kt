@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.ConfirmationResumeResult
import cc.noharry.agent.kernel.api.HookContext
import cc.noharry.agent.kernel.api.HookDecision
import cc.noharry.agent.kernel.api.HookFailurePolicy
import cc.noharry.agent.kernel.api.RuntimeHook
import cc.noharry.agent.kernel.api.RuntimeHookRegistration
import cc.noharry.agent.kernel.api.ToolMatcher
import cc.noharry.agent.kernel.api.ToolSource
import cc.noharry.agent.kernel.api.ToolUse
import cc.noharry.agent.kernel.api.ToolUseResult
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.core.loop.failed
import cc.noharry.agent.kernel.core.loop.recordPostToolUse
import cc.noharry.agent.kernel.core.tools.MCP_RESULT_INVALID_JSON_CODE
import cc.noharry.agent.kernel.core.tools.MCP_TOOL_IS_ERROR_CODE
import cc.noharry.agent.kernel.core.tools.MaterializerContext
import cc.noharry.agent.policy.PolicyAction
import cc.noharry.agent.policy.PolicyDecision
import cc.noharry.agent.runtime.run.RuntimeHookChain
import cc.noharry.agent.vfs.VfsFileSystem
import cc.noharry.agent.vfs.VfsMemorySource
import cc.noharry.agent.vfs.VfsSourceResolver
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeHookPolicyTest {
    @Test
    fun backendPostHookReceivesMcpAwareFailure() = runBlocking {
        val observed = CompletableDeferred<ToolUseResult>()
        val chain = RuntimeHookChain(
            listOf(
                RuntimeHookRegistration(
                    hook = object : RuntimeHook {
                        override suspend fun onPostToolUse(
                            call: ToolUse,
                            result: ToolUseResult,
                            context: HookContext,
                        ) {
                            observed.complete(result)
                        }
                    },
                ),
            ),
        )

        chain.recordPostToolUse(
            toolUse = ToolUse("mcp__default__set_temperature", "{}", ToolSource.BACKEND),
            resultJson = """{"result":{"content":[],"isError":true}}""",
            context = MaterializerContext("session-1", "run-1", "request-1"),
        )

        val result = withTimeout(ASYNC_POST_HOOK_TEST_TIMEOUT_MS) { observed.await() }
        assertFalse(result.ok)
        assertEquals(MCP_TOOL_IS_ERROR_CODE, result.code)
    }

    @Test
    fun preHookDenySkipsBackendAndFeedsRecoverableToolResult() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(hookWorkspace())
            .toolProvider(backend)
            .hook(denyTool("set_temperature"))
            .modelClient(backendThenFinishModel(seenRequests))
            .build()

        val events = runtime.run(agentRequest()).toList()
        val result = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result

        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertTrue(backend.calledTools.isEmpty())
        assertTrue(seenRequests.containsToolResult("HOOK_DENIED"))
    }

    @Test
    fun preHookTimeoutFailsClosedAndSkipsBackend() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(hookWorkspace())
            .toolProvider(backend)
            .hookTimeoutMillis(1)
            .hook(slowAllowHook())
            .modelClient(backendThenFinishModel(seenRequests))
            .build()

        runtime.run(agentRequest()).toList()

        assertTrue(backend.calledTools.isEmpty())
        assertTrue(seenRequests.containsToolResult("HOOK_FAILED"))
    }

    @Test
    fun preHookExceptionFailsClosedAndSkipsBackend() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(hookWorkspace())
            .toolProvider(backend)
            .hook(throwingHook())
            .modelClient(backendThenFinishModel(seenRequests))
            .build()

        runtime.run(agentRequest()).toList()

        assertTrue(backend.calledTools.isEmpty())
        assertTrue(seenRequests.containsToolResult("HOOK_FAILED"))
    }

    @Test
    fun preHookExceptionCanFailOpenAndContinueBackendExecution() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(hookWorkspace())
            .toolProvider(backend)
            .hook(throwingHook(), failurePolicy = HookFailurePolicy.FAIL_OPEN)
            .modelClient(backendThenFinishModel(seenRequests))
            .build()

        runtime.run(agentRequest()).toList()

        assertEquals(listOf("set_temperature"), backend.calledTools)
        assertFalse(seenRequests.containsToolResult("HOOK_FAILED"))
    }

    @Test
    fun preHookTimeoutCanFailOpenAndContinueBackendExecution() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(hookWorkspace())
            .toolProvider(backend)
            .hook(
                slowAllowHook(),
                timeoutMillis = 1,
                failurePolicy = HookFailurePolicy.FAIL_OPEN,
            )
            .modelClient(backendThenFinishModel(seenRequests))
            .build()

        runtime.run(agentRequest()).toList()

        assertEquals(listOf("set_temperature"), backend.calledTools)
        assertFalse(seenRequests.containsToolResult("HOOK_FAILED"))
    }

    @Test
    fun policyHookFailureStaysFailClosedBeforeFailOpenHostHook() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val hostCalls = AtomicInteger(0)
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(hookWorkspace())
            .toolProvider(backend)
            .policyRule { action, _ ->
                if (action is PolicyAction.Call && originalMcpToolName(action.toolName) == "set_temperature") {
                    error("policy failed")
                }
                PolicyDecision.Allow
            }
            .hook(
                countEveryPreHook(hostCalls),
                matcher = ToolMatcher.toolName(qualifiedMcpTool("set_temperature")),
                failurePolicy = HookFailurePolicy.FAIL_OPEN,
            )
            .modelClient(backendThenFinishModel(seenRequests))
            .build()

        runtime.run(agentRequest()).toList()

        assertEquals(0, hostCalls.get())
        assertTrue(backend.calledTools.isEmpty())
        assertTrue(seenRequests.containsToolResult("HOOK_FAILED"))
    }

    @Test
    fun malformedBackendArgumentsFailBeforeHooksAndBackend() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val preHookCalls = AtomicInteger(0)
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(hookWorkspace())
            .toolProvider(backend)
            .hook(countingHook(preHookCalls))
            .modelClient(malformedBackendThenFinishModel(seenRequests))
            .build()

        runtime.run(agentRequest()).toList()

        assertEquals(0, preHookCalls.get())
        assertTrue(backend.calledTools.isEmpty())
        assertTrue(seenRequests.containsToolResult("SCHEMA_VALIDATION_FAILED"))
    }

    @Test
    fun preHookRewriteThreadsThroughLaterHooksAndBackendExecution() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val seenHookArguments = mutableListOf<String>()
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(hookWorkspace())
            .toolProvider(backend)
            .hook(rewriteToolArguments("set_temperature", TEMPERATURE_REWRITE_ARGS))
            .hook(recordPreHookArguments("set_temperature", seenHookArguments))
            .modelClient(backendThenFinishModel(seenRequests))
            .build()

        runtime.run(agentRequest()).toList()

        assertEquals(listOf("set_temperature"), backend.calledTools)
        assertEquals(listOf(TEMPERATURE_REWRITE_ARGS), backend.calledArguments)
        assertEquals(listOf(TEMPERATURE_REWRITE_ARGS), seenHookArguments)
    }

    @Test
    fun preHookRewriteUpdatesWorkspaceWriteArguments() = runBlocking {
        val workspace = hookWorkspace()
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(workspace)
            .hook(
                rewriteToolArguments(
                    "write_file",
                    """{"path":"/memory/rewritten.txt","content":"rewritten"}""",
                ),
            )
            .modelClient(writeThenFinishModel(seenRequests))
            .build()

        runtime.run(agentRequest()).toList()

        assertFalse(File(workspace, "memory/secret.txt").exists())
        assertEquals("rewritten", File(workspace, "memory/rewritten.txt").readText())
    }

    @Test
    fun preHookRewriteAppliesToExecutePlanCallStep() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(hookWorkspace())
            .toolProvider(backend)
            .hook(rewriteToolArguments("set_temperature", TEMPERATURE_REWRITE_ARGS))
            .modelClient(executePlanThenFinishModel(seenRequests))
            .build()

        runtime.run(agentRequest()).toList()

        assertEquals(listOf("set_temperature"), backend.calledTools)
        assertEquals(listOf(TEMPERATURE_REWRITE_ARGS), backend.calledArguments)
    }

    @Test
    fun invalidPreHookRewriteFailsBeforeBackendExecution() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(hookWorkspace())
            .toolProvider(backend)
            .hook(rewriteToolArguments("set_temperature", "[]"))
            .modelClient(backendThenFinishModel(seenRequests))
            .build()

        runtime.run(agentRequest()).toList()

        assertTrue(backend.calledTools.isEmpty())
        assertTrue(seenRequests.containsToolResult("SCHEMA_VALIDATION_FAILED"))
    }

    @Test
    fun preHookCancellationPropagatesAndSkipsBackend() = runBlocking {
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(hookWorkspace())
            .toolProvider(backend)
            .hook(cancellingHook())
            .modelClient(backendOnlyModel())
            .build()

        val result = runCatching { runtime.run(agentRequest()).toList() }

        assertTrue(result.exceptionOrNull() is CancellationException)
        assertTrue(backend.calledTools.isEmpty())
    }

    @Test
    fun preHookDenyAppliesToExecutePlanCallStep() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(hookWorkspace())
            .toolProvider(backend)
            .hook(denyTool("set_temperature"))
            .modelClient(executePlanThenFinishModel(seenRequests))
            .build()

        runtime.run(agentRequest()).toList()

        assertTrue(backend.calledTools.isEmpty())
        assertTrue(seenRequests.any { request -> request.messages.any { it.content.contains("HOOK_DENIED") } })
    }

    @Test
    fun preHookRequireConfirmationPausesAndApproveExecutesBackendOnce() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(hookWorkspace())
            .toolProvider(backend)
            .hook(confirmUntilApproved())
            .modelClient(backendThenFinishModel(seenRequests))
            .build()

        val pending = runtime.run(agentRequest()).toList()
            .filterIsInstance<AgentEvent.ConfirmationRequired>()
            .single()
            .pending
        val accepted = runtime.resumeConfirmation(approve(pending.pendingId))

        assertEquals(ConfirmationResumeResult.Accepted, accepted)
        assertEquals(listOf("set_temperature"), backend.calledTools)
        assertEquals(RuntimeRunStatus.COMPLETED, runtime.getRuntimeResult("request-1")?.status)
        assertEquals("recovered", runtime.getRuntimeResult("request-1")?.response)
    }

    @Test
    fun confirmedBackendMcpFailuresStopResumeBeforeAnotherModelRound() = runBlocking {
        val failures = listOf(
            """{"ok":true,"isError":true}""" to MCP_TOOL_IS_ERROR_CODE,
            """{"ok":true,"result":{"isError":true}}""" to MCP_TOOL_IS_ERROR_CODE,
            "not-json" to MCP_RESULT_INVALID_JSON_CODE,
        )

        failures.forEachIndexed { index, (resultJson, expectedCode) ->
            val backend = LoopMcpProvider(
                resultJson = resultJson,
                exposedTools = listOf(mcpTool("set_temperature")),
            )
            val runtime = AgentRuntimeBuilder.create()
                .workspaceRoot(hookWorkspace())
                .toolProvider(backend)
                .hook(confirmUntilApproved())
                .modelClient(backendThenFinishModel(mutableListOf()))
                .build()

            val pending = runtime.run(agentRequest().copy(requestId = "confirmed-failure-$index")).toList()
                .filterIsInstance<AgentEvent.ConfirmationRequired>()
                .single()
                .pending

            assertEquals(ConfirmationResumeResult.Accepted, runtime.resumeConfirmation(approve(pending.pendingId)))
            val result = runtime.getRuntimeResult("confirmed-failure-$index")
            assertEquals(RuntimeRunStatus.FAILED, result?.status)
            assertEquals(expectedCode, result?.errorCode)
            assertEquals(listOf("set_temperature"), backend.calledTools)
        }
    }

    @Test
    fun preHookRewriteIsPreservedAcrossConfirmationResume() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(hookWorkspace())
            .toolProvider(backend)
            .hook(rewriteToolArguments("set_temperature", TEMPERATURE_REWRITE_ARGS))
            .hook(confirmUntilApproved())
            .modelClient(backendThenFinishModel(seenRequests))
            .build()

        val pending = runtime.run(agentRequest()).toList()
            .filterIsInstance<AgentEvent.ConfirmationRequired>()
            .single()
            .pending
        val accepted = runtime.resumeConfirmation(approve(pending.pendingId))

        assertEquals(ConfirmationResumeResult.Accepted, accepted)
        assertEquals(listOf("set_temperature"), backend.calledTools)
        assertEquals(listOf(TEMPERATURE_REWRITE_ARGS), backend.calledArguments)
    }

    @Test
    fun policyConfigCanRequireConfirmationForBackendGlob() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(hookWorkspace())
            .toolProvider(backend)
            .policyConfigJson(
                """
                {"rules":[{"match":{"tools":["${qualifiedMcpTool("set_temperature")}"],"sources":["BACKEND"]},"decision":"require_confirmation","prompt":"Approve backend?"}]}
                """.trimIndent(),
            )
            .modelClient(backendThenFinishModel(seenRequests))
            .build()

        val pending = runtime.run(agentRequest()).toList()
            .filterIsInstance<AgentEvent.ConfirmationRequired>()
            .single()
            .pending
        val accepted = runtime.resumeConfirmation(approve(pending.pendingId))

        assertEquals(ConfirmationResumeResult.Accepted, accepted)
        assertEquals(listOf("set_temperature"), backend.calledTools)
    }

    @Test
    fun policyDenyRunsBeforeHostConfirmationHook() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(hookWorkspace())
            .toolProvider(backend)
            .policyConfigJson(BACKEND_DENY_POLICY)
            .hook(confirmUntilApproved())
            .modelClient(backendThenFinishModel(seenRequests))
            .build()

        val events = runtime.run(agentRequest()).toList()

        assertTrue(events.filterIsInstance<AgentEvent.ConfirmationRequired>().isEmpty())
        assertTrue(backend.calledTools.isEmpty())
        assertTrue(seenRequests.containsToolResult("POLICY_DENIED"))
        assertTrue(seenRequests.containsToolResult("policy blocked"))
    }

    @Test
    fun confirmationReasonsAreAggregatedAcrossPolicyAndHostHooks() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(hookWorkspace())
            .toolProvider(backend)
            .policyConfigJson(BACKEND_CONFIRM_POLICY)
            .hook(confirmWithPrompt("Host needs approval", "Host approval?"))
            .modelClient(backendThenFinishModel(seenRequests))
            .build()

        val pending = runtime.run(agentRequest()).toList()
            .filterIsInstance<AgentEvent.ConfirmationRequired>()
            .single()
            .pending
        val prompt = runtime.getRuntimeHandoff("request-1")?.nextActions?.single().orEmpty()
        val accepted = runtime.resumeConfirmation(approve(pending.pendingId))

        assertTrue(prompt.contains("Approve backend?"))
        assertTrue(prompt.contains("Host approval?"))
        assertEquals(ConfirmationResumeResult.Accepted, accepted)
        assertEquals(listOf("set_temperature"), backend.calledTools)
    }

    @Test
    fun hookChainAggregatesConfirmationReasonsAndPrompts() = runBlocking {
        val chain = RuntimeHookChain(
            listOf(
                RuntimeHookRegistration(confirmWithPrompt("Policy reason", "Policy prompt")),
                RuntimeHookRegistration(confirmWithPrompt("Host reason", "Host prompt")),
            ),
        )

        val decision = chain.preToolUse(
            ToolUse(qualifiedMcpTool("set_temperature"), "{}", ToolSource.BACKEND),
            HookContext(sessionId = "session-1", runId = "request-1", requestId = "request-1"),
        )

        require(decision is HookDecision.RequireConfirmation)
        assertTrue(decision.reason.contains("Policy reason"))
        assertTrue(decision.reason.contains("Host reason"))
        assertTrue(decision.prompt.contains("Policy prompt"))
        assertTrue(decision.prompt.contains("Host prompt"))
    }

    @Test
    fun hookMatcherSkipsUnmatchedRegistrationAndInvokesMatchingRegistration() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val skippedCalls = AtomicInteger(0)
        val matchedCalls = AtomicInteger(0)
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(hookWorkspace())
            .toolProvider(backend)
            .hook(countEveryPreHook(skippedCalls), matcher = ToolMatcher.toolName("write_file"))
            .hook(
                countEveryPreHook(matchedCalls),
                matcher = ToolMatcher.toolName(qualifiedMcpTool("set_temperature")),
            )
            .modelClient(backendThenFinishModel(seenRequests))
            .build()

        runtime.run(agentRequest()).toList()

        assertEquals(0, skippedCalls.get())
        assertEquals(1, matchedCalls.get())
        assertEquals(listOf("set_temperature"), backend.calledTools)
    }

    @Test
    fun hookRegistrationTimeoutOverridesDefaultTimeout() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(hookWorkspace())
            .toolProvider(backend)
            .hook(
                slowAllowHook(),
                matcher = ToolMatcher.toolName(qualifiedMcpTool("set_temperature")),
                timeoutMillis = 1,
            )
            .modelClient(backendThenFinishModel(seenRequests))
            .build()

        runtime.run(agentRequest()).toList()

        assertTrue(backend.calledTools.isEmpty())
        assertTrue(seenRequests.containsToolResult("HOOK_FAILED"))
    }

    @Test
    fun globalHookTimeoutStillAppliesAtBuildForDefaultRegistrations() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(hookWorkspace())
            .toolProvider(backend)
            .hook(slowAllowHook())
            .hookTimeoutMillis(1)
            .modelClient(backendThenFinishModel(seenRequests))
            .build()

        runtime.run(agentRequest()).toList()

        assertTrue(backend.calledTools.isEmpty())
        assertTrue(seenRequests.containsToolResult("HOOK_FAILED"))
    }

    @Test
    fun slowPostHookDoesNotBlockBackendResultLoop() = runBlocking {
        val postStarted = CompletableDeferred<Unit>()
        val releasePostHook = CompletableDeferred<Unit>()
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(hookWorkspace())
            .toolProvider(backend)
            .hook(blockingPostHook(postStarted, releasePostHook))
            .modelClient(backendThenFinishModel(seenRequests))
            .build()

        try {
            val eventsDeferred = async {
                runtime.run(agentRequest()).toList()
            }
            withTimeout(ASYNC_POST_HOOK_TEST_TIMEOUT_MS) { postStarted.await() }
            val result = withTimeout(ASYNC_POST_HOOK_TEST_TIMEOUT_MS) {
                eventsDeferred.await()
                    .filterIsInstance<AgentEvent.ResultAvailable>()
                    .single()
                    .result
            }

            assertEquals(RuntimeRunStatus.COMPLETED, result.status)
            assertEquals(listOf("set_temperature"), backend.calledTools)
        } finally {
            releasePostHook.complete(Unit)
        }
    }

    @Test
    fun policyConfigPathLoadsJsonFromVirtualWorkspace() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val runtime = AgentRuntimeBuilder.create()
            .virtualWorkspace(policyWorkspace(BACKEND_CONFIRM_POLICY))
            .toolProvider(backend)
            .policyConfigPath("/config/policy/backend-confirm.json")
            .modelClient(backendThenFinishModel(seenRequests))
            .build()

        val pending = runtime.run(agentRequest()).toList()
            .filterIsInstance<AgentEvent.ConfirmationRequired>()
            .single()
            .pending
        val accepted = runtime.resumeConfirmation(approve(pending.pendingId))

        assertEquals(ConfirmationResumeResult.Accepted, accepted)
        assertEquals(listOf("set_temperature"), backend.calledTools)
    }

    @Test
    fun invalidPolicyConfigFailsFastAtBuild() {
        val result = runCatching {
            AgentRuntimeBuilder.create()
                .policyConfigJson("""{"rules":[{"decision":"unknown"}]}""")
                .build()
        }

        assertTrue(requireNotNull(result.exceptionOrNull()).message.orEmpty().contains("POLICY_CONFIG_INVALID"))
    }

    @Test
    fun invalidPolicyConfigSourceFailsFastAtBuild() {
        val result = runCatching {
            AgentRuntimeBuilder.create()
                .policyConfigJson("""{"rules":[{"match":{"sources":["BACKENDD"]},"decision":"deny"}]}""")
                .build()
        }

        assertTrue(requireNotNull(result.exceptionOrNull()).message.orEmpty().contains("POLICY_CONFIG_INVALID"))
    }

    @Test
    fun policyConfigCanDenyWorkspaceWriteByPathPrefixAndArgsPattern() = runBlocking {
        val workspace = Files.createTempDirectory("agent-runtime-policy-").toFile().canonicalFile
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(workspace)
            .policyConfigJson(
                """
                {"rules":[{"match":{"tools":["write_file"],"sources":["NATIVE_WRITE"],"pathPrefix":"/memory/","argsPattern":"blocked"},"decision":"deny","userMessage":"blocked write"}]}
                """.trimIndent(),
            )
            .modelClient(writeThenFinishModel(seenRequests))
            .build()

        runtime.run(agentRequest()).toList()

        assertFalse(File(workspace, "memory/secret.txt").exists())
        assertTrue(seenRequests[1].messages.last().content.contains("POLICY_DENIED"))
    }

    @Test
    fun policyPathPrefixMatchesCanonicalMemoryAndScratchPaths() = runBlocking {
        val cases = listOf(
            "/memory/" to "//memory/secret.txt",
            "/scratch/" to "//scratch/secret.txt",
        )

        cases.forEachIndexed { index, (pathPrefix, path) ->
            val workspace = Files.createTempDirectory("agent-runtime-policy-canonical-").toFile().canonicalFile
            val seenRequests = mutableListOf<ModelRequest>()
            val runtime = AgentRuntimeBuilder.create()
                .workspaceRoot(workspace)
                .policyConfigJson(
                    """{"rules":[{"match":{"tools":["write_file"],"sources":["NATIVE_WRITE"],"pathPrefix":"$pathPrefix"},"decision":"deny","userMessage":"canonical path blocked"}]}""",
                )
                .modelClient(
                    FakeModelClient(
                        eventsByRound = listOf(
                            listOf(
                                ModelEvent.ToolCall(
                                    "write-$index",
                                    "write_file",
                                    """{"path":"$path","content":"blocked"}""",
                                ),
                                ModelEvent.Completed,
                            ),
                            listOf(
                                ModelEvent.ToolCall("finish-$index", "finish", """{"response":"done"}"""),
                                ModelEvent.Completed,
                            ),
                        ),
                        seenRequests = seenRequests,
                    ),
                )
                .build()

            runtime.run(agentRequest().copy(requestId = "canonical-policy-$index")).toList()

            assertTrue(seenRequests.containsToolResult("POLICY_DENIED"))
            assertTrue(seenRequests.containsToolResult("canonical path blocked"))
            assertFalse(File(workspace, "memory/secret.txt").exists())
            assertFalse(File(workspace, "scratch/secret.txt").exists())
        }
    }

    @Test
    fun lifecycleHooksObserveRunStartAndFinish() = runBlocking {
        val events = mutableListOf<String>()
        val runtime = AgentRuntimeBuilder.create()
            .hook(lifecycleRecorder(events))
            .modelClient(finishOnlyModel())
            .build()

        runtime.run(agentRequest()).toList()

        assertEquals(listOf("started:request-1", "finished:request-1:COMPLETED"), events)
    }

}

private fun List<ModelRequest>.containsToolResult(token: String): Boolean =
    any { request -> request.messages.any { it.content.contains(token) } }

private fun hookWorkspace(): File =
    Files.createTempDirectory("agent-runtime-hook-policy-").toFile().canonicalFile

private fun policyWorkspace(policyJson: String): VfsFileSystem {
    val policySource = VfsMemorySource(mapOf("backend-confirm.json" to policyJson))
    val runtimeSource = VfsMemorySource()
    return VfsFileSystem.fromJson(
        jsonText = """
            {
              "schemaVersion": 1,
              "vfsContractVersion": "1.0.0",
              "workspaceRoot": "/",
              "mounts": [
                {
                  "id": "policy",
                  "source": "memory://policy",
                  "target": "/config/policy",
                  "mode": "readOnly",
                  "priority": 50
                },
                {
                  "id": "runtime",
                  "source": "memory://runtime",
                  "target": "/",
                  "mode": "readWrite",
                  "priority": 10
                }
              ]
            }
        """.trimIndent(),
        sourceResolver = VfsSourceResolver { source ->
            when (source) {
                "memory://policy" -> policySource
                "memory://runtime" -> runtimeSource
                else -> null
            }
        },
    )
}

private val BACKEND_CONFIRM_POLICY = """
    {"rules":[{"match":{"tools":["mcp__default__set_*"],"sources":["BACKEND"]},"decision":"require_confirmation","prompt":"Approve backend?"}]}
""".trimIndent()

private val BACKEND_DENY_POLICY = """
    {"rules":[{"match":{"tools":["mcp__default__set_*"],"sources":["BACKEND"]},"decision":"deny","userMessage":"policy blocked"}]}
""".trimIndent()

private fun confirmWithPrompt(reason: String, prompt: String): RuntimeHook = object : RuntimeHook {
    override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision =
        if (originalMcpToolName(call.toolName) != "set_temperature" || context.approvedConfirmation) {
            HookDecision.Allow()
        } else {
            HookDecision.RequireConfirmation(reason, prompt)
        }
}

private fun rewriteToolArguments(toolName: String, updatedArgumentsJson: String): RuntimeHook = object : RuntimeHook {
    override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision =
        if (originalMcpToolName(call.toolName) == toolName) {
            HookDecision.Allow(updatedArgumentsJson = updatedArgumentsJson)
        } else {
            HookDecision.Allow()
        }
}

private fun recordPreHookArguments(toolName: String, seenArguments: MutableList<String>): RuntimeHook =
    object : RuntimeHook {
        override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision {
            if (originalMcpToolName(call.toolName) == toolName) {
                seenArguments += call.argumentsJson
            }
            return HookDecision.Allow()
        }
    }

private fun countEveryPreHook(counter: AtomicInteger): RuntimeHook = object : RuntimeHook {
    override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision {
        counter.incrementAndGet()
        return HookDecision.Allow()
    }
}

private fun countingHook(counter: AtomicInteger): RuntimeHook = object : RuntimeHook {
    override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision {
        if (originalMcpToolName(call.toolName) == "set_temperature") {
            counter.incrementAndGet()
        }
        return HookDecision.Allow()
    }
}

private fun blockingPostHook(
    postStarted: CompletableDeferred<Unit>,
    releasePostHook: CompletableDeferred<Unit>,
): RuntimeHook = object : RuntimeHook {
    override suspend fun onPostToolUse(call: ToolUse, result: ToolUseResult, context: HookContext) {
        if (originalMcpToolName(call.toolName) == "set_temperature") {
            postStarted.complete(Unit)
            releasePostHook.await()
        }
    }
}

private const val ASYNC_POST_HOOK_TEST_TIMEOUT_MS = 5_000L
private const val TEMPERATURE_REWRITE_ARGS = """{"degreesCelsius":22}"""
