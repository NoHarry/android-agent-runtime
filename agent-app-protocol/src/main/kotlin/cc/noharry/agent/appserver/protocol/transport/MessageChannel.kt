package cc.noharry.agent.appserver.protocol.transport

import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcCodec
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcException
import cc.noharry.agent.appserver.protocol.jsonrpc.JsonRpcMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Writer

/** A two-way pipe of [JsonRpcMessage]s; [incoming] closes when the other end goes away. */
interface MessageChannel {
    val incoming: ReceiveChannel<JsonRpcMessage>

    suspend fun send(message: JsonRpcMessage)

    fun close()
}

/**
 * Two ends of one connection inside the process: what one sends the other receives, as is, with no encoding. The
 * messages already hold their params as JSON trees, so the two sides share nothing they could change under each other.
 */
object InProcessChannels {
    fun pair(): Pair<MessageChannel, MessageChannel> {
        val toSecond = Channel<JsonRpcMessage>(Channel.UNLIMITED)
        val toFirst = Channel<JsonRpcMessage>(Channel.UNLIMITED)
        return End(toFirst, toSecond) to End(toSecond, toFirst)
    }

    private class End(
        override val incoming: Channel<JsonRpcMessage>,
        private val outgoing: Channel<JsonRpcMessage>,
    ) : MessageChannel {
        override suspend fun send(message: JsonRpcMessage) = outgoing.send(message)

        override fun close() {
            outgoing.close()
            incoming.close()
        }
    }
}

/**
 * A connection over a byte stream (a socket), one JSON message per line, as `codex app-server` speaks over stdio. A
 * line that is not a message is passed to [onBadLine] and skipped; the reader stops when the stream ends.
 */
class LineMessageChannel(
    input: InputStream,
    output: OutputStream,
    scope: CoroutineScope,
    private val onBadLine: (String, JsonRpcException) -> Unit = { _, _ -> },
    /** The stream failed under the reader; it is then treated as ended. */
    private val onReadError: (IOException) -> Unit = {},
    private val onClosed: () -> Unit = {},
) : MessageChannel {
    private val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
    private val writer: Writer = OutputStreamWriter(output, Charsets.UTF_8)
    private val writeLock = Mutex()
    private val received = Channel<JsonRpcMessage>(Channel.UNLIMITED)
    override val incoming: ReceiveChannel<JsonRpcMessage> = received
    private val readLoop: Job = scope.launch(Dispatchers.IO) { readLines() }

    /** Writes [message] as one line; a stream that closed reads as a closed channel. */
    override suspend fun send(message: JsonRpcMessage) {
        val line = JsonRpcCodec.encode(message)
        writeLock.withLock {
            withContext(Dispatchers.IO) {
                try {
                    writer.write(line)
                    writer.write("\n")
                    writer.flush()
                } catch (closed: IOException) {
                    throw ClosedSendChannelException(closed.message)
                }
            }
        }
    }

    override fun close() {
        readLoop.cancel()
        received.close()
        runCatching { reader.close() }
        runCatching { writer.close() }
        onClosed()
    }

    private fun readLines() {
        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                try {
                    received.trySend(JsonRpcCodec.decode(line))
                } catch (bad: JsonRpcException) {
                    onBadLine(line, bad)
                }
            }
        } catch (failed: IOException) {
            onReadError(failed)
        } finally {
            received.close()
        }
    }
}
