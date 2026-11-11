package cc.noharry.agent.appserver.protocol

import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.ToolCallStatus
import cc.noharry.agent.appserver.protocol.TranscriptRole
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcCodec
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcError
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcErrorCodes
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcException
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcNotification
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcRequest
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcResponse
import cc.noharry.agent.appserver.protocol.jsonrpc.ProtocolJson
import cc.noharry.agent.appserver.protocol.text
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JsonRpcCodecTest {
    @Test
    fun messagesSurviveTheWireAsLinesWithoutAJsonrpcMember() {
        val messages = listOf(
            JsonRpcRequest(JsonPrimitive(7), "turn/start", buildJsonObject { put("text", "去世界之窗") }),
            JsonRpcRequest(JsonPrimitive("a"), "thread/list"),
            JsonRpcNotification("item/delta", buildJsonObject { put("delta", "好") }),
            JsonRpcResponse(JsonPrimitive(7), result = buildJsonObject { }),
            JsonRpcResponse(JsonPrimitive(8), error = JsonRpcError(JsonRpcErrorCodes.METHOD_NOT_FOUND, "nope")),
        )
        for (message in messages) {
            val line = JsonRpcCodec.encode(message)
            assertFalse(line.contains('\n'))
            assertFalse(line.contains("jsonrpc"))
            assertEquals(message, JsonRpcCodec.decode(line))
        }
    }

    @Test
    fun aNumberIdAndAStringIdAreDifferentRequests() {
        val number = JsonRpcCodec.decode("""{"id":1,"method":"m"}""") as JsonRpcRequest
        val string = JsonRpcCodec.decode("""{"id":"1","method":"m"}""") as JsonRpcRequest
        assertFalse(number.id == string.id)
    }

    @Test
    fun whatIsNotAMessageIsAnErrorWithItsCode() {
        assertCode(JsonRpcErrorCodes.PARSE_ERROR, "{not json")
        assertCode(JsonRpcErrorCodes.INVALID_REQUEST, "[1,2]")
        assertCode(JsonRpcErrorCodes.INVALID_REQUEST, """{"id":3}""")
        assertCode(JsonRpcErrorCodes.INVALID_REQUEST, """{"method":4}""")
        assertCode(JsonRpcErrorCodes.INVALID_REQUEST, """{"id":1,"error":"denied"}""")
        assertCode(JsonRpcErrorCodes.INVALID_REQUEST, """{"id":1,"error":{"message":"no code"}}""")
    }

    @Test
    fun aNullErrorBesideAResultIsNoError() {
        assertEquals(JsonRpcResponse(JsonPrimitive(1), result = buildJsonObject { }), JsonRpcCodec.decode("""{"id":1,"result":{},"error":null}"""))
    }

    @Test
    fun aBadRequestIsAnsweredUnderItsIdAndABadResponseIsNot() {
        val bad = JsonRpcException(JsonRpcErrorCodes.INVALID_REQUEST, "method is not a string")
        assertEquals(JsonRpcResponse(JsonPrimitive(5), error = bad.toError()), JsonRpcCodec.answerTo("""{"id":5,"method":7}""", bad))
        assertEquals(JsonRpcResponse(JsonNull, error = bad.toError()), JsonRpcCodec.answerTo("{not json", bad))
        assertNull(JsonRpcCodec.answerTo("""{"id":1,"error":"denied"}""", bad))
    }

    @Test
    fun itemsCarryTheirKindAsTypeAndLeaveFoldingToTheClient() {
        val item: ConversationItem = ConversationItem.ToolCallItem(
            id = 3, toolName = "navigation_route", status = ToolCallStatus.DONE, startedAt = 1, finishedAt = 2,
        )
        val json = ProtocolJson.encodeToString(ConversationItem.serializer(), item)
        assertTrue(json, json.contains(""""type":"toolCall""""))
        assertFalse(json.contains("collapsed"))
        assertEquals(item, ProtocolJson.decodeFromString(ConversationItem.serializer(), json))
    }

    @Test
    fun aNewerPeersExtraFieldsAreSkipped() {
        val json = """{"type":"message","id":1,"role":"ASSISTANT","content":"好","final":true,"speaker":"小博"}"""
        val item = ProtocolJson.decodeFromString(ConversationItem.serializer(), json)
        assertEquals(ConversationItem.MessageItem(1, TranscriptRole.ASSISTANT, "好", final = true), item)
    }

    private fun assertCode(code: Int, line: String) {
        try {
            JsonRpcCodec.decode(line)
            fail("decoded $line")
        } catch (error: JsonRpcException) {
            assertEquals(line, code, error.code)
        }
    }
}
