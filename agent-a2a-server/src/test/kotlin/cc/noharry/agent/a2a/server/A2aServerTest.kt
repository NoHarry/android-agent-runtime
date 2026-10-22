package cc.noharry.agent.a2a.server

import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test

class A2aServerTest {
    private val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()

    @Test fun activeTaskAnswersUseOriginalOwnerAndKeepOrdinaryContextAdmissionBusy() {
        val ready = CountDownLatch(1)
        val done = CompletableDeferred<Unit>()
        val executions = AtomicInteger()
        val received = mutableListOf<String>()
        val host = object : A2aRequestHandler {
            override fun execute(request: A2aRequest) = flow {
                executions.incrementAndGet()
                emit(A2aOutput.Question("question-1", "选择城市", listOf("苏州", "上海"), "pending"))
                ready.countDown()
                done.await()
                emit(A2aOutput.Result("完成"))
            }
            override suspend fun postInput(request: A2aRequest, messageId: String, text: String, questionId: String?): A2aInputReceipt {
                if (questionId != "question-1") return A2aInputReceipt(false, code = "QUESTION_NOT_FOUND")
                synchronized(received) {
                    if (messageId in received) return A2aInputReceipt(true, duplicate = true)
                    received += messageId
                }
                return A2aInputReceipt(true)
            }
        }
        withServer(host) { server ->
            val task = rpc(server, "message/send", message(blocking = false))["result"]!!.jsonObject
            assertTrue(ready.await(2, TimeUnit.SECONDS))
            val context = task.string("contextId")!!
            assertNotNull(rpc(server, "message/send", message(context = context, blocking = false))["error"])
            val base = message("苏州", context, false)
            fun control(questionId: String, contextId: String = context): JsonObject = JsonObject(base + ("message" to
                JsonObject(base["message"]!!.jsonObject + mapOf("taskId" to task["id"]!!, "contextId" to text(contextId),
                    "metadata" to jsonObject("questionId" to text(questionId))))))
            assertNotNull(rpc(server, "message/send", control("question-1", "foreign"))["error"])
            assertNotNull(rpc(server, "message/send", control("unknown"))["error"])
            val answered = rpc(server, "message/send", control("question-1"))["result"]!!.jsonObject
            assertEquals(task["id"], answered["id"])
            assertEquals("queued", answered["metadata"]!!.jsonObject["inputReceipt"]!!.jsonObject.string("state"))
            val duplicate = rpc(server, "message/send", control("question-1"))["result"]!!.jsonObject
            assertEquals("duplicate", duplicate["metadata"]!!.jsonObject["inputReceipt"]!!.jsonObject.string("state"))
            assertEquals(listOf("m-1"), received)
            assertEquals(1, executions.get())
            val artifact = answered["artifacts"]!!.jsonArray.single().jsonObject
            assertEquals("question", artifact["metadata"]!!.jsonObject.string("channel"))
            assertEquals("question-1", artifact["metadata"]!!.jsonObject.string("questionId"))
            done.complete(Unit)
        }
    }

    @Test fun clarificationKeepsSpeechAndDisplayAndInputRequiredState() = withServer({ flowOf(
        A2aOutput.Speech("您要查询哪座城市？", final = true),
        A2aOutput.Result("您要查询哪座城市？", A2aOutput.State.INPUT_REQUIRED),
    ) }) { server ->
        val task = rpc(server, "message/send", message())["result"]!!.jsonObject
        assertEquals("input-required", task["status"]!!.jsonObject.string("state"))
        assertEquals(listOf("speech", "display"), task["artifacts"]!!.jsonArray.map {
            it.jsonObject["metadata"]!!.jsonObject.string("channel")
        })
        assertEquals(task, rpc(server, "tasks/get", jsonObject("id" to task["id"]!!))["result"])
        val next = rpc(server, "message/send", message(context = task.string("contextId")))["result"]!!.jsonObject
        assertEquals(task["contextId"], next["contextId"])
    }

