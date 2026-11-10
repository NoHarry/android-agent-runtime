package cc.noharry.agent.samples.acp

import cc.noharry.agent.AgentRuntime
import cc.noharry.agent.acp.AcpAgent
import cc.noharry.agent.acp.AcpAgentConfig
import cc.noharry.agent.builder
import cc.noharry.agent.model.OpenAiModelClient
import cc.noharry.agent.runtime.functionTools
import java.io.File
import java.time.LocalDateTime
import kotlinx.coroutines.runBlocking

/**
 * An agent an ACP client (an editor such as Zed) starts as a subprocess and talks to over stdin and stdout: an
 * OpenAI-compatible model, one tool of its own, and conversations kept under ~/.agent-acp-sample so the client can
 * load them again. Build it with `./gradlew :samples:acp-jvm:installDist` and point the client at
 * `samples/acp-jvm/build/install/acp-jvm/bin/acp-jvm`, with AGENT_API_KEY (and AGENT_BASE_URL, AGENT_MODEL) set.
 * Nothing but protocol messages may go to stdout; say anything else on stderr.
 */
fun main() = runBlocking {
    val apiKey = System.getenv("AGENT_API_KEY") ?: error("Set AGENT_API_KEY")
    val clock = functionTools("clock") {
        tool("now", "The current local date and time.", readOnly = true) { LocalDateTime.now().toString() }
    }
    val runtime = AgentRuntime.builder()
        .model(
            OpenAiModelClient(
                apiKeyProvider = { apiKey },
                model = System.getenv("AGENT_MODEL") ?: "gpt-4o-mini",
                baseUrl = System.getenv("AGENT_BASE_URL") ?: "https://api.openai.com/v1",
                stream = true,
            ),
        )
        .tools(clock)
        .storage(File(System.getProperty("user.home"), ".agent-acp-sample"))
        .build()
    runtime.use { AcpAgent(it, AcpAgentConfig(name = "acp-jvm", title = "ACP sample agent", version = "1")).serveStdio() }
}
