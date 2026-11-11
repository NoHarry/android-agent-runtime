package cc.noharry.agent.appserver.packages

import android.content.Context
import cc.noharry.agent.appserver.protocol.PackageProblem
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** An installed package: its id, the version installed, and its publisher's key (hex), null when it is unsigned. */
internal class PackageRecord(val id: String, val version: String, val publisherKey: String?)

/**
 * Where installed packages live, in the app's private files where no other app can change them, under the server's
 * own directory ([DIR]): each version's files in `<id>/<version>/`, and which version is installed in
 * `<id>/installed.json` with its publisher's key, the key every later version must be signed with. An install is
 * written beside what is installed and put in place whole ([stage], [place]); only [commit] makes it the installed one,
 * so a process that dies half way comes back with what was installed before ([tidyAll] clears what it left). Only
 * directories named as package ids, and within them only what an install leaves, are ever deleted.
 */
internal class PackageStore(
    private val root: File,
) {
    constructor(context: Context) : this(File(context.filesDir, DIR))

    /** Every installed package whose record reads; those whose record does not are [unreadable]. */
    fun records(): List<PackageRecord> = packageDirs().mapNotNull { record(it.name) }

    /** The ids of installed packages whose record (installed.json) is there but cannot be read, or names another id. */
    fun unreadable(): List<String> = packageDirs().filter { File(it, RECORD).exists() && record(it.name) == null }.map { it.name }

    fun record(id: String): PackageRecord? = readRecord(File(packageDir(id), RECORD))?.takeIf { it.id == id }

    /** Where [record]'s files are. */
    fun dir(record: PackageRecord): File = versionDir(record.id, record.version)

    /** Writes [checked]'s files (and its signature.json, the digests alone for an unsigned one) beside what is installed. */
    fun stage(checked: CheckedPackage): File {
        val staged = child(packageDir(checked.manifest.id), STAGING_PREFIX + UUID.randomUUID())
        try {
            if (!staged.mkdirs()) throw IOException("mkdirs")
            val files = checked.files - PackageFormat.SIGNATURE + (PackageFormat.SIGNATURE to checked.signatureFile())
            files.forEach { (path, bytes) ->
                checkPackagePath(path)
                val target = inside(staged, path)
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
            }
        } catch (failed: IOException) {
            staged.deleteRecursively()
            refuse(PackageProblem.STORAGE_FAILED, failed.javaClass.simpleName)
        }
        return staged
    }

    /** Puts [staged] in place as [id]'s [version]; the same version installed already moves aside until [Placed.done]. */
    fun place(id: String, version: String, staged: File): Placed {
        val target = versionDir(id, version)
        val aside = target.takeIf { it.exists() }?.let { child(packageDir(id), PREVIOUS_PREFIX + UUID.randomUUID()) }
        if (aside != null && !target.renameTo(aside)) refuse(PackageProblem.STORAGE_FAILED, "move aside")
        if (!staged.renameTo(target)) {
            staged.deleteRecursively()
            aside?.renameTo(target)
            refuse(PackageProblem.STORAGE_FAILED, "put in place")
        }
        return Placed(target, aside)
    }

    /** Makes [record] the installed version of its id and deletes every other version and leftover of it. */
    fun commit(record: PackageRecord) {
        val dir = packageDir(record.id)
        val temporary = child(dir, RECORD + TEMPORARY_SUFFIX)
        try {
            temporary.writeText(recordJson(record), Charsets.UTF_8)
            if (!temporary.renameTo(File(dir, RECORD))) throw IOException("rename")
        } catch (failed: IOException) {
            refuse(PackageProblem.STORAGE_FAILED, failed.message.orEmpty())
        }
        tidy(record.id)
    }

    /** Deletes [id]'s files, every version. */
    fun delete(id: String) {
        packageDir(id).deleteRecursively()
    }

    /**
     * At the process's start, [tidy] for every installed package; a package directory with no record that holds only
     * what an install leaves is a first install that never finished, and goes whole. Returns the ids that went (their
     * secrets go too). A directory not named as a package id, or holding anything else, is left as it is.
     */
    fun tidyAll(): List<String> = packageDirs().mapNotNull { dir ->
        runCatching {
            when {
                File(dir, RECORD).exists() -> null.also { tidy(dir.name) }
                dir.listFiles().orEmpty().all { isLeftover(it.name, installed = null) } -> dir.name.also(::delete)
                else -> null
            }
        }.getOrNull()
    }

    /**
     * What an install of [id] the process did not finish left beside what is installed: staged files, versions moved
     * aside, a record half written, versions not installed. Nothing while its record cannot be read.
     */
    fun tidy(id: String) {
        val installed = record(id)?.version ?: return
        packageDir(id).listFiles().orEmpty().filter { isLeftover(it.name, installed) }.forEach { it.deleteRecursively() }
    }

    private fun isLeftover(name: String, installed: String?): Boolean =
        name.startsWith(STAGING_PREFIX) || name.startsWith(PREVIOUS_PREFIX) || name == RECORD + TEMPORARY_SUFFIX ||
            (name != installed && SemVer.parse(name) != null)

    private fun packageDirs(): List<File> = root.listFiles().orEmpty().filter { it.isDirectory && isPackageId(it.name) }.sortedBy { it.name }

    private fun packageDir(id: String): File = child(root, id)

    private fun versionDir(id: String, version: String): File {
        if (SemVer.parse(version) == null) refuse(PackageProblem.BAD_VERSION, version)
        return child(packageDir(id), version)
    }

    companion object {
        /** Under the app's files directory. */
        const val DIR = "app_server/plugins"
        private const val RECORD = "installed.json"
        private const val TEMPORARY_SUFFIX = ".tmp"
        private const val STAGING_PREFIX = ".staging-"
        private const val PREVIOUS_PREFIX = ".previous-"
    }
}

