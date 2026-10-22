package cc.noharry.agent.model

import cc.noharry.agent.kernel.api.model.ModelClientException

import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import cc.noharry.agent.kernel.api.model.ModelImage
import cc.noharry.agent.kernel.api.model.ServerSideSearchCapability
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelResponseMetadata
import cc.noharry.agent.kernel.core.completed

class ModelClientContractTest {
    @Test
    fun openAiNonStreamingParsesThinkingTextAndToolCall() = runBlocking {
        val transport = FakeHttpTransport(
            executeResponse = providerResponse(
                """
                    {
                      "choices":[{
                        "message":{
                          "reasoning_content":"thinking",
                          "content":"visible text",
                          "tool_calls":[{
                            "id":"call-1",
                            "type":"function",
                            "function":{"name":"demo_tool","arguments":"{\"type\":\"plan\"}"}
                          }]
                        }
                      }]
                    }
                """.trimIndent(),
            ),
        )
        val events = OpenAiModelClient(::contractApiKey, CONTRACT_MODEL, CONTRACT_BASE_URL, transport = transport)
            .complete(contractModelRequest())
            .toList()

        assertEquals(listOf("thinking", "visible text", "demo_tool", "Completed"), eventSummary(events))
        assertEquals("$CONTRACT_BASE_URL/chat/completions", transport.executeRequests.single().url)
        assertEquals("Bearer $CONTRACT_API_KEY", transport.executeRequests.single().headers["Authorization"])
        assertFalse(transport.executeRequests.single().headers.containsKey("User-Agent"))
        assertRequestBody(transport.executeRequests.single().contractBodyJson(), "stream" to false)
        assertFalse(transport.executeRequests.single().contractBodyText().contains(CONTRACT_API_KEY))
    }

    @Test
    fun openAiNonStreamingParsesProviderMetadata() = runBlocking {
        val transport = FakeHttpTransport(
            executeResponse = providerResponse(
                """
                    {
                      "model":"deepseek-v4-flash",
                      "system_fingerprint":"fp-unit-test",
                      "choices":[{"message":{"content":"visible text"}}]
                    }
                """.trimIndent(),
            ),
        )

        val events = OpenAiModelClient(::contractApiKey, CONTRACT_MODEL, CONTRACT_BASE_URL, transport = transport)
            .complete(contractModelRequest())
            .toList()
        val metadata = events.filterIsInstance<ModelResponseMetadata>().single()

        assertEquals(listOf("metadata", "visible text", "Completed"), eventSummary(events))
        assertEquals("deepseek-v4-flash", metadata.model)
        assertEquals("fp-unit-test", metadata.systemFingerprint)
    }

    @Test
    fun openAiChatEnablesServerSideSearchByDefault() = runBlocking {
        val transport = FakeHttpTransport(executeResponse = providerResponse("""{"choices":[{"message":{}}]}"""))

        OpenAiModelClient(::contractApiKey, CONTRACT_MODEL, CONTRACT_BASE_URL, transport = transport)
            .complete(contractModelRequest())
            .toList()

        val body = transport.executeRequests.single().contractBodyJson()
        assertTrue(body.containsKey("web_search_options"))
    }

    @Test
    fun openAiChatSearchCapabilityNoneOmitsSearchField() = runBlocking {
        val transport = FakeHttpTransport(executeResponse = providerResponse("""{"choices":[{"message":{}}]}"""))

        OpenAiModelClient(
            ::contractApiKey,
            CONTRACT_MODEL,
            CONTRACT_BASE_URL,
            searchCapability = ServerSideSearchCapability.NONE,
            transport = transport,
        ).complete(contractModelRequest()).toList()

        assertFalse(transport.executeRequests.single().contractBodyText().contains("web_search"))
    }

