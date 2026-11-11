package cc.noharry.agent.appserver.packages

import cc.noharry.agent.appserver.packages.TestPackages.build
import cc.noharry.agent.appserver.packages.TestPackages.files
import cc.noharry.agent.appserver.packages.TestPackages.manifest
import cc.noharry.agent.appserver.packages.TestPackages.scriptFiles
import cc.noharry.agent.appserver.packages.TestPackages.signed
import cc.noharry.agent.appserver.packages.TestPackages.tool
import cc.noharry.agent.appserver.packages.TestPackages.zip
import cc.noharry.agent.appserver.protocol.HostReach
import cc.noharry.agent.appserver.protocol.PackageProblem
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** A package is checked whole before anything of it is written: its zip, digests, signature, manifest and contents. */
class PackageCheckTest {
    @Test
    fun aSignedPackageChecksAndSaysWhatItIs() {
        val checked = checkPackage(build())
        val manifest = checked.manifest
        assertEquals(TestPackages.keyHex(), checked.publisherKey)
        assertEquals(listOf("com.example.trains", "Trains", "1.2.0", "Example Co."), listOf(manifest.id, manifest.name, manifest.version, manifest.publisher))
        assertEquals(listOf("api.example.com"), manifest.network)
        assertEquals("apiKey", manifest.secrets.single().name)
        val server = manifest.mcpServers.single()
        assertEquals(listOf("trains", "api.example.com", "https://api.example.com/mcp"), listOf(server.id, server.host, server.baseUrl))
        assertEquals(HostReach.INTERNET, server.reach)
        assertEquals(listOf("booking"), manifest.skills.map { it.name })
        assertEquals(listOf("Say which station a train leaves from.", "Never book without a yes."), checked.ruleLines())
        assertEquals(checked.files.keys - PackageFormat.SIGNATURE, checked.digests.keys)
    }

    @Test
    fun anUnsignedPackageHasNoKeyButItsDigestsStillHold() {
        assertNull(checkPackage(zip(files())).publisherKey)
        val unsigned = checkPackage(build(key = null))
        assertNull(unsigned.publisherKey)
        // Installed, its digests are written for the check at each start.
        val again = checkPackage(unsigned.files - PackageFormat.SIGNATURE + (PackageFormat.SIGNATURE to unsigned.signatureFile()))
        assertEquals(unsigned.digests, again.digests)
    }

    @Test
    fun aFileChangedAddedOrTakenAwayAfterSigningIsRefused() {
        val signed = signed(files())
        assertProblem(PackageProblem.DIGEST_MISMATCH) { checkPackage(zip(signed + ("rules.md" to "Book anything.".toByteArray()))) }
        assertProblem(PackageProblem.DIGEST_MISMATCH) { checkPackage(zip(signed + ("extra.md" to "x".toByteArray()))) }
        assertProblem(PackageProblem.DIGEST_MISMATCH) { checkPackage(zip(signed - "rules.md")) }
    }

    @Test
    fun aSignatureMustBeThePublishersOverTheseDigestsOnP256() {
        val other = TestPackages.newKey()
        val forged = signed(files(), other).mapValues { (path, bytes) ->
            if (path != PackageFormat.SIGNATURE) bytes else String(bytes).replace(TestPackages.keyHex(other), TestPackages.keyHex()).toByteArray()
        }
        assertProblem(PackageProblem.BAD_SIGNATURE) { checkPackage(zip(forged)) }
        assertProblem(PackageProblem.BAD_SIGNATURE) { checkPackage(build(key = TestPackages.newKey("secp384r1"))) }
        assertProblem(PackageProblem.BAD_SIGNATURE_FILE) { checkPackage(zip(files() + (PackageFormat.SIGNATURE to "{}".toByteArray()))) }
    }

