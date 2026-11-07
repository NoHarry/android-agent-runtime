package cc.noharry.agent.runtime

import cc.noharry.agent.*
import cc.noharry.agent.model.OpenAiModelClient
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ThreadImageInputTest {
    @Test fun imageBytesReachTheModelAndRemainInHistory() = runBlocking {
        val model = ThreadScriptModel(listOf(finishEvents("a picture")))
        threadBuilder(model).build().use { runtime ->
            val bytes = byteArrayOf(1, 2, 3, 4)
            val thread = runtime.startThread()
            val result = thread.run(TurnInput.of(InputPart.Text("describe"), InputPart.Image(bytes, "image/png")))
            assertEquals(TurnStatus.COMPLETED, result.status)
            val image = model.requests.single().messages.first { it.role == "user" }.images.single()
            assertArrayEquals(bytes, image.bytes)
            assertEquals("image/png", image.mediaType)
            bytes[0] = 9
            val saved = thread.items().filterIsInstance<ThreadItem.UserMessage>().single().input.filterIsInstance<InputPart.Image>().single()
            assertEquals(1, saved.bytes[0].toInt())
        }
    }

    @Test fun unsupportedImageInputFailsBeforeCallingModel() = runBlocking {
        val model = ThreadScriptModel(emptyList(), images = false)
        threadBuilder(model).build().use { runtime ->
            val events = runtime.startThread().runStreamed(TurnInput.of(InputPart.Image(byteArrayOf(1), "image/png"))).toList()
            assertTurnEvents(events)
            assertEquals(cc.noharry.agent.TurnErrorCodes.IMAGE_INPUT_UNSUPPORTED, (events.last() as ThreadEvent.TurnFailed).error.code)
            assertTrue(model.requests.isEmpty())
        }
    }

    @Test fun configuredTextOnlyModeFailsBeforeCallingModel() = runBlocking {
        val model = ThreadScriptModel(emptyList())
        threadBuilder(model).imageInput(false).build().use { runtime ->
            val result = runtime.startThread().run(TurnInput.of(InputPart.Image(byteArrayOf(1), "image/png")))
            assertEquals(cc.noharry.agent.TurnErrorCodes.IMAGE_INPUT_UNSUPPORTED, result.error?.code)
            assertTrue(model.requests.isEmpty())
        }
    }

    @Test fun knownTextOnlyProviderModelsDeclareCapability() {
        assertFalse(OpenAiModelClient({ "unused" }, model = "deepseek-chat").supportsImageInput())
        assertFalse(OpenAiModelClient({ "unused" }, model = "gpt-3.5-turbo").supportsImageInput())
        assertTrue(OpenAiModelClient({ "unused" }, model = "gpt-4o").supportsImageInput())
    }
}
