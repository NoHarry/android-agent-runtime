package cc.noharry.agent.appserver.packages

import android.util.Log
import cc.noharry.agent.appserver.protocol.PackageChange
import cc.noharry.agent.appserver.protocol.PackageProblem
import cc.noharry.agent.appserver.protocol.PackageView
import cc.noharry.agent.appserver.protocol.PluginConfirmParams
import cc.noharry.agent.appserver.protocol.PluginInstallResult
import cc.noharry.agent.appserver.protocol.PluginView
import cc.noharry.agent.appserver.server.AppServerPlugin
import cc.noharry.agent.appserver.server.PluginHost
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Installing packages while the server runs, in two steps: [check] reads a downloaded package whole and says what it
 * is and asks for, nothing written; [confirm] unpacks it, keeps its secrets, registers it with the plugin kernel and
 * starts it. An update must come from the key the first install recorded; the version it replaces stays on disk until
 * the new one has started and comes back if it does not. [remove] stops one and deletes its files and secrets. The
 * kernel is used on [main], files on [io]; one install or removal at a time.
 */
internal class PackageInstaller(
    private val host: PluginHost,
    private val store: PackageStore,
    private val secrets: PackageSecrets,
    private val trust: PackageTrust,
    private val main: CoroutineContext = Dispatchers.Main.immediate,
    private val io: CoroutineContext = Dispatchers.IO,
    /** A removed package's skills: their switches go with it. */
    private val forgetSkills: (List<String>) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    /** The host's most seconds for one call of a package's script tool, whatever the tool asks. */
    private val maxScriptSeconds: Int = DEFAULT_MAX_SCRIPT_SECONDS,
    /** Whether the app includes the sandbox scripts run in (agent-exec-js); a package with script tools needs it. */
    private val scriptsAvailable: Boolean = JsSandbox.available,
    /**
     * The names the host's other tool servers go by now (its tool providers, the configured MCP servers, the running
     * plugins', the server's own), on [main]: a package's tools may not go by one of them.
     */
    private val otherToolServers: () -> Set<String> = { emptySet() },
) {
    private val pending = LinkedHashMap<String, Pending>()
    private val changes = Mutex()

    /**
     * The tool server names the installed packages' tools go by ([packageToolServers]), as of the last install or
     * removal: an MCP server the person adds takes none of them.
     */
    @Volatile
    var toolServerNames: Set<String> = emptySet()
        private set

    /**
     * At the process's start, on the main thread: every installed package to the kernel; one that fails, or whose record
     * cannot be read, shows as damaged. A first install that never finished goes, with its secrets.
     */
    fun loadInstalled() {
        store.tidyAll().forEach(secrets::clear)
        store.unreadable().forEach { id -> addDamaged(UnreadablePackage(id)) }
        store.records().forEach { record ->
            val plugin = PackagePlugin(record, store.dir(record), trust, secrets, maxScriptSeconds)
            try {
                // Checked once here for what clients show even while it is off; starting checks again.
                runCatching { plugin.verify() }
                host.add(plugin)
            } catch (failed: Exception) {
                logNotStarted(record.id, failed)
            }
        }
        noteToolServerNames()
    }

    /** On [main]: what [toolServerNames] says, from the packages registered now. */
    private fun noteToolServerNames() {
        toolServerNames = host.plugins().filterIsInstance<PackagePlugin>().flatMapTo(hashSetOf()) { it.manifest?.let(::packageToolServers).orEmpty() }
    }

    private fun addDamaged(plugin: UnreadablePackage) = try {
        host.add(plugin)
    } catch (failed: Exception) {
        logNotStarted(plugin.id, failed)
    }

    private fun logNotStarted(id: String, failed: Exception) =
        Log.w(LOG_TAG, "package $id did not start: ${(failed as? PackageException)?.problem ?: failed.javaClass.simpleName}")

    /** Step one: [bytes] checked whole, and what it is and asks for; nothing runs and nothing is written. */
    suspend fun check(bytes: ByteArray): PluginInstallResult {
        val checked = withContext(io) { checkPackage(bytes) }
        val manifest = checked.manifest
        if (!checked.signed && !trust.allowUnsigned) refuse(PackageProblem.UNSIGNED_NOT_ALLOWED)
        if (manifest.tools.isNotEmpty() && !scriptsAvailable) refuse(PackageProblem.SCRIPTS_UNAVAILABLE, JsSandbox.MISSING)
        withContext(main) { checkToolServers(manifest) }
        val installed = withContext(main) { host.plugin(manifest.id) }
        if (installed != null && installed !is InstalledPackage) refuse(PackageProblem.ID_TAKEN, manifest.id)
        if (installed is UnreadablePackage) throw UnreadablePackage.damage()
        val record = withContext(io) { store.record(manifest.id) }
        record?.let { checkUpdate(it, checked) }
        val trusted = trust.trusts(checked.publisherKey)
        val kept = withContext(io) { secrets.read(manifest.id).keys }
        val newRecord = PackageRecord(manifest.id, manifest.version, checked.publisherKey)
        val view = packageView(newRecord, manifest, checked.ruleLines().size, trusted, kept, damage = null, maxScriptSeconds)
        val change = record?.let { changeOf(it.version, (installed as? PackagePlugin)?.manifest, view) }
        val installId = UUID.randomUUID().toString()
        synchronized(pending) {
            expire()
            while (pending.size >= MAX_PENDING) pending.remove(pending.keys.first())
            pending[installId] = Pending(checked, !trusted, record?.version, now() + PENDING_MILLIS)
        }
        return PluginInstallResult(installId, view, needsAcknowledgement = !trusted, update = change, expiresInSeconds = PENDING_SECONDS)
    }

    /** Step two: installs what [params] names, with its secrets; the views of the plugins after it. */
    suspend fun confirm(params: PluginConfirmParams): List<PluginView> = changes.withLock {
        val waiting = synchronized(pending) {
            expire()
            pending[params.installId]
        } ?: refuse(PackageProblem.INSTALL_EXPIRED)
        if (waiting.needsAcknowledgement && !params.acknowledgeUntrusted) refuse(PackageProblem.NEEDS_ACKNOWLEDGEMENT)
        val id = waiting.checked.manifest.id
        val kept = withContext(io) { secrets.read(id) }
        val values = secretValues(waiting.checked.manifest, params.secrets, kept)
        synchronized(pending) { pending.remove(params.installId) } ?: refuse(PackageProblem.INSTALL_EXPIRED)
        val record = withContext(io) { store.record(id) }
        if (record?.version != waiting.fromVersion) refuse(PackageProblem.INSTALL_EXPIRED, "what is installed changed")
        record?.let { checkUpdate(it, waiting.checked) }
        // Another package, or a server the person added, may have taken a name since the check.
        withContext(main) { checkToolServers(waiting.checked.manifest) }
        install(waiting.checked, values, kept)
        withContext(main) {
            noteToolServerNames()
            host.views()
        }
    }

    /** Stops the package [id] and deletes its files and secrets; a plugin built into the host is refused. */
    suspend fun remove(id: String): List<PluginView> = changes.withLock {
        val plugin = withContext(main) { host.plugin(id) } as? InstalledPackage ?: refuse(PackageProblem.NOT_A_PACKAGE_PLUGIN, id)
        val skills = (plugin as? PackagePlugin)?.manifest?.skills?.map { scopedSkillName(id, it.name) }.orEmpty()
        withContext(main) { host.remove(id) }
        withContext(io) {
            store.delete(id)
            secrets.clear(id)
            forgetSkills(skills)
        }
        withContext(main) {
            noteToolServerNames()
            host.views()
        }
    }

    private suspend fun install(checked: CheckedPackage, values: Map<String, String>, kept: Map<String, String>) {
        val manifest = checked.manifest
        val record = PackageRecord(manifest.id, manifest.version, checked.publisherKey)
        val old = withContext(main) { host.plugin(manifest.id) }
        val placed = withContext(io) { store.place(manifest.id, manifest.version, store.stage(checked)) }
        try {
            withContext(io) { secrets.save(manifest.id, values) }
            withContext(main) { start(PackagePlugin(record, placed.dir, trust, secrets, maxScriptSeconds), old) }
        } catch (failed: Exception) {
            withContext(io) {
                placed.undo()
                // What was kept before (none for a first install).
                secrets.save(manifest.id, kept)
            }
            withContext(main) { restore(manifest.id, old) }
            refuse(PackageProblem.START_FAILED, (failed as? PackageException)?.message ?: failed.javaClass.simpleName)
        }
        withContext(io) {
            store.commit(record)
            placed.done()
        }
    }

    /** Checks [plugin]'s files as unpacked, then registers it in place of [old] (or anew) and starts it. */
    private fun start(plugin: PackagePlugin, old: AppServerPlugin?) {
        plugin.verify()
        if (old == null) host.add(plugin) else host.replace(plugin)
    }

    /** What was there before an install that failed: the old version again, or nothing. */
    private fun restore(id: String, old: AppServerPlugin?) {
        val current = host.plugin(id) ?: return
        try {
            if (old == null) host.remove(id) else if (current !== old) host.replace(old)
        } catch (failed: Exception) {
            Log.w(LOG_TAG, "package $id: the version before did not start again: ${failed.javaClass.simpleName}")
        }
    }

    /**
     * Refuses [manifest] when a name its tools would go by ([packageToolServers]) is another installed package's or one
     * of [otherToolServers]: a run would give its tools another name, one that changes with what else is installed.
     */
    private fun checkToolServers(manifest: PackageManifest) {
        val names = packageToolServers(manifest)
        if (names.isEmpty()) return
        host.plugins().filterIsInstance<PackagePlugin>().filter { it.id != manifest.id }.forEach { other ->
            val shared = other.manifest?.let(::packageToolServers).orEmpty().intersect(names)
            shared.firstOrNull()?.let { refuse(PackageProblem.TOOL_SERVER_TAKEN, "$it (${other.id})") }
        }
        names.intersect(otherToolServers()).firstOrNull()?.let { refuse(PackageProblem.TOOL_SERVER_TAKEN, it) }
    }

    /** An update: signed by the key the first install recorded, and not older than what is installed. */
    private fun checkUpdate(record: PackageRecord, checked: CheckedPackage) {
        if (record.publisherKey != checked.publisherKey) refuse(PackageProblem.PUBLISHER_KEY_CHANGED, record.publisherKey?.let(::keyFingerprint) ?: "unsigned")
        if (checkNotNull(SemVer.parse(checked.manifest.version)) < checkNotNull(SemVer.parse(record.version))) refuse(PackageProblem.OLDER_VERSION, record.version)
    }

    private fun expire() {
        val time = now()
        pending.entries.removeAll { it.value.expiresAt <= time }
    }

    private class Pending(val checked: CheckedPackage, val needsAcknowledgement: Boolean, val fromVersion: String?, val expiresAt: Long)

    private companion object {
        const val LOG_TAG = "AppServerPackages"
        const val PENDING_SECONDS = 15 * 60
        const val PENDING_MILLIS = PENDING_SECONDS * 1000L

        /** Each holds a package in memory, up to 5 MB. */
        const val MAX_PENDING = 3
    }
}