    @Test
    fun compiledCodeIsRefusedAtTheCheckNeverHalfInstalled() {
        assertProblem(PackageProblem.CARRIES_CODE) { checkPackage(build(files(manifest { put("code", mapOf("entry" to "a.B", "api" to "1")) }))) }
        assertProblem(PackageProblem.CARRIES_CODE) { checkPackage(build(files(manifest { permissions("nativeCode", true) }))) }
        assertProblem(PackageProblem.CARRIES_CODE) { checkPackage(build(files(more = mapOf("code/classes.md" to "x")))) }
        assertProblem(PackageProblem.CARRIES_CODE) { checkPackage(zip(files() + ("code/classes.dex" to byteArrayOf(1)))) }
        assertProblem(PackageProblem.CARRIES_CODE) { checkPackage(zip(files() + ("Code/notes.md" to "x".toByteArray()))) }
        assertProblem(PackageProblem.CARRIES_CODE) { checkPackage(build(scriptFiles() + ("code/x.js" to "x".toByteArray()))) }
        // Saying it carries none is not asking.
        checkPackage(build(files(manifest { permissions("nativeCode", false) })))
    }

    @Test
    fun aPackageWithScriptToolsChecksAndAStepAPackageStillDoes() {
        val checked = checkPackage(build(scriptFiles(listOf(tool(), tool("seats", readOnly = false)), hostTools = listOf("web_search", "mcp__calendar__events"))))
        val tools = checked.manifest.tools
        assertEquals(listOf("fare", "seats"), tools.map { it.name })
        assertEquals(listOf(true, false), tools.map { it.readOnly })
        assertEquals("scripts/fare.js", tools.first().script)
        assertEquals(TestPackages.SCRIPT, checked.scriptText(tools.first()))
        assertEquals(listOf("web_search", "mcp__calendar__events"), checked.manifest.hostTools)
        // readOnly left out is a tool that acts.
        assertFalse(checkPackage(build(scriptFiles(listOf(tool { remove("readOnly") })))).manifest.tools.single().readOnly)
        assertTrue(checkPackage(build()).manifest.tools.isEmpty())
    }

    @Test
    fun aScriptIsOnlyThereAsAToolsScript() {
        assertProblem(PackageProblem.CARRIES_SCRIPTS) { checkPackage(zip(files() + ("scripts/tool.js" to "x".toByteArray()))) }
        assertProblem(PackageProblem.CARRIES_SCRIPTS) { checkPackage(build(scriptFiles() + ("scripts/other.js" to "x".toByteArray()))) }
        assertProblem(PackageProblem.BAD_TOOL) { checkPackage(build(scriptFiles() - "scripts/fare.js")) }
        assertProblem(PackageProblem.FILE_TYPE) { checkPackage(build(scriptFiles() + ("tool.js" to "x".toByteArray()))) }
        assertProblem(PackageProblem.FILE_TYPE) { checkPackage(build(scriptFiles() + ("skills/booking/run.js" to "x".toByteArray()))) }
        assertProblem(PackageProblem.FILE_TYPE) { checkPackage(build(scriptFiles() + ("scripts/notes.md" to "x".toByteArray()))) }
        assertProblem(PackageProblem.NOT_UTF8) { checkPackage(build(scriptFiles() + ("scripts/fare.js" to byteArrayOf(0xC3.toByte(), 0x28)))) }
        val big = "x".repeat(PackageFormat.MAX_SCRIPT_BYTES + 1).toByteArray()
        assertProblem(PackageProblem.TOO_LARGE) { checkPackage(build(scriptFiles() + ("scripts/fare.js" to big))) }
        assertProblem(PackageProblem.BAD_TOOL) { checkPackage(build(files(manifest { permissions("hostTools", listOf("read_file")) }))) }
    }

    @Test
    fun aToolIsAsTheFormatSays() {
        fun check(vararg tools: Map<String, Any?>, hostTools: List<String> = emptyList()) = checkPackage(build(scriptFiles(tools.toList(), hostTools)))
        listOf("Fare", "1fare", "fare-x", "f".repeat(25), "").forEach { name ->
            assertProblem(PackageProblem.BAD_TOOL) { check(tool { put("name", name) }) }
        }
        assertProblem(PackageProblem.BAD_TOOL) { check(tool(), tool()) }
        assertProblem(PackageProblem.BAD_TOOL) { check(tool { remove("description") }) }
        assertProblem(PackageProblem.BAD_TOOL) { check(tool { put("description", "Fare‮x") }) }
        assertProblem(PackageProblem.BAD_TOOL) { check(tool { put("script", "skills/fare.js") }) }
        assertProblem(PackageProblem.BAD_TOOL) { check(tool { put("script", "scripts/fare.md") }) }
        assertProblem(PackageProblem.BAD_PATH) { check(tool { put("script", "scripts/../fare.js") }) }
        assertProblem(PackageProblem.BAD_TOOL) { check(tool { put("readOnly", "true") }) }
        assertProblem(PackageProblem.BAD_TOOL) { check(tool { put("code", "x") }) }
        assertProblem(PackageProblem.BAD_TOOL) { check(tool(), hostTools = listOf("bad name")) }
        assertProblem(PackageProblem.BAD_TOOL) { check(tool(), hostTools = listOf("a", "a")) }
        val many = (1..PackageFormat.MAX_TOOLS + 1).map { tool("t$it") }
        assertProblem(PackageProblem.BAD_MANIFEST) { check(*many.toTypedArray()) }
        // A description may have line breaks.
        check(tool { put("description", "The fare.\nIn euros.") })
    }

