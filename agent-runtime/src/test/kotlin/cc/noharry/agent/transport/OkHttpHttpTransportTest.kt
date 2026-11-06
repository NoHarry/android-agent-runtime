package cc.noharry.agent.transport

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A server that takes the request and never answers: a caller's timeout must end the call, not OkHttp's. */
class OkHttpHttpTransportTest {
    private lateinit var server: ServerSocket
    private val held = CopyOnWriteArrayList<Socket>()

    @Before
    fun listen() {
        server = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) runCatching { held += server.accept() }
        }
    }

    @After
    fun close() {
        held.forEach { runCatching { it.close() } }
        server.close()
    }

    private val request get() = HttpRequest(url = "http://127.0.0.1:${server.localPort}/", method = "POST", body = "{}".toByteArray())

    @Test
    fun aCancelledExecuteEndsAtOnce() = runBlocking {
        val started = System.nanoTime()

        val answer = withTimeoutOrNull(TIMEOUT_MS) { OkHttpHttpTransport().execute(request) }

        assertNull(answer)
        assertTrue(elapsedMs(started) < ENDS_WITHIN_MS)
    }

    @Test
    fun aCancelledStreamEndsAtOnce() = runBlocking {
        val started = System.nanoTime()

        val events = withTimeoutOrNull(TIMEOUT_MS) { OkHttpHttpTransport().stream(request).toList() }

        assertNull(events)
        assertTrue(elapsedMs(started) < ENDS_WITHIN_MS)
    }

    private fun elapsedMs(started: Long) = (System.nanoTime() - started) / 1_000_000
}

private const val TIMEOUT_MS = 300L
private const val ENDS_WITHIN_MS = 3_000L
