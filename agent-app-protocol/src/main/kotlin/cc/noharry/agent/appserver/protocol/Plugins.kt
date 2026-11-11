package cc.noharry.agent.appserver.protocol

import cc.noharry.agent.appserver.protocol.jsonrpc.NotificationMethod
import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One of the host's plugins: whether it is turned on, and whether it runs (one turned on waits while a plugin it
 * [needs] is off). A [required] one cannot be turned off. One a person installed ([PluginSource.PACKAGE]) has its
 * package ([pkg]): who published it, what it asks for, whether it is damaged. [problem] says why one does not run: one a
 * library declares that needs a plugin the app does not have ([PackageProblem.NEEDS_MISSING], those ids in its detail)
 * is left out and cannot be turned on; one that failed to start ([PackageProblem.START_FAILED], why in its detail)
 * stays off until it is turned on again.
 */
@Serializable
data class PluginView(
    val id: String,
    val enabled: Boolean,
    val running: Boolean,
    val required: Boolean = false,
    val needs: List<String> = emptyList(),
    val source: PluginSource = PluginSource.BUILT_IN,
    @SerialName("package")
    val pkg: PackageView? = null,
    val problem: PackageRefusal? = null,
)

/** Where a plugin came from: built into the host's app, or a package a person installed. */
@Serializable
enum class PluginSource {
    @SerialName("built-in")
    BUILT_IN,

    @SerialName("package")
    PACKAGE,
}

/**
 * A plugin package: who it is ([id], [name], [version]), who published it, whether it is [unsigned] (only a host that
 * allows those installs one, and every screen listing it says so) or [damaged] (a file changed since it was installed,
 * or its record cannot be read, when [version] is empty: it does not start; [damage] says what the check found), what
 * it asks for and what it brings.
 */
@Serializable
data class PackageView(
    val id: String,
    val name: String,
    val version: String,
    val description: String = "",
    val publisher: PackagePublisher,
    val unsigned: Boolean = false,
    val damaged: Boolean = false,
    val damage: PackageRefusal? = null,
    val permissions: PackagePermissions = PackagePermissions(),
    val contents: PackageContents = PackageContents(),
)

/**
 * Who published a package: the [name] it gives (its own words, not checked), its key ([key], hex of the DER public
 * key) and that key's [fingerprint] (both null unsigned), and whether the host trusts that key.
 */
@Serializable
data class PackagePublisher(val name: String, val key: String? = null, val fingerprint: String? = null, val trusted: Boolean = false)

/**
 * What a package asks for: the hosts its MCP servers and scripts reach, the values the person types for it, and the
 * host's tools its scripts call ([hostTools]; only tools that read run for them).
 */
@Serializable
data class PackagePermissions(
    val network: List<PackageHost> = emptyList(),
    val secrets: List<PackageSecret> = emptyList(),
    val hostTools: List<String> = emptyList(),
)

/** A host a package reaches, and where it is: the internet, this device, or the person's own network. */
@Serializable
data class PackageHost(val host: String, val reach: HostReach)

/** A value the person types once for a package (a key, an account): its [name], its [label], and whether one is kept. */
@Serializable
data class PackageSecret(val name: String, val label: String, val set: Boolean = false)

/**
 * What a package brings: skills, MCP servers (whose tools that act ask first; every one of their tools, when its
 * publisher is not one the host trusts), lines of rules, and tools written as scripts ([tools]).
 */
@Serializable
data class PackageContents(
    val skills: List<PackageSkill> = emptyList(),
    val mcpServers: List<PackageMcpServer> = emptyList(),
    val ruleLines: Int = 0,
    val tools: List<PackageTool> = emptyList(),
)

/** One of a package's skills, as the model reads it, and whether it is in every run's prompt (a trusted publisher's). */
@Serializable
data class PackageSkill(val name: String, val preload: Boolean = false)

/** One of a package's MCP servers: its id in the package, its label, and the host it reaches. */
@Serializable
data class PackageMcpServer(val id: String, val label: String, val host: String, val reach: HostReach)

/**
 * One of a package's tools written as a script: its [name] in the package, what it says it does, whether its publisher
 * says it only reads ([readOnly]), whether it [asks] the person before each run (it does unless it only reads and its
 * publisher is trusted), the seconds one call asks for ([timeoutSeconds], host calls included) and the seconds it runs
 * with ([effectiveTimeoutSeconds]: the host may hold it to less).
 */
