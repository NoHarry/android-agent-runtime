@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.packages

import cc.noharry.agent.spi.ExecEngine
import cc.noharry.agent.spi.ExecRequest
import cc.noharry.agent.spi.ExecResult
import cc.noharry.agent.spi.ExecToolBridge
import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.RuntimeEffectKind
import cc.noharry.agent.spi.ToolCallContext
import cc.noharry.agent.spi.ToolCallResult
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.ToolProvider
import cc.noharry.agent.spi.reliability.RuntimeErrorCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One call of a script tool in the sandbox's real engine (QuickJS through Zipline's JVM build, the same version a
 * device runs): what run(input) returns comes back as JSON, a thrown error is the tool's failure, and the limits hold.
 */
class ScriptRunnerTest {
    private val logs = mutableListOf<String>()
    private val reads = Tools()

    @Test
    fun whatRunReturnsComesBackAsJson() {
        val result = call("function run(input) { return { trip: input.from + '-' + input.to, fare: 12.5, seats: [1, 2] }; }")
        assertTrue(result.resultJson, result.success)
        assertEquals("""{"trip":"Bern-Basel","fare":12.5,"seats":[1,2]}""", result.resultJson)
        assertEquals("""{"result":42}""", call("function run() { return 42; }").resultJson)
        assertEquals("""{"result":null}""", call("function run() { }").resultJson)
        assertEquals("""{"result":"Bern"}""", call("function run(input) { return input.from; }").resultJson)
    }

    @Test
    fun aThrownErrorIsTheToolsFailure() {
        assertFailure("Error: no trains today", call("function run() { throw new Error('no trains today'); }"))
        assertFailure("the script defines no function run(input)", call("const fare = 3;"))
        assertFailure("run returned a promise", call("async function run() { return 1; }"))
        assertFailure("TypeError", call("function run(input) { return input.missing.deeper; }"))
        assertFailure("the script failed: ", call("function run() { return 1 +; }"))
        assertFailure("TypeError", call("function run() { const loop = {}; loop.self = loop; return loop; }"))
    }

    @Test
    fun argumentsAreCheckedBeforeTheScriptRuns() {
        val script = "function run(input) { host.log('ran'); return input; }"
        assertFailure("to: missing", call(script, """{"from": "Bern"}"""))
        assertFailure("from: not string", call(script, """{"from": 1, "to": "Basel"}"""))
        assertFailure("arguments: not JSON", call(script, "{\"from\": }"))
        assertFailure("arguments: over 1 KiB", call(script, """{"from": "${"x".repeat(2000)}", "to": "Basel"}""", limits = ScriptLimits(argumentBytes = 1024)))
        assertTrue(logs.isEmpty())
    }

    @Test
    fun aScriptThatOutlastsItsToolsOwnTimeIsStoppedThere() {
        val started = System.nanoTime()
        val result = runBlocking { runner().call(toolAsking(1), "function run() { while (true) {} }", """{"from": "Bern", "to": "Basel"}""", CONTEXT) }
        val millis = (System.nanoTime() - started) / 1_000_000
        assertFailure("the script ran over its time (1 s)", result)
        assertTrue("stopped after $millis ms", millis in 1_000..4_000)
    }