    @Test fun cardAndBlockingTextTaskRoundTrip() = withServer({ request ->
        flowOf(A2aOutput.Speech("播报文字"), A2aOutput.Result(request.text))
    }) { server ->
        val card = get(server, "/.well-known/agent-card.json")
        assertEquals("0.3.0", card.string("protocolVersion"))
        assertEquals("http://127.0.0.1:${server.localPort}/a2a", card.string("url"))
        val extension = card["capabilities"]!!.jsonObject["extensions"]!!.jsonArray.single().jsonObject
        assertEquals(TASK_CONTROL_EXTENSION, extension.string("uri"))
        val task = rpc(server, "message/send", message("你好\n第二行"))["result"]!!.jsonObject
        assertEquals("completed", task["status"]!!.jsonObject.string("state"))
        val artifacts = task["artifacts"]!!.jsonArray
        assertEquals("tts", artifacts[0].jsonObject["metadata"]!!.jsonObject.string("kind"))
        assertEquals("speech", artifacts[0].jsonObject["metadata"]!!.jsonObject.string("channel"))
        assertEquals("display", artifacts[1].jsonObject["metadata"]!!.jsonObject.string("channel"))
        assertEquals("response", artifacts[1].jsonObject["metadata"]!!.jsonObject.string("kind"))
        assertEquals("status", task["status"]!!.jsonObject["message"]!!.jsonObject["metadata"]!!.jsonObject.string("channel"))
        assertEquals("你好\n第二行", artifacts[1].jsonObject["parts"]!!.jsonArray[0].jsonObject.string("text"))
        assertEquals(task, rpc(server, "tasks/get", jsonObject("id" to task["id"]!!))["result"])
    }

    @Test fun scalarMessageMetadataReachesHostAndMalformedMetadataIsIgnored() {
        val seen = java.util.Collections.synchronizedList(mutableListOf<Map<String, String>>())
        withServer({ request -> seen += request.metadata; flowOf(A2aOutput.Result("ok")) }) { server ->
            val tagged = buildJsonObject {
                put("message", buildJsonObject {
                    put("role", "user"); put("messageId", "m-tag")
                    put("parts", JsonArray(listOf(textPart("hi"))))
                    put("metadata", buildJsonObject { put("debugMirror", true); put("nested", jsonObject("a" to text("b"))) })
                })
            }
            assertEquals("completed", rpc(server, "message/send", tagged)["result"]!!.jsonObject["status"]!!.jsonObject.string("state"))
            val malformed = JsonObject(tagged + ("message" to JsonObject(tagged["message"]!!.jsonObject + ("metadata" to text("x")))))
            assertEquals("completed", rpc(server, "message/send", malformed)["result"]!!.jsonObject["status"]!!.jsonObject.string("state"))
        }
        assertEquals(listOf(mapOf("debugMirror" to "true"), emptyMap()), seen)
    }

    @Test fun sseDeliversProgressBeforeExecutionCompletesAndExactlyOneTerminal() {
        val release = CompletableDeferred<Unit>()
        withServer({ flow {
            emit(A2aOutput.Status("正在调用导航", "started", "navigate", "call-1"))
            emit(A2aOutput.Speech("正在为你查询路线。"))
            release.await()
            emit(A2aOutput.Status("导航调用完成", "completed", "navigate", "call-1"))
            emit(A2aOutput.Speech("路线已准备好", final = true))
            emit(A2aOutput.Result("路线已准备好"))
        } }) { server ->
            post(server, "message/stream", message()).use { response ->
                assertEquals("text/event-stream; charset=utf-8", response.header("Content-Type"))
                val source = response.body!!.source()
                val events = mutableListOf<JsonObject>()
                while (events.none { it["artifact"]?.jsonObject?.string("name") == "tts" }) {
                    val line = source.readUtf8Line() ?: error("Premature EOF")
                    if (line.startsWith("data: ")) events += Json.parseToJsonElement(line.substring(6)).jsonObject["result"]!!.jsonObject
                }
                assertEquals("task", events.first().string("kind"))
                assertTrue(events.any { it["metadata"]?.jsonObject?.string("toolCallId") == "call-1" })
                assertFalse(events.any { it["final"] == JsonPrimitive(true) })
                release.complete(Unit)
                source.readUtf8().lineSequence().filter { it.startsWith("data: ") }.forEach {
                    events += Json.parseToJsonElement(it.substring(6)).jsonObject["result"]!!.jsonObject
                }
                assertEquals(1, events.count { it["final"] == JsonPrimitive(true) })
                assertEquals("completed", events.last()["status"]!!.jsonObject.string("state"))
                assertOutputChannels(server, events)
            }
        }
    }

