package cc.noharry.agent.appserver.packages

import cc.noharry.agent.appserver.protocol.HostReach
import cc.noharry.agent.appserver.protocol.PackageProblem
import java.net.URI
import java.net.URISyntaxException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * A package's plugin.json, format 1, as checked by [parseManifest]: who it is, what it asks for ([network],
 * [secrets], [hostTools]) and what it brings. Paths are the package's own, checked as paths; a file it names is
 * checked to exist by [checkPackageContents].
 */
internal class PackageManifest(
    val id: String,
    val name: String,
    val version: String,
    val publisher: String,
    val description: String,
    val minServer: String?,
    /** The hosts its MCP servers and scripts may reach, each named. */
    val network: List<String>,
    val secrets: List<ManifestSecret>,
    val mcpServers: List<ManifestMcpServer>,
    val skills: List<ManifestSkill>,
    val rules: String?,
    val icon: String?,
    /** Its tools written as scripts. */
    val tools: List<ManifestTool> = emptyList(),
    /** The host's tools its scripts may call (those that only read), as `host.tool` names them. */
    val hostTools: List<String> = emptyList(),
)

/**
 * A tool written as a script: [name] within the package, [description] for the model, [inputSchema] its arguments
 * are checked against (the subset [ScriptSchema] reads), [script] (`scripts/….js`, defining `function run(input)`),
 * whether its publisher says it only reads ([readOnly]; taken only from a trusted publisher), and the seconds one call
 * asks for ([timeoutSeconds], host calls included; the host may hold it to less, [scriptToolSeconds]).
 */
internal class ManifestTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val script: String,
    val readOnly: Boolean,
    val timeoutSeconds: Int = PackageFormat.DEFAULT_TOOL_SECONDS,
)

/** A value the person types once at install ([name] as `${name}` names it, [label] as the form shows it). */
internal class ManifestSecret(val name: String, val label: String)

/**
 * One of the package's MCP servers: [id] within the package, [label], its address as written ([url], which may hold
 * `${name}` only as a whole query value) and [headers] (which may hold `${name}` anywhere in a value).
 */
internal class ManifestMcpServer(
    val id: String,
    val label: String,
    val url: String,
    val headers: Map<String, String>,
    val host: String,
    /** [url] without the query parameters that take a secret. */
    val baseUrl: String,
    /** Query parameters set from secrets: parameter → secret. */
    val secretQuery: Map<String, String>,
) {
    val reach: HostReach get() = hostReach(host)
}

/** A skill the package brings: `skills/<name>/SKILL.md`, asked to be preloaded or not. */
internal class ManifestSkill(val name: String, val path: String, val preload: Boolean)

/**
 * Reads plugin.json strictly: a format other than [PackageFormat.FORMAT] is refused before anything else, then
 * anything that carries compiled code (reserved), then any member the format does not have, then each value.
 */
