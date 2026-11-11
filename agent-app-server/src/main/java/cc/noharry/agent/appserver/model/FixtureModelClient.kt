@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.model

import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.modelText
import cc.noharry.agent.spi.ExperimentalAgentRuntimeApi
import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelMessage
import cc.noharry.agent.spi.model.ModelRequest
import cc.noharry.agent.runtime.RuntimeInspection.RUN_CONTEXT_HEADER
import cc.noharry.agent.runtime.RuntimeInspection.DEFERRED_TOOLS_HEADER
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonPrimitive

/**
 * The simulated backend's model when the host has none of its own ([cc.noharry.agent.appserver.server.AppServerConfig.simulatedModel]):
 * with no network, it introduces the assistant when asked who it is, and otherwise says what it heard and that no
 * simulated case covers it, in the persona's language.
 */
internal class FixtureModelClient : ModelClient {
    @OptIn(ExperimentalAgentRuntimeApi::class)
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()

    private var calls = 0

    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        val heard = latestUserText(request.messages)
        val reply = if (heard.lowercase().isIntroRequest()) introduction() else uncovered(heard)
        calls += 1
        emit(ModelEvent.UsageDelta(inputTokens = request.messages.sumOf { it.content.length } / USAGE_CHAR_DIVISOR, outputTokens = 8))
        emit(ModelEvent.ToolCall("fixture-finish-$calls", "finish", """{"response":${JsonPrimitive(reply)}}"""))
        emit(ModelEvent.Completed)
    }
}

private fun introduction(): String = AppHost.persona.name.let { name ->
    modelText("我是$name：在这台设备上帮你查信息、调用工具，把事办完。", "I'm $name: I look things up and use tools on this device to get things done for you.")
}

private fun uncovered(heard: String): String = heard.oneLine().let { said ->
    modelText(
        "Simulated MCP 收到了本轮输入：$said。当前模拟场景未覆盖这个输入，请切换 Live 或补充模拟用例。",
        "The simulated model got this turn's input: $said. No simulated case covers it; switch to a live model or add one.",
    )
}

private fun String.isIntroRequest(): Boolean =
    contains("介绍") || contains("你自己") || contains("名字") || contains("who are you") || contains("your name")

private fun String.oneLine(): String = replace(Regex("""\s+"""), " ").trim().take(ECHO_CHARS)

/** The person's last words: not the run's context, the deferred tools' list or the persona's opening. */
private fun latestUserText(messages: List<ModelMessage>): String =
    messages.lastOrNull { message ->
        message.role == "user" &&
            !message.content.startsWith(RUN_CONTEXT_HEADER) &&
            !message.content.startsWith(DEFERRED_TOOLS_HEADER) &&
            !(AppHost.persona.identity.isNotEmpty() && message.content.startsWith(AppHost.persona.identity))
    }?.content.orEmpty()

private const val USAGE_CHAR_DIVISOR = 4
private const val ECHO_CHARS = 80
