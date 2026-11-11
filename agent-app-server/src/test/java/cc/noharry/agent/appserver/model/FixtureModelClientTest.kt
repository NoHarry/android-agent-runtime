package cc.noharry.agent.appserver.model

import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.Persona
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelMessage
import cc.noharry.agent.spi.model.ModelRequest
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The server's own simulated model, for a host without a scripted one: who it is, else what it heard. */
class FixtureModelClientTest {
    @After
    fun reset() = AppHost.install(persona = Persona.GENERIC)

    @Test
    fun itIntroducesTheAssistantOrSaysWhatItHeardInThePersonasLanguage() = runBlocking {
        assertTrue(reply("Who are you?").startsWith("I'm ${Persona.GENERIC.name}:"))
        assertTrue(reply("Book me a table").contains("got this turn's input: Book me a table. No simulated case covers it"))
        AppHost.install(persona = Persona(Persona.GENERIC.name, Persona.GENERIC.identity, language = "zh-CN"))
        assertTrue(reply("介绍一下你自己").startsWith("我是${Persona.GENERIC.name}："))
        assertTrue(reply("帮我订\n个位子").contains("收到了本轮输入：帮我订 个位子。当前模拟场景未覆盖这个输入"))
    }

    private suspend fun reply(text: String): String {
        val events = FixtureModelClient().complete(ModelRequest(messages = listOf(ModelMessage("user", text)))).toList()
        assertEquals(ModelEvent.Completed, events.last())
        val finish = events.filterIsInstance<ModelEvent.ToolCall>().single { it.name == "finish" }
        return Json.parseToJsonElement(finish.argumentsJson).jsonObject.getValue("response").jsonPrimitive.content
    }
}