    @Test
    fun aToolAsksForItsTimeInWholeSecondsFromOneTo120AndTenWhenItSaysNone() {
        fun seconds(value: Any?) = checkPackage(build(scriptFiles(listOf(tool { put("timeoutSeconds", value) })))).manifest.tools.single().timeoutSeconds
        assertEquals(PackageFormat.DEFAULT_TOOL_SECONDS, checkPackage(build(scriptFiles())).manifest.tools.single().timeoutSeconds)
        assertEquals(10, PackageFormat.DEFAULT_TOOL_SECONDS)
        assertEquals(listOf(1, 3, 120), listOf(1, 3, 120).map(::seconds))
        listOf<Any>(0, -1, 121, 1000, Long.MAX_VALUE, 2.5, 3.0, "10", true).forEach { value ->
            assertProblem(PackageProblem.BAD_TOOL) { seconds(value) }
        }
        val written = { member: String ->
            val manifest = String(scriptFiles().getValue(PackageFormat.MANIFEST)).replace("\"readOnly\":true", "\"readOnly\":true,$member")
            checkPackage(build(scriptFiles() + (PackageFormat.MANIFEST to manifest.toByteArray())))
        }
        // As JSON writes them: null, an exponent, a leading zero or plus are not an integer of seconds.
        listOf("null", "1e1", "1E1", "05", "+5", "5.0").forEach { number ->
            assertProblem(PackageProblem.BAD_TOOL) { written("\"timeoutSeconds\":$number") }
        }
        assertEquals(5, written("\"timeoutSeconds\":5").manifest.tools.single().timeoutSeconds)
    }

    @Test
    fun anInputSchemaUsesOnlyWhatTheServerChecks() {
        fun schema(schema: Map<String, Any?>) = checkPackage(build(scriptFiles(listOf(tool { put("inputSchema", schema) }))))
        assertProblem(PackageProblem.BAD_TOOL) { schema(mapOf("type" to "array")) }
        assertProblem(PackageProblem.BAD_TOOL) { schema(mapOf("properties" to emptyMap<String, Any>())) }
        listOf("pattern" to "^a+$", "\$ref" to "#/x", "oneOf" to listOf(emptyMap<String, Any>()), "patternProperties" to emptyMap<String, Any>()).forEach { (key, value) ->
            assertProblem(PackageProblem.BAD_TOOL) { schema(mapOf("type" to "object", "properties" to mapOf("a" to mapOf("type" to "string", key to value)))) }
        }
        assertProblem(PackageProblem.BAD_TOOL) { schema(mapOf("type" to "object", "properties" to mapOf("a" to mapOf("type" to "text")))) }
        assertProblem(PackageProblem.BAD_TOOL) { schema(mapOf("type" to "object", "properties" to mapOf("a" to mapOf("maxLength" to -1)))) }
        assertProblem(PackageProblem.BAD_TOOL) { schema(mapOf("type" to "object", "properties" to mapOf("a" to mapOf("maxLength" to 1.5)))) }
        assertProblem(PackageProblem.BAD_TOOL) { schema(mapOf("type" to "object", "required" to listOf(1))) }
        val deep = (1..ScriptSchema.MAX_DEPTH + 1).fold<Int, Map<String, Any?>>(mapOf("type" to "string")) { inner, _ -> mapOf("type" to "array", "items" to inner) }
        assertProblem(PackageProblem.BAD_TOOL) { schema(mapOf("type" to "object", "properties" to mapOf("a" to deep))) }
        assertProblem(PackageProblem.BAD_TOOL) { schema(mapOf("type" to "object", "description" to "x".repeat(ScriptSchema.MAX_SCHEMA_CHARS))) }
        val rich = mapOf(
            "type" to "object",
            "\$schema" to "https://json-schema.org/draft/2020-12/schema",
            "properties" to mapOf(
                "day" to mapOf("type" to "string", "enum" to listOf("today", "tomorrow"), "description" to "Which day"),
                "count" to mapOf("type" to listOf("integer", "null"), "minimum" to 1, "maximum" to 9, "default" to 3),
                "tags" to mapOf("type" to "array", "items" to mapOf("type" to "string", "maxLength" to 20), "maxItems" to 5),
            ),
            "additionalProperties" to false,
        )
        assertEquals(3, schema(rich).manifest.tools.single().inputSchema.getValue("properties").let { (it as kotlinx.serialization.json.JsonObject).size })
    }

