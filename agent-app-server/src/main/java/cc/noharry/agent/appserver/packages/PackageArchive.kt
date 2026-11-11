package cc.noharry.agent.appserver.packages

import cc.noharry.agent.appserver.protocol.PackageProblem
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * A package's files read from its zip, in memory and nothing written: each path checked ([checkPackagePath]), at most
 * [PackageFormat.MAX_FILES] files and [PackageFormat.MAX_BYTES] in all however the zip says it is packed (sizes are
 * counted as the bytes come), then each file's kind checked ([checkPackageFiles]).
 */
internal fun readPackageArchive(bytes: ByteArray): Map<String, ByteArray> {
    if (bytes.size > PackageFormat.MAX_BYTES) refuse(PackageProblem.TOO_LARGE, "${bytes.size} bytes")
    val files = linkedMapOf<String, ByteArray>()
    val counter = SizeCounter()
    try {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entries = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entries > PackageFormat.MAX_ENTRIES) refuse(PackageProblem.TOO_MANY_FILES, "$entries entries")
                if (entry.isDirectory) continue
                addFile(files, entry.name, counter.read(zip))
            }
        }
    } catch (broken: ZipException) {
        refuse(PackageProblem.NOT_A_PACKAGE, broken.message.orEmpty())
    } catch (broken: IOException) {
        refuse(PackageProblem.NOT_A_PACKAGE, broken.message.orEmpty())
    } catch (badName: IllegalArgumentException) {
        // A file name that is not UTF-8.
        refuse(PackageProblem.NOT_A_PACKAGE, badName.message.orEmpty())
    }
    if (files.isEmpty()) refuse(PackageProblem.NOT_A_PACKAGE, "no files")
    return checkPackageFiles(files)
}

/**
 * An installed package's files read again from [dir], under the same rules as its zip: only plain files inside it,
 * none through a link out of it.
 */
internal fun readPackageDirectory(dir: File): Map<String, ByteArray> {
    val root = dir.canonicalFile
    val files = linkedMapOf<String, ByteArray>()
    val counter = SizeCounter()
    val inside = { file: File -> file.canonicalFile.path.let { it == root.path || it.startsWith(root.path + File.separator) } }
    root.walkTopDown().onEnter { inside(it) || refuse(PackageProblem.BAD_PATH, it.name) }.filter { it != root }.forEach { file ->
        if (!inside(file)) refuse(PackageProblem.BAD_PATH, file.name)
        if (file.isDirectory) return@forEach
        if (!file.isFile) refuse(PackageProblem.BAD_PATH, file.name)
        val path = file.relativeTo(root).invariantSeparatorsPath
        addFile(files, path, file.inputStream().use(counter::read))
    }
    return checkPackageFiles(files)
}

/** [path] as a package may name a file: relative, plain characters, no hidden or empty parts, not too deep or long. */
internal fun checkPackagePath(path: String) {
    val parts = path.split('/')
    val ok = path.length <= PackageFormat.MAX_PATH_CHARS && parts.size <= PackageFormat.MAX_DEPTH && parts.all(PATH_PART::matches)
    if (!ok) refuse(PackageProblem.BAD_PATH, path.take(PackageFormat.MAX_PATH_CHARS))
}

/**
 * The files' kinds: no compiled code (reserved, refused); markdown and json in UTF-8 and png that is png; under
 * `scripts/` JavaScript in UTF-8 only, each within [PackageFormat.MAX_SCRIPT_BYTES]. Whether a tool names each
 * script is the manifest's check ([checkPackageContents]).
 */
internal fun checkPackageFiles(files: Map<String, ByteArray>): Map<String, ByteArray> {
    files.keys.firstOrNull { it.startsWith(PackageFormat.CODE_DIR, ignoreCase = true) }?.let { refuse(PackageProblem.CARRIES_CODE, it) }
    files.forEach { (path, bytes) ->
        val extension = path.substringAfterLast('/').substringAfterLast('.', "")
        val script = path.startsWith(PackageFormat.SCRIPTS_DIR)
        when {
            script != (extension == PackageFormat.SCRIPT_EXTENSION) -> refuse(PackageProblem.FILE_TYPE, path)
            script -> {
                if (bytes.size > PackageFormat.MAX_SCRIPT_BYTES) refuse(PackageProblem.TOO_LARGE, path)
                if (!isUtf8(bytes)) refuse(PackageProblem.NOT_UTF8, path)
            }
            extension in PackageFormat.TEXT_EXTENSIONS -> if (!isUtf8(bytes)) refuse(PackageProblem.NOT_UTF8, path)
            extension == PackageFormat.PNG_EXTENSION -> if (!bytes.startsWith(PNG_SIGNATURE)) refuse(PackageProblem.FILE_TYPE, path)
            else -> refuse(PackageProblem.FILE_TYPE, path)
        }
    }
    return files
}

/** [text] decoded as UTF-8, refusing what is not; a byte-order mark is dropped. */
internal fun packageText(path: String, bytes: ByteArray): String {
    if (!isUtf8(bytes)) refuse(PackageProblem.NOT_UTF8, path)
    return bytes.toString(Charsets.UTF_8).removePrefix(BYTE_ORDER_MARK)
}

private fun addFile(files: MutableMap<String, ByteArray>, path: String, bytes: ByteArray) {
    checkPackagePath(path)
    if (path in files || files.keys.any { it.equals(path, ignoreCase = true) }) refuse(PackageProblem.DUPLICATE_FILE, path)
    files[path] = bytes
    if (files.size > PackageFormat.MAX_FILES) refuse(PackageProblem.TOO_MANY_FILES, "${files.size} files")
}

/** Reads streams while the bytes read so far stay within the package's limit. */
private class SizeCounter {
    private var total = 0L

    fun read(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return out.toByteArray()
            total += read
            if (total > PackageFormat.MAX_BYTES) refuse(PackageProblem.TOO_LARGE, "over ${PackageFormat.MAX_BYTES} bytes unpacked")
            out.write(buffer, 0, read)
        }
    }
}

private fun isUtf8(bytes: ByteArray): Boolean = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
    true
} catch (_: CharacterCodingException) {
    false
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

/** One part of a path: letters, digits, `.`, `_` and `-`, not starting with a dot (no `.`, `..` or hidden files). */
private val PATH_PART = Regex("""[A-Za-z0-9_-][A-Za-z0-9._-]{0,99}""")
private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
private const val BYTE_ORDER_MARK = "\uFEFF"
private const val BUFFER_BYTES = 8192
