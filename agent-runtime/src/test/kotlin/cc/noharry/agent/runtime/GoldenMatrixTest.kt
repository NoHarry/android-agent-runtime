@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.ConfirmationResumeResult
import cc.noharry.agent.kernel.api.SessionRunConflictPolicy
import cc.noharry.agent.dream.DreamMode
import cc.noharry.agent.dream.DreamRequest
import cc.noharry.agent.dream.NoOpDreamWorker
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.reliability.RuntimeEvidenceType
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import cc.noharry.agent.kernel.core.RuntimeBuildType
import cc.noharry.agent.kernel.core.ToolProviderRegistry
import cc.noharry.agent.kernel.core.TraceVisibilityConfig
import cc.noharry.agent.kernel.core.RELEASE_RAW_TRACE_ERROR

class GoldenMatrixTest {
    @Test
    fun tc001AutonomousMcpBrowseExecutesLowRiskTool() = runBlocking {
        val backend = GoldenMcpProvider(schemas = mapOf("set_temperature" to TEMPERATURE_SCHEMA))
        val model = GoldenScriptedModel(
            listOf(
                round(tool("read_file", """{"path":"/mcp/servers.json"}""")),
                round(tool("read_file", """{"path":"/mcp/servers/default/tools.json"}""")),
                round(tool("read_file", """{"path":"/mcp/tools/${qualifiedMcpTool("set_temperature")}/schema.json"}""")),
                round(planCall("set_temperature", """{"degreesCelsius":22}""")),
                round(finish("climate updated")),
            ),
        )
        val runtime = runtime(model, backend, workspace()).build() as DefaultAgentRuntime

        val events = runtime.run(goldenRequest()).toList()

        assertEquals(listOf("set_temperature"), backend.calledTools)
        assertFalse(model.emittedToolNames.contains("search_files"))
        assertFalse(backend.calledTools.contains("seat_adjust"))
        assertEquals(RuntimeRunStatus.COMPLETED, runtime.getRunState("request-1")?.status)
        assertTrue(runtime.getRuntimeTraceEvents("request-1").hasEvents("RUN_CREATED", "RESULT_WRITTEN"))
        assertTrue(events.any { it is AgentEvent.NativeToolCallFinished && it.toolName.contains("set_temperature") })
    }

    @Test
    fun tc002ModelReadsMemoryWithoutRuntimeMemoryHit() = runBlocking {
        val root = workspace()
        MemoryWorkspace(File(root, "memory")).apply {
            initialize()
            writeMemory(MemoryCategory.FEEDBACK, "driver-seat", "Driver prefers calm cabin.")
        }
        val backend = GoldenMcpProvider()
        val model = GoldenScriptedModel(
            listOf(
                round(tool("read_file", """{"path":"/memory/MEMORY.md"}""")),
                round(tool("read_file", """{"path":"/memory/feedback/driver-seat.md"}""")),
                round(schemaRead("set_temperature")),
                round(planCall("set_temperature", "{}")),
                round(finish("memory considered")),
            ),
        )
        val runtime = runtime(model, backend, root).build()

        runtime.run(goldenRequest()).toList()
        val traceNames = runtime.getRuntimeTraceEvents("request-1").map { it.eventName }

        assertEquals(listOf("set_temperature"), backend.calledTools)
        assertFalse(traceNames.contains("MEMORY_HIT"))
        assertFalse(traceNames.contains("ROUTINE_MATCH"))
        assertFalse(backend.calledTools.contains("seat_adjust"))
    }

    @Test
    fun tc003ConfirmationApproveResumesBackendOnce() = runBlocking {
        val backend = GoldenMcpProvider()
        val runtime = runtime(executePlanModel(), backend, workspace())
            .policyRule(confirmUnlessApproved())
            .build()
        val pending = runtime.run(goldenRequest()).toList()
            .filterIsInstance<AgentEvent.ConfirmationRequired>()
            .single()
            .pending

        assertTrue(backend.calledTools.isEmpty())
        assertNotNull(runtime.getRuntimeHandoff("request-1"))
        assertEquals(ConfirmationResumeResult.Accepted, runtime.resumeConfirmation(approve(pending.pendingId)))
        assertEquals(ConfirmationResumeResult.AlreadyConsumed, runtime.resumeConfirmation(approve(pending.pendingId)))
        assertEquals(listOf("set_temperature"), backend.calledTools)
        assertEquals(RuntimeRunStatus.COMPLETED, runtime.getRuntimeResult("request-1")?.status)
    }