    @Test
    fun theManifestIsReadStrictly() {
        assertProblem(PackageProblem.UNKNOWN_FORMAT) { checkPackage(build(files(manifest { put("format", 2); put("code", "x") }))) }
        assertProblem(PackageProblem.UNKNOWN_FORMAT) { checkPackage(build(files(manifest { put("format", "1") }))) }
        assertProblem(PackageProblem.BAD_MANIFEST) { checkPackage(build(files(manifest { put("homepage", "x") }))) }
        assertProblem(PackageProblem.BAD_MANIFEST) { checkPackage(build(files(manifest { put("templates", listOf("templates/card.json")) }))) }
        assertProblem(PackageProblem.BAD_MANIFEST) { checkPackage(build(files(manifest { put("name", "Trains‮exe") }))) }
        assertProblem(PackageProblem.BAD_MANIFEST) { checkPackage(build(files(manifest { remove("publisher") }))) }
        listOf("trains", "Com.example", "com..example", "com.example/..", "1com.example").forEach { id ->
            assertProblem(PackageProblem.BAD_ID) { checkPackage(build(files(manifest { put("id", id) }))) }
        }
        listOf("1.2", "1.2.0+build", "01.2.0", "v1.2.0").forEach { version ->
            assertProblem(PackageProblem.BAD_VERSION) { checkPackage(build(files(manifest { put("version", version) }))) }
        }
        assertProblem(PackageProblem.NEEDS_NEWER_SERVER) { checkPackage(build(files(manifest { put("minServer", "99.0.0") }))) }
        assertProblem(PackageProblem.NO_MANIFEST) { checkPackage(build(files() - PackageFormat.MANIFEST)) }
    }

    @Test
    fun aZipCannotNameAPathOutsideThePackageNorRunOverItsLimits() {
        listOf("../evil.md", "/abs.md", "a\\b.md", ".hidden.md", "a//b.md", "skills/../../x.md").forEach { path ->
            assertProblem(PackageProblem.BAD_PATH) { checkPackage(zip(files() + (path to "x".toByteArray()))) }
        }
        assertProblem(PackageProblem.DUPLICATE_FILE) { checkPackage(zip(files() + ("RULES.md" to "x".toByteArray()))) }
        val many = (1..PackageFormat.MAX_FILES).associate { "t/$it.md" to "x".toByteArray() }
        assertProblem(PackageProblem.TOO_MANY_FILES) { checkPackage(zip(files() + many)) }
        // Small packed, too big unpacked.
        assertProblem(PackageProblem.TOO_LARGE) { checkPackage(zip(files() + ("big.md" to ByteArray(PackageFormat.MAX_BYTES + 1) { 'a'.code.toByte() }))) }
        assertProblem(PackageProblem.TOO_LARGE) { checkPackage(ByteArray(PackageFormat.MAX_BYTES + 1)) }
        assertProblem(PackageProblem.NOT_A_PACKAGE) { checkPackage("not a zip".toByteArray()) }
    }