    private fun assertOutputChannels(server: A2aServer, events: List<JsonObject>) {
        val artifacts = events.mapNotNull { it["artifact"]?.jsonObject }
        val metadata = artifacts.map { it["metadata"]!!.jsonObject }
        assertEquals(listOf("speech", "speech", "display"), metadata.map { it.string("channel") })
        assertEquals(listOf("progress", "final", "final"), metadata.map { it.string("phase") })
        assertEquals(listOf("tts", "tts", "response"), metadata.map { it.string("kind") })
        // Identical speech and display text still represent independent consumer channels.
        assertEquals(artifacts[1]["parts"], artifacts[2]["parts"])
        events.filter { it.string("kind") == "status-update" }.forEach { event ->
            assertEquals("status", event["metadata"]!!.jsonObject.string("channel"))
            assertEquals("status", event["status"]!!.jsonObject["message"]!!.jsonObject["metadata"]!!.jsonObject.string("channel"))
        }
        assertEquals("status", events.first()["status"]!!.jsonObject["message"]!!.jsonObject["metadata"]!!.jsonObject.string("channel"))
        val task = rpc(server, "tasks/get", jsonObject("id" to events.first()["id"]!!))["result"]!!.jsonObject
        assertEquals(JsonArray(artifacts), task["artifacts"])
    }

