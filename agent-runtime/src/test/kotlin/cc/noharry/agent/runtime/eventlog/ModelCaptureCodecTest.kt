@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.eventlog

import cc.noharry.agent.kernel.api.SessionEventPayload
import cc.noharry.agent.kernel.api.SessionJson
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelImage
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.model.ModelRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCaptureCodecTest {
    @Test
    fun modelRequestPersistsImageBytesAndRequestHints() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val request = ModelRequest(
            messages = listOf(
                ModelMessage(
                    role = "user",
                    content = "see this",
                    images = listOf(ModelImage(bytes, "image/png", "/tmp/a.png")),
                ),
            ),
            toolChoice = "auto",
            promptCacheKey = "cache-1",
        )

        val payload = request.toSessionPayload()
        val encoded = java.util.Base64.getEncoder().encodeToString(bytes)
        val messages = payload.messages.renderAttachments()
        assertEquals("auto", payload.toolChoice)
        assertEquals("cache-1", payload.promptCacheKey)
        assertEquals(SessionJson.Null, payload.options)
        assertEquals(1, payload.attachments.size)
        assertTrue(messages.contains(encoded))
        assertTrue(messages.contains("bytesBase64"))
    }

    @Test
    fun searchActivityIsPersistedInsteadOfDropped() {
        val payload = ModelEvent.SearchActivity(
            provider = "OpenAI",
            queryPreview = "latest sdk",
            resultCount = 2,
        ).toSessionAppendPayload() as SessionEventPayload.ModelResponseMetadata

        val fields = payload.fields as SessionJson.Object
        assertEquals(SEARCH_ACTIVITY_KIND, (fields.values["kind"] as SessionJson.Text).value)
        assertEquals("OpenAI", (fields.values["provider"] as SessionJson.Text).value)
        assertEquals("2", (fields.values["resultCount"] as SessionJson.Number).literal)
    }

    @Test
    fun completedAndUsageDeltasStayOutOfTheStreamCapturePath() {
        assertNull(ModelEvent.Completed.toSessionAppendPayload())
        assertNull(ModelEvent.UsageDelta(inputTokens = 1).toSessionAppendPayload())
        assertNotNull(ModelEvent.TextDelta("hi").toSessionAppendPayload())
    }
}

private fun SessionJson.renderAttachments(): String {
    val messages = this as SessionJson.Array
    val first = messages.values.first() as SessionJson.Object
    return first.values["attachments"].toString()
}
