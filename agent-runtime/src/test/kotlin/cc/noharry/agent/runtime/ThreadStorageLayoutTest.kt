package cc.noharry.agent.runtime

import cc.noharry.agent.*
import cc.noharry.agent.runtime.thread.ThreadStore
import cc.noharry.agent.runtime.thread.ThreadRecordCodec
import java.io.File
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ThreadStorageLayoutTest {
    @Test fun snapshotsAreSeparateAndRepeatedImagesAreSharedWithProviderHistory() = runBlocking {
        val root = threadRoot()
        val image = ByteArray(128 * 1024) { (it % 251).toByte() }
        val model = ThreadScriptModel(List(12) { finishEvents("answer") })
        threadBuilder(model, root).build().use { runtime ->
            val thread = runtime.startThread(ThreadOptions(threadId = "images"))
            var first: File? = null
            var firstText = ""
            repeat(12) { index ->
                thread.run(TurnInput(listOf(InputPart.Text("image $index"), InputPart.Image(image, "image/png"))), TurnOptions(turnId = "turn-$index"))
                if (index == 0) {
                    first = snapshotFiles(root).single()
                    firstText = first!!.readText()
                }
            }
            assertEquals(firstText, first!!.readText())
            assertEquals(12, snapshotFiles(root).size)
            val blobs = File(root, "storage").walkTopDown().filter { it.isFile && it.parentFile.name == "blobs" && it.path.contains(".data/") }.toList()
            assertEquals(1, blobs.size)
            assertArrayEquals(image, blobs.single().readBytes())
            val metadata = File(root, "storage").listFiles().orEmpty().single { it.name.endsWith(".thread.json") }.readText()
            assertFalse(metadata.contains("input"))
            assertFalse(metadata.contains("base64"))
            assertTrue(metadata.toByteArray().size < 4096)
            val history = File(root, "storage").listFiles().orEmpty().single { it.name.endsWith(".history.json") }.readText()
            assertTrue(history.contains("blobSha256"))
            assertFalse(history.contains(Base64.getEncoder().encodeToString(image)))
        }
        threadBuilder(ThreadScriptModel(emptyList()), root).build().use { runtime ->
            val items = runtime.resumeThread("images").items().filterIsInstance<ThreadItem.UserMessage>()
            assertEquals(12, items.size)
            items.forEach { assertArrayEquals(image, it.input.filterIsInstance<InputPart.Image>().single().bytes) }
        }
    }

    @Test fun listingReadsOnlyMetadataEvenWhenAnOldSnapshotIsDamaged() = runBlocking {
        val root = threadRoot()
        threadBuilder(ThreadScriptModel(listOf(finishEvents("answer"))), root).build().use { runtime ->
            runtime.startThread(ThreadOptions(threadId = "listed")).run("hello")
            snapshotFiles(root).single().writeText("{invalid")
            assertEquals(listOf("listed"), runtime.listThreads().map { it.id })
        }
    }

    @Test fun oldInlineSnapshotsMigrateOnceWithoutLosingTheirImages() {
        val root = threadRoot()
        val store = ThreadStore(root)
        store.create("old", ThreadOptions())
        val target = root.listFiles().orEmpty().single { it.name.endsWith(".thread.json") }
        val image = byteArrayOf(1, 2, 3)
        val turn = TurnResult("old-turn", TurnStatus.COMPLETED, "old answer",
            listOf(ThreadItem.UserMessage("input", "old-turn", listOf(InputPart.Image(image, "image/png")))))
        val original = Json.parseToJsonElement(target.readText()).jsonObject
        target.writeText(JsonObject(original + mapOf("version" to JsonPrimitive(1),
            "turns" to JsonArray(listOf(ThreadRecordCodec(DefaultTraceRedactor).turn(turn)))) - "turnIds").toString())
        val restored = checkNotNull(ThreadStore(root).turn("old", "old-turn"))
        assertEquals("old answer", restored.finalResponse)
        assertArrayEquals(image, (restored.items.single() as ThreadItem.UserMessage).input.filterIsInstance<InputPart.Image>().single().bytes)
        assertEquals(2, Json.parseToJsonElement(target.readText()).jsonObject.getValue("version").jsonPrimitive.int)
        assertEquals(1, root.walkTopDown().count { it.isFile && it.parentFile.name == "turns" })
    }

    private fun snapshotFiles(root: File): List<File> = File(root, "storage").walkTopDown()
        .filter { it.isFile && it.parentFile.name == "turns" }.toList()
}