internal fun parseManifest(text: String, serverVersion: String = PackageFormat.SERVER_VERSION): PackageManifest {
    val root = jsonObject(text) ?: refuse(PackageProblem.BAD_MANIFEST, "not a JSON object")
    val format = root[KEY_FORMAT] as? JsonPrimitive
    if (format == null || format.isString || format.intOrNull != PackageFormat.FORMAT) refuse(PackageProblem.UNKNOWN_FORMAT, format?.content.orEmpty())
    val permissions = root[KEY_PERMISSIONS]?.let { it as? JsonObject ?: refuse(PackageProblem.BAD_MANIFEST, KEY_PERMISSIONS) } ?: JsonObject(emptyMap())
    refuseReserved(root, permissions)
    unknownMembers(root, TOP_KEYS, "")
    unknownMembers(permissions, PERMISSION_KEYS + RESERVED_PERMISSION_KEYS, "$KEY_PERMISSIONS.")
    val id = root.text(KEY_ID, required = true)!!
    if (!isPackageId(id)) refuse(PackageProblem.BAD_ID, id.take(MAX_ID_CHARS))
    val version = root.text(KEY_VERSION, required = true)!!
    SemVer.parse(version) ?: refuse(PackageProblem.BAD_VERSION, version.take(MAX_LABEL_CHARS))
    val minServer = root.text(KEY_MIN_SERVER)?.also { needed ->
        val wanted = SemVer.parse(needed) ?: refuse(PackageProblem.BAD_VERSION, KEY_MIN_SERVER)
        if (wanted > checkNotNull(SemVer.parse(serverVersion))) refuse(PackageProblem.NEEDS_NEWER_SERVER, needed)
    }
    val network = hosts(permissions)
    val secrets = secrets(permissions)
    val tools = tools(root)
    val hostTools = hostTools(permissions)
    if (hostTools.isNotEmpty() && tools.isEmpty()) refuse(PackageProblem.BAD_TOOL, "$KEY_HOST_TOOLS without $KEY_TOOLS")
    return PackageManifest(
        id = id,
        version = version,
        minServer = minServer,
        name = root.display(KEY_NAME, MAX_NAME_CHARS, required = true)!!,
        publisher = root.display(KEY_PUBLISHER, MAX_PUBLISHER_CHARS, required = true)!!,
        description = root.display(KEY_DESCRIPTION, MAX_DESCRIPTION_CHARS).orEmpty(),
        network = network,
        secrets = secrets,
        mcpServers = mcpServers(root, network.toSet(), secrets.map { it.name }.toSet()),
        skills = skills(root),
        rules = root.text(KEY_RULES)?.also { path(it, KEY_RULES, setOf(MD)) },
        icon = root.text(KEY_ICON)?.also { path(it, KEY_ICON, setOf(PackageFormat.PNG_EXTENSION)) },
        tools = tools,
        hostTools = hostTools,
    )
}

/** Compiled code (step C) is reserved: a package that asks for it is refused, never half-installed. */
private fun refuseReserved(root: JsonObject, permissions: JsonObject) {
    if (KEY_CODE in root) refuse(PackageProblem.CARRIES_CODE, KEY_CODE)
    permissions[KEY_NATIVE_CODE]?.let { if ((it as? JsonPrimitive)?.booleanOrNull != false) refuse(PackageProblem.CARRIES_CODE, KEY_NATIVE_CODE) }
}

private fun tools(root: JsonObject): List<ManifestTool> {
    val tools = root.list(KEY_TOOLS, PackageFormat.MAX_TOOLS).map { element ->
        tool(element as? JsonObject ?: refuse(PackageProblem.BAD_TOOL, KEY_TOOLS))
    }
    if (tools.map { it.name }.toSet().size != tools.size) refuse(PackageProblem.BAD_TOOL, "a name twice")
    return tools
}

