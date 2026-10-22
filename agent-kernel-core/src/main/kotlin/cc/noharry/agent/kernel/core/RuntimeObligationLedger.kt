@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.CompletionImpact
import cc.noharry.agent.kernel.api.RuntimeActionOutcome
import cc.noharry.agent.kernel.api.RuntimeActionStatus
import cc.noharry.agent.kernel.api.RuntimeCommitStatus
import cc.noharry.agent.kernel.api.RuntimeEffectKind
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionSummary
import java.security.MessageDigest
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

data class RuntimeActionDescriptor(
    val providerId: String,
    val actionName: String,
    val effectKind: RuntimeEffectKind,
    val impact: CompletionImpact,
    val effectKeyDigest: String? = null,
)

class RuntimeObligationLedger(
    private var runSecret: String = UUID.randomUUID().toString(),
) {
    private val lock = Any()
    private val obligations = linkedMapOf<String, RuntimeActionOutcome>()

    fun declareRequiredMutating(
        runId: String,
        descriptor: RuntimeActionDescriptor,
        argumentsJson: String,
    ): RuntimeActionOutcome? {
        if (descriptor.effectKind != RuntimeEffectKind.MUTATING) return null
        if (descriptor.impact != CompletionImpact.REQUIRED_ON_CALL) return null
        val id = obligationId(runId, descriptor, argumentsJson)
        return synchronized(lock) {
            val existing = obligations[id]
            if (existing != null && existing.isSatisfied()) {
                return@synchronized existing
            }
            RuntimeActionOutcome(
                obligationId = id,
                actionName = boundedToken(descriptor.actionName, MAX_ACTION_NAME_CHARS),
                status = RuntimeActionStatus.DECLARED,
                commitStatus = RuntimeCommitStatus.NOT_COMMITTED,
            ).also { obligations[id] = it }
        }
    }

    fun recordOutcome(outcome: RuntimeActionOutcome): RuntimeActionOutcome = synchronized(lock) {
        val prior = obligations[outcome.obligationId]
        if (prior != null && prior.isSatisfied()) {
            return@synchronized prior
        }
        val merged = outcome.copy(
            obligationId = boundedToken(outcome.obligationId, MAX_OBLIGATION_ID_CHARS),
            actionName = boundedToken(outcome.actionName.ifBlank { prior?.actionName.orEmpty() }, MAX_ACTION_NAME_CHARS),
            safeCode = outcome.safeCode?.let { boundedToken(it, MAX_SAFE_CODE_CHARS) },
        )
        obligations[merged.obligationId] = merged
        merged
    }

    fun markCancelled() = synchronized(lock) {
        obligations.replaceAll { _, value ->
            if (value.isRequiredUnresolved()) {
                value.copy(status = RuntimeActionStatus.CANCELLED, commitStatus = RuntimeCommitStatus.NOT_COMMITTED)
            } else {
                value
            }
        }
    }

    fun snapshot(): List<RuntimeActionOutcome> = synchronized(lock) { obligations.values.toList() }

    fun summarize(requested: RuntimeCompletionOutcome): RuntimeCompletionSummary {
        val required = snapshot()
        val succeeded = required.count { it.isSatisfied() }
        val unresolved = required.filter { it.isRequiredUnresolved() }
        val digestSource = required.joinToString("|") { "${it.obligationId}:${it.status}:${it.commitStatus}" }
        return RuntimeCompletionSummary(
            outcome = when {
                requested == RuntimeCompletionOutcome.FAILED -> RuntimeCompletionOutcome.FAILED
                requested == RuntimeCompletionOutcome.UNKNOWN -> RuntimeCompletionOutcome.UNKNOWN
                unresolved.isNotEmpty() && requested == RuntimeCompletionOutcome.SUCCEEDED ->
                    RuntimeCompletionOutcome.FAILED
                unresolved.isNotEmpty() -> requested
                else -> requested
            },
            requiredTotal = required.size,
            requiredSucceeded = succeeded,
            requiredUnresolved = unresolved.size,
            unresolvedCodes = unresolved.mapNotNull { it.safeCode }.distinct().take(MAX_UNRESOLVED_CODES),
            projectionDigest = obligationDigest(digestSource).take(PROJECTION_DIGEST_CHARS),
        )
    }

    fun encode(): String = ObligationLedgerJson.encode(runSecret, snapshot())

    fun restore(encoded: String) {
        val decoded = ObligationLedgerJson.decode(encoded)
        synchronized(lock) {
            if (decoded == null) {
                runSecret = UUID.randomUUID().toString()
                obligations.clear()
                if (encoded.isNotBlank()) {
                    obligations[RESTORE_UNKNOWN_ID] = unresolvedUnknownOutcome()
                }
                return
            }
            runSecret = decoded.secret
            obligations.clear()
            decoded.obligations.forEach { obligations[it.obligationId] = it }
        }
    }

    fun obligationId(
        runId: String,
        descriptor: RuntimeActionDescriptor,
        argumentsJson: String,
    ): String {
        val effectOrArgs = descriptor.effectKeyDigest?.takeIf { it.isNotBlank() }
            ?: canonicalActionArguments(argumentsJson)
        val identity = "${descriptor.providerId}\u001f${descriptor.actionName}\u001f$effectOrArgs"
        return obligationDigest("$runSecret|$runId|$identity")
    }

    companion object {
        fun decode(encoded: String?): RuntimeObligationLedger {
            if (encoded.isNullOrBlank()) return RuntimeObligationLedger()
            val ledger = RuntimeObligationLedger()
            ledger.restore(encoded)
            return ledger
        }
    }
}

