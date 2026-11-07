package cc.noharry.agent.runtime

import cc.noharry.agent.*
import cc.noharry.agent.runtime.eventlog.FileBackedSessionEventLog
import cc.noharry.agent.spi.SessionAttachmentRef
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ThreadAttachmentInputTest {
    @Test fun textAttachmentContentReachesModelAndVisibleItemKeepsReference() = runBlocking {
        val root = threadRoot()
        val log = FileBackedSessionEventLog(File(root, "attachments"))
        val bytes = "Meeting notes: approve the blue design.".toByteArray(Charsets.UTF_8)
        val ref = attachmentRef(bytes, "text/plain")
        assertTrue(log.putAttachment("attached", ref, bytes))
        val model = ThreadScriptModel(listOf(finishEvents("read")))
        threadBuilder(model, root).sessionEventLog(log).build().use { runtime ->
            val result = runtime.startThread(ThreadOptions(threadId = "attached")).run(TurnInput.of(InputPart.Attachment(ref)))
            assertEquals(TurnStatus.COMPLETED, result.status)
            assertTrue(model.requests.single().messages.first { it.role == "user" }.content.contains(bytes.toString(Charsets.UTF_8)))
            assertEquals(ref, result.items.filterIsInstance<ThreadItem.UserMessage>().single().input.filterIsInstance<InputPart.Attachment>().single().ref)
        }
    }

    @Test fun imageAttachmentReachesModelAsImage() = runBlocking {
        val root = threadRoot()
        val log = FileBackedSessionEventLog(File(root, "attachments"))
        val bytes = byteArrayOf(1, 2, 3)
        val ref = attachmentRef(bytes, "image/png")
        assertTrue(log.putAttachment("attached", ref, bytes))
        val model = ThreadScriptModel(listOf(finishEvents("image")))
        threadBuilder(model, root).sessionEventLog(log).build().use { runtime ->
            val result = runtime.startThread(ThreadOptions(threadId = "attached")).run(TurnInput.of(InputPart.Attachment(ref)))
            assertEquals(TurnStatus.COMPLETED, result.status)
            assertArrayEquals(bytes, model.requests.single().messages.first { it.role == "user" }.images.single().bytes)
        }
    }

    @Test fun missingAttachmentFailsBeforeCallingModel() = runBlocking {
        val model = ThreadScriptModel(emptyList())
        threadBuilder(model).build().use { runtime ->
            val ref = attachmentRef("missing".toByteArray(), "text/plain")
            val result = runtime.startThread().run(TurnInput.of(InputPart.Attachment(ref)))
            assertEquals("ATTACHMENT_NOT_FOUND", result.error?.code)
            assertTrue(model.requests.isEmpty())
        }
    }

    @Test fun unsupportedAttachmentFailsBeforeCallingModel() = runBlocking {
        val root = threadRoot()
        val log = FileBackedSessionEventLog(File(root, "attachments"))
        val ref = attachmentRef(byteArrayOf(1), "application/octet-stream")
        assertTrue(log.putAttachment("attached", ref, byteArrayOf(1)))
        val model = ThreadScriptModel(emptyList())
        threadBuilder(model, root).sessionEventLog(log).build().use { runtime ->
            val result = runtime.startThread(ThreadOptions(threadId = "attached")).run(TurnInput.of(InputPart.Attachment(ref)))
            assertEquals("ATTACHMENT_MEDIA_UNSUPPORTED", result.error?.code)
            assertTrue(model.requests.isEmpty())
        }
    }
}

private fun attachmentRef(bytes: ByteArray, mediaType: String): SessionAttachmentRef = SessionAttachmentRef(
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) },
    mediaType, bytes.size.toLong(), "input-file",
)
