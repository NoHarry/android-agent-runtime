package cc.noharry.agent.acp

import java.io.BufferedReader
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One ACP connection: JSON-RPC messages, one per line, in both directions. The protocol defines stdio; a host on a
 * device serves the same messages over any stream pair it accepts (a local socket, a WebSocket bridge).
 */
interface AcpTransport : AutoCloseable {
    /** The next message, without its line end; null when the other side closed the connection. */
    suspend fun receive(): String?

    /** Writes [message] (one line of JSON, no line break inside) and flushes it. */
    suspend fun send(message: String)
}

/**
 * [AcpTransport] over a pair of streams, UTF-8, one message per line: `System.in`/`System.out` for an agent an editor
 * starts as a subprocess, or a socket's streams. Nothing else may write to [output] (on stdio, log to stderr).
 */
class StreamAcpTransport(input: InputStream, private val output: OutputStream) : AcpTransport {
    private val reader: BufferedReader = input.bufferedReader(Charsets.UTF_8)

    override suspend fun receive(): String? = withContext(Dispatchers.IO) {
        try { reader.readLine() } catch (_: java.io.IOException) { null }
    }

    override suspend fun send(message: String): Unit = withContext(Dispatchers.IO) {
        synchronized(output) {
            output.write(message.toByteArray(Charsets.UTF_8))
            output.write('\n'.code)
            output.flush()
        }
    }

    override fun close() {
        runCatching { reader.close() }
        runCatching { output.close() }
    }
}