private fun tool(item: JsonObject): ManifestTool {
    (item.keys - TOOL_KEYS).firstOrNull()?.let { refuse(PackageProblem.BAD_TOOL, "$KEY_TOOLS.${it.take(MAX_LABEL_CHARS)}") }
    val name = (item[KEY_NAME] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: refuse(PackageProblem.BAD_TOOL, "$KEY_TOOLS.$KEY_NAME")
    if (!TOOL_NAME.matches(name)) refuse(PackageProblem.BAD_TOOL, "name ${name.take(MAX_LABEL_CHARS)}")
    val description = (item[KEY_DESCRIPTION] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
    val hidden = description?.any { (it.isISOControl() && it != '\n') || Character.getType(it) == Character.FORMAT.toInt() } ?: false
    if (description.isNullOrEmpty() || description.length > MAX_TOOL_DESCRIPTION_CHARS || hidden) {
        refuse(PackageProblem.BAD_TOOL, "$name $KEY_DESCRIPTION")
    }
    val schema = item[KEY_INPUT_SCHEMA] as? JsonObject ?: refuse(PackageProblem.BAD_TOOL, "$name $KEY_INPUT_SCHEMA")
    ScriptSchema.check(name, schema)
    val script = (item[KEY_SCRIPT] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: refuse(PackageProblem.BAD_TOOL, "$name $KEY_SCRIPT")
    checkPackagePath(script)
    if (!script.startsWith(PackageFormat.SCRIPTS_DIR) || script.substringAfterLast('.', "") != PackageFormat.SCRIPT_EXTENSION) {
        refuse(PackageProblem.BAD_TOOL, "$name $KEY_SCRIPT ${script.take(MAX_LABEL_CHARS)}")
    }
    val readOnly = item[KEY_READ_ONLY]?.let { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.booleanOrNull ?: refuse(PackageProblem.BAD_TOOL, "$name $KEY_READ_ONLY") }
    val seconds = item[KEY_TIMEOUT_SECONDS]?.let { toolSeconds(name, it) } ?: PackageFormat.DEFAULT_TOOL_SECONDS
    return ManifestTool(name, description, schema, script, readOnly ?: false, seconds)
}

/** A tool's own time: a whole number of seconds written as JSON writes an integer, from 1 to the format's most. */
private fun toolSeconds(tool: String, element: JsonElement): Int {
    val text = (element as? JsonPrimitive)?.takeIf { !it.isString }?.content
    val seconds = text?.toIntOrNull()?.takeIf { it.toString() == text }
    if (seconds == null || seconds !in 1..PackageFormat.MAX_TOOL_SECONDS) refuse(PackageProblem.BAD_TOOL, "$tool $KEY_TIMEOUT_SECONDS")
    return seconds
}

/** The host's tools its scripts call: each named as a tool's name may be, each once. */
private fun hostTools(permissions: JsonObject): List<String> {
    val names = permissions.list(KEY_HOST_TOOLS, MAX_HOST_TOOLS).map { element ->
        stringOf(element, KEY_HOST_TOOLS).also { if (!HOST_TOOL_NAME.matches(it)) refuse(PackageProblem.BAD_TOOL, "$KEY_HOST_TOOLS: ${it.take(MAX_LABEL_CHARS)}") }
    }
    if (names.toSet().size != names.size) refuse(PackageProblem.BAD_TOOL, "$KEY_HOST_TOOLS: a name twice")
    return names
}

private fun hosts(permissions: JsonObject): List<String> = permissions.list(KEY_NETWORK, MAX_HOSTS).map { element ->
    stringOf(element, KEY_NETWORK).also { if (!isPackageHost(it)) refuse(PackageProblem.BAD_MANIFEST, "$KEY_NETWORK: ${it.take(MAX_LABEL_CHARS)}") }
}.distinct()

private fun secrets(permissions: JsonObject): List<ManifestSecret> {
    val secrets = permissions.list(KEY_SECRETS, MAX_SECRETS).map { element ->
        val item = element as? JsonObject ?: refuse(PackageProblem.BAD_MANIFEST, KEY_SECRETS)
        unknownMembers(item, setOf(KEY_NAME, KEY_LABEL), "$KEY_SECRETS.")
        val name = item.text(KEY_NAME, required = true)!!
        if (!SECRET_NAME.matches(name)) refuse(PackageProblem.BAD_MANIFEST, "$KEY_SECRETS: ${name.take(MAX_LABEL_CHARS)}")
        ManifestSecret(name, item.display(KEY_LABEL, MAX_LABEL_CHARS, required = true)!!)
    }
    if (secrets.map { it.name }.toSet().size != secrets.size) refuse(PackageProblem.BAD_MANIFEST, "$KEY_SECRETS: a name twice")
    return secrets
}

private fun mcpServers(root: JsonObject, network: Set<String>, secrets: Set<String>): List<ManifestMcpServer> {
    val servers = root.list(KEY_MCP_SERVERS, MAX_MCP_SERVERS).map { element ->
        mcpServer(element as? JsonObject ?: refuse(PackageProblem.BAD_MCP_SERVER, KEY_MCP_SERVERS), network, secrets)
    }
    if (servers.map { it.id }.toSet().size != servers.size) refuse(PackageProblem.BAD_MCP_SERVER, "an id twice")
    return servers
}

private fun mcpServer(item: JsonObject, network: Set<String>, secrets: Set<String>): ManifestMcpServer {
    unknownMembers(item, setOf(KEY_ID, KEY_LABEL, KEY_URL, KEY_HEADERS), "$KEY_MCP_SERVERS.")
    val id = item.text(KEY_ID, required = true)!!
    if (!SERVER_ID.matches(id)) refuse(PackageProblem.BAD_MCP_SERVER, "id ${id.take(MAX_LABEL_CHARS)}")
    val label = item.display(KEY_LABEL, MAX_SERVER_LABEL_CHARS, required = true)!!
    val url = item.text(KEY_URL, required = true)!!
    val address = mcpAddress(url, id, secrets)
    if (address.host !in network) refuse(PackageProblem.HOST_NOT_PERMITTED, address.host)
    val headers = (item[KEY_HEADERS]?.let { it as? JsonObject ?: refuse(PackageProblem.BAD_MCP_SERVER, "$id headers") } ?: JsonObject(emptyMap()))
    if (headers.size > MAX_HEADERS) refuse(PackageProblem.BAD_MCP_SERVER, "$id headers")
    val checked = headers.mapValues { (name, value) -> header(id, name, stringOf(value, "$id $name"), secrets) }
    return ManifestMcpServer(id, label, url, checked, address.host, address.base, address.secretQuery)
}

private fun header(server: String, name: String, value: String, secrets: Set<String>): String {
    if (!HEADER_NAME.matches(name) || name.lowercase() in RESERVED_HEADERS) refuse(PackageProblem.BAD_MCP_SERVER, "$server header $name")
    if (value.length > MAX_HEADER_CHARS || value.any { it.isISOControl() }) refuse(PackageProblem.BAD_MCP_SERVER, "$server header $name")
    placeholders(value, server, secrets)
    return value
}

private class McpAddress(val host: String, val base: String, val secretQuery: Map<String, String>)

/**
 * An MCP server's address: HTTPS (HTTP only on this device, as the app allows cleartext only there), no user part
 * or fragment, a host as [isPackageHost] reads one, and `${name}` of [secrets] only as a whole query value.
 */
private fun mcpAddress(url: String, server: String, secrets: Set<String>): McpAddress {
    val bad = { why: String -> refuse(PackageProblem.BAD_MCP_SERVER, "$server url: $why") }
    if (url.length > MAX_URL_CHARS || '#' in url || url.any { it.isWhitespace() || it.isISOControl() }) bad("form")
    val base = url.substringBefore('?')
    if (PLACEHOLDER_START in base) bad("a secret outside the query")
    val uri = try { URI(base) } catch (_: URISyntaxException) { bad("form") }
    val host = uri.host ?: bad("no host")
    val scheme = uri.scheme
    if (uri.rawUserInfo != null || !isPackageHost(host) || !base.startsWith("$scheme://$host")) bad("host")
    if (scheme != HTTPS && !(scheme == HTTP && hostReach(host) == HostReach.DEVICE)) bad("not HTTPS")
    val kept = mutableListOf<String>()
    val secretQuery = linkedMapOf<String, String>()
    url.substringAfter('?', "").split('&').filter(String::isNotEmpty).forEach { parameter ->
        val name = parameter.substringBefore('=')
        val value = parameter.substringAfter('=', "")
        if (PLACEHOLDER_START in name) bad("a secret in a parameter's name")
        if (PLACEHOLDER_START !in value) {
            kept += parameter
            return@forEach
        }
        val secret = PLACEHOLDER.matchEntire(value)?.groupValues?.get(1) ?: bad("a secret is not a whole value")
        if (secret !in secrets) refuse(PackageProblem.UNKNOWN_SECRET, secret)
        secretQuery[name] = secret
    }
    return McpAddress(host, if (kept.isEmpty()) base else "$base?${kept.joinToString("&")}", secretQuery)
}

/** Whether [name] is a package's id as the format writes one: reverse-domain, at most 100 characters. */
internal fun isPackageId(name: String): Boolean = name.length <= MAX_ID_CHARS && ID.matches(name)

/** The `${name}`s in [value], each one of [secrets]; a `${` that starts none is refused. */
private fun placeholders(value: String, server: String, secrets: Set<String>): List<String> {
    val found = PLACEHOLDER.findAll(value).map { it.groupValues[1] }.toList()
    if (PLACEHOLDER.replace(value, "").contains(PLACEHOLDER_START)) refuse(PackageProblem.BAD_MCP_SERVER, "$server: a broken $PLACEHOLDER_START")
    found.firstOrNull { it !in secrets }?.let { refuse(PackageProblem.UNKNOWN_SECRET, it) }
    return found
}

/** The names [value] holds as `${name}`; null when a `${` starts none. */
internal fun secretNamesIn(value: String): List<String>? {
    if (PLACEHOLDER.replace(value, "").contains(PLACEHOLDER_START)) return null
    return PLACEHOLDER.findAll(value).map { it.groupValues[1] }.toList()
}

/** The name [value] is, when it is one `${name}` and nothing else. */
internal fun wholeSecretName(value: String): String? = PLACEHOLDER.matchEntire(value)?.groupValues?.get(1)

/** [value] with each `${name}` replaced by [secrets]' value; null when one of them is not set. */
internal fun substituteSecrets(value: String, secrets: Map<String, String>): String? {
    if (PLACEHOLDER.findAll(value).any { secrets[it.groupValues[1]].isNullOrEmpty() }) return null
    return PLACEHOLDER.replace(value) { secrets.getValue(it.groupValues[1]) }
}

private fun skills(root: JsonObject): List<ManifestSkill> {
    val skills = root.list(KEY_SKILLS, MAX_SKILLS).map { element ->
        val (path, preload) = when (element) {
            is JsonObject -> {
                unknownMembers(element, setOf(KEY_PATH, KEY_PRELOAD), "$KEY_SKILLS.")
                val preload = element[KEY_PRELOAD]?.let { (it as? JsonPrimitive)?.booleanOrNull ?: refuse(PackageProblem.BAD_MANIFEST, KEY_PRELOAD) }
                element.text(KEY_PATH, required = true)!! to (preload ?: false)
            }
            else -> stringOf(element, KEY_SKILLS) to false
        }
        val name = SKILL_PATH.matchEntire(path)?.groupValues?.get(1) ?: refuse(PackageProblem.BAD_SKILL, path.take(MAX_LABEL_CHARS))
        ManifestSkill(name, path, preload)
    }
    if (skills.map { it.name }.toSet().size != skills.size) refuse(PackageProblem.BAD_SKILL, "a skill twice")
    return skills
}

private fun path(value: String, key: String, extensions: Set<String>) {
    checkPackagePath(value)
    if (value.substringAfterLast('.', "") !in extensions) refuse(PackageProblem.BAD_MANIFEST, "$key: $value")
}

private fun jsonObject(text: String): JsonObject? = try {
    Json.parseToJsonElement(text) as? JsonObject
} catch (_: SerializationException) {
    null
}

private fun unknownMembers(item: JsonObject, known: Set<String>, where: String) {
    (item.keys - known).firstOrNull()?.let { refuse(PackageProblem.BAD_MANIFEST, "$where${it.take(MAX_LABEL_CHARS)}") }
}

private fun JsonObject.list(key: String, max: Int): JsonArray {
    val list = this[key]?.let { it as? JsonArray ?: refuse(PackageProblem.BAD_MANIFEST, key) } ?: JsonArray(emptyList())
    if (list.size > max) refuse(PackageProblem.BAD_MANIFEST, "$key: over $max")
    return list
}

private fun stringOf(element: JsonElement, where: String): String =
    (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: refuse(PackageProblem.BAD_MANIFEST, where)

private fun JsonObject.text(key: String, required: Boolean = false): String? {
    val value = this[key] ?: return if (required) refuse(PackageProblem.BAD_MANIFEST, "$key is missing") else null
    return stringOf(value, key)
}

/** Text a screen shows: not blank, no control or formatting characters (which could reorder what it shows), short. */
private fun JsonObject.display(key: String, max: Int, required: Boolean = false): String? {
    val value = text(key, required)?.trim() ?: return null
    val hidden = value.any { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
    if ((required && value.isEmpty()) || value.length > max || hidden) refuse(PackageProblem.BAD_MANIFEST, key)
    return value
}

private const val KEY_FORMAT = "format"
private const val KEY_ID = "id"
private const val KEY_NAME = "name"
private const val KEY_VERSION = "version"
private const val KEY_PUBLISHER = "publisher"
private const val KEY_DESCRIPTION = "description"
private const val KEY_MIN_SERVER = "minServer"
private const val KEY_PERMISSIONS = "permissions"
private const val KEY_NETWORK = "network"
private const val KEY_SECRETS = "secrets"
private const val KEY_MCP_SERVERS = "mcpServers"
private const val KEY_SKILLS = "skills"
private const val KEY_RULES = "rules"
private const val KEY_ICON = "icon"
private const val KEY_LABEL = "label"
private const val KEY_URL = "url"
private const val KEY_HEADERS = "headers"
private const val KEY_PATH = "path"
private const val KEY_PRELOAD = "preload"
private const val KEY_CODE = "code"
private const val KEY_NATIVE_CODE = "nativeCode"
private const val KEY_HOST_TOOLS = "hostTools"
private const val KEY_TOOLS = "tools"
private const val KEY_INPUT_SCHEMA = "inputSchema"
private const val KEY_SCRIPT = "script"
private const val KEY_READ_ONLY = "readOnly"
private const val KEY_TIMEOUT_SECONDS = "timeoutSeconds"
private val TOP_KEYS = setOf(
    KEY_FORMAT, KEY_ID, KEY_NAME, KEY_VERSION, KEY_PUBLISHER, KEY_DESCRIPTION, KEY_MIN_SERVER, KEY_PERMISSIONS,
    KEY_MCP_SERVERS, KEY_SKILLS, KEY_RULES, KEY_ICON, KEY_TOOLS,
)
private val PERMISSION_KEYS = setOf(KEY_NETWORK, KEY_SECRETS, KEY_HOST_TOOLS)
private val RESERVED_PERMISSION_KEYS = setOf(KEY_NATIVE_CODE)
private val TOOL_KEYS = setOf(KEY_NAME, KEY_DESCRIPTION, KEY_INPUT_SCHEMA, KEY_SCRIPT, KEY_READ_ONLY, KEY_TIMEOUT_SECONDS)
private const val HTTPS = "https"
private const val HTTP = "http"
private const val MD = "md"
private const val PLACEHOLDER_START = "\${"
private const val MAX_ID_CHARS = 100
private const val MAX_NAME_CHARS = 60
private const val MAX_PUBLISHER_CHARS = 80
private const val MAX_DESCRIPTION_CHARS = 500
private const val MAX_LABEL_CHARS = 60
private const val MAX_SERVER_LABEL_CHARS = 40
private const val MAX_URL_CHARS = 500
private const val MAX_HEADER_CHARS = 1024
private const val MAX_HOSTS = 20
private const val MAX_SECRETS = 10
private const val MAX_MCP_SERVERS = 10
private const val MAX_HEADERS = 10
private const val MAX_SKILLS = 20
private const val MAX_HOST_TOOLS = 20
private const val MAX_TOOL_DESCRIPTION_CHARS = 1000

/**
 * A script tool's name: short enough that `mcp__<server>__<tool>` stays within the 64 characters model services take
 * (the server's name is at most 32 and a digit), so the gate reads the name the model calls.
 */
private val TOOL_NAME = Regex("""[a-z][a-z0-9_]{0,23}""")

/** A host tool as `host.tool` names it: its name in its server, or the whole `mcp__<server>__<tool>`. */
private val HOST_TOOL_NAME = Regex("""[A-Za-z0-9_-]{1,64}""")

/** Reverse-domain: two parts or more, each a lower-case letter then letters, digits, `-` or `_`. */
private val ID = Regex("""[a-z][a-z0-9_-]*(?:\.[a-z][a-z0-9_-]*)+""")
private val SERVER_ID = Regex("""[a-z][a-z0-9]{0,23}""")
private val SECRET_NAME = Regex("""[A-Za-z][A-Za-z0-9_]{0,31}""")
private val SKILL_PATH = Regex("""skills/([a-z0-9]+(?:-[a-z0-9]+)*)""")
// Android's regex engine refuses a bare closing brace, which the JVM that runs the unit tests accepts.
private val PLACEHOLDER = Regex("""\$\{([A-Za-z][A-Za-z0-9_]{0,31})\}""")
private val HEADER_NAME = Regex("""[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}""")
private val RESERVED_HEADERS = setOf("content-type", "accept", "mcp-session-id", "mcp-protocol-version", "content-length", "host")
