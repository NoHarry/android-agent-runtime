package cc.noharry.agent.kernel.core.loop
import cc.noharry.agent.kernel.api.model.ModelImage
import cc.noharry.agent.kernel.api.model.ModelMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelImageRequestViewTest {
    private val view = ModelImageRequestView()

    @Test
    fun foldsOldestImagesInStepsOfThree() {
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 3, 3, 3, 6, 6, 6, 9), (0..12).map(view::foldedCount))
    }

    @Test
    fun fewImagesLeaveTheRequestUntouched() {
        val messages = screenshots(5)

        assertSame(messages, view.apply(messages))
    }

    @Test
    fun foldedImagesBecomePlaceholdersAndNewestStay() {
        val messages = screenshots(7)

        val shaped = view.apply(messages)

        assertEquals(listOf(0, 0, 0, 1, 1, 1, 1), shaped.map { it.images.size })
        assertTrue(shaped[0].content.endsWith("[image omitted: older images fold first, newer ones follow: obs_0.jpg]"))
        assertEquals(messages[3], shaped[3])
        assertEquals(listOf(1, 1, 1, 1, 1, 1, 1), messages.map { it.images.size })
    }

    @Test
    fun textOnlyModelGetsNoImages() {
        val shaped = ModelImageRequestView(imageInputEnabled = false).apply(screenshots(2))

        assertEquals(listOf(0, 0), shaped.map { it.images.size })
        assertTrue(shaped[1].content.endsWith("[image omitted: this model accepts text only: obs_1.jpg]"))
    }

    @Test
    fun foldingIsStableAsTheHistoryGrows() {
        val first = view.apply(screenshots(7))
        val second = view.apply(screenshots(8))

        assertEquals(first.take(7).map { it.content }, second.take(7).map { it.content })
    }

    private fun screenshots(count: Int): List<ModelMessage> =
        (0 until count).map { index ->
            ModelMessage(
                role = "tool",
                content = """{"observationId":"obs_$index"}""",
                toolCallId = "call-$index",
                toolName = "android_observe",
                images = listOf(ModelImage(byteArrayOf(index.toByte(), 1), "image/jpeg", "obs_$index.jpg")),
            )
        }
}