    @Test
    fun tc004ConfirmationDenyAndTimeoutDoNotCallBackend() = runBlocking {
        val deniedBackend = GoldenMcpProvider()
        val deniedRuntime = runtime(executePlanModel(), deniedBackend, workspace())
            .policyRule(confirmUnlessApproved())
            .build()
        val deniedPending = deniedRuntime.run(goldenRequest()).toList()
            .filterIsInstance<AgentEvent.ConfirmationRequired>()
            .single()
            .pending

        assertTrue(deniedRuntime.resumeConfirmation(deny(deniedPending.pendingId)) is ConfirmationResumeResult.Rejected)
        assertTrue(deniedBackend.calledTools.isEmpty())
        assertEquals(RuntimeRunStatus.CANCELLED, deniedRuntime.getRunState("request-1")?.status)

        var now = 1_000L
        val timeoutBackend = GoldenMcpProvider()
        val timeoutRuntime = runtime(executePlanModel(), timeoutBackend, workspace())
            .nowEpochMillis { now }
            .policyRule(confirmUnlessApproved())
            .build()
        val timeoutPending = timeoutRuntime.run(goldenRequest()).toList()
            .filterIsInstance<AgentEvent.ConfirmationRequired>()
            .single()
            .pending
        now = timeoutPending.expiresAtEpochMillis

        assertEquals(ConfirmationResumeResult.Expired, timeoutRuntime.resumeConfirmation(approve(timeoutPending.pendingId)))
        assertTrue(timeoutBackend.calledTools.isEmpty())
        assertEquals(RuntimeRunStatus.CANCELLED, timeoutRuntime.getRunState("request-1")?.status)
    }

    @Test
    fun tc005TracePrivacyRejectsRawTraceAndRedactsSummary() = runBlocking {
        val rawTraceRead = VirtualWorkspace(workspace()).executeJson(
            toolName = "read_file",
            argumentsJson = """{"path":"/traces/current.jsonl"}""",
        )
        val releaseError = try {
            AgentRuntimeBuilder.create()
                .traceVisibility(TraceVisibilityConfig(RuntimeBuildType.RELEASE, exposeRawTraceWorkspace = true))
                .build()
            null
        } catch (error: IllegalArgumentException) {
            error.message
        }
        val runtime = runtime(sensitiveModel(), GoldenMcpProvider(), workspace()).build() as DefaultAgentRuntime

        runtime.run(goldenRequest()).toList()
        val summary = runtime.getRedactedTraceSummary("request-1").toString()

        assertTrue(rawTraceRead.contains("INVALID_WORKSPACE_PATH"))
        assertEquals(RELEASE_RAW_TRACE_ERROR, releaseError)
        assertFalse(summary.contains("sk-goldsecret123"))
        assertFalse(summary.contains("13800138000"))
        assertTrue(summary.contains("[REDACTED_SECRET]"))
    }

    @Test
    fun tc006LazySchemaMaterializerInspectsOnce() = runBlocking {
        val backend = GoldenMcpProvider(schemas = mapOf("set_temperature" to TEMPERATURE_SCHEMA))
        val workspace = VirtualWorkspace(
            workspace(),
            VirtualFileMaterializer(mcpRegistry = ToolProviderRegistry(listOf(backend))),
        )

        val first = workspace.executeJson(
            "read_file",
            """{"path":"/mcp/tools/${qualifiedMcpTool("set_temperature")}/schema.json"}""",
        )
        val second = workspace.executeJson(
            "read_file",
            """{"path":"/mcp/tools/${qualifiedMcpTool("set_temperature")}/schema.json"}""",
        )

        assertTrue(first.contains("degreesCelsius"))
        assertTrue(second.contains("cache_hit\":true"))
    }

    @Test
    fun tc007DirectBackendCallsCanContinueAfterWorkspaceRead() = runBlocking {
        val root = workspace().also { File(it, "state.json").writeText("""{"state":"ready"}""") }
        val backend = GoldenMcpProvider()
        val model = GoldenScriptedModel(
            listOf(
                round(schemaRead("set_temperature")),
                round(planCall("set_temperature", "{}")),
                round(tool("read_file", """{"path":"/state.json"}""")),
                round(schemaRead("read_cabin")),
                round(planCall("read_cabin", "{}")),
                round(finish("two plans complete")),
            ),
        )
        val runtime = runtime(model, backend, root).build() as DefaultAgentRuntime

        runtime.run(goldenRequest()).toList()
        val workpad = runtime.getRuntimeWorkpad("request-1")

        assertEquals(listOf("set_temperature", "read_cabin"), backend.calledTools)
        assertEquals(2, workpad?.attemptedTools?.count { it.contains("set_temperature") || it.contains("read_cabin") })
        assertTrue(workpad?.planSummaries.orEmpty().isEmpty())
        assertTrue(workpad?.readPaths.orEmpty().contains("/state.json"))
    }

