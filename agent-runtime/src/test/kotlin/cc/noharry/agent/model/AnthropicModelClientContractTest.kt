package cc.noharry.agent.model

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import cc.noharry.agent.kernel.api.model.ServerSideSearchCapability
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelResponseMetadata

class AnthropicModelClientContractTest {
    @Test
    fun anthropicNonStreamingParsesThinkingTextAndToolUse() = runBlocking {
        val transport = FakeHttpTransport(
            executeResponse = providerResponse(
                """
                    {
                      "content":[
                        {"type":"thinking","thinking":"thinking"},
                        {"type":"text","text":"visible text"},
                        {"type":"tool_use","id":"tool-1","name":"demo_tool","input":{"type":"plan"}}
                      ]
                    }
                """.trimIndent(),
            ),
        )
        val events = AnthropicModelClient(
            ::contractApiKey,
            CONTRACT_MODEL,
            CONTRACT_BASE_URL,
            serverSideSearch = false,
            transport = transport,
        )
            .complete(contractModelRequest())
            .toList()

        assertEquals(listOf("thinking", "visible text", "demo_tool", "Completed"), eventSummary(events))
        assertEquals("$CONTRACT_BASE_URL/messages", transport.executeRequests.single().url)
        assertEquals(CONTRACT_API_KEY, transport.executeRequests.single().headers["x-api-key"])
        assertEquals("2023-06-01", transport.executeRequests.single().headers["anthropic-version"])
        assertRequestBody(transport.executeRequests.single().contractBodyJson(), "stream" to false)
        assertFalse(transport.executeRequests.single().contractBodyText().contains(CONTRACT_API_KEY))
    }

    @Test
    fun anthropicNonStreamingParsesProviderMetadata() = runBlocking {
        val transport = FakeHttpTransport(
            executeResponse = providerResponse(
                """
                    {
                      "model":"claude-sonnet-test",
                      "content":[{"type":"text","text":"visible text"}]
                    }
                """.trimIndent(),
            ),
        )

        val events = AnthropicModelClient(
            ::contractApiKey,
            CONTRACT_MODEL,
            CONTRACT_BASE_URL,
            serverSideSearch = false,
            transport = transport,
        )
            .complete(contractModelRequest())
            .toList()
        val metadata = events.filterIsInstance<ModelResponseMetadata>().single()

        assertEquals(listOf("metadata", "visible text", "Completed"), eventSummary(events))
        assertEquals("claude-sonnet-test", metadata.model)
        assertEquals("", metadata.systemFingerprint)
    }

