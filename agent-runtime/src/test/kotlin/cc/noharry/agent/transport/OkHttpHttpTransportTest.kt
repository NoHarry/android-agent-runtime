package cc.noharry.agent.transport

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
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

    @Test
    fun aHostsProxyCarriesTheCall() = runBlocking {
        val proxy = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
        val requestLine = CompletableFuture<String>()
        thread(isDaemon = true) {
            proxy.accept().use { socket ->
                val reader = socket.getInputStream().bufferedReader()
                requestLine.complete(reader.readLine())
                // The whole request is read before answering: a socket closed with bytes unread may reset the answer.
                val length = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }
                    .firstNotNullOfOrNull { it.substringAfter("Content-Length:", "").trim().toIntOrNull() } ?: 0
                repeat(length) { reader.read() }
                socket.getOutputStream().write("HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n".toByteArray())
                socket.getOutputStream().flush()
            }
        }
        try {
            val transport = OkHttpHttpTransport(proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(InetAddress.getLoopbackAddress(), proxy.localPort)))

            val answer = transport.execute(HttpRequest(url = "http://model.invalid/v1", method = "POST", body = "{}".toByteArray()))

            assertEquals(204, answer.statusCode)
            assertEquals("POST http://model.invalid/v1 HTTP/1.1", requestLine.get(1, TimeUnit.SECONDS))
        } finally {
            proxy.close()
        }
    }

    @Test
    fun aHostsReadTimeoutEndsASilentCall() = runBlocking<Unit> {
        val started = System.nanoTime()

        assertThrows(IOException::class.java) { runBlocking { OkHttpHttpTransport(readTimeoutMillis = 200).execute(request) } }

        assertTrue(elapsedMs(started) < ENDS_WITHIN_MS)
        assertThrows(IllegalArgumentException::class.java) { OkHttpHttpTransport(connectTimeoutMillis = 0) }
    }

    private fun elapsedMs(started: Long) = (System.nanoTime() - started) / 1_000_000
}

private const val TIMEOUT_MS = 300L
private const val ENDS_WITHIN_MS = 3_000L