/** An install put in place: [done] when it started, [undo] when it did not (the version moved aside comes back). */
internal class Placed(val dir: File, private val aside: File?) {
    fun done() {
        aside?.deleteRecursively()
    }

    fun undo() {
        dir.deleteRecursively()
        aside?.renameTo(dir)
    }
}

private fun readRecord(file: File): PackageRecord? {
    val root = try {
        Json.parseToJsonElement(file.readText(Charsets.UTF_8)) as? JsonObject
    } catch (_: IOException) {
        null
    } catch (_: SerializationException) {
        null
    } ?: return null
    val text = { key: String -> (root[key] as? JsonPrimitive)?.contentOrNull }
    val id = text(KEY_ID) ?: return null
    val version = text(KEY_VERSION)?.takeIf { SemVer.parse(it) != null } ?: return null
    return PackageRecord(id, version, text(KEY_PUBLISHER_KEY)?.takeIf { it.hexBytes() != null })
}

private fun recordJson(record: PackageRecord): String = buildJsonObject {
    put(KEY_ID, record.id)
    put(KEY_VERSION, record.version)
    put(KEY_PUBLISHER_KEY, record.publisherKey?.let(::JsonPrimitive) ?: JsonNull)
}.toString()

private const val KEY_ID = "id"
private const val KEY_VERSION = "version"
private const val KEY_PUBLISHER_KEY = "publisherKey"

/**
 * The values a person typed for each installed package (a key, an account), kept as the server keeps its other keys
 * ([cc.noharry.agent.appserver.mcp.McpSecrets]): as plain JSON in the app's private no-backup storage (not encrypted),
 * one file per package under the server's own directory ([DIR]), never shown again, logged or given to the model, and
 * deleted with the package. A package reads only its own.
 */
internal open class PackageSecrets(private val root: File) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, DIR))

    open fun read(id: String): Map<String, String> {
        val text = runCatching { file(id).readText(Charsets.UTF_8) }.getOrNull() ?: return emptyMap()
        val values = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyMap()
        return values.mapNotNull { (name, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { name to it } }.toMap()
    }

    open fun save(id: String, values: Map<String, String>) {
        if (values.isEmpty()) return clear(id)
        try {
            root.mkdirs()
            file(id).writeText(JsonObject(values.mapValues { JsonPrimitive(it.value) }).toString(), Charsets.UTF_8)
        } catch (failed: IOException) {
            refuse(PackageProblem.STORAGE_FAILED, failed.javaClass.simpleName)
        }
    }

    fun clear(id: String) {
        file(id).delete()
    }

    private fun file(id: String): File = child(root, "$id.json")

    companion object {
        /** Under the app's no-backup files directory. */
        const val DIR = "app_server/plugin-secrets"
    }
}

/** [name] directly inside [parent], refused if it would be anywhere else. */
private fun child(parent: File, name: String): File {
    val file = File(parent, name)
    if ('/' in name || name == "." || name == ".." || file.canonicalFile.parentFile != parent.canonicalFile) refuse(PackageProblem.BAD_PATH, name)
    return file
}

/** [path] (a checked package path) inside [dir], refused if it would leave it. */
private fun inside(dir: File, path: String): File {
    val file = File(dir, path)
    if (!file.canonicalPath.startsWith(dir.canonicalPath + File.separator)) refuse(PackageProblem.BAD_PATH, path)
    return file
}