@Serializable
data class PackageTool(
    val name: String,
    val description: String = "",
    val readOnly: Boolean = false,
    val asks: Boolean = true,
    val timeoutSeconds: Int = 10,
    val effectiveTimeoutSeconds: Int = 10,
)

@Serializable
data class PluginListResult(val plugins: List<PluginView>)

@Serializable
data class PluginSetParams(val id: String, val enabled: Boolean)

/**
 * A package to install, from one of: [url] (HTTPS), [file] (a `content://` address, or a `file://` one in the app's own
 * external files) or [data] (the package itself, base64).
 */
@Serializable
data class PluginInstallParams(val url: String? = null, val file: String? = null, val data: String? = null)

/**
 * A package fetched and checked, nothing of it running: what it is and asks for ([pkg]), whether confirming it needs
 * the person to accept a publisher the host does not know or an unsigned package ([needsAcknowledgement]: the second,
 * plainer warning), and for an update what it asks beyond the installed version. [installId] confirms it within
 * [expiresInSeconds].
 */
@Serializable
data class PluginInstallResult(
    val installId: String,
    @SerialName("package")
    val pkg: PackageView,
    val needsAcknowledgement: Boolean,
    val update: PackageChange? = null,
    val expiresInSeconds: Int,
)

/**
 * An update: the version installed now ([fromVersion]), and what the new one asks for or brings that it did not (a
 * tool counts as new when it is, when it now runs without asking, or when it asks for more time than before).
 */
@Serializable
data class PackageChange(
    val fromVersion: String,
    val network: List<PackageHost> = emptyList(),
    val secrets: List<PackageSecret> = emptyList(),
    val mcpServers: List<PackageMcpServer> = emptyList(),
    val skills: List<PackageSkill> = emptyList(),
    val hostTools: List<String> = emptyList(),
    val tools: List<PackageTool> = emptyList(),
)

/**
 * Installs what [installId] checked: [secrets] are the values its permissions ask for (on an update, one left out keeps
 * the value it had), [acknowledgeUntrusted] that the person accepted the second warning when one was needed.
 */
@Serializable
data class PluginConfirmParams(
    val installId: String,
    val secrets: Map<String, String> = emptyMap(),
    val acknowledgeUntrusted: Boolean = false,
)

@Serializable
data class PluginRemoveParams(val id: String)

/** The server's features as they are now ([ServerFeatures]), after a plugin started or stopped. */
@Serializable
data class ServerFeaturesParams(val features: Set<String>)

/**
 * The host's plugins, and turning one on or off while the server runs (what is chosen is kept across restarts);
 * installing a package in two steps (fetch and check, then confirm) and removing one. A refused package or step
 * answers with an error whose data is a [PackageRefusal].
 */
object PluginRequests {
    val LIST = RequestMethod("plugins/list", Empty.serializer(), PluginListResult.serializer())
    val SET = RequestMethod("plugins/set", PluginSetParams.serializer(), PluginListResult.serializer())
    val INSTALL = RequestMethod("plugins/install", PluginInstallParams.serializer(), PluginInstallResult.serializer())
    val CONFIRM = RequestMethod("plugins/confirm", PluginConfirmParams.serializer(), PluginListResult.serializer())
    val REMOVE = RequestMethod("plugins/remove", PluginRemoveParams.serializer(), PluginListResult.serializer())
}

object PluginNotifications {
    /** A plugin started or stopped: the features of [ServerInfo.features] changed. */
    val FEATURES = NotificationMethod("server/features", ServerFeaturesParams.serializer())
}