    @Test fun cancelWaitsForHostCleanupAndContextCannotOverlap() {
        val entered = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        withServer({ flow {
            try { entered.countDown(); awaitCancellation() } finally { cleaned.countDown() }
        } }) { server ->
            val task = rpc(server, "message/send", message(blocking = false))["result"]!!.jsonObject
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val busy = rpc(server, "message/send", message(context = task.string("contextId"), blocking = false))
            assertEquals(-32000, busy["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
            val canceled = rpc(server, "tasks/cancel", jsonObject("id" to task["id"]!!))["result"]!!.jsonObject
            assertEquals(0L, cleaned.count)
            assertEquals("canceled", canceled["status"]!!.jsonObject.string("state"))
            assertEquals(-32002, rpc(server, "tasks/cancel", jsonObject("id" to task["id"]!!))["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        }
    }

    @Test fun anInterruptingMessageCancelsTheContextsRunningTaskThenRunsInItsPlace() {
        val entered = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        val ran = java.util.Collections.synchronizedList(mutableListOf<String>())
        withServer({ request -> flow {
            ran += request.text
            if (request.text == "第一个") {
                try { entered.countDown(); awaitCancellation() } finally { cleaned.countDown() }
            }
            emit(A2aOutput.Result(request.text))
        } }) { server ->
            val first = rpc(server, "message/send", message("第一个", blocking = false))["result"]!!.jsonObject
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val context = first.string("contextId")!!

            val second = rpc(server, "message/send", interrupting(message("第二个", context)))["result"]!!.jsonObject
            assertEquals("the old task's host cleanup ran before the new task", 0L, cleaned.count)
            assertEquals("completed", second["status"]!!.jsonObject.string("state"))
            assertEquals(context, second.string("contextId"))
            val old = rpc(server, "tasks/get", jsonObject("id" to first["id"]!!))["result"]!!.jsonObject["status"]!!.jsonObject
            assertEquals("canceled", old.string("state"))
            assertEquals(INTERRUPTED_TEXT, old["message"]!!.jsonObject["parts"]!!.jsonArray.single().jsonObject.string("text"))

            // With nothing running it is an ordinary message.
            val third = rpc(server, "message/send", interrupting(message("第三个", context)))["result"]!!.jsonObject
            assertEquals("completed", third["status"]!!.jsonObject.string("state"))
            assertEquals(listOf("第一个", "第二个", "第三个"), ran)
        }
    }

    @Test fun disconnectCancelsOnlyItsStreamingTask() {
        val cleaned = CountDownLatch(1)
        withServer({ flow {
            try { awaitCancellation() } finally { cleaned.countDown() }
        } }) { server ->
            post(server, "message/stream", message()).use { response ->
                assertTrue(response.body!!.source().readUtf8Line()!!.startsWith("data: "))
            }
            assertTrue("Disconnect must release the host", cleaned.await(4, TimeUnit.SECONDS))
        }
    }

    @Test fun rejectsInvalidInputWithoutExecutingAndPreservesRpcIds() {
        val calls = AtomicInteger()
        withServer({ calls.incrementAndGet(); flowOf(A2aOutput.Result("ok")) }) { server ->
            val invalid = listOf(message(" "), message(context = "../foreign"), jsonObject())
            invalid.forEach { params ->
                val reply = rpc(server, "message/send", params)
                assertEquals(text("request-1"), reply["id"])
                assertEquals(-32602, reply["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
            }
            val method = rpc(server, "unknown", jsonObject())
            assertEquals(-32601, method["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
            assertEquals(0, calls.get())
        }
    }

    @Test fun missingResultThrownErrorAndTimeoutAreFailures() {
        val handlers: List<A2aRequestHandler> = listOf(
            A2aRequestHandler { emptyFlow() },
            A2aRequestHandler { flow { error("secret must not escape") } },
            A2aRequestHandler { flow { awaitCancellation() } },
        )
        handlers.forEach { handler ->
            withServer(handler, A2aServerConfig(port = 0, taskTimeoutMillis = 100)) { server ->
                val task = rpc(server, "message/send", message())["result"]!!.jsonObject
                assertEquals("failed", task["status"]!!.jsonObject.string("state"))
                assertEquals("display", task["artifacts"]!!.jsonArray.single().jsonObject["metadata"]!!.jsonObject.string("channel"))
                assertFalse(task.toString().contains("secret must not escape"))
            }
        }
    }

    @Test fun retainedTaskBoundEvictsOldCompletedTasksAndAllowsKnownContext() {
        withServer({ flowOf(A2aOutput.Result(it.contextId)) }, A2aServerConfig(port = 0, maxActiveTasks = 1, maxRetainedTasks = 2)) { server ->
            val first = rpc(server, "message/send", message())["result"]!!.jsonObject
            val next = rpc(server, "message/send", message(context = first.string("contextId")))["result"]!!.jsonObject
            assertEquals(first["contextId"], next["contextId"])
            assertNotEquals(first["id"], next["id"])
            rpc(server, "message/send", message())
            assertNotNull(rpc(server, "tasks/get", jsonObject("id" to first["id"]!!))["error"])
        }
    }

    @Test fun httpLimitsOriginAndContentTypeAreEnforced() = withServer({ flowOf(A2aOutput.Result("ok")) }) { server ->
        val request = Request.Builder().url(url(server, "/a2a"))
            .post("{}".toRequestBody("application/json".toMediaType())).header("Origin", "https://example.test").build()
        client.newCall(request).execute().use { assertEquals(403, it.code) }
        Socket("127.0.0.1", server.localPort).use { socket ->
            socket.soTimeout = 2_000
            socket.getOutputStream().write("POST /a2a HTTP/1.1\r\nContent-Length: 999999\r\n\r\n".toByteArray())
            assertTrue(socket.getInputStream().bufferedReader().readLine().contains("413"))
        }
    }

    @Test fun closingServerCancelsHostAndReleasesListeningPort() {
        val entered = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        val server = A2aServer(A2aServerConfig(port = 0)) { flow {
            try { entered.countDown(); awaitCancellation() } finally { cleaned.countDown() }
        } }
        val port = server.start()
        try {
            rpc(server, "message/send", message(blocking = false))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
        } finally { server.close() }
        assertTrue(cleaned.await(2, TimeUnit.SECONDS))
        assertTrue(runCatching { Socket("127.0.0.1", port).use { } }.isFailure)
        server.close()
    }

    @Test fun terminalIsNotVisibleUntilHostFinalizationFinishes() {
        val resultSent = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        withServer({ flow {
            try { emit(A2aOutput.Result("完成")); resultSent.countDown() } finally { release.await() }
        } }) { server ->
            val accepted = rpc(server, "message/send", message(blocking = false))["result"]!!.jsonObject
            assertTrue(resultSent.await(2, TimeUnit.SECONDS))
            val id = jsonObject("id" to accepted["id"]!!)
            val running = rpc(server, "tasks/get", id)["result"]!!.jsonObject
            assertEquals("working", running["status"]!!.jsonObject.string("state"))
            release.complete(Unit)
        }
    }

    @Test fun artifactOverflowFailsWithoutStoringUnboundedOutput() = withServer({ flow {
        emit(A2aOutput.Speech("x".repeat(1_048_577)))
        emit(A2aOutput.Result("incorrect success"))
    } }) { server ->
        val task = rpc(server, "message/send", message())["result"]!!.jsonObject
        assertEquals("failed", task["status"]!!.jsonObject.string("state"))
        assertTrue(task.toString().length < 2_000)
    }

    @Test fun questionOptionsCountTowardTheArtifactSizeLimit() = withServer({ flow {
        emit(A2aOutput.Question("question", "Choose", listOf("x".repeat(1_048_577)), "pending"))
        emit(A2aOutput.Result("incorrect success"))
    } }) { server ->
        val task = rpc(server, "message/send", message())["result"]!!.jsonObject
        assertEquals("failed", task["status"]!!.jsonObject.string("state"))
        assertTrue(task.toString().length < 2_000)
    }

    @Test fun filePartsAreRejectedBeforeHostExecution() = withServer({ error("Must not execute") }) { server ->
        val params = jsonObject("message" to jsonObject(
            "role" to text("user"), "messageId" to text("m-file"),
            "parts" to JsonArray(listOf(jsonObject("kind" to text("file")))),
        ))
        assertEquals(-32005, rpc(server, "message/send", params)["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
    }

    private fun withServer(handler: A2aRequestHandler, config: A2aServerConfig = A2aServerConfig(port = 0), block: (A2aServer) -> Unit) {
        A2aServer(config, handler).use { it.start(); block(it) }
    }

    private fun message(value: String = "执行文字", context: String? = null, blocking: Boolean = true): JsonObject = buildJsonObject {
        put("message", buildJsonObject {
            put("role", "user"); put("messageId", "m-1")
            put("parts", JsonArray(listOf(textPart(value))))
            context?.let { put("contextId", it) }
        })
        put("configuration", jsonObject("blocking" to JsonPrimitive(blocking)))
    }

    private fun interrupting(params: JsonObject): JsonObject = JsonObject(params + ("message" to
        JsonObject(params["message"]!!.jsonObject + ("metadata" to jsonObject("interrupt" to JsonPrimitive(true))))))

    private fun post(server: A2aServer, method: String, params: JsonObject): Response {
        val body = jsonObject("jsonrpc" to text("2.0"), "id" to text("request-1"), "method" to text(method), "params" to params)
        return client.newCall(Request.Builder().url(url(server, "/a2a"))
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()).execute()
    }

    private fun rpc(server: A2aServer, method: String, params: JsonObject): JsonObject =
        post(server, method, params).use { assertEquals(200, it.code); Json.parseToJsonElement(it.body!!.string()).jsonObject }

    private fun get(server: A2aServer, path: String): JsonObject =
        client.newCall(Request.Builder().url(url(server, path)).build()).execute().use {
            assertEquals(200, it.code); Json.parseToJsonElement(it.body!!.string()).jsonObject
        }

    private fun url(server: A2aServer, path: String) = "http://127.0.0.1:${server.localPort}$path"
}