fun RuntimeActionOutcome.isSatisfied(): Boolean =
    status == RuntimeActionStatus.SUCCEEDED &&
        (commitStatus == RuntimeCommitStatus.COMMITTED || commitStatus == RuntimeCommitStatus.NO_CHANGE_REQUIRED)

fun RuntimeActionOutcome.isRequiredUnresolved(): Boolean = !isSatisfied()

fun unresolvedUnknownOutcome(): RuntimeActionOutcome =
    RuntimeActionOutcome(
        obligationId = RESTORE_UNKNOWN_ID,
        actionName = RESTORE_UNKNOWN_ACTION,
        status = RuntimeActionStatus.UNKNOWN,
        commitStatus = RuntimeCommitStatus.UNKNOWN,
        safeCode = RESTORE_UNKNOWN_CODE,
    )

const val REQUIRED_ACTION_UNRESOLVED = "REQUIRED_ACTION_UNRESOLVED"
internal const val COMPLETION_PROJECTION_INVALID = "COMPLETION_PROJECTION_INVALID"
internal const val FINISH_OUTCOME_SUCCESS = "success"
internal const val FINISH_OUTCOME_PARTIAL = "partial"
internal const val FINISH_OUTCOME_BLOCKED = "blocked"

private const val MAX_UNRESOLVED_CODES = 8
private const val PROJECTION_DIGEST_CHARS = 16
internal const val LEDGER_SCHEMA_VERSION = 1
internal const val MAX_ACTION_NAME_CHARS = 128
internal const val MAX_SAFE_CODE_CHARS = 64
internal const val MAX_OBLIGATION_ID_CHARS = 128
internal const val MAX_LEDGER_SECRET_CHARS = 128
const val MAX_LEDGER_ITEMS = 256
const val MAX_LEDGER_ENCODED_CHARS = 65_536
internal const val RESTORE_UNKNOWN_ACTION = "unknown"
const val RESTORE_UNKNOWN_CODE = "LEDGER_RESTORE_UNKNOWN"
val RESTORE_UNKNOWN_ID: String = obligationDigest("restore-unknown")

fun boundedToken(value: String, maxChars: Int): String =
    value.filter { ch -> ch.code in 32..126 && ch != '"' && ch != '\\' }.take(maxChars)

fun obligationDigest(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { byte -> "%02x".format(byte) }
}

data class DecodedObligationLedger(
    val secret: String,
    val obligations: List<RuntimeActionOutcome>,
)

object ObligationLedgerJson {
    fun encode(secret: String, obligations: List<RuntimeActionOutcome>): String =
        try {
            encodeStrict(secret, obligations)
        } catch (_: RuntimeException) {
            encodeUnknownMarker()
        }

    private fun encodeStrict(secret: String, obligations: List<RuntimeActionOutcome>): String {
        if (obligations.size > MAX_LEDGER_ITEMS) error("ledger item array exceeds bound")
        val safeSecret = requireSafeToken(secret, MAX_LEDGER_SECRET_CHARS, digest = false)
        val encoded = buildJsonObject {
            put("v", LEDGER_SCHEMA_VERSION)
            put("secret", safeSecret)
            put(
                "items",
                buildJsonArray {
                    obligations.sortedBy { it.obligationId }.forEach { item ->
                        add(
                            buildJsonObject {
                                put("id", requireSafeToken(item.obligationId, MAX_OBLIGATION_ID_CHARS, digest = true))
                                put("action", requireSafeToken(item.actionName, MAX_ACTION_NAME_CHARS, digest = false))
                                put("status", item.status.name)
                                put("commit", item.commitStatus.name)
                                put(
                                    "code",
                                    item.safeCode?.let {
                                        JsonPrimitive(requireSafeToken(it, MAX_SAFE_CODE_CHARS, digest = false))
                                    } ?: JsonNull,
                                )
                                put("retryable", item.retryable)
                            },
                        )
                    }
                },
            )
        }.toString()
        if (encoded.length > MAX_LEDGER_ENCODED_CHARS) error("encoded ledger exceeds bound")
        if (encoded.any { ch -> ch == '\t' || ch == '\r' || ch == '\n' || ch.code < 32 }) {
            error("encoded ledger contains control characters")
        }
        return encoded
    }

