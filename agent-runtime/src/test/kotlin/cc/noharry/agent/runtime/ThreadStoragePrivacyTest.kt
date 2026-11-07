package cc.noharry.agent.runtime

import cc.noharry.agent.*
import cc.noharry.agent.runtime.thread.ThreadStore
import cc.noharry.agent.spi.ToolSource
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ThreadStoragePrivacyTest {
    @Test fun storedToolsKeepJsonTypesAndRemoveCredentials() {
        val root = threadRoot()
        val payload = """{"ts":1730900000000,"enabled":true,"empty":null,"nested":[{"api_key":"opaque-key","accessToken":"opaque-access","password":42,"authorization":"opaque-auth","secret":{"value":"opaque-nested"}}],"text":"Bearer opaque-bearer","phone":"13800138000"}"""
        saveTool(root, payload)
        val restored = ThreadStore(root).items("privacy").filterIsInstance<ThreadItem.ToolCall>().single()
        for (value in listOf(restored.arguments, checkNotNull(restored.output))) {
            val json = Json.parseToJsonElement(value).jsonObject
            assertEquals(1730900000000L, json.getValue("ts").jsonPrimitive.long)
            assertFalse(json.getValue("ts").jsonPrimitive.isString)
            assertTrue(json.getValue("enabled").jsonPrimitive.boolean)
            assertEquals(JsonNull, json["empty"])
            assertEquals("[REDACTED_PHONE]", json.getValue("phone").jsonPrimitive.content)
            assertFalse(value.contains("opaque-"))
        }
        assertFalse(root.walkTopDown().filter(File::isFile).joinToString { it.readText() }.contains("opaque-"))
    }

    @Test fun aJsonDocumentInsideAStringStaysValidJson() {
        val root = threadRoot()
        val inner = """{"access_token":"opaque-token","n":1,"items":["Bearer opaque-bearer"]}"""
        saveTool(root, buildJsonObject {
            put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", inner) }) })
        }.toString())
        val output = ThreadStore(root).items("privacy").filterIsInstance<ThreadItem.ToolCall>().single().output.orEmpty()
        val text = Json.parseToJsonElement(output).jsonObject.getValue("content").jsonArray.single()
            .jsonObject.getValue("text").jsonPrimitive.content
        val nested = Json.parseToJsonElement(text).jsonObject
        assertEquals(1, nested.getValue("n").jsonPrimitive.int)
        assertFalse(text.contains("opaque-"))
    }

    @Test fun disabledPhoneAndLocationRulesStillRemoveCredentials() {
        val root = threadRoot()
        saveTool(root, """{"phone":"13800138000","text":"lat=31.2304 Bearer opaque-bearer","refresh_token":"opaque-refresh"}""",
            StandardTraceRedactor(phones = false, locations = false))
        val output = ThreadStore(root).items("privacy").filterIsInstance<ThreadItem.ToolCall>().single().output.orEmpty()
        assertTrue(output.contains("13800138000"))
        assertTrue(output.contains("lat=31.2304"))
        assertFalse(output.contains("opaque-"))
    }

    @Test fun restartKeepsUserAndFinalAnswerOriginal() = runBlocking {
        val root = threadRoot()
        val original = "Contact 13800138000 at lat=31.2304 with Bearer literal-user-text"
        threadBuilder(ThreadScriptModel(listOf(finishEvents(original))), root).build().use { runtime ->
            assertEquals(original, runtime.startThread(ThreadOptions(threadId = "words")).run(original).finalResponse)
        }
        threadBuilder(ThreadScriptModel(emptyList()), root).build().use { runtime ->
            val items = runtime.resumeThread("words").items()
            assertEquals(original, (items.filterIsInstance<ThreadItem.UserMessage>().single().input.single() as InputPart.Text).text)
            assertEquals(original, items.filterIsInstance<ThreadItem.AgentMessage>().last { it.phase == MessagePhase.FINAL }.text)
        }
        val saved = File(root, "storage").walkTopDown().single { it.isFile && it.parentFile.name == "turns" }
        val turn = Json.parseToJsonElement(saved.readText()).jsonObject
        assertEquals(original, turn.getValue("finalResponse").jsonPrimitive.content)
    }

    @Test fun plainTextToolOutputAlsoRemovesBearerCredentials() {
        val root = threadRoot()
        saveTool(root, "Header: Bearer opaque-bearer and password=opaque-password")
        assertFalse(ThreadStore(root).items("privacy").filterIsInstance<ThreadItem.ToolCall>().single().output.orEmpty().contains("opaque-"))
    }

    @Test fun quotedPlainTextCredentialsAreRemovedInFull() {
        val root = threadRoot()
        saveTool(root, """password="alpha beta gamma" token='delta epsilon zeta' api_key="escaped\" quote value""" + '"')
        val output = ThreadStore(root).items("privacy").filterIsInstance<ThreadItem.ToolCall>().single().output
        assertEquals("[REDACTED_SECRET] [REDACTED_SECRET] [REDACTED_SECRET]", output)
    }

    private fun saveTool(root: File, payload: String, redactor: TraceRedactor = DefaultTraceRedactor) {
        val store = ThreadStore(root, redactor)
        store.create("privacy", ThreadOptions())
        val item = ThreadItem.ToolCall("tool", "turn", "example", ToolSource.BACKEND,
            arguments = payload, output = payload, status = ItemStatus.COMPLETED)
        store.saveTurn("privacy", TurnResult("turn", TurnStatus.COMPLETED, "done", listOf(item)))
    }
}