    @Test
    fun aCallRunsForWhatItsToolAsksHeldToTheHostsMost() {
        fun timeOf(tool: ManifestTool, maxSeconds: Int): Pair<Long, String> {
            val engine = TimingOut()
            val limits = ScriptLimits(maxSeconds = maxSeconds)
            val grant = ScriptGrant("com.example.trains", emptySet(), emptySet(), emptyMap(), emptySet())
            val runner = ScriptRunner(grant, ScriptHttp(grant, limits, scriptHttpClient()), ScriptHostTools(emptySet(), emptyList()), {}, limits) { engine }
            val result = runBlocking { runner.call(tool, "function run() { return 1; }", """{"from": "Bern", "to": "Basel"}""", CONTEXT) }
            val error = (Json.parseToJsonElement(result.resultJson) as JsonObject)["error"]!!.jsonPrimitive.content
            return engine.timeoutMillis to error
        }
        assertEquals(30_000L to "the script ran over its time (30 s)", timeOf(toolAsking(120), maxSeconds = 30))
        assertEquals(5_000L to "the script ran over its time (5 s)", timeOf(TOOL, maxSeconds = 5))
        assertEquals(10_000L to "the script ran over its time (10 s)", timeOf(TOOL, maxSeconds = 30))
        assertEquals(3_000L, timeOf(toolAsking(3), maxSeconds = 30).first)
        assertEquals(30, ScriptLimits().maxSeconds)
        assertThrows(IllegalArgumentException::class.java) { ScriptLimits(maxSeconds = 0) }
    }

    @Test
    fun memoryAndStackAreBounded() {
        assertFailure("memory", call("function run() { const all = []; while (true) all.push(new Array(100000).fill(7)); }"))
        assertFailure("stack", call("function deeper(n) { return deeper(n + 1) + 1; } function run() { return deeper(0); }"))
        // The engine is still usable after each.
        assertTrue(call("function run() { return 1; }").success)
    }

    @Test
    fun whatRunReturnsIsCappedInBytes() {
        assertFailure("run returned over 64 KiB", call("function run() { return 'x'.repeat(70000); }"))
        // Under the cap in characters, over it in UTF-8.
        assertFailure("run returned over 64 KiB", call("function run() { return '中'.repeat(30000); }"))
        assertTrue(call("function run() { return 'x'.repeat(60000); }").success)
    }

    @Test
    fun hostLogLinesAreTheAppsMarkedAsThePackagesAndCapped() {
        call("function run() { host.log('one'); host.log({ n: 2 }); for (let i = 0; i < 30; i++) host.log('x'.repeat(900)); return 1; }")
        assertEquals("package com.example.trains, tool fare: one", logs[0])
        assertEquals("package com.example.trains, tool fare: [object Object]", logs[1])
        assertEquals(20, logs.size)
        assertEquals(500, logs.last().substringAfter("tool fare: ").length)
        logs.clear()
        call("function run() { host.log('before'); throw new Error('x'); }")
        assertEquals(listOf("package com.example.trains, tool fare: before"), logs)
    }

    @Test
    fun theEnginesOwnGlobalsAreOutOfReachAndAnyOtherHostCallIsRefused() {
        assertEquals("""{"result":"undefined undefined"}""", call("function run() { return typeof tools + ' ' + typeof emit; }").resultJson)
        val other = call("function run() { return JSON.parse(__pkgCall('host.files', '{}')); }")
        assertEquals("host.files is not a host function (host.http, host.tool, host.log)", Json.parseToJsonElement(other.resultJson).let { (it as JsonObject)["error"]!!.jsonPrimitive.content })
    }

    @Test
    fun hostToolCallsOnlyTheNamedToolsThatRead() {
        val runner = runner(hostTools = ScriptHostTools(setOf("weather", "mcp__home__lights", "set_lights"), listOf(reads)))
        val read = call("function run() { return host.tool('weather', { city: 'Bern' }); }", runner = runner)
        assertEquals("""{"city":"Bern","sky":"clear"}""", read.resultJson)
        assertEquals(listOf("weather {\"city\":\"Bern\"}"), reads.calls)
        val byWholeName = call("function run() { return host.tool('mcp__home__lights'); }", runner = runner)
        assertEquals("""{"on":true}""", byWholeName.resultJson)
        fun refusal(name: String) = call("function run() { try { host.tool('$name'); return 'ran'; } catch (e) { return e.message; } }", runner = runner)
            .resultJson.let { Json.parseToJsonElement(it) as JsonObject }["result"]!!.jsonPrimitive.content
        assertEquals("host.tool: set_lights does not say it only reads; scripts call only tools that read", refusal("set_lights"))
        assertEquals("host.tool: forecast is not among the host tools this package asked for", refusal("forecast"))
        assertEquals(2, reads.calls.size)
    }