    @Test
    fun anthropicEnablesServerSideSearchByDefault() = runBlocking {
        val transport = FakeHttpTransport(executeResponse = providerResponse("""{"content":[]}"""))

        AnthropicModelClient(
            ::contractApiKey,
            CONTRACT_MODEL,
            CONTRACT_BASE_URL,
            transport = transport,
        )
            .complete(contractModelRequest())
            .toList()

        val tools = transport.executeRequests.single().contractBodyJson()["tools"]?.jsonArray.orEmpty()
        assertEquals("web_search_20250305", tools.first().jsonObject["type"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun anthropicSearchCapabilityNoneOmitsSearchField() = runBlocking {
        val transport = FakeHttpTransport(executeResponse = providerResponse("""{"content":[]}"""))

        AnthropicModelClient(
            ::contractApiKey,
            CONTRACT_MODEL,
            CONTRACT_BASE_URL,
            searchCapability = ServerSideSearchCapability.NONE,
            transport = transport,
        ).complete(contractModelRequest()).toList()

        assertFalse(transport.executeRequests.single().contractBodyText().contains("web_search"))
    }

    @Test
    fun anthropicCanEnableServerSideSearchTool() = runBlocking {
        val transport = FakeHttpTransport(executeResponse = providerResponse("""{"content":[]}"""))

        AnthropicModelClient(
            ::contractApiKey,
            CONTRACT_MODEL,
            CONTRACT_BASE_URL,
            serverSideSearch = true,
            transport = transport,
        ).complete(contractModelRequest()).toList()

        val tools = transport.executeRequests.single().contractBodyJson()["tools"]?.jsonArray.orEmpty()
        val search = tools.first().jsonObject
        val custom = tools[1].jsonObject
        assertEquals("web_search_20250305", search["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("web_search", search["name"]?.jsonPrimitive?.contentOrNull)
        assertEquals("demo_tool", custom["name"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun anthropicParsesServerToolSearchBlocks() = runBlocking {
        val transport = FakeHttpTransport(
            executeResponse = providerResponse(
                """
                    {
                      "content":[
                        {"type":"server_tool_use","id":"srv-1","name":"web_search","input":{"query":"latest sdk"}},
                        {"type":"web_search_tool_result","tool_use_id":"srv-1","content":[{"title":"A"},{"title":"B"}]}
                      ]
                    }
                """.trimIndent(),
            ),
        )

        val events = AnthropicModelClient(::contractApiKey, CONTRACT_MODEL, CONTRACT_BASE_URL, transport = transport)
            .complete(contractModelRequest())
            .toList()
        val search = events.filterIsInstance<ModelEvent.SearchActivity>()

        assertEquals("latest sdk", search.first().queryPreview)
        assertEquals(2, search.last().resultCount)
    }

    @Test
    fun anthropicSerializesImageContentBlocks() = runBlocking {
        val transport = FakeHttpTransport(executeResponse = providerResponse("""{"content":[]}"""))

        AnthropicModelClient(::contractApiKey, CONTRACT_MODEL, CONTRACT_BASE_URL, transport = transport)
            .complete(contractMultimodalRequest())
            .toList()

        val body = transport.executeRequests.single().contractBodyJson()
        val message = body["messages"]?.jsonArray?.single()?.jsonObject
        val content = message?.get("content")?.jsonArray.orEmpty()
        val text = content[0].jsonObject
        val image = content[1].jsonObject
        val source = image["source"]?.jsonObject

        assertEquals("text", text["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("inspect image", text["text"]?.jsonPrimitive?.contentOrNull)
        assertEquals("image", image["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("base64", source?.get("type")?.jsonPrimitive?.contentOrNull)
        assertEquals("image/png", source?.get("media_type")?.jsonPrimitive?.contentOrNull)
        assertEquals("AQIDBA==", source?.get("data")?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun anthropicStreamingAccumulatesServerToolUseQuery() = runBlocking {
        val transport = FakeHttpTransport(
            streamText = """
                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"server_tool_use","id":"srv-stream","name":"web_search","input":{}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"query\":\""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"广州 天气\"}"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: content_block_start
                data: {"type":"content_block_start","index":1,"content_block":{"type":"web_search_tool_result","tool_use_id":"srv-stream","content":[{"title":"A"}]}}
            """.trimIndent(),
        )
        val events = AnthropicModelClient(
            ::contractApiKey,
            CONTRACT_MODEL,
            CONTRACT_BASE_URL,
            stream = true,
            transport = transport,
        )
            .complete(contractModelRequest())
            .toList()
        val search = events.filterIsInstance<ModelEvent.SearchActivity>()

        assertEquals("广州 天气", search.first().queryPreview)
        assertEquals(1, search.last().resultCount)
        assertEquals(0, events.filterIsInstance<ModelEvent.ToolCall>().size)
    }

    @Test
    fun anthropicStreamingParsesThinkingTextAndToolUseDeltas() = runBlocking {
        val transport = FakeHttpTransport(
            streamText = """
                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"thinking"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"visible"}}

                event: content_block_start
                data: {"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"tool-stream","name":"demo_tool","input":{}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"type\":\""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"plan\"}"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":2}
            """.trimIndent(),
        )
        val events = AnthropicModelClient(
            ::contractApiKey,
            CONTRACT_MODEL,
            CONTRACT_BASE_URL,
            serverSideSearch = false,
            stream = true,
            transport = transport,
        )
            .complete(contractModelRequest())
            .toList()

        assertEquals(listOf("thinking", "visible", "demo_tool", "Completed"), eventSummary(events))
        assertEquals(0, transport.executeRequests.size)
        assertRequestBody(transport.streamRequests.single().contractBodyJson(), "stream" to true)
        assertEquals("""{"type":"plan"}""", events.filterIsInstance<ModelEvent.ToolCall>().single().argumentsJson)
    }

    @Test
    fun anthropicStreamingParsesNestedAndExclusiveCacheUsage() = runBlocking {
        val transport = FakeHttpTransport(
            streamText = """
                event: message_start
                data: {"type":"message_start","message":{"usage":{"input_tokens":100,"output_tokens":0,"cache_read_input_tokens":1900,"cache_creation_input_tokens":0}}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}

                event: message_delta
                data: {"type":"message_delta","usage":{"output_tokens":8}}
            """.trimIndent(),
        )
        val events = AnthropicModelClient(
            ::contractApiKey,
            CONTRACT_MODEL,
            CONTRACT_BASE_URL,
            serverSideSearch = false,
            stream = true,
            transport = transport,
        )
            .complete(contractModelRequest())
            .toList()

        val usages = events.filterIsInstance<ModelEvent.UsageDelta>()
        assertEquals(2, usages.size)
        // Anthropic input_tokens excludes cache fields; runtime stores inclusive input.
        assertEquals(2000, usages[0].inputTokens)
        assertEquals(1900, usages[0].cacheReadTokens)
        assertEquals(0, usages[0].cacheWriteTokens)
        assertEquals(8, usages[1].outputTokens)
    }

    @Test
    fun repeatedCumulativeUsageCountsInputAndCacheOnceAcrossStream() = runBlocking {
        val transport = FakeHttpTransport(streamText = """
            event: message_start
            data: {"type":"message_start","message":{"usage":{"input_tokens":56066,"cache_read_input_tokens":69120,"output_tokens":0}}}

            event: message_delta
            data: {"type":"message_delta","usage":{"input_tokens":56066,"cache_read_input_tokens":69120,"output_tokens":100}}

            event: message_delta
            data: {"type":"message_delta","usage":{"output_tokens":183}}
        """.trimIndent())
        val events = AnthropicModelClient(::contractApiKey, CONTRACT_MODEL, CONTRACT_BASE_URL,
            serverSideSearch = false, stream = true, transport = transport).complete(contractModelRequest()).toList()
        val usage = events.filterIsInstance<ModelEvent.UsageDelta>()
        assertEquals(125186, usage.sumOf { it.inputTokens })
        assertEquals(69120, usage.sumOf { it.cacheReadTokens })
        assertEquals(183, usage.sumOf { it.outputTokens })
        assertEquals(listOf(125186, 0, 0), usage.map { it.inputTokens })
    }

    @Test
    fun anthropicStreamingParsesDsmlTextToolCalls() = runBlocking {
        val transport = FakeHttpTransport(
            streamText = """
                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"<｜｜DSML｜｜tool_calls>\n<｜｜DSML｜｜invoke name=\"update_todo\">\n"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"<｜｜DSML｜｜parameter name=\"todos\" string=\"false\">[{\"content\":\"播放推荐的主题曲\",\"status\":\"pending\"}]</｜｜DSML｜｜parameter>\n</｜｜DSML｜｜invoke>\n</｜｜DSML｜｜tool_calls>"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}
            """.trimIndent(),
        )

        val events = AnthropicModelClient(
            ::contractApiKey,
            CONTRACT_MODEL,
            CONTRACT_BASE_URL,
            serverSideSearch = false,
            stream = true,
            transport = transport,
        )
            .complete(contractModelRequest())
            .toList()

        assertEquals(listOf("update_todo", "Completed"), eventSummary(events))
        assertEquals(emptyList<ModelEvent.TextDelta>(), events.filterIsInstance<ModelEvent.TextDelta>())
        val toolCall = events.filterIsInstance<ModelEvent.ToolCall>().single()
        assertEquals("update_todo", toolCall.name)
        assertEquals(
            """{"todos":[{"content":"播放推荐的主题曲","status":"pending"}]}""",
            toolCall.argumentsJson,
        )
    }

    @Test
    fun anthropicNonStreamingParsesDsmlTextToolCalls() = runBlocking {
        val transport = FakeHttpTransport(
            executeResponse = providerResponse(
                """
                    {
                      "content":[{
                        "type":"text",
                        "text":"<｜｜DSML｜｜tool_calls>\n<｜｜DSML｜｜invoke name=\"finish\">\n<｜｜DSML｜｜parameter name=\"response\" string=\"true\">已完成</｜｜DSML｜｜parameter>\n</｜｜DSML｜｜invoke>\n</｜｜DSML｜｜tool_calls>"
                      }]
                    }
                """.trimIndent(),
            ),
        )

        val events = AnthropicModelClient(
            ::contractApiKey,
            CONTRACT_MODEL,
            CONTRACT_BASE_URL,
            serverSideSearch = false,
            transport = transport,
        )
            .complete(contractModelRequest())
            .toList()

        assertEquals(listOf("finish", "Completed"), eventSummary(events))
        val toolCall = events.filterIsInstance<ModelEvent.ToolCall>().single()
        assertEquals("""{"response":"已完成"}""", toolCall.argumentsJson)
    }

    @Test
    fun anthropicSerializesToolLoopFollowUpMessages() = runBlocking {
        val transport = FakeHttpTransport(executeResponse = providerResponse("""{"content":[]}"""))

        AnthropicModelClient(
            ::contractApiKey,
            CONTRACT_MODEL,
            CONTRACT_BASE_URL,
            serverSideSearch = false,
            transport = transport,
        )
            .complete(contractToolLoopRequest())
            .toList()

        val messages = transport.executeRequests.single().contractBodyJson()["messages"]?.jsonArray.orEmpty()
        val assistant = messages[1].jsonObject
        val toolResultMessage = messages[2].jsonObject
        val toolUse = assistant["content"]?.jsonArray?.single()?.jsonObject
        val toolResult = toolResultMessage["content"]?.jsonArray?.single()?.jsonObject

        assertEquals("assistant", assistant["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals("tool_use", toolUse?.get("type")?.jsonPrimitive?.contentOrNull)
        assertEquals("call-1", toolUse?.get("id")?.jsonPrimitive?.contentOrNull)
        assertEquals("demo_tool", toolUse?.get("name")?.jsonPrimitive?.contentOrNull)
        assertEquals("plan", toolUse?.get("input")?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull)
        assertEquals("user", toolResultMessage["role"]?.jsonPrimitive?.contentOrNull)
        assertEquals("tool_result", toolResult?.get("type")?.jsonPrimitive?.contentOrNull)
        assertEquals("call-1", toolResult?.get("tool_use_id")?.jsonPrimitive?.contentOrNull)
        assertEquals("""{"ok":true}""", toolResult?.get("content")?.jsonPrimitive?.contentOrNull)
    }
}
