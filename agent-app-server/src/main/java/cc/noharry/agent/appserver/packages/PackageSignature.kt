package cc.noharry.agent.appserver.packages

import cc.noharry.agent.appserver.protocol.PackageProblem
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.X509EncodedKeySpec
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * signature.json: the SHA-256 of every other file ([digests], by path) and, when signed, the publisher's ECDSA P-256
 * key ([publicKey], DER SubjectPublicKeyInfo naming the curve, point uncompressed) and its SHA-256 signature over [signedPayload] of the digests. Keys and
 * signatures are hex. Unsigned, it has the digests alone.
 */
internal class PackageSignature(
    val digests: Map<String, String>,
    val publicKey: ByteArray? = null,
    val signature: ByteArray? = null,
) {
    /** The file as written: unsigned when there is no key. */
    fun toJson(): String = buildJsonObject {
        put(KEY_FORMAT, PackageFormat.FORMAT)
        if (publicKey != null && signature != null) {
            put(KEY_ALGORITHM, ALGORITHM)
            put(KEY_PUBLIC_KEY, publicKey.toHex())
        }
        put(KEY_DIGESTS, JsonObject(digests.toSortedMap().mapValues { JsonPrimitive(it.value) }))
        signature?.let { put(KEY_SIGNATURE, it.toHex()) }
    }.toString()

    companion object {
        fun parse(text: String): PackageSignature {
            val root = try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (_: SerializationException) {
                null
            } ?: refuse(PackageProblem.BAD_SIGNATURE_FILE, "not a JSON object")
            if ((root[KEY_FORMAT] as? JsonPrimitive)?.intOrNull != PackageFormat.FORMAT) refuse(PackageProblem.BAD_SIGNATURE_FILE, KEY_FORMAT)
            (root.keys - KNOWN_KEYS).firstOrNull()?.let { refuse(PackageProblem.BAD_SIGNATURE_FILE, it) }
            val digests = (root[KEY_DIGESTS] as? JsonObject)?.mapValues { (path, digest) ->
                (digest as? JsonPrimitive)?.takeIf { it.isString && DIGEST.matches(it.content) }?.content
                    ?: refuse(PackageProblem.BAD_SIGNATURE_FILE, path)
            } ?: refuse(PackageProblem.BAD_SIGNATURE_FILE, KEY_DIGESTS)
            val signed = listOf(KEY_ALGORITHM, KEY_PUBLIC_KEY, KEY_SIGNATURE).map { root.text(it) }
            if (signed.all { it == null }) return PackageSignature(digests)
            val (algorithm, key, signature) = signed
            if (algorithm != ALGORITHM || key == null || signature == null) refuse(PackageProblem.BAD_SIGNATURE_FILE, KEY_ALGORITHM)
            return PackageSignature(
                digests,
                key.hexBytes() ?: refuse(PackageProblem.BAD_SIGNATURE_FILE, KEY_PUBLIC_KEY),
                signature.hexBytes() ?: refuse(PackageProblem.BAD_SIGNATURE_FILE, KEY_SIGNATURE),
            )
        }

        private fun JsonObject.text(key: String): String? =
            this[key]?.let { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: refuse(PackageProblem.BAD_SIGNATURE_FILE, key) }

        private const val KEY_FORMAT = "format"
        private const val KEY_ALGORITHM = "algorithm"
        private const val KEY_PUBLIC_KEY = "publicKey"
        private const val KEY_DIGESTS = "digests"
        private const val KEY_SIGNATURE = "signature"
        private val KNOWN_KEYS = setOf(KEY_FORMAT, KEY_ALGORITHM, KEY_PUBLIC_KEY, KEY_DIGESTS, KEY_SIGNATURE)
        private val DIGEST = Regex("[0-9a-f]{64}")
        const val ALGORITHM = "ecdsa-p256-sha256"
    }
}

/** What a publisher signs: a fixed first line, then `<sha256>  <path>` per file in path order, each line ending in \n. */
internal fun signedPayload(digests: Map<String, String>): ByteArray =
    (PAYLOAD_HEADER + digests.toSortedMap().entries.joinToString("") { (path, digest) -> "$digest  $path\n" }).toByteArray(Charsets.UTF_8)