/** Why the server refused a plugin package, or a step of installing one: the `data` of a refused request. */
@Serializable
enum class PackageProblem {
    /** Not a zip, or not one this server can read. */
    NOT_A_PACKAGE,
    /** Over 5 MB, packed or unpacked; or a script over 128 KiB. */
    TOO_LARGE,
    /** Over 200 files. */
    TOO_MANY_FILES,
    /** A file's path leaves the package, is absolute, hidden or has characters a path may not. */
    BAD_PATH,
    /** Two files with one path (letter case aside). */
    DUPLICATE_FILE,
    /** A file that is not markdown, json or png (or JavaScript under `scripts/`), or a png that is not one. */
    FILE_TYPE,
    /** A text file that is not UTF-8. */
    NOT_UTF8,
    /** It carries compiled code (`code/`, `code`, `nativeCode`), which this server does not load. */
    CARRIES_CODE,
    /** It carries a script that no tool of its manifest names. */
    CARRIES_SCRIPTS,
    /** No plugin.json. */
    NO_MANIFEST,
    /** plugin.json is not as the format says: a member missing, of the wrong kind, too long, or unknown. */
    BAD_MANIFEST,
    /** A format this server does not know. */
    UNKNOWN_FORMAT,
    /** The id is not a reverse-domain name. */
    BAD_ID,
    /** The version is not semantic (1.2.0). */
    BAD_VERSION,
    /** It needs a newer server (minServer). */
    NEEDS_NEWER_SERVER,
    /** signature.json is not as the format says. */
    BAD_SIGNATURE_FILE,
    /** A file is not what its digest says, or the files are not the ones signature.json lists. */
    DIGEST_MISMATCH,
    /** The signature does not verify, or the key is not an ECDSA P-256 key. */
    BAD_SIGNATURE,
    /** Unsigned, and the host does not take unsigned packages. */
    UNSIGNED_NOT_ALLOWED,
    /** An update signed by another key than the installed version: remove that one first. */
    PUBLISHER_KEY_CHANGED,
    /** Older than the installed version. */
    OLDER_VERSION,
    /** Its id is one of the host's own plugins'. */
    ID_TAKEN,
    /** A skill the manifest names is missing or its SKILL.md is not as the runtime reads one. */
    BAD_SKILL,
    /** rules.md is missing or over 20 lines or 2,000 characters. */
    BAD_RULES,
    /** An MCP server's address, headers or id are not as the format says. */
    BAD_MCP_SERVER,
    /** An MCP server's host is not among the package's network permissions. */
    HOST_NOT_PERMITTED,
    /** A `${name}` that names no secret of the package. */
    UNKNOWN_SECRET,
    /**
     * A tool written as a script is not as the format says: its name, description, input schema (a keyword the server
     * does not check), script or time (`timeoutSeconds`: whole seconds, 1 to 120); or `hostTools` names a tool badly
     * or comes without tools.
     */
    BAD_TOOL,
    /**
     * A name its tools would go by (`mcp__<name>__…`: its id's last segment for its script tools,
     * `<last segment>_<server id>` for an MCP server) is another installed package's (named in the detail) or one of the
     * host's other tool servers': a package with another last segment has to be used, or the other one removed.
     */
    TOOL_SERVER_TAKEN,
    /** It has tools written as scripts, and the app does not include the sandbox they run in (agent-exec-js). */
    SCRIPTS_UNAVAILABLE,
    /** None, or more than one, of url, file and data; or an address that is not HTTPS. */
    BAD_SOURCE,
    /** The package could not be fetched. */
    DOWNLOAD_FAILED,
    /** No install waits under this id (it was confirmed, or it expired). */
    INSTALL_EXPIRED,
    /** The publisher is not one the host trusts (or it is unsigned): the person has to accept that first. */
    NEEDS_ACKNOWLEDGEMENT,
    /** A secret the package needs was not given. */
    MISSING_SECRET,
    /** A secret's value is empty, too long or has line breaks; or it names no secret of the package. */
    BAD_SECRET,
    /** It did not start; what was installed before is back. */
    START_FAILED,
    /** A plugin a library declares needs plugins the app does not have (named in the detail): it is left out. */
    NEEDS_MISSING,
    /** Not an installed package (a plugin built into the host is not removed). */
    NOT_A_PACKAGE_PLUGIN,
    /**
     * The device's storage refused it; or what the server keeps of an installed package (its record) cannot be read,
     * so the package does not start and its id takes no install until it is removed.
     */
    STORAGE_FAILED,
}

/** What a refusal of a package carries: [problem], and [detail] (the file, member or value it is about). */
@Serializable
data class PackageRefusal(val problem: PackageProblem, val detail: String = "")

/** Where a host a package reaches is, as the install screen names it. */
@Serializable
enum class HostReach {
    @SerialName("internet")
    INTERNET,

    /** This device (loopback). */
    @SerialName("device")
    DEVICE,

    /** The person's own network (private addresses, `.local` names). */
    @SerialName("local-network")
    LOCAL_NETWORK,
}
