package cc.noharry.agent.appserver.evidence
import cc.noharry.agent.transport.HttpRequest
import cc.noharry.agent.transport.HttpResponse
import cc.noharry.agent.appserver.usage.TokenDelta
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    @Test
    fun requestAndResponseLogsAreRedactedAndSummarized() {
        val logFile = Files.createTempDirectory("debug-diagnostics-").resolve("run.log").toFile()
        val entries = mutableListOf<DiagnosticEntry>()
        val diagnostics = AppDiagnostics(
            logFile = logFile,
            onEntry = { entries += it },
            nowMillis = { 123L },
        )

        val requestId = diagnostics.recordRequest(
            provider = "OpenAI",
            mode = "execute",
            request = HttpRequest(
                method = "POST",
                url = "https://provider.test/v1/chat/completions",
                headers = mapOf("Authorization" to "Bearer sk-unitsecret123"),
                body = providerBody().toByteArray(Charsets.UTF_8),
            ),
        )
        diagnostics.recordResponse(
            provider = "OpenAI",
            mode = "execute",
            callId = requestId,
            response = HttpResponse(400, body = """{"error":"bad request"}""".toByteArray(Charsets.UTF_8)),
        )

        kotlinx.coroutines.runBlocking { diagnostics.flush() }
        val log = logFile.readText(Charsets.UTF_8)
        // Request is file-only; failed response (400) still surfaces one UI entry.
        assertEquals(1, entries.size)
        assertTrue(entries.single().title.contains("Provider response"))
        assertTrue(log.contains("status=400"))
        assertTrue(log.contains("model=deepseek-v4-pro"))
        assertTrue(log.contains("roles=user/assistant/tool"))
        assertTrue(log.contains("toolResultMessages=1"))
        assertTrue(log.contains("read_file"))
        assertFalse(log.contains("sk-unitsecret123"))
        assertFalse(entries.joinToString("\n") { it.body }.contains("sk-unitsecret123"))
    }

    @Test
    fun redactionCoversBearerTokensAndJsonSecrets() {
        val redacted = """
            {"api_key":"sk-jsonsecret123","note":"Bearer sk-bearersecret123","raw":"sk-rawsecret123"}
        """.trimIndent().redacted()

        assertFalse(redacted.contains("sk-jsonsecret123"))
        assertFalse(redacted.contains("sk-bearersecret123"))
        assertFalse(redacted.contains("sk-rawsecret123"))
        assertTrue(redacted.contains("<redacted>"))
        assertTrue(redacted.contains("sk-REDACTED"))
    }

    @Test
    fun streamDoneLogsChunkSummaryAndRedactedBodyPrefix() {
        val logFile = Files.createTempDirectory("debug-diagnostics-stream-").resolve("run.log").toFile()
        val entries = mutableListOf<DiagnosticEntry>()
        val diagnostics = AppDiagnostics(
            logFile = logFile,
            onEntry = { entries += it },
            nowMillis = { 456L },
        )

        diagnostics.recordStreamDone(
            provider = "OpenAI",
            callId = 3,
            chunks = 2,
            bytes = 128,
            bodyPrefix = """data: {"api_key":"sk-streamsecret123","content":"ok"}""",
        )

        kotlinx.coroutines.runBlocking { diagnostics.flush() }
        val log = logFile.readText(Charsets.UTF_8)
        assertTrue(log.contains("chunks=2 bytes=128"))
        assertTrue(log.contains("bodyPrefix=data:"))
        assertFalse(log.contains("sk-streamsecret123"))
        // Successful stream completion stays off the conversation timeline.
        assertEquals(0, entries.size)
    }

    @Test
    fun responseEmitsTokenUsageWithoutRawBodyInUiEntry() {
        val logFile = Files.createTempDirectory("debug-diagnostics-usage-").resolve("run.log").toFile()
        val entries = mutableListOf<DiagnosticEntry>()
        val usages = mutableListOf<TokenDelta>()
        val diagnostics = AppDiagnostics(
            logFile = logFile,
            onEntry = { entries += it },
            onTokenUsage = { usages += it },
            nowMillis = { 789L },
        )

        diagnostics.recordResponse(
            provider = "OpenAI",
            mode = "execute",
            callId = 7,
            response = HttpResponse(
                200,
                body = """{"usage":{"prompt_tokens":10,"completion_tokens":4,"total_tokens":14}}"""
                    .toByteArray(Charsets.UTF_8),
            ),
        )

        assertEquals(1, usages.size)
        assertEquals(10, usages.single().inputTokens)
        // Successful responses stay file-only; token usage still reaches the board.
        assertTrue(entries.isEmpty())
        kotlinx.coroutines.runBlocking { diagnostics.flush() }
        assertTrue(logFile.readText(Charsets.UTF_8).contains("prompt_tokens"))
    }
}

private fun providerBody(): String =
    """
        {
          "model": "deepseek-v4-pro",
          "stream": false,
          "messages": [
            {"role": "user", "content": "debug"},
            {
              "role": "assistant",
              "content": "",
              "tool_calls": [
                {
                  "id": "call_00_debug",
                  "type": "function",
                  "function": {"name": "read_file", "arguments": "{\"path\":\"/mcp/servers.json\"}"}
                }
              ]
            },
            {"role": "tool", "tool_call_id": "call_00_debug", "content": "{\"ok\":true}"}
          ],
          "tools": [
            {
              "type": "function",
              "function": {"name": "read_file", "parameters": {"type": "object"}}
            }
          ]
        }
    """.trimIndent()
