@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.SessionEventLog
import cc.noharry.agent.kernel.api.SessionEventPayload
import cc.noharry.agent.kernel.api.SessionJson
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelImage
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.core.DynamicToolSource
import cc.noharry.agent.kernel.core.MaterializerContext
import cc.noharry.agent.runtime.eventlog.FileBackedSessionEventLog
import cc.noharry.agent.runtime.eventlog.InMemorySessionEventLog
import cc.noharry.agent.runtime.eventlog.toAttachmentRef
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionImageAttachmentTest {
    private val image = ModelImage(ByteArray(2048) { (it % 251).toByte() }, "image/jpeg", "android-use/obs_1.jpg")
    private val context = MaterializerContext(sessionId = "s", runId = "r", requestId = "q")

    @Test
    fun fileLogStoresRequestImageOnceAndKeepsOnlyItsReference() {
        val root = Files.createTempDirectory("attachments-request-").toFile()
        val log = FileBackedSessionEventLog(root)
        val recorder = recorder(log)

        repeat(2) { recorder.recordModelRequest(context, ModelRequest(listOf(screenshotMessage()))) }

        val attachments = requestAttachments(log)
        assertEquals(2, attachments.size)
        attachments.forEach { attachment ->
            assertEquals("session-attachment", attachment.text("storage"))
            assertNull(attachment.values["bytesBase64"])
            assertEquals(image.toAttachmentRef().sha256, attachment.text("sha256"))
        }
        assertArrayEquals(image.bytes, log.readAttachment("s", image.toAttachmentRef().sha256))
        assertEquals(1, attachmentFiles(root).size)
    }

    @Test
    fun logWithoutAttachmentStoreKeepsBytesInline() {
        val log = InMemorySessionEventLog()

        recorder(log).recordModelRequest(context, ModelRequest(listOf(screenshotMessage())))

        val attachment = requestAttachments(log).single()
        assertFalse(attachment.text("bytesBase64").isNullOrBlank())
        assertNull(attachment.values["storage"])
    }

    @Test
    fun toolResultImagesSurviveReopeningTheLog() {
        val root = Files.createTempDirectory("attachments-tool-").toFile()
        val recorder = recorder(FileBackedSessionEventLog(root))
        val call = ModelEvent.ToolCall("call-1", "android_observe", "{}")

        recorder.recordToolStarted(context, call, DynamicToolSource.BACKEND)
        recorder.recordToolFinishedWithImages(context, call, DynamicToolSource.BACKEND, """{"ok":true}""", listOf(image))
        recorder.flushPendingToolRecordings()

        val reopened = FileBackedSessionEventLog(root)
        val result = reopened.replay("s", "r").map { it.payload }.filterIsInstance<SessionEventPayload.ToolResult>().single()
        assertEquals(listOf(image.toAttachmentRef()), result.attachments)
        assertArrayEquals(image.bytes, reopened.readAttachment("s", result.attachments.single().sha256))
    }

    @Test
    fun aToolResultNamesOnlyAttachmentsTheLogKept() {
        val log = InMemorySessionEventLog()
        val recorder = recorder(log)
        val call = ModelEvent.ToolCall("call-1", "android_observe", "{}")

        recorder.recordToolStarted(context, call, DynamicToolSource.BACKEND)
        recorder.recordToolFinishedWithImages(context, call, DynamicToolSource.BACKEND, """{"ok":true}""", listOf(image))
        recorder.recordModelRequest(context, ModelRequest(listOf(screenshotMessage())))

        val result = log.replay("s", "r").map { it.payload }.filterIsInstance<SessionEventPayload.ToolResult>().single()
        assertTrue(result.attachments.isEmpty())
        // The bytes stay inline in the request that carried them to the model.
        assertFalse(requestAttachments(log).single().text("bytesBase64").isNullOrBlank())
    }

    @Test
    fun corruptOrMismatchedAttachmentsAreRefused() {
        val root = Files.createTempDirectory("attachments-corrupt-").toFile()
        val log = FileBackedSessionEventLog(root)
        val ref = image.toAttachmentRef()

        assertFalse(log.putAttachment("s", ref, byteArrayOf(9, 9, 9)))
        log.putAttachment("s", ref, image.bytes)
        attachmentFiles(root).single().writeBytes(byteArrayOf(0, 0, 0))

        assertNull(log.readAttachment("s", ref.sha256))
        assertNull(log.readAttachment("other-session", ref.sha256))
    }

    private fun recorder(log: SessionEventLog): RuntimeSessionRecorder {
        var clock = 100L
        return RuntimeSessionRecorder(null, log) { clock++ }.also {
            it.startRun(AgentRequest("q", "s", "input"), "r")
        }
    }

    private fun screenshotMessage(): ModelMessage =
        ModelMessage(role = "tool", content = "{}", toolCallId = "call-1", toolName = "android_observe", images = listOf(image))

    private fun requestAttachments(log: SessionEventLog): List<SessionJson.Object> =
        log.replay("s", "r").map { it.payload }.filterIsInstance<SessionEventPayload.ModelRequest>().flatMap { request ->
            (request.messages as SessionJson.Array).values.flatMap { message ->
                ((message as SessionJson.Object).values["attachments"] as? SessionJson.Array)?.values.orEmpty()
            }
        }.map { it as SessionJson.Object }

    private fun attachmentFiles(root: File): List<File> =
        root.walkTopDown().filter { it.isFile && it.parentFile.parentFile.name == "attachments" }.toList()

    private fun SessionJson.Object.text(key: String): String? = (values[key] as? SessionJson.Text)?.value
}