    @Test
    fun onlyMarkdownJsonAndPngInUtf8() {
        assertProblem(PackageProblem.FILE_TYPE) { checkPackage(zip(files() + ("run.sh" to "x".toByteArray()))) }
        assertProblem(PackageProblem.FILE_TYPE) { checkPackage(zip(files() + ("page.html" to "<p>x</p>".toByteArray()))) }
        assertProblem(PackageProblem.FILE_TYPE) { checkPackage(zip(files() + ("icon.png" to "not a png".toByteArray()))) }
        assertProblem(PackageProblem.NOT_UTF8) { checkPackage(zip(files() + ("notes.md" to byteArrayOf(0xC3.toByte(), 0x28)))) }
    }

    @Test
    fun anMcpServerReachesOnlyItsNamedHostsOverHttps() {
        fun server(url: String, headers: Map<String, String> = emptyMap(), hosts: List<String> = listOf("api.example.com")) = manifest {
            put("permissions", mapOf("network" to hosts, "secrets" to listOf(mapOf("name" to "apiKey", "label" to "Key"))))
            put("mcpServers", listOf(mapOf("id" to "trains", "label" to "Trains", "url" to url, "headers" to headers)))
        }
        fun check(manifest: String) = checkPackage(build(files(manifest))).manifest.mcpServers.single()
        assertProblem(PackageProblem.HOST_NOT_PERMITTED) { check(server("https://evil.example.net/mcp")) }
        assertProblem(PackageProblem.BAD_MCP_SERVER) { check(server("http://api.example.com/mcp")) }
        assertProblem(PackageProblem.BAD_MCP_SERVER) { check(server("https://user@api.example.com/mcp")) }
        assertProblem(PackageProblem.BAD_MCP_SERVER) { check(server("https://api.example.com/\${apiKey}/mcp")) }
        assertProblem(PackageProblem.UNKNOWN_SECRET) { check(server("https://api.example.com/mcp", mapOf("X-Key" to "\${other}"))) }
        assertProblem(PackageProblem.BAD_MCP_SERVER) { check(server("https://api.example.com/mcp", mapOf("Host" to "x"))) }
        assertProblem(PackageProblem.BAD_MANIFEST) { check(server("https://2130706433/mcp", hosts = listOf("2130706433"))) }
        assertEquals(HostReach.DEVICE, check(server("http://127.0.0.1:8790/mcp", hosts = listOf("127.0.0.1"))).reach)
        assertEquals(HostReach.LOCAL_NETWORK, check(server("https://192.168.1.20/mcp", hosts = listOf("192.168.1.20"))).reach)
        val keyed = check(server("https://api.example.com/mcp?v=2&key=\${apiKey}"))
        assertEquals("https://api.example.com/mcp?v=2", keyed.baseUrl)
        assertEquals(mapOf("key" to "apiKey"), keyed.secretQuery)
    }

    @Test
    fun skillsAndRulesAreThereAndWithinTheirCaps() {
        assertProblem(PackageProblem.BAD_SKILL) { checkPackage(build(files() - "skills/booking/SKILL.md")) }
        assertProblem(PackageProblem.BAD_SKILL) { checkPackage(build(files(more = mapOf("skills/booking/SKILL.md" to TestPackages.SKILL.replace("name: booking", "name: other"))))) }
        assertProblem(PackageProblem.BAD_SKILL) { checkPackage(build(files(manifest { put("skills", listOf("skills/../x")) }))) }
        assertProblem(PackageProblem.BAD_RULES) { checkPackage(build(files(more = mapOf("rules.md" to "line\n".repeat(21))))) }
        assertProblem(PackageProblem.BAD_RULES) { checkPackage(build(files(more = mapOf("rules.md" to "x".repeat(2001))))) }
        val preload = checkPackage(build(files(manifest { put("skills", listOf(mapOf("path" to "skills/booking", "preload" to true))) })))
        assertTrue(preload.manifest.skills.single().preload)
    }

    @Test
    fun secretsVersionsAndKeysReadAsTheyShould() {
        assertEquals("Bearer k1", substituteSecrets("Bearer \${apiKey}", mapOf("apiKey" to "k1")))
        assertNull(substituteSecrets("Bearer \${apiKey}", emptyMap()))
        val ordered = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0", "1.2.0", "1.10.0")
        assertEquals(ordered, ordered.shuffled().sortedWith(compareBy { SemVer.parse(it)!! }))
        assertEquals(TestPackages.keyHex(), normalizedKeyHex(TestPackages.keyHex().uppercase()))
        assertNull(normalizedKeyHex("00ff"))
        assertArrayEquals(byteArrayOf(0, -1), "00ff".hexBytes())
        assertEquals(39, keyFingerprint(TestPackages.keyHex()).length)
    }