    @Test
    fun openAiChatCanEnableServerSideSearch() = runBlocking {
        val transport = FakeHttpTransport(executeResponse = providerResponse("""{"choices":[{"message":{}}]}"""))

        OpenAiModelClient(
            ::contractApiKey,
            CONTRACT_MODEL,
            CONTRACT_BASE_URL,
            serverSideSearch = true,
            transport = transport,
        ).complete(contractModelRequest()).toList()

        val body = transport.executeRequests.single().contractBodyJson()
        assertTrue(body.containsKey("web_search_options"))
        assertEquals("demo_tool", body["tools"]?.jsonArray?.single()?.jsonObject
            ?.get("function")?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun openAiChatParsesUrlCitationAsSearchActivity() = runBlocking {
        val transport = FakeHttpTransport(
            executeResponse = providerResponse(
                """
                    {"choices":[{"message":{"content":"answer","annotations":[{"type":"url_citation","url_citation":{"title":"T"}}]}}]}
                """.trimIndent(),
            ),
        )

        val events = OpenAiModelClient(::contractApiKey, CONTRACT_MODEL, CONTRACT_BASE_URL, transport = transport)
            .complete(contractModelRequest())
            .toList()

        val search = events.filterIsInstance<ModelEvent.SearchActivity>().single()
        assertEquals("OpenAI", search.provider)
        assertEquals(1, search.resultCount)
    }

    @Test
    fun openAiChatSerializesImageContentParts() = runBlocking {
        val transport = FakeHttpTransport(executeResponse = providerResponse("""{"choices":[{"message":{}}]}"""))

        OpenAiModelClient(::contractApiKey, CONTRACT_MODEL, CONTRACT_BASE_URL, transport = transport)
            .complete(contractMultimodalRequest())
            .toList()

        val body = transport.executeRequests.single().contractBodyJson()
        val message = body["messages"]?.jsonArray?.single()?.jsonObject
        val content = message?.get("content")?.jsonArray.orEmpty()
        val text = content[0].jsonObject
        val image = content[1].jsonObject
        val imageUrl = image["image_url"]?.jsonObject

        assertEquals("text", text["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("inspect image", text["text"]?.jsonPrimitive?.contentOrNull)
        assertEquals("image_url", image["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("data:image/png;base64,AQIDBA==", imageUrl?.get("url")?.jsonPrimitive?.contentOrNull)
        assertEquals("high", imageUrl?.get("detail")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun openAiStreamingParsesThinkingTextAndToolCallDeltas() = runBlocking {
        val transport = FakeHttpTransport(
            streamText = """
                data: {"choices":[{"delta":{"reasoning_content":"think "}}]}

                data: {"choices":[{"delta":{"content":"visible"}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-stream","function":{"name":"demo_tool","arguments":"{\"type\":\""}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"plan\"}"}}]}}]}

                data: {"usage":{"prompt_tokens":120,"completion_tokens":8,"prompt_tokens_details":{"cached_tokens":64}}}

                data: [DONE]
            """.trimIndent(),
        )
        val events = OpenAiModelClient(::contractApiKey, CONTRACT_MODEL, CONTRACT_BASE_URL, stream = true, transport = transport)
            .complete(contractModelRequest())
            .toList()

        assertEquals(listOf("think ", "visible", "usage", "demo_tool", "Completed"), eventSummary(events))
        assertEquals(0, transport.executeRequests.size)
        val body = transport.streamRequests.single().contractBodyJson()
        assertRequestBody(body, "stream" to true)
        assertEquals(true, body["stream_options"]?.jsonObject?.get("include_usage")?.jsonPrimitive?.boolean)
        val usage = events.filterIsInstance<ModelEvent.UsageDelta>().single()
        assertEquals(120, usage.inputTokens)
        assertEquals(8, usage.outputTokens)
        assertEquals(64, usage.cacheReadTokens)
        assertEquals("""{"type":"plan"}""", events.filterIsInstance<ModelEvent.ToolCall>().single().argumentsJson)
    }

    @Test
    fun openAiStreamingParsesProviderMetadataOnce() = runBlocking {
        val transport = FakeHttpTransport(
            streamText = """
                data: {"model":"deepseek-v4-flash","system_fingerprint":"fp-stream","choices":[{"delta":{"content":"visible"}}]}

                data: {"model":"deepseek-v4-flash","system_fingerprint":"fp-stream","choices":[{"delta":{"content":" text"}}]}

                data: [DONE]
            """.trimIndent(),
        )

        val events = OpenAiModelClient(::contractApiKey, CONTRACT_MODEL, CONTRACT_BASE_URL, stream = true, transport = transport)
            .complete(contractModelRequest())
            .toList()
        val metadata = events.filterIsInstance<ModelResponseMetadata>().single()

        assertEquals(listOf("metadata", "visible", " text", "Completed"), eventSummary(events))
        assertEquals("deepseek-v4-flash", metadata.model)
        assertEquals("fp-stream", metadata.systemFingerprint)
    }

    @Test
    fun openAiResponsesStreamingParsesTextAndToolCallDeltas() = runBlocking {
        val transport = FakeHttpTransport(streamText = OPENAI_RESPONSES_STREAM_TEXT)

        val events = OpenAiModelClient(
            apiKeyProvider = ::contractApiKey,
            model = CONTRACT_MODEL,
            baseUrl = CONTRACT_BASE_URL,
            stream = true,
            endpoint = "responses",
            serverSideSearch = false,
            transport = transport,
        ).complete(contractModelRequest()).toList()

        assertEquals(listOf("visible", "demo_tool", "Completed"), eventSummary(events))
        assertEquals("$CONTRACT_BASE_URL/responses", transport.streamRequests.single().url)
        assertEquals(OPENAI_RESPONSES_USER_AGENT, transport.streamRequests.single().headers["User-Agent"])
        assertRequestBody(transport.streamRequests.single().contractBodyJson(), "stream" to true)
        assertEquals("""{"type":"plan"}""", events.filterIsInstance<ModelEvent.ToolCall>().single().argumentsJson)
    }

    @Test
    fun openAiResponsesNonStreamingParsesOutputAndFunctionCall() = runBlocking {
        val transport = FakeHttpTransport(executeResponse = providerResponse(OPENAI_RESPONSES_RESPONSE_BODY))

        val events = OpenAiModelClient(
            ::contractApiKey,
            CONTRACT_MODEL,
            CONTRACT_BASE_URL,
            endpoint = "responses",
            serverSideSearch = false,
            transport = transport,
        )
            .complete(contractModelRequest())
            .toList()

        assertEquals(listOf("visible text", "demo_tool", "Completed"), eventSummary(events))
        assertEquals("$CONTRACT_BASE_URL/responses", transport.executeRequests.single().url)
        assertEquals(OPENAI_RESPONSES_USER_AGENT, transport.executeRequests.single().headers["User-Agent"])
        assertRequestBody(transport.executeRequests.single().contractBodyJson(), "stream" to false)
    }

    @Test
    fun openAiResponsesEnablesServerSideSearchByDefault() = runBlocking {
        val transport = FakeHttpTransport(executeResponse = providerResponse(OPENAI_RESPONSES_RESPONSE_BODY))

        OpenAiModelClient(
            ::contractApiKey,
            CONTRACT_MODEL,
            CONTRACT_BASE_URL,
            endpoint = "responses",
            transport = transport,
        ).complete(contractModelRequest()).toList()

        val tools = transport.executeRequests.single().contractBodyJson()["tools"]?.jsonArray.orEmpty()
        assertEquals("web_search", tools.first().jsonObject["type"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun openAiResponsesSerializesCodexCompatibleCacheFields() = runBlocking {
        val transport = FakeHttpTransport(executeResponse = providerResponse(OPENAI_RESPONSES_RESPONSE_BODY))

        OpenAiModelClient(
            ::contractApiKey,
            CONTRACT_MODEL,
            CONTRACT_BASE_URL,
            endpoint = "responses",
            transport = transport,
        ).complete(contractModelRequest().copy(promptCacheKey = "agent-runtime:session-1")).toList()

        val body = transport.executeRequests.single().contractBodyJson()
        assertEquals("agent-runtime:session-1", body["prompt_cache_key"]?.jsonPrimitive?.contentOrNull)
        assertEquals("auto", body["tool_choice"]?.jsonPrimitive?.contentOrNull)
        assertEquals(true, body["parallel_tool_calls"]?.jsonPrimitive?.boolean)
        assertEquals(false, body["store"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun openAiResponsesCanOverrideToolChoiceForHostedSearchValidation() = runBlocking {
        val transport = FakeHttpTransport(executeResponse = providerResponse(OPENAI_RESPONSES_RESPONSE_BODY))

        OpenAiModelClient(
            ::contractApiKey,
            CONTRACT_MODEL,
            CONTRACT_BASE_URL,
            endpoint = "responses",
            transport = transport,
        ).complete(contractModelRequest().copy(toolChoice = "required")).toList()

        assertEquals("required", transport.executeRequests.single().contractBodyJson()["tool_choice"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun openAiResponsesOmitsBlankPromptCacheKey() = runBlocking {
        val transport = FakeHttpTransport(executeResponse = providerResponse(OPENAI_RESPONSES_RESPONSE_BODY))

        OpenAiModelClient(
            ::contractApiKey,
            CONTRACT_MODEL,
            CONTRACT_BASE_URL,
            endpoint = "responses",
            transport = transport,
        ).complete(contractModelRequest().copy(promptCacheKey = " ")).toList()

        assertFalse(transport.executeRequests.single().contractBodyJson().containsKey("prompt_cache_key"))
    }

    @Test
    fun openAiResponsesSearchCapabilityNoneOmitsSearchTool() = runBlocking {
        val transport = FakeHttpTransport(executeResponse = providerResponse(OPENAI_RESPONSES_RESPONSE_BODY))

        OpenAiModelClient(
            ::contractApiKey,
            CONTRACT_MODEL,
            CONTRACT_BASE_URL,
            endpoint = "responses",
            searchCapability = ServerSideSearchCapability.NONE,
            transport = transport,
        ).complete(contractModelRequest()).toList()

        assertFalse(transport.executeRequests.single().contractBodyText().contains("web_search"))
    }

    @Test
    fun openAiResponsesCanEnableServerSideSearchTool() = runBlocking {
        val transport = FakeHttpTransport(executeResponse = providerResponse(OPENAI_RESPONSES_RESPONSE_BODY))

        OpenAiModelClient(
            ::contractApiKey,
            CONTRACT_MODEL,
            CONTRACT_BASE_URL,
            endpoint = "responses",
            serverSideSearch = true,
            transport = transport,
        ).complete(contractModelRequest()).toList()

        val tools = transport.executeRequests.single().contractBodyJson()["tools"]?.jsonArray.orEmpty()
        assertEquals("web_search", tools.first().jsonObject["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("function", tools[1].jsonObject["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("demo_tool", tools[1].jsonObject["name"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun openAiResponsesStreamingParsesWebSearchCall() = runBlocking {
        val transport = FakeHttpTransport(
            streamText = """
                data: {"type":"response.output_item.done","item":{"type":"web_search_call","id":"ws-1","status":"completed","action":{"type":"search","query":"王菲 最近 唱了什么歌"}}}

                data: {"type":"response.output_text.delta","delta":"searched"}

                data: [DONE]
            """.trimIndent(),
        )

        val events = OpenAiModelClient(
            ::contractApiKey,
            CONTRACT_MODEL,
            CONTRACT_BASE_URL,
            stream = true,
            endpoint = "responses",
            transport = transport,
        ).complete(contractModelRequest()).toList()

        val search = events.filterIsInstance<ModelEvent.SearchActivity>().single()
        assertEquals(listOf("search", "searched", "Completed"), eventSummary(events))
        assertEquals("王菲 最近 唱了什么歌", search.queryPreview)
    }

    @Test
    fun openAiResponsesSerializesImageInputParts() = runBlocking {
        val transport = FakeHttpTransport(executeResponse = providerResponse(OPENAI_RESPONSES_RESPONSE_BODY))

        OpenAiModelClient(
            ::contractApiKey,
            CONTRACT_MODEL,
            CONTRACT_BASE_URL,
            endpoint = "responses",
            transport = transport,
        ).complete(contractMultimodalRequest()).toList()

        val body = transport.executeRequests.single().contractBodyJson()
        val item = body["input"]?.jsonArray?.single()?.jsonObject
        val content = item?.get("content")?.jsonArray.orEmpty()
        val text = content[0].jsonObject
        val image = content[1].jsonObject

        assertEquals("input_text", text["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("inspect image", text["text"]?.jsonPrimitive?.contentOrNull)
        assertEquals("input_image", image["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("data:image/png;base64,AQIDBA==", image["image_url"]?.jsonPrimitive?.contentOrNull)
        assertEquals("high", image["detail"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun modelImageRejectsUnsupportedMediaAndOversizePayloads() {
        val mediaError = try {
            ModelImage(byteArrayOf(1), "text/plain", "/tmp/not-image.txt")
            null
        } catch (error: IllegalArgumentException) {
            error
        }
        val sizeError = try {
            ModelImage(ByteArray(4 * 1024 * 1024 + 1) { 1 }, "image/png", "/tmp/large.png")
            null
        } catch (error: IllegalArgumentException) {
            error
        }

        requireNotNull(mediaError)
        requireNotNull(sizeError)
        assertTrue(mediaError.message.orEmpty().contains("Unsupported image media type"))
        assertTrue(sizeError.message.orEmpty().contains("exceeds max bytes"))
    }

    @Test
    fun openAiResponsesNonStreamingParsesProviderMetadata() = runBlocking {
        val transport = FakeHttpTransport(
            executeResponse = providerResponse(
                """
                    {
                      "model":"gpt-5",
                      "system_fingerprint":"fp-responses",
                      "output":[{"type":"message","content":[{"type":"output_text","text":"visible"}]}]
                    }
                """.trimIndent(),
            ),
        )

        val events = OpenAiModelClient(::contractApiKey, CONTRACT_MODEL, CONTRACT_BASE_URL, endpoint = "responses", transport = transport)
            .complete(contractModelRequest())
            .toList()
        val metadata = events.filterIsInstance<ModelResponseMetadata>().single()

        assertEquals(listOf("metadata", "visible", "Completed"), eventSummary(events))
        assertEquals("gpt-5", metadata.model)
        assertEquals("fp-responses", metadata.systemFingerprint)
    }

    @Test
    fun openAiSerializesToolLoopFollowUpMessages() = runBlocking {
        val transport = FakeHttpTransport(executeResponse = providerResponse("""{"choices":[{"message":{}}]}"""))

        OpenAiModelClient(::contractApiKey, CONTRACT_MODEL, CONTRACT_BASE_URL, transport = transport)
            .complete(contractToolLoopRequest())
            .toList()

        val messages = transport.executeRequests.single().contractBodyJson()["messages"]?.jsonArray.orEmpty()
        val assistant = messages[1].jsonObject
        val tool = messages[2].jsonObject
        val toolCall = assistant["tool_calls"]?.jsonArray?.single()?.jsonObject
        val function = toolCall?.get("function")?.jsonObject

        assertEquals("assistant", assistant["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals("call-1", toolCall?.get("id")?.jsonPrimitive?.contentOrNull)
        assertEquals("demo_tool", function?.get("name")?.jsonPrimitive?.contentOrNull)
        assertEquals("""{"type":"plan"}""", function?.get("arguments")?.jsonPrimitive?.contentOrNull)
        assertEquals("tool", tool["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals("call-1", tool["tool_call_id"]?.jsonPrimitive?.contentOrNull)
        assertEquals("""{"ok":true}""", tool["content"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun blankApiKeyDoesNotCallTransport() {
        val transport = FakeHttpTransport()
        val error = try {
            runBlocking { OpenAiModelClient({ "" }, transport).complete(contractModelRequest()).toList() }
            null
        } catch (error: ModelClientException) {
            error
        }

        requireNotNull(error)
        assertEquals(RuntimeErrorCode.PROVIDER_AUTH_FAILED, error.code)
        assertFalse(error.recoverable)
        assertTrue(error.message.orEmpty().contains("credential"))

        assertTrue(transport.executeRequests.isEmpty())
        assertTrue(transport.streamRequests.isEmpty())
    }
}

private const val OPENAI_RESPONSES_USER_AGENT =
    "codex-tui/0.136.0 (Ubuntu 24.4.0; x86_64) xterm-256color (codex-tui; 0.136.0)"