/**
 * Checks [files] against [signature]: the digests name exactly the files but signature.json, each matches, and a
 * signed one verifies with its key. Returns the publisher's key as hex of its DER encoding, or null when unsigned.
 */
internal fun verifyPackageSignature(files: Map<String, ByteArray>, signature: PackageSignature): String? {
    val listed = files.keys - PackageFormat.SIGNATURE
    (listed - signature.digests.keys).firstOrNull()?.let { refuse(PackageProblem.DIGEST_MISMATCH, "$it is not listed") }
    (signature.digests.keys - listed).firstOrNull()?.let { refuse(PackageProblem.DIGEST_MISMATCH, "$it is missing") }
    listed.firstOrNull { sha256Hex(files.getValue(it)) != signature.digests[it] }?.let { refuse(PackageProblem.DIGEST_MISMATCH, it) }
    val encoded = signature.publicKey ?: return null
    val key = publisherKey(encoded) ?: refuse(PackageProblem.BAD_SIGNATURE, "not an ECDSA P-256 key")
    val verifies = try {
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initVerify(key)
            update(signedPayload(signature.digests))
            verify(signature.signature)
        }
    } catch (_: GeneralSecurityException) {
        false
    }
    if (!verifies) refuse(PackageProblem.BAD_SIGNATURE)
    return encoded.toHex()
}

/**
 * [encoded] as an ECDSA key on P-256, or null when it is not one in the one form a publisher's key takes: a DER
 * SubjectPublicKeyInfo naming the curve P-256 (not giving its parameters) with the point uncompressed, 91 bytes. The
 * form is checked byte by byte before any provider reads it, so which keys are taken, and the hex a key is known by,
 * do not depend on the device's security provider.
 */
internal fun publisherKey(encoded: ByteArray): PublicKey? {
    val header = P256_KEY_HEADER
    val form = encoded.size == header.size + P256_POINT_BYTES && encoded.copyOfRange(0, header.size).contentEquals(header) &&
        encoded[header.size] == UNCOMPRESSED_POINT
    if (!form) return null
    return try {
        (KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(X509EncodedKeySpec(encoded)) as? ECPublicKey)?.takeIf { key ->
            key.params.order == P256_ORDER && (key.params.curve.field as? ECFieldFp)?.p == P256_PRIME
        }
    } catch (_: GeneralSecurityException) {
        null
    }
}

/** A host's trusted key as hex, as the server writes a package's (lower case), or null when it is not a publisher's key. */
internal fun normalizedKeyHex(hex: String): String? = hex.trim().lowercase().hexBytes()?.takeIf { publisherKey(it) != null }?.toHex()

/** The short name of a key a person can compare: the first 16 bytes of its SHA-256, in groups. */
internal fun keyFingerprint(keyHex: String): String =
    sha256Hex(keyHex.hexBytes() ?: ByteArray(0)).take(FINGERPRINT_CHARS).chunked(FINGERPRINT_GROUP).joinToString(":")

internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }

internal fun String.hexBytes(): ByteArray? {
    if (length % 2 != 0 || !all { it in '0'..'9' || it in 'a'..'f' }) return null
    return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(HEX_RADIX).toByte() }
}

private const val PAYLOAD_HEADER = "agentplugin-signature-1\n"
private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
private const val KEY_ALGORITHM = "EC"
private const val FINGERPRINT_CHARS = 32
private const val FINGERPRINT_GROUP = 4
private const val BYTE_MASK = 0xFF
private const val HEX_RADIX = 16
private val P256_ORDER = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", HEX_RADIX)
private val P256_PRIME = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", HEX_RADIX)

/**
 * The fixed start of a P-256 key's SubjectPublicKeyInfo: SEQUENCE, AlgorithmIdentifier (id-ecPublicKey, prime256v1),
 * then a BIT STRING of 66 bytes (no unused bits) holding the point.
 */
private val P256_KEY_HEADER = checkNotNull("3059301306072a8648ce3d020106082a8648ce3d030107034200".hexBytes())

/** An uncompressed point: 0x04, then X and Y of 32 bytes each. */
private const val P256_POINT_BYTES = 65
private const val UNCOMPRESSED_POINT: Byte = 0x04