    @Test
    fun aScriptMakesAtMostTwentyHostCallsAndTheSameOnesEachTime() {
        val runner = runner(hostTools = ScriptHostTools(setOf("weather"), listOf(reads)))
        val many = call("function run() { for (let i = 0; i < 21; i++) host.tool('weather', { city: 'c' + i }); return 1; }", runner = runner)
        assertFailure("Too many", many)
        assertEquals(20, reads.calls.size)
        reads.calls.clear()
        val random = call("function run() { host.tool('weather', { city: String(Math.random()) }); return host.tool('weather', { city: 'b' }); }", runner = runner)
        assertFailure("Math.random", random)
        assertEquals(1, reads.calls.size)
    }

    private fun runner(limits: ScriptLimits = ScriptLimits(), hostTools: ScriptHostTools = ScriptHostTools(emptySet(), emptyList())): ScriptRunner {
        val grant = ScriptGrant("com.example.trains", emptySet(), emptySet(), emptyMap(), hostTools = emptySet())
        return ScriptRunner(grant, ScriptHttp(grant, limits, scriptHttpClient()), hostTools, { logs += it }, limits)
    }

    private fun call(
        script: String,
        arguments: String = """{"from": "Bern", "to": "Basel"}""",
        limits: ScriptLimits = ScriptLimits(),
        runner: ScriptRunner = runner(limits),
    ): ToolCallResult = runBlocking { runner.call(TOOL, script, arguments, CONTEXT) }

    private fun assertFailure(expected: String, result: ToolCallResult) {
        assertFalse(result.resultJson, result.success)
        val error = (Json.parseToJsonElement(result.resultJson) as JsonObject)["error"]!!.jsonPrimitive.content
        assertTrue("\"$expected\" not in \"$error\"", expected in error)
    }

    /** An engine that keeps the time it was given and answers that the script ran over it. */
    private class TimingOut : ExecEngine {
        var timeoutMillis = -1L
        override val providerConcurrency = ProviderConcurrencyDeclaration.serialized()
        override suspend fun run(request: ExecRequest, bridge: ExecToolBridge): ExecResult {
            timeoutMillis = request.timeoutMillis
            return ExecResult(ok = false, errorCode = RuntimeErrorCode.EXEC_TIMEOUT.wireCode, message = "Exec timed out.")
        }
    }

    /** The example's tool, asking for [seconds]. */
    private fun toolAsking(seconds: Int): ManifestTool =
        checkPackage(TestPackages.build(TestPackages.scriptFiles(listOf(TestPackages.tool { put("timeoutSeconds", seconds) })))).manifest.tools.single()

    /** A host's tools: weather and lights only read, set_lights acts. */
    private class Tools : ToolProvider {
        val calls = mutableListOf<String>()
        override val providerId = "home"
        override suspend fun listTools() = listOf(
            ToolDescriptor("weather", inputSchemaJson = "{}", effectKind = RuntimeEffectKind.READ_ONLY.name),
            ToolDescriptor("lights", inputSchemaJson = "{}", effectKind = RuntimeEffectKind.READ_ONLY.name),
            ToolDescriptor("set_lights", inputSchemaJson = "{}"),
        )
        override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult {
            calls += "$toolName $argumentsJson"
            val city = (Json.parseToJsonElement(argumentsJson) as JsonObject)["city"]?.jsonPrimitive?.content
            return ToolCallResult(true, if (toolName == "lights") """{"on":true}""" else """{"city":"$city","sky":"clear"}""")
        }
    }

    private companion object {
        val TOOL = checkPackage(TestPackages.build(TestPackages.scriptFiles())).manifest.tools.single()
        val CONTEXT = ToolCallContext("session", "run", "request")
    }
}