    private fun encodeUnknownMarker(): String =
        buildJsonObject {
            put("v", LEDGER_SCHEMA_VERSION)
            put("secret", "restore-unknown")
            put(
                "items",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", RESTORE_UNKNOWN_ID)
                            put("action", RESTORE_UNKNOWN_ACTION)
                            put("status", RuntimeActionStatus.UNKNOWN.name)
                            put("commit", RuntimeCommitStatus.UNKNOWN.name)
                            put("code", RESTORE_UNKNOWN_CODE)
                            put("retryable", false)
                        },
                    )
                },
            )
        }.toString()

    fun decode(encoded: String?): DecodedObligationLedger? {
        if (encoded.isNullOrBlank()) return null
        if (encoded.length > MAX_LEDGER_ENCODED_CHARS) return null
        if (encoded.any { it == '\t' || it == '\r' || it == '\n' || it.code < 32 }) return null
        return try {
            decodeStrict(encoded)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun decodeStrict(encoded: String): DecodedObligationLedger {
        val root = Json.parseToJsonElement(encoded) as? JsonObject
            ?: error("ledger root must be an object")
        val versionPrimitive = root["v"] as? JsonPrimitive ?: error("ledger version missing")
        if (versionPrimitive.isString) error("ledger version must be a number")
        val version = versionPrimitive.intOrNull ?: error("ledger version missing")
        if (version != LEDGER_SCHEMA_VERSION) error("unsupported ledger version")
        val secret = requireSafeToken(requireJsonString(root["secret"]), MAX_LEDGER_SECRET_CHARS, digest = false)
        val items = root["items"] as? JsonArray ?: error("ledger items missing")
        if (items.size > MAX_LEDGER_ITEMS) error("ledger item array exceeds bound")
        val seen = linkedSetOf<String>()
        val obligations = items.map { element ->
            val obj = element as? JsonObject ?: error("ledger item must be an object")
            val id = requireSafeToken(requireJsonString(obj["id"]), MAX_OBLIGATION_ID_CHARS, digest = true)
            val action = requireSafeToken(requireJsonString(obj["action"]), MAX_ACTION_NAME_CHARS, digest = false)
            if (!seen.add(id)) error("duplicate obligation id")
            val status = parseEnum<RuntimeActionStatus>(requireJsonString(obj["status"]))
            val commit = parseEnum<RuntimeCommitStatus>(requireJsonString(obj["commit"]))
            val code = when (val elementCode = obj["code"]) {
                null, is JsonNull -> null
                else -> requireSafeToken(requireJsonString(elementCode), MAX_SAFE_CODE_CHARS, digest = false)
            }
            val retryablePrimitive = obj["retryable"] as? JsonPrimitive
                ?: error("retryable must be boolean")
            if (retryablePrimitive.isString) error("retryable must be boolean")
            val retryable = retryablePrimitive.booleanOrNull
                ?: error("retryable must be boolean")
            RuntimeActionOutcome(
                obligationId = id,
                actionName = action,
                status = status,
                commitStatus = commit,
                safeCode = code,
                retryable = retryable,
            )
        }
        return DecodedObligationLedger(secret, obligations)
    }

    private fun requireJsonString(value: kotlinx.serialization.json.JsonElement?): String {
        val primitive = value as? JsonPrimitive ?: error("expected json string")
        if (!primitive.isString) error("expected json string")
        return primitive.content
    }

    private fun requireSafeToken(value: String?, maxChars: Int, digest: Boolean): String {
        if (value.isNullOrBlank()) error("token missing")
        if (value.length > maxChars) error("token exceeds bound")
        if (value.any { ch -> ch.code < 32 || ch == '"' || ch == '\\' || ch == '\t' || ch == '\r' || ch == '\n' }) {
            error("token contains forbidden characters")
        }
        if (digest && !SHA256_HEX.matches(value)) error("token is not a digest")
        return value
    }

    private inline fun <reified T : Enum<T>> parseEnum(raw: String?): T {
        if (raw.isNullOrBlank()) error("enum missing")
        return enumValues<T>().firstOrNull { it.name == raw } ?: error("invalid enum $raw")
    }
}

private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
