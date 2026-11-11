package cc.noharry.agent.appserver.packages

import android.content.Context
import android.net.Uri
import android.util.Base64
import cc.noharry.agent.appserver.http.appServerHttpClient
import cc.noharry.agent.appserver.protocol.PackageProblem
import cc.noharry.agent.appserver.protocol.PluginInstallParams
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/**
 * The bytes of the package [params] names, at most [PackageFormat.MAX_BYTES]: downloaded over HTTPS (never following
 * a redirect to plain HTTP), read from a `content://` address or a file in the app's own external files, or decoded
 * from base64. What a refusal says never repeats the address, which may carry a token.
 */
internal suspend fun fetchPackage(context: Context, params: PluginInstallParams): ByteArray = withContext(Dispatchers.IO) {
    val given = listOfNotNull(params.url, params.file, params.data)
    if (given.size != 1) refuse(PackageProblem.BAD_SOURCE, "one of url, file and data")
    val (url, file, data) = Triple(params.url, params.file, params.data)
    when {
        url != null -> download(context, url)
        file != null -> readFile(context, file)
        else -> decode(checkNotNull(data))
    }
}

private fun download(context: Context, address: String): ByteArray {
    val url = address.toHttpUrlOrNull() ?: refuse(PackageProblem.BAD_SOURCE, "not an address")
    if (!url.isHttps) refuse(PackageProblem.BAD_SOURCE, "not HTTPS")
    val client = appServerHttpClient(context).followSslRedirects(false).callTimeout(DOWNLOAD_SECONDS, TimeUnit.SECONDS).build()
    return try {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful || !response.request.url.isHttps) refuse(PackageProblem.DOWNLOAD_FAILED, "HTTP ${response.code}")
            val body = response.body ?: refuse(PackageProblem.DOWNLOAD_FAILED, "no body")
            if (body.contentLength() > PackageFormat.MAX_BYTES) refuse(PackageProblem.TOO_LARGE, "${body.contentLength()} bytes")
            body.byteStream().use(::readCapped)
        }
    } catch (failed: IOException) {
        refuse(PackageProblem.DOWNLOAD_FAILED, failed.javaClass.simpleName)
    }
}

private fun readFile(context: Context, address: String): ByteArray {
    val uri = Uri.parse(address)
    return try {
        when (uri.scheme) {
            "content" -> context.contentResolver.openInputStream(uri)?.use(::readCapped) ?: refuse(PackageProblem.BAD_SOURCE, "unreadable")
            "file" -> appFile(context, uri.path.orEmpty()).inputStream().use(::readCapped)
            else -> refuse(PackageProblem.BAD_SOURCE, "not content:// or file://")
        }
    } catch (failed: IOException) {
        refuse(PackageProblem.BAD_SOURCE, failed.javaClass.simpleName)
    } catch (failed: SecurityException) {
        refuse(PackageProblem.BAD_SOURCE, failed.javaClass.simpleName)
    }
}

/** A file only from the app's own external files (where `adb push` can put one), never its private ones. */
private fun appFile(context: Context, path: String): File {
    val root = context.getExternalFilesDir(null)?.canonicalFile ?: refuse(PackageProblem.BAD_SOURCE, "no external files")
    val file = File(path).canonicalFile
    if (!file.path.startsWith(root.path + File.separator) || !file.isFile) refuse(PackageProblem.BAD_SOURCE, "not in the app's external files")
    return file
}

private fun decode(data: String): ByteArray {
    if (data.length > MAX_BASE64_CHARS) refuse(PackageProblem.TOO_LARGE, "${data.length} characters")
    return try {
        Base64.decode(data, Base64.DEFAULT)
    } catch (_: IllegalArgumentException) {
        refuse(PackageProblem.BAD_SOURCE, "not base64")
    }
}

private fun readCapped(input: InputStream): ByteArray {
    val bytes = input.readNBytesCompat(PackageFormat.MAX_BYTES + 1)
    if (bytes.size > PackageFormat.MAX_BYTES) refuse(PackageProblem.TOO_LARGE, "over ${PackageFormat.MAX_BYTES} bytes")
    return bytes
}

/** Up to [limit] bytes of the stream (InputStream.readNBytes is API 33). */
private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(BUFFER_BYTES)
    while (out.size() < limit) {
        val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}

private const val DOWNLOAD_SECONDS = 60L
private const val BUFFER_BYTES = 8192

/** Base64 of the largest package, with room for line breaks. */
private const val MAX_BASE64_CHARS = PackageFormat.MAX_BYTES / 3 * 4 + 4096
