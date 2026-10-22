package cc.noharry.agent.a2a.server

import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class A2aTaskStore(
    private val config: A2aServerConfig,
    private val scope: CoroutineScope,
    private val handler: A2aRequestHandler,
) {
    private val tasks = linkedMapOf<String, A2aTask>()

    @Synchronized
    fun start(input: String, contextId: String?, streaming: Boolean, metadata: Map<String, String> = emptyMap()): A2aTask {
        if (contextId != null && tasks.values.none { it.request.contextId == contextId }) {
            invalidParams("Unknown or expired contextId")
        }
        val active = tasks.values.filter { !it.done.isCompleted }
        if (active.size >= config.maxActiveTasks || active.any { it.request.contextId == contextId }) {
            throw A2aRpcError(-32000, "Server or context is busy")
        }
        while (tasks.size >= config.maxRetainedTasks) {
            val oldest = tasks.entries.firstOrNull { it.value.done.isCompleted } ?: break
            tasks.remove(oldest.key)
        }
        val request = A2aRequest(UUID.randomUUID().toString(), contextId ?: UUID.randomUUID().toString(), input, metadata)
        val task = A2aTask(request, streaming)
        tasks[request.taskId] = task
        task.job = scope.launch(start = CoroutineStart.LAZY) { execute(task) }
        task.job.invokeOnCompletion { if (!task.done.isCompleted) task.failDelivery() }
        task.job.start()
        return task
    }

    /**
     * Starts [input] in place of [contextId]'s running task: that task is canceled first and ends `canceled` with
     * [INTERRUPTED_TEXT] after its host cleanup, so the two never overlap. With nothing running it is a plain [start].
     */
    suspend fun interrupt(input: String, contextId: String?, streaming: Boolean, metadata: Map<String, String>): A2aTask {
        val running = synchronized(this) {
            tasks.values.filter { contextId != null && it.request.contextId == contextId && !it.done.isCompleted }
        }
        running.forEach { task ->
            task.interrupted = true
            task.job.cancel()
            if (withTimeoutOrNull(CANCEL_TIMEOUT_MS) { task.done.await(); true } != true) {
                throw A2aRpcError(-32002, "Task cancellation is still pending")
            }
        }
        return start(input, contextId, streaming, metadata)
    }

    @Synchronized
    fun get(id: String): A2aTask = tasks[id] ?: throw A2aRpcError(-32001, "Task not found")

    /** Control input does not admit another execution or acquire another session owner. */
    suspend fun postInput(taskId: String, contextId: String?, messageId: String, input: String, questionId: String?): JsonObject {
        val task = get(taskId)
        if (contextId != task.request.contextId) invalidParams("Task contextId mismatch")
        if (task.terminal || task.done.isCompleted) throw A2aRpcError(-32002, "Task is not active")
        val receipt = try {
            handler.postInput(task.request, messageId, input, questionId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw A2aRpcError(-32002, "Input could not be recorded")
        }
        if (!receipt.accepted) throw A2aRpcError(-32002, "Input rejected: ${receipt.code}")
        val metadata = buildJsonObject {
            put("inputReceipt", buildJsonObject {
                put("messageId", messageId)
                put("state", if (receipt.duplicate) "duplicate" else "queued")
                questionId?.let { put("questionId", it) }
            })
        }
        return JsonObject(task.snapshot() + ("metadata" to metadata))
    }

    suspend fun cancel(id: String): A2aTask {
        val task = get(id)
        if (task.terminal || task.done.isCompleted) throw A2aRpcError(-32002, "Task is not cancelable")
        task.job.cancel()
        if (withTimeoutOrNull(CANCEL_TIMEOUT_MS) { task.done.await(); true } != true) {
            throw A2aRpcError(-32002, "Task cancellation is still pending")
        }
        return task
    }

    private suspend fun execute(task: A2aTask) {
        val result = try {
            withTimeout(config.taskTimeoutMillis) { collectResult(task) }
        } catch (_: TimeoutCancellationException) {
            A2aOutput.Result("任务超时或输出接收过慢", A2aOutput.State.FAILED)
        } catch (_: CancellationException) {
            A2aOutput.Result(if (task.interrupted) INTERRUPTED_TEXT else "任务已取消", A2aOutput.State.CANCELED)
        } catch (_: Exception) {
            A2aOutput.Result("执行失败，请检查 sample 配置和本地日志", A2aOutput.State.FAILED)
        }
        withContext(NonCancellable) {
            try { task.finish(result) } catch (_: Exception) { task.failDelivery() }
        }
    }

    private suspend fun collectResult(task: A2aTask): A2aOutput.Result {
        task.publish(A2aOutput.Status("开始执行", "started"))
        var result: A2aOutput.Result? = null
        handler.execute(task.request).collect { output ->
            check(result == null) { "Output after terminal result" }
            if (output is A2aOutput.Result) result = output else task.publish(output)
        }
        return result ?: A2aOutput.Result("运行结束但没有终态结果", A2aOutput.State.FAILED)
    }

    private companion object { const val CANCEL_TIMEOUT_MS = 10_000L }
}

/** The terminal status text of a task canceled by an interrupting message. */
internal const val INTERRUPTED_TEXT = "已被新任务打断"
