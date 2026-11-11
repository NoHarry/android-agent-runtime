package cc.noharry.agent.appserver.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What an MCP server's answer reads as for a watch: a resource's readings, a tool's structured or JSON answer. */
class McpReadingsTest {
    @Test
    fun aReadingComesOutOfAResourcesAnswer() {
        // A server that wraps each reading as {"data":…,"ts":…,"uri":…}: the data is the reading.
        val answer = """{"id":11,"jsonrpc":"2.0","result":{"contents":[{"mimeType":"application/json",""" +
            """"text":"{\"data\":{\"temperature\":27.5},\"ts\":1792037417814,\"uri\":\"$WEATHER_URI\"}","uri":"$WEATHER_URI"}]}}"""
        assertEquals("""{"temperature":27.5}""", resourceData(Json.parseToJsonElement(answer).jsonObject).toString())
        // Another MCP server's reading is its text as it is.
        val plain = """{"result":{"contents":[{"uri":"x://y","text":"{\"level\":3}"}]}}"""
        assertEquals("""{"level":3}""", resourceData(Json.parseToJsonElement(plain).jsonObject).toString())
        val unavailable = """{"id":12,"jsonrpc":"2.0","error":{"code":-32006,"message":"unavailable"}}"""
        assertNull(resourceData(Json.parseToJsonElement(unavailable).jsonObject))
        assertEquals(emptyList<JsonObject>(), resourceReadings(Json.parseToJsonElement(unavailable).jsonObject))
    }

    @Test
    fun aResourceMayAnswerOneReadingPerItem() {
        fun content(station: Int) = """{"mimeType":"application/json","text":"{\"data\":{\"aqi\":-1000,\"station\":$station},""" +
            """\"ts\":1792404402111,\"uri\":\"$AIR_URI\"}","uri":"$AIR_URI"}"""
        val answer = """{"id":12,"jsonrpc":"2.0","result":{"contents":[${(0..2).joinToString(",") { content(it) }}]}}"""
        val readings = resourceReadings(Json.parseToJsonElement(answer).jsonObject)

        assertEquals(listOf(0, 1, 2), readings.map { it["station"]!!.jsonPrimitive.int })
        assertEquals(readings.first(), resourceData(Json.parseToJsonElement(answer).jsonObject))
    }

    @Test
    fun aToolsAnswerReadsAsItsStructuredContentElseItsJsonText() {
        // As Kiwi.com's open search-flight answered on 2026-10-21 (trimmed): structuredContent and the same JSON as text.
        val itineraries = """{"currency":"CNY","itineraries":[{"price":803.0,"outbound":{"from":"SZX","to":"TFU","stops":0}}]}"""
        val structured = """{"jsonrpc":"2.0","id":3,"result":{"isError":false,"structuredContent":$itineraries,"content":[{"type":"text","text":"ignored"}]}}"""
        val reading = toolReading(Json.parseToJsonElement(structured).jsonObject)!!
        assertEquals(Json.parseToJsonElement(itineraries).jsonObject, reading)
        val text = """{"result":{"content":[{"type":"text","text":${JsonPrimitive(itineraries)}}]}}"""
        assertEquals(reading, toolReading(Json.parseToJsonElement(text).jsonObject))
        assertNull(toolReading(Json.parseToJsonElement("""{"result":{"isError":true,"content":[{"type":"text","text":"{}"}]}}""").jsonObject))
        assertNull(toolReading(Json.parseToJsonElement("""{"result":{"content":[{"type":"text","text":"晴，27 度"}]}}""").jsonObject))
    }

    private companion object {
        const val WEATHER_URI = "weather://shenzhen/now"
        const val AIR_URI = "weather://shenzhen/air"
    }
}
