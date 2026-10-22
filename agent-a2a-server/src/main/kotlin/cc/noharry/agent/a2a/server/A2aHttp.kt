package cc.noharry.agent.a2a.server

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import kotlinx.serialization.json.JsonObject

internal data class A2aHttpRequest(val method: String, val path: String, val body: String)
internal class A2aHttpError(val status: Int) : RuntimeException()

internal fun readHttp(socket: Socket): A2aHttpRequest {
    socket.soTimeout = HTTP_TIMEOUT_MS
    val input = socket.getInputStream().buffered()
    val lines = readHeader(input).split("\r\n")
    val request = lines.first().split(' ')
    if (request.size != 3 || request[2] != "HTTP/1.1") throw A2aHttpError(400)
    val headers = linkedMapOf<String, String>()
    for (line in lines.drop(1).filter(String::isNotBlank)) {
        val key = line.substringBefore(':').lowercase()
        if (':' !in line || key in headers) throw A2aHttpError(400)
        headers[key] = line.substringAfter(':').trim()
    }
    if (headers["transfer-encoding"] != null) throw A2aHttpError(400)
    // Browsers must not be able to POST tool-executing requests across origins.
    if (headers["origin"] != null) throw A2aHttpError(403)
    val length = headers["content-length"]?.toIntOrNull() ?: if (request[0] == "GET") 0 else -1
    if (length < 0) throw A2aHttpError(411)
    if (length > MAX_BODY_BYTES) throw A2aHttpError(413)
    if (request[0] == "POST" && headers["content-type"]?.substringBefore(';')?.trim() != "application/json") {
        throw A2aHttpError(415)
    }
    val body = ByteArray(length)
    var offset = 0
    while (offset < length) {
        val count = input.read(body, offset, length - offset)
        if (count < 0) throw A2aHttpError(400)
        offset += count
    }
    return A2aHttpRequest(request[0], request[1], body.toString(Charsets.UTF_8))
}

private fun readHeader(input: InputStream): String {
    val bytes = ByteArrayOutputStream()
    var matched = 0
    val ending = byteArrayOf(13, 10, 13, 10)
    while (bytes.size() < MAX_HEADER_BYTES) {
        val next = input.read()
        if (next < 0) throw A2aHttpError(400)
        bytes.write(next)
        matched = if (next == ending[matched].toInt()) matched + 1 else if (next == 13) 1 else 0
        if (matched == ending.size) return bytes.toString("US-ASCII")
    }
    throw A2aHttpError(431)
}

internal fun writeJson(output: OutputStream, value: JsonObject, status: Int = 200) {
    val body = value.toString().toByteArray(Charsets.UTF_8)
    val header = "HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\n" +
        "Content-Type: application/json; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
        "Connection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n\r\n"
    output.write(header.toByteArray(Charsets.US_ASCII))
    output.write(body)
    output.flush()
}

internal fun startSse(output: OutputStream) {
    output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\n" +
        "Connection: close\r\nCache-Control: no-cache\r\nX-Accel-Buffering: no\r\n\r\n").toByteArray())
    output.flush()
}

internal fun writeSse(output: OutputStream, value: JsonObject?) {
    val frame = value?.let { "data: $it\n\n" } ?: ": keep-alive\n\n"
    output.write(frame.toByteArray(Charsets.UTF_8))
    output.flush()
}

internal const val HTTP_TIMEOUT_MS = 10_000
private const val MAX_BODY_BYTES = 65_536
private const val MAX_HEADER_BYTES = 8_192