/**
 * The values [manifest]'s secrets take: those [given] (named by it, not empty, not too long, no line breaks), else those
 * [kept] from the version installed before; every one it asks for is needed. A refusal names a secret, never a value.
 */
internal fun secretValues(manifest: PackageManifest, given: Map<String, String>, kept: Map<String, String>): Map<String, String> {
    val names = manifest.secrets.map { it.name }.toSet()
    given.keys.firstOrNull { it !in names }?.let { refuse(PackageProblem.BAD_SECRET, it) }
    given.forEach { (name, value) ->
        if (value.isEmpty() || value.length > MAX_SECRET_CHARS || value.any { it.isISOControl() }) refuse(PackageProblem.BAD_SECRET, name)
    }
    return manifest.secrets.associate { secret ->
        secret.name to (given[secret.name] ?: kept[secret.name] ?: refuse(PackageProblem.MISSING_SECRET, secret.name))
    }
}

/**
 * What [view] (the new version) asks for or brings that [old] (the installed one, null when unreadable) did not; a
 * tool is new when its name is, when it asked before and does not now, or when it asks for more time than before (as
 * asked, whatever the host holds it to).
 */
internal fun changeOf(fromVersion: String, old: PackageManifest?, view: PackageView): PackageChange {
    val hosts = old?.network.orEmpty().toSet()
    val secrets = old?.secrets.orEmpty().map { it.name }.toSet()
    val servers = old?.mcpServers.orEmpty().map { it.id to it.host }.toSet()
    val skills = old?.let { manifest -> manifest.skills.map { scopedSkillName(manifest.id, it.name) } }.orEmpty().toSet()
    val hostTools = old?.hostTools.orEmpty().toSet()
    val oldTools = old?.tools.orEmpty().associateBy { it.name }
    return PackageChange(
        fromVersion = fromVersion,
        network = view.permissions.network.filter { it.host !in hosts },
        secrets = view.permissions.secrets.filter { it.name !in secrets },
        mcpServers = view.contents.mcpServers.filter { (it.id to it.host) !in servers },
        skills = view.contents.skills.filter { it.name !in skills },
        hostTools = view.permissions.hostTools.filter { it !in hostTools },
        // An update is signed by the same key, so trust is the same: a tool that did not say it only reads asked.
        tools = view.contents.tools.filter { tool ->
            oldTools[tool.name]?.let { old -> (!old.readOnly && !tool.asks) || tool.timeoutSeconds > old.timeoutSeconds } ?: true
        },
    )
}

private const val MAX_SECRET_CHARS = 4096
