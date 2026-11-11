package cc.noharry.agent.appserver.packages

import cc.noharry.agent.appserver.protocol.HostReach
import cc.noharry.agent.appserver.protocol.PackageProblem

/**
 * Plugin packages a person downloads (`<id>-<version>.agentplugin`): a zip of a manifest (plugin.json), the digests
 * and signature of every other file (signature.json), skills, rules, an icon and the scripts of its tools, with no
 * compiled code. This file holds what the format fixes: its limits and names.
 */
internal object PackageFormat {
    /** The only format this server reads. */
    const val FORMAT = 1

    /**
     * The server's version as packages name it in `minServer`: the version it was built as ([BUILT_VERSION]), without
     * a pre-release (a 0.5.0-SNAPSHOT build serves packages that need 0.5.0).
     */
    val SERVER_VERSION: String = BUILT_VERSION.substringBefore('-')

    const val MANIFEST = "plugin.json"
    const val SIGNATURE = "signature.json"
    const val SKILL_FILE = "SKILL.md"

    /** A package, packed, and all its files unpacked. */
    const val MAX_BYTES = 5 * 1024 * 1024
    const val MAX_FILES = 200

    /** Directories in a zip count too, so a zip of empty entries cannot run on. */
    const val MAX_ENTRIES = 400
    const val MAX_PATH_CHARS = 200
    const val MAX_DEPTH = 8
    const val MAX_RULE_LINES = 20
    const val MAX_RULE_CHARS = 2000
    const val MAX_ICON_BYTES = 256 * 1024

    /** What format 1 holds: markdown, json and png, and JavaScript under [SCRIPTS_DIR]. */
    val TEXT_EXTENSIONS = setOf("md", "json")
    const val PNG_EXTENSION = "png"
    const val SCRIPT_EXTENSION = "js"

    /** Reserved for compiled code (step C): a package carrying it is refused. */
    const val CODE_DIR = "code/"

    /** The scripts of its tools: each file here must be one a tool names. */
    const val SCRIPTS_DIR = "scripts/"

    /** A script, and the tools a package may have. */
    const val MAX_SCRIPT_BYTES = 128 * 1024
    const val MAX_TOOLS = 20

    /**
     * A script tool's time for one call when its manifest asks for none (`timeoutSeconds`), and the most it may ask
     * for; the host may hold it to less ([ScriptLimits.maxSeconds]).
     */
    const val DEFAULT_TOOL_SECONDS = 10
    const val MAX_TOOL_SECONDS = 120
}

/** A package refused, or a step of installing it: [problem], and [detail] (the file, member or value it is about). */
internal class PackageException(val problem: PackageProblem, val detail: String = "") :
    Exception(if (detail.isEmpty()) problem.name else "${problem.name}: $detail")

internal fun refuse(problem: PackageProblem, detail: String = ""): Nothing = throw PackageException(problem, detail)

/** A semantic version: major.minor.patch, with an optional pre-release; no build metadata (it names a directory). */
internal class SemVer private constructor(private val numbers: List<Int>, private val pre: List<String>) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int {
        numbers.zip(other.numbers).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> return a.compareTo(b) }
        if (pre.isEmpty() || other.pre.isEmpty()) return other.pre.size.coerceAtMost(1) - pre.size.coerceAtMost(1)
        pre.zip(other.pre).firstOrNull { (a, b) -> a != b }?.let { (a, b) -> return comparePart(a, b) }
        return pre.size.compareTo(other.pre.size)
    }

    private fun comparePart(a: String, b: String): Int {
        val (x, y) = a.toIntOrNull() to b.toIntOrNull()
        return when {
            x != null && y != null -> x.compareTo(y)
            x != null -> -1
            y != null -> 1
            else -> a.compareTo(b)
        }
    }

    companion object {
        private val PATTERN = Regex("""(0|[1-9]\d{0,8})\.(0|[1-9]\d{0,8})\.(0|[1-9]\d{0,8})(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?""")
        private const val MAX_CHARS = 32

        fun parse(text: String): SemVer? {
            if (text.length > MAX_CHARS) return null
            val match = PATTERN.matchEntire(text) ?: return null
            val (major, minor, patch, pre) = match.destructured
            return SemVer(listOf(major, minor, patch).map(String::toInt), if (pre.isEmpty()) emptyList() else pre.split('.'))
        }
    }
}

/**
 * Where [host] is: this device (loopback), the person's own network (private and link-local addresses, `.local`
 * names) or the internet. A name that resolves to a private address reads as the internet: only literal addresses
 * and those names are known without asking the network.
 */
internal fun hostReach(host: String): HostReach {
    val ip = ipv4(host)
    return when {
        host == LOCALHOST || host.endsWith(".$LOCALHOST") || ip?.get(0) in DEVICE_OCTETS -> HostReach.DEVICE
        host.endsWith(MDNS_SUFFIX) -> HostReach.LOCAL_NETWORK
        ip != null && isPrivate(ip) -> HostReach.LOCAL_NETWORK
        else -> HostReach.INTERNET
    }
}

/**
 * Whether [host] is a host as a package may name one: a dotted IPv4 address (no other spelling of a number), or a
 * lower-case name whose last part starts with a letter (so no number reads as an address).
 */
internal fun isPackageHost(host: String): Boolean =
    ipv4(host) != null || (host.length <= MAX_HOST_CHARS && HOST_NAME.matches(host) && host.substringAfterLast('.').first().isLetter())

private fun ipv4(host: String): List<Int>? {
    val parts = host.split('.')
    if (parts.size != IPV4_PARTS || parts.any { !IPV4_PART.matches(it) }) return null
    return parts.map(String::toInt).takeIf { octets -> octets.all { it <= MAX_OCTET } }
}

private fun isPrivate(ip: List<Int>): Boolean {
    val (a, b) = ip
    return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
}

private const val LOCALHOST = "localhost"
/** 127/8 is loopback; 0.0.0.0 reaches this device too on Linux. */
private val DEVICE_OCTETS = setOf(0, 127)
private const val MDNS_SUFFIX = ".local"
private const val IPV4_PARTS = 4
private const val MAX_OCTET = 255
private const val MAX_HOST_CHARS = 253
private val IPV4_PART = Regex("""0|[1-9]\d{0,2}""")
private val HOST_NAME = Regex("""[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*""")
