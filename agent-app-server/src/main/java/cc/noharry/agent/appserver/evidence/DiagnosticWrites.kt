package cc.noharry.agent.appserver.evidence
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/** Diagnostic files are asynchronous projections, independent of the lossless Kernel EventLog. */
internal object DiagnosticWrites {
    private const val QUEUE_CAPACITY = 256
    private data class Write(val file: File?, val text: String?, val name: String?, val ack: CompletableDeferred<Unit>?)
    private val queue = Channel<Write>(QUEUE_CAPACITY)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val failures = mutableMapOf<String, Exception>()

    init {
        scope.launch {
            for (write in queue) {
                val file = write.file
                if (file != null) {
                    try {
                        file.parentFile?.mkdirs()
                        file.appendText(requireNotNull(write.text), Charsets.UTF_8)
                    } catch (error: Exception) { failures[file.name] = error }
                } else {
                    val failure = failures.remove(write.name)
                    if (failure == null) write.ack?.complete(Unit) else write.ack?.completeExceptionally(failure)
                }
            }
        }
    }

    fun publish(file: File, text: String) {
        check(queue.trySend(Write(file, text, null, null)).isSuccess) { "Diagnostic subscriber queue overflow" }
    }

    suspend fun flush(name: String) {
        val ack = CompletableDeferred<Unit>()
        queue.send(Write(null, null, name, ack))
        ack.await()
    }

}