    @Test
    fun tc008IndependentBackendFailureDoesNotBlockSiblingCall() = runBlocking {
        val backend = GoldenMcpProvider(failingTools = setOf("set_temperature"))
        val model = GoldenScriptedModel(
            listOf(
                round(schemaRead("set_temperature"), schemaRead("read_cabin")),
                round(
                    tool(qualifiedMcpTool("set_temperature"), "{}"),
                    tool(qualifiedMcpTool("read_cabin"), "{}"),
                ),
                round(finish("partial handled")),
            ),
        )
        val runtime = runtime(model, backend, workspace()).build()

        runtime.run(goldenRequest()).toList()

        assertEquals(listOf("set_temperature", "read_cabin"), backend.calledTools)
        assertEquals(RuntimeRunStatus.COMPLETED, runtime.getRuntimeResult("request-1")?.status)
    }

    @Test
    fun tc009ResumeFirstBlocksNewRunWhenConfirmationPending() = runBlocking {
        val backend = GoldenMcpProvider()
        val runtime = runtime(executePlanModel(), backend, workspace())
            .policyRule(confirmUnlessApproved())
            .build()

        runtime.run(goldenRequest()).toList()
        val missingPolicy = runtime.run(goldenRequest(requestId = "request-2")).toList()
            .filterIsInstance<AgentEvent.RuntimeBlocked>()
            .single()
        val blocked = runtime.run(
            goldenRequest(requestId = "request-3")
                .copy(sessionRunConflictPolicy = SessionRunConflictPolicy.RESUME_CURRENT),
        ).toList()
            .filterIsInstance<AgentEvent.RuntimeBlocked>()
            .single()

        assertTrue(backend.calledTools.isEmpty())
        assertEquals(RuntimeErrorCode.ADMISSION_POLICY_REQUIRED.wireCode, missingPolicy.blocker.code)
        assertEquals("PENDING_CONFIRMATION_EXISTS", blocked.blocker.code)
        assertNotNull(blocked.blocker.handoff)
        assertNull(runtime.getRuntimeResult("request-1"))
    }

    @Test
    fun tc010SchemaFailureCanBeFixedAndCandidateStaysReviewOnly() = runBlocking {
        val root = workspace()
        val backend = GoldenMcpProvider(schemas = mapOf("set_temperature" to TEMPERATURE_SCHEMA))
        val model = GoldenScriptedModel(
            listOf(
                round(schemaRead("set_temperature")),
                round(planCall("set_temperature", "{}")),
                round(planCall("set_temperature", """{"degreesCelsius":22}""")),
                round(finish("schema fixed")),
            ),
        )
        val runtime = runtime(model, backend, root).build()

        runtime.run(goldenRequest()).toList()
        val dream = NoOpDreamWorker().run(
            DreamRequest(root, File(root, "trace"), File(root, "memory"), DreamMode.CONSOLIDATE_MEMORY),
        )

        assertTrue(model.seenRequests.any { request ->
            request.messages.any { it.content.contains("SCHEMA_VALIDATION_FAILED") }
        })
        assertEquals(listOf("set_temperature"), backend.calledTools)
        assertEquals(RuntimeRunStatus.COMPLETED, runtime.getRuntimeResult("request-1")?.status)
        assertTrue(runtime.getRuntimeTraceEvents("request-1").hasEvents("RESULT_WRITTEN"))
        assertTrue(runtime.getRuntimeResult("request-1")?.evidenceIds.orEmpty().isNotEmpty())
        assertTrue(dream.candidates.isEmpty())
        assertFalse(File(root, "memory/user").listFiles().orEmpty().any { it.isFile })
    }

    private fun runtime(model: GoldenScriptedModel, backend: GoldenMcpProvider, root: File): AgentRuntimeBuilder =
        AgentRuntimeBuilder.create().workspaceRoot(root).toolProvider(backend).modelClient(model)

    private fun workspace(): File = Files.createTempDirectory("agent-golden-").toFile().canonicalFile

    private fun executePlanModel(): GoldenScriptedModel =
        GoldenScriptedModel(
            listOf(
                round(schemaRead("set_temperature")),
                round(planCall("set_temperature", "{}")),
                round(finish()),
            ),
        )

    private fun sensitiveModel(): GoldenScriptedModel =
        GoldenScriptedModel(
            listOf(
                round(schemaRead("set_temperature")),
                round(planCall("set_temperature", """{"token":"sk-goldsecret123"}""")),
                round(finish("done token=sk-goldsecret123 phone=13800138000")),
            ),
        )

    private fun planCall(toolName: String, argumentsJson: String): ModelEvent.ToolCall =
        tool(qualifiedMcpTool(toolName), argumentsJson)

    private fun List<cc.noharry.agent.kernel.api.reliability.RuntimeTraceEvent>.hasEvents(vararg names: String): Boolean =
        names.all { expected -> any { it.eventName == expected } }
}
