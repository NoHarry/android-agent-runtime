@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.spi.model.ModelMessage
import cc.noharry.agent.spi.model.ModelRequest
import cc.noharry.agent.spi.model.ModelTool
import org.junit.Assert.assertEquals
import org.junit.Test

class PromptPrefixTest {
    @Test fun reportsTheFirstPartThatChangedSinceTheSessionsPreviousRequest() {
        val key = "prefix-test-${System.nanoTime()}"
        fun request(system: String, tools: List<String>, vararg texts: String) = ModelRequest(
            messages = texts.map { ModelMessage(role = "user", content = it) },
            tools = tools.map { ModelTool(it, "{}") },
            system = system,
            promptCacheKey = key,
        )

        assertEquals("first request of this session", PromptPrefix.compare(request("规则", listOf("finish"), "空调")))
        // A tool added at the end still ends the cached prefix there: the messages come after the tools.
        assertEquals(
            "system same 6/6B · tools same 1/2 · messages same 1/2 · first change: tools",
            PromptPrefix.compare(request("规则", listOf("finish", "set_temp"), "空调", "结果")),
        )
        assertEquals(
            "system same 6/6B · tools same 2/2 · messages same 2/3 · first change: none (only appended)",
            PromptPrefix.compare(request("规则", listOf("finish", "set_temp"), "空调", "结果", "完成")),
        )
        assertEquals(
            "system same 6/6B · tools same 2/2 · messages same 0/1 · first change: messages",
            PromptPrefix.compare(request("规则", listOf("finish", "set_temp"), "风量")),
        )
        assertEquals(
            "system same 6/9B · tools same 2/2 · messages same 0/1 · first change: system",
            PromptPrefix.compare(request("规则二", listOf("finish", "set_temp"), "音量")),
        )
    }
}