    @Test
    fun theServersVersionIsTheVersionItWasBuiltAsWithoutAPreRelease() {
        assertEquals(BUILT_VERSION.substringBefore('-'), PackageFormat.SERVER_VERSION)
        assertTrue(PackageFormat.SERVER_VERSION, SemVer.parse(PackageFormat.SERVER_VERSION) != null && '-' !in PackageFormat.SERVER_VERSION)
        // A package that needs the version being built installs on it.
        assertEquals(PackageFormat.SERVER_VERSION, checkPackage(build(files(manifest { put("minServer", PackageFormat.SERVER_VERSION) }))).manifest.minServer)
    }

    @Test
    fun aPublishersKeyIsTakenOnlyAsTheNamedCurveWithItsPointUncompressed() {
        val key = TestPackages.keyHex().hexBytes()!!
        val point = key.copyOfRange(key.size - 65, key.size)
        assertEquals(key.toHex(), publisherKey(key)!!.encoded.toHex())
        // The same point with the curve's parameters written out, which some providers read as P-256.
        val ecPublicKey = der(0x06, "2a8648ce3d0201".hexBytes()!!)
        val explicit = der(0x30, der(0x30, ecPublicKey + explicitP256()) + der(0x03, byteArrayOf(0) + point))
        assertNull(publisherKey(explicit))
        assertNull(normalizedKeyHex(explicit.toHex()))
        // A byte more, a header naming another curve (the last byte of its OID), a point marked compressed.
        assertNull(publisherKey(key + byteArrayOf(0)))
        assertNull(publisherKey(key.copyOf().also { it[22] = 0x08 }))
        assertNull(publisherKey(key.copyOf().also { it[26] = 0x02 }))
        // A package whose signature.json carries the explicit form is refused although the signature is the publisher's.
        val swapped = TestPackages.signed(files()).mapValues { (path, bytes) ->
            if (path != PackageFormat.SIGNATURE) bytes else String(bytes).replace(key.toHex(), explicit.toHex()).toByteArray()
        }
        assertProblem(PackageProblem.BAD_SIGNATURE) { checkPackage(zip(swapped)) }
    }

    /** P-256's ECParameters written out: version 1, the prime field, a and b, the base point, the order, cofactor 1. */
    private fun explicitP256(): ByteArray {
        fun int(hex: String) = der(0x02, java.math.BigInteger(hex, 16).toByteArray())
        fun octets(hex: String) = der(0x04, hex.lowercase().hexBytes()!!)
        val p = "FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF"
        val field = der(0x30, der(0x06, "2a8648ce3d0101".hexBytes()!!) + int(p))
        val curve = der(0x30, octets("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC") +
            octets("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B"))
        val base = octets("046B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C2964FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5")
        return der(0x30, int("1") + field + curve + base + int("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551") + int("1"))
    }

    /** A DER element: [tag], its length, [content]. */
    private fun der(tag: Int, content: ByteArray): ByteArray {
        val size = content.size
        val length = when {
            size < 0x80 -> byteArrayOf(size.toByte())
            size < 0x100 -> byteArrayOf(0x81.toByte(), size.toByte())
            else -> byteArrayOf(0x82.toByte(), (size shr 8).toByte(), size.toByte())
        }
        return byteArrayOf(tag.toByte()) + length + content
    }

    /** The example's permissions with [key] added. */
    private fun MutableMap<String, Any?>.permissions(key: String, value: Any) {
        val secrets = listOf(mapOf("name" to "apiKey", "label" to "Example API key"))
        put("permissions", mapOf("network" to listOf("api.example.com"), "secrets" to secrets, key to value))
    }

    private fun assertProblem(problem: PackageProblem, block: () -> Unit) {
        val refused = assertThrows(PackageException::class.java) { block() }
        assertEquals(refused.message, problem, refused.problem)
    }
}
