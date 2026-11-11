package cc.noharry.agent.appserver.console
import cc.noharry.agent.appserver.AppServerPluginApi
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Writes [text] to [file] whole, through a temp file synced to disk before the rename: power cut at ACC off leaves the
 * old file or the new one, never an empty one. Throws [IOException] when it cannot (a full disk), so the caller can
 * say so instead of crashing. Plans and the memory plugin keep their books this way.
 */
@AppServerPluginApi
fun saveFileAtomically(file: File, text: String) {
    val dir = file.parentFile ?: throw IOException("no folder for ${file.name}")
    if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not make ${dir.name}")
    val temp = File(dir, "${file.name}.tmp")
    FileOutputStream(temp).use { out ->
        out.write(text.toByteArray(Charsets.UTF_8))
        out.fd.sync()
    }
    if (!temp.renameTo(file)) {
        file.delete()
        if (!temp.renameTo(file)) throw IOException("could not save ${file.name}")
    }
}

/** Moves a [file] that could not be read to `<name>.corrupt`, so the next save does not write over it. */
@AppServerPluginApi
fun setAsideUnreadable(file: File) {
    file.renameTo(File(file.parentFile, "${file.name}.corrupt"))
}

/** [text] on one line: a line break or other control character becomes a space (the runtime drops facts with them). */
@AppServerPluginApi
fun singleLine(text: String): String = text.replace(CONTROL_CHARS, " ").trim()

private val CONTROL_CHARS = Regex("[\\u0000-\\u001F\\u007F]+")
