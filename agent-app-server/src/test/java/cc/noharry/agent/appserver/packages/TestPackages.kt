package cc.noharry.agent.appserver.packages

import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Packages built in memory as the publishing tool builds them: the files zipped with signature.json, which lists
 * their digests and, with a key, carries the publisher's key and signature. The default is the design's example.
 */
internal object TestPackages {
    val publisher: KeyPair by lazy { newKey() }

    fun newKey(curve: String = "secp256r1"): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair()

    fun keyHex(key: KeyPair = publisher): String = key.public.encoded.toHex()

    /** The example manifest, as a map [change] may alter (null removes a member). */
    fun manifest(change: MutableMap<String, Any?>.() -> Unit = {}): String {
        val members = linkedMapOf<String, Any?>(
            "format" to 1,
            "id" to "com.example.trains",
            "name" to "Trains",
            "version" to "1.2.0",
            "publisher" to "Example Co.",
            "description" to "Search trains and seats.",
            "minServer" to "0.5.0",
            "permissions" to mapOf(
                "network" to listOf("api.example.com"),
                "secrets" to listOf(mapOf("name" to "apiKey", "label" to "Example API key")),
            ),
            "mcpServers" to listOf(
                mapOf(
                    "id" to "trains", "label" to "Trains", "url" to "https://api.example.com/mcp",
                    "headers" to mapOf("Authorization" to "Bearer \${apiKey}"),
                ),
            ),
            "skills" to listOf("skills/booking"),
            "rules" to "rules.md",
        )
        members.change()
        return json(members.filterValues { it != null }).toString()
    }

    /** The example package's files, with [manifest] and [more] files, unsigned. */
    fun files(manifest: String = manifest(), more: Map<String, String> = emptyMap()): Map<String, ByteArray> =
        (mapOf(
            PackageFormat.MANIFEST to manifest,
            "skills/booking/SKILL.md" to SKILL,
            "rules.md" to "Say which station a train leaves from.\nNever book without a yes.\n",
        ) + more).mapValues { it.value.toByteArray(Charsets.UTF_8) }

    /** [files] with signature.json: digests, and a signature by [key] unless it is null. */
    fun signed(files: Map<String, ByteArray>, key: KeyPair? = publisher): Map<String, ByteArray> {
        val digests = files.mapValues { sha256Hex(it.value) }
        val signature = key?.let {
            Signature.getInstance("SHA256withECDSA").run {
                initSign(it.private)
                update(signedPayload(digests))
                sign()
            }
        }
        return files + (PackageFormat.SIGNATURE to PackageSignature(digests, key?.public?.encoded, signature).toJson().toByteArray())
    }

    /** A script tool as the manifest names it; [change] may alter it (null removes a member). */
    fun tool(name: String = "fare", readOnly: Boolean = true, change: MutableMap<String, Any?>.() -> Unit = {}): Map<String, Any?> {
        val properties = mapOf("from" to mapOf("type" to "string"), "to" to mapOf("type" to "string"))
        val members = linkedMapOf<String, Any?>(
            "name" to name,
            "description" to "The fare between two stations.",
            "inputSchema" to mapOf("type" to "object", "properties" to properties, "required" to listOf("from", "to")),
            "script" to "scripts/$name.js",
            "readOnly" to readOnly,
        )
        members.change()
        return members.filterValues { it != null }
    }

    /** The example with [tools] (each with a script that returns its input) and [hostTools] asked for. */
    fun scriptFiles(tools: List<Map<String, Any?>> = listOf(tool()), hostTools: List<String> = emptyList()): Map<String, ByteArray> {
        val withTools = manifest {
            val permissions = mapOf(
                "network" to listOf("api.example.com"),
                "secrets" to listOf(mapOf("name" to "apiKey", "label" to "Example API key")),
                "hostTools" to hostTools,
            )
            put("permissions", permissions)
            put("tools", tools)
        }
        // A script path a test makes up elsewhere stays without its file, so the manifest's check is what refuses it.
        val scripts = tools.mapNotNull { it["script"] as? String }.filter { it.startsWith("scripts/") && it.endsWith(".js") }.distinct().associateWith { SCRIPT }
        return files(withTools, scripts)
    }

    const val SCRIPT = "function run(input) {\n  return { from: input.from, to: input.to };\n}\n"

    /** The files as a zip, in the order given; names are taken as they are, so tests can write ones a check refuses. */
    fun zip(files: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            files.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** A whole package: [files] signed by [key] (unsigned when null) and zipped. */
    fun build(files: Map<String, ByteArray> = files(), key: KeyPair? = publisher): ByteArray = zip(signed(files, key))

    const val SKILL = "---\nname: booking\ndescription: Book a seat on a train once the user has chosen one.\n---\n\n" +
        "# Booking\n\nAsk which train, then call the booking tool.\n"

    private fun json(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k as String to json(v) })
        is List<*> -> JsonArray(value.map(::json))
        else -> error("not JSON: $value")
    }
}
