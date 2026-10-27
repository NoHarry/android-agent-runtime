@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.exec.js

import cc.noharry.agent.kernel.api.ExecRequest
import cc.noharry.agent.kernel.api.ExecToolBridge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A script's tool calls happen once each and in order, as the script makes them, across the engine's re-runs. */
class ZiplineExecEngineReplayTest {
    private class Car : ExecToolBridge {
        var temperature = 24
        val calls = mutableListOf<String>()
        override suspend fun callTool(name: String, argumentsJson: String): String {
            calls += name
            return when (name) {
                "set_climate" -> { temperature += 2; """{"ok":true}""" }
                else -> """{"temperature":$temperature}"""
            }
        }
        override suspend fun emitOutput(text: String) = Unit
    }

    private fun run(code: String, car: Car) = runBlocking {
        ZiplineExecEngine().run(ExecRequest("run-1", code, timeoutMillis = 10_000, outputTokenBudget = 1_000), car)
    }

    @Test fun aReadAfterAWriteSeesTheNewState() {
        val car = Car()
        val result = run(
            """
            const before = JSON.parse(tools.call("get_climate", "{}")).temperature;
            tools.call("set_climate", "{}");
            const after = JSON.parse(tools.call("get_climate", "{}")).temperature;
            return before + "->" + after;
            """.trimIndent(),
            car,
        )
        assertEquals(result.message, "24->26", result.output)
        assertEquals(listOf("get_climate", "set_climate", "get_climate"), car.calls)
    }

    @Test fun theSameCallMadeTwiceRunsTwice() {
        val car = Car()
        run("""tools.call("set_climate", "{}"); tools.call("set_climate", "{}"); return "done";""", car)
        assertEquals(28, car.temperature)
    }

    @Test fun aScriptThatCallsDifferentlyOnReplayStopsBeforeRunningAnyToolAgain() {
        val car = Car()
        val result = run("""tools.call("get_climate", String(Math.random())); return "ok";""", car)
        assertTrue(result.message.orEmpty(), !result.ok)
        assertEquals(1, car.calls.size)
    }

    @Test fun aTryCatchAroundTheCallDoesNotSkipTheTool() {
        val car = Car()
        val result = run(
            """let t = "?"; try { t = JSON.parse(tools.call("get_climate", "{}")).temperature; } catch (e) {} return "temperature " + t;""",
            car,
        )
        assertEquals("temperature 24", result.output)
    }
}
