package cc.noharry.agent.samples.quickstart

import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.mcp.McpHttpClient
import cc.noharry.agent.mcp.McpToolProvider
import cc.noharry.agent.model.OpenAiModelClient
import cc.noharry.agent.runtime.AgentRuntimeBuilder
import cc.noharry.agent.runtime.functionTools
import cc.noharry.agent.transport.OkHttpHttpTransport
import java.nio.file.Files
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking

/**
 * A command-line agent: an OpenAI-compatible model, one tool of its own and, with MCP_URL, a remote MCP server.
 *
 *     AGENT_API_KEY=… [AGENT_BASE_URL=…] [AGENT_MODEL=…] [MCP_URL=…] ./gradlew :samples:quickstart-jvm:run --args="…"
 */
fun main(args: Array<String>) = runBlocking {
    val apiKey = System.getenv("AGENT_API_KEY") ?: error("Set AGENT_API_KEY")
    val clock = functionTools("clock") {
        tool("now", "The current local date and time.", readOnly = true) { LocalDateTime.now().toString() }
    }
    val builder = AgentRuntimeBuilder.create()
        .modelClient(
            OpenAiModelClient(
                apiKeyProvider = { apiKey },
                model = System.getenv("AGENT_MODEL") ?: "gpt-4o-mini",
                baseUrl = System.getenv("AGENT_BASE_URL") ?: "https://api.openai.com/v1",
                stream = true,
            ),
        )
        .toolProvider(clock)
        .workspaceRoot(Files.createTempDirectory("agent-workspace").toFile())
    System.getenv("MCP_URL")?.let { url ->
        val client = McpHttpClient(url, headers = { emptyMap() }, http = OkHttpHttpTransport(), clientName = "quickstart", clientVersion = "0.1")
        builder.toolProvider(McpToolProvider(client, serverId = "remote"))
    }
    val question = args.joinToString(" ").ifBlank { "What time is it now?" }
    builder.build().run(AgentRequest(requestId = "r1", sessionId = "s1", input = question)).collect { event ->
        when (event) {
            is AgentEvent.TextDelta -> print(event.text)
            is AgentEvent.NativeToolCallStarted -> println("[tool] ${event.toolName}")
            is AgentEvent.Failed -> println("[failed] ${event.code}: ${event.message}")
            else -> Unit
        }
    }
    println()
}
