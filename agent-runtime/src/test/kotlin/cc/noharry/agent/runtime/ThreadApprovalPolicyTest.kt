@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.*
import cc.noharry.agent.spi.*
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.kernel.core.tools.NetworkToolPolicyDecision
import cc.noharry.agent.runtime.thread.approvalDomain
import cc.noharry.agent.runtime.thread.ThreadItemCodec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ThreadApprovalPolicyTest {
    @Test fun legacyAskDenialsAndExpirationsDoNotClaimUserApproval() {
        val codec = ThreadItemCodec(DefaultTraceRedactor)
        for (status in listOf(ItemStatus.DECLINED, ItemStatus.FAILED, ItemStatus.COMPLETED)) {
            val item = ThreadItem.ToolCall("item", "turn", "tool", ToolSource.BACKEND, status = status,
                approvedBy = ApprovalSource.USER)
            val encoded = codec.encode(item)
            val legacy = JsonObject(encoded + ("approvedBy" to JsonPrimitive("ASK")))
            val expected = ApprovalSource.USER.takeIf { status == ItemStatus.COMPLETED }
            assertEquals(expected, (codec.decode(legacy) as ThreadItem.ToolCall).approvedBy)
            assertEquals(ApprovalSource.USER, (codec.decode(encoded) as ThreadItem.ToolCall).approvedBy)
        }
    }

    @Test fun approvalSourceDescribesTheDecisionAndDenialNeverClaimsApproval() = runBlocking {
        for (policy in ApprovalPolicy.entries) approvalBuilder().build().use { runtime ->
            val events = mutableListOf<ThreadEvent>()
            runtime.startThread(ThreadOptions(approvalPolicy = policy)).runStreamed("set temperature").collect { event ->
                events += event
                if (event is ThreadEvent.ItemStarted && event.item is Approval) (event.item as Approval).approve()
            }
            assertTurnEvents(events)
            val tool = events.filterIsInstance<ThreadEvent.ItemCompleted>().map { it.item }
                .filterIsInstance<ThreadItem.ToolCall>().single { it.tool == qualifiedMcpTool("set_temperature") }
            val source = when (policy) {
                ApprovalPolicy.ASK, ApprovalPolicy.REMEMBER -> ApprovalSource.USER
                ApprovalPolicy.APPROVE_ALL -> ApprovalSource.AUTOMATIC
                ApprovalPolicy.DENY_ALL -> null
            }
            assertEquals(source, tool.approvedBy)
        }
    }

    @Test fun rememberingOneMcpToolDoesNotApproveAnotherToolOnTheSameServer() = runBlocking {
        val names = listOf("set_temperature", "set_fan")
        val scripts = names.flatMapIndexed { index, name -> listOf(
            listOf(schemaRead(name), ModelEvent.Completed),
            listOf(ModelEvent.ToolCall("call-$index", qualifiedMcpTool(name), "{}"), ModelEvent.Completed),
        ) } + listOf(finishEvents("done"))
        val hook = object : RuntimeHook {
            override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision =
                if (call.source != ToolSource.BACKEND || context.approvedConfirmation) HookDecision.Allow()
                else HookDecision.RequireConfirmation("Needs approval", "Approve this tool?")
        }
        val provider = LoopMcpProvider(exposedTools = names.map(::mcpTool))
        threadBuilder(ThreadScriptModel(scripts)).tools(provider).hook(hook).build().use { runtime ->
            val approvals = mutableListOf<Approval>()
            runtime.startThread(ThreadOptions(approvalPolicy = ApprovalPolicy.REMEMBER)).runStreamed("set both").collect { event ->
                if (event is ThreadEvent.ItemStarted && event.item is Approval) {
                    approvals += event.item as Approval
                    (event.item as Approval).approve()
                }
            }
            assertEquals(2, approvals.size)
            assertEquals(2, approvals.map { it.domain }.toSet().size)
            assertEquals(names, provider.calledTools)
            assertTrue(approvals.all { it.domain.startsWith("mcp:") })
        }
    }

    @Test fun networkApprovalAreasUseTheSameCanonicalHostsAsTheNetworkPolicy() {
        val backend = NetworkToolBackend()
        for (url in listOf("https://FOO_BAR.example/path", "  https://foo_bar.example./other  ")) {
            val arguments = """{"url":"$url"}"""
            val policy = backend.authorize("run", arguments) as NetworkToolPolicyDecision.RequiresConfirmation
            assertEquals("web_fetch:foo_bar.example", approvalDomain("web_fetch", arguments))
            assertEquals("foo_bar.example", backend.activityDomain("web_fetch", arguments))
            assertTrue(policy.prompt.contains("foo_bar.example"))
        }
        assertNotEquals(approvalDomain("web_fetch", """{"url":"https://example.com"}"""),
            approvalDomain("mcp__example.com__fetch", "{}"))
        assertNotEquals(approvalDomain("mcp__server_one__first", "{}"), approvalDomain("mcp__server_one__second", "{}"))
    }

    @Test fun invalidNetworkTargetsNeverGetARememberableCatchAllArea() {
        for (arguments in listOf("{}", "not-json", """{"url":"invalid"}""", """{"url":"https://user:password@example.com"}""",
            """{"url":"https://example.com","max_bytes":0}""", """{"url":"http://127.0.0.1"}""")) {
            assertNull(approvalDomain("web_fetch", arguments))
            assertTrue(NetworkToolBackend().authorize("run", arguments) is NetworkToolPolicyDecision.Denied)
        }
    }
}
