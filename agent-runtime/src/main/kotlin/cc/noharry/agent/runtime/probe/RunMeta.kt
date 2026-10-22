package cc.noharry.agent.runtime.probe

/**
 * Run identity + fingerprints for multi-arm comparability (SPEC-RPB-003 / B3).
 * Internal only; host may inject buildId / isolation / case binding.
 */
internal data class IsolationDeclaration(
    val session: String,
    val memory: String,
    val cache: String,
)

internal data class CaseBinding(
    val casePackId: String = "none",
    val promptSha256: String? = null,
)

internal data class ToolCatalogEntry(
    val qualifiedName: String,
    val source: String,
    val schemaDigest: String,
)

internal data class RunMeta(
    val schemaVersion: Int = 1,
    val runId: String,
    val sessionId: String,
    val armId: String,
    val buildId: String,
    val startedAtEpochMillis: Long,
    val provider: String,
    val protocol: String,
    val model: String,
    val modelRef: String? = null,
    val profile: String? = null,
    val variant: String? = null,
    val baseUrlHost: String,
    val searchMode: String? = null,
    val fetchMode: String? = null,
    val stream: Boolean? = null,
    val backend: String? = null,
    val isolation: IsolationDeclaration,
    val caseBinding: CaseBinding,
    val enabledProbePacks: List<String>,
    val toolset: List<ToolCatalogEntry>,
    val declaredVariables: List<String> = emptyList(),
    val configFingerprint: String,
    val controlFingerprint: String,
    val toolsetFingerprint: String,
    val comparable: Boolean,
    val incompleteReasons: List<String> = emptyList(),
)

/** Allowed Variable IDs for declaredVariables (B3). */
internal val ALLOWED_VARIABLE_IDS: Set<String> = setOf(
    "provider", "protocol", "model", "modelRef", "profile", "variant",
    "baseUrlHost", "searchMode", "fetchMode", "stream", "backend", "buildId",
    "enabledProbePacks", "isolation.session", "isolation.memory", "isolation.cache",
    "isolation", "casePackId", "promptSha256", "toolsetFingerprint",
)

internal val FORBIDDEN_VARIABLE_IDS: Set<String> = setOf(
    "search", "fetch", "toolset", "configFingerprint", "controlFingerprint",
    "armId", "runId", "sessionId", "startedAtEpochMillis",
)

private val PROMPT_SHA256_HEX = Regex("^[0-9a-f]{64}$")

internal data class ResolvedConfigObject(
    val provider: String,
    val protocol: String,
    val model: String,
    val modelRef: String? = null,
    val profile: String? = null,
    val variant: String? = null,
    val baseUrlHost: String,
    val searchMode: String? = null,
    val fetchMode: String? = null,
    val stream: Boolean? = null,
    val backend: String? = null,
    val isolation: IsolationDeclaration,
    val enabledProbePacks: List<String>,
    val buildId: String,
    val casePackId: String,
    val promptSha256: String? = null,
    val toolsetFingerprint: String,
)

internal fun validateDeclaredVariables(ids: List<String>): List<String> {
    val errors = mutableListOf<String>()
    val set = ids.toSet()
    if ("isolation" in set &&
        (set.contains("isolation.session") || set.contains("isolation.memory") || set.contains("isolation.cache"))
    ) {
        errors += "isolation conflicts with leaf isolation.* ids"
    }
    for (id in ids) {
        when {
            id in FORBIDDEN_VARIABLE_IDS -> errors += "forbidden variable id: $id"
            id !in ALLOWED_VARIABLE_IDS -> errors += "unknown variable id: $id"
        }
    }
    return errors
}

internal fun buildRunMeta(
    runId: String,
    sessionId: String,
    armId: String,
    buildId: String,
    startedAtEpochMillis: Long,
    provider: String,
    protocol: String,
    model: String,
    baseUrlHost: String,
    isolation: IsolationDeclaration,
    caseBinding: CaseBinding,
    enabledProbePacks: List<String>,
    toolset: List<ToolCatalogEntry>,
    declaredVariables: List<String> = emptyList(),
    modelRef: String? = null,
    profile: String? = null,
    variant: String? = null,
    searchMode: String? = null,
    fetchMode: String? = null,
    stream: Boolean? = null,
    backend: String? = null,
): RunMeta {
    val reasons = mutableListOf<String>()
    if (runId.isBlank()) reasons += "missing runId"
    if (sessionId.isBlank()) reasons += "missing sessionId"
    if (armId.isBlank()) reasons += "missing armId"
    if (buildId.isBlank()) reasons += "missing buildId"
    if (provider.isBlank()) reasons += "missing provider"
    if (protocol.isBlank()) reasons += "missing protocol"
    if (model.isBlank()) reasons += "missing model"
    if (baseUrlHost.isBlank()) reasons += "missing baseUrlHost"
    reasons += validateDeclaredVariables(declaredVariables)
    // A6: incomplete toolset fingerprints must fail closed (never comparable=true).
    for (tool in toolset) {
        if (tool.schemaDigest.isBlank() ||
            tool.schemaDigest == SCHEMA_DIGEST_ERROR ||
            tool.qualifiedName.isBlank()
        ) {
            reasons += "toolset incomplete: ${tool.qualifiedName.ifBlank { "<blank>" }} schemaDigest=${tool.schemaDigest.ifBlank { "<blank>" }}"
        }
    }
    if (caseBinding.casePackId != "none") {
        val sha = caseBinding.promptSha256
        if (sha.isNullOrBlank() || !PROMPT_SHA256_HEX.matches(sha.lowercase())) {
            reasons += "caseBinding.promptSha256 required (64 hex) when casePackId != none"
        }
    }

    val sortedPacks = enabledProbePacks.sorted()
    val sortedTools = toolset.sortedBy { it.qualifiedName }
    val toolsetFp = FingerprintCanonicalizer.toolsetFingerprint(sortedTools)
    val resolved = ResolvedConfigObject(
        provider = provider,
        protocol = protocol,
        model = model,
        modelRef = modelRef,
        profile = profile,
        variant = variant,
        baseUrlHost = baseUrlHost.lowercase(),
        searchMode = searchMode,
        fetchMode = fetchMode,
        stream = stream,
        backend = backend,
        isolation = isolation,
        enabledProbePacks = sortedPacks,
        buildId = buildId,
        casePackId = caseBinding.casePackId,
        promptSha256 = caseBinding.promptSha256,
        toolsetFingerprint = toolsetFp,
    )
    val configFp = try {
        FingerprintCanonicalizer.configFingerprint(resolved)
    } catch (e: Exception) {
        reasons += "configFingerprint error: ${e.message}"
        "error"
    }
    val controlFp = try {
        FingerprintCanonicalizer.controlFingerprint(resolved, declaredVariables)
    } catch (e: Exception) {
        reasons += "controlFingerprint error: ${e.message}"
        "error"
    }
    val comparable = reasons.isEmpty()
    return RunMeta(
        runId = runId,
        sessionId = sessionId,
        armId = armId,
        buildId = buildId,
        startedAtEpochMillis = startedAtEpochMillis,
        provider = provider,
        protocol = protocol,
        model = model,
        modelRef = modelRef,
        profile = profile,
        variant = variant,
        baseUrlHost = baseUrlHost.lowercase(),
        searchMode = searchMode,
        fetchMode = fetchMode,
        stream = stream,
        backend = backend,
        isolation = isolation,
        caseBinding = caseBinding,
        enabledProbePacks = sortedPacks,
        toolset = sortedTools,
        declaredVariables = declaredVariables.sorted(),
        configFingerprint = configFp,
        controlFingerprint = controlFp,
        toolsetFingerprint = toolsetFp,
        comparable = comparable,
        incompleteReasons = reasons,
    )
}

/**
 * Multi-arm R1–R8 comparability (SPEC-RPB-003).
 * Returns null if comparable; otherwise reason codes.
 */
internal fun compareArms(metas: List<RunMeta>): List<String> {
    if (metas.isEmpty()) return listOf("empty")
    val reasons = mutableListOf<String>()
    for (m in metas) {
        if (!m.comparable) reasons += "R1:${m.runId}:not_comparable"
    }
    val first = metas.first()
    val vars = first.declaredVariables.toSet()
    for (m in metas.drop(1)) {
        if (m.declaredVariables.toSet() != vars) reasons += "R5:${m.runId}:declaredVariables_mismatch"
        if (m.controlFingerprint != first.controlFingerprint) reasons += "R4:${m.runId}:controlFingerprint_mismatch"
        if ("casePackId" !in vars && "promptSha256" !in vars) {
            if (m.caseBinding.casePackId != first.caseBinding.casePackId ||
                m.caseBinding.promptSha256 != first.caseBinding.promptSha256
            ) {
                reasons += "R2:${m.runId}:case_mismatch"
            }
        }
        if ("isolation" !in vars &&
            "isolation.session" !in vars &&
            m.isolation.session != first.isolation.session
        ) {
            reasons += "R3:${m.runId}:isolation.session"
        }
        if ("isolation" !in vars &&
            "isolation.memory" !in vars &&
            m.isolation.memory != first.isolation.memory
        ) {
            reasons += "R3:${m.runId}:isolation.memory"
        }
        if ("isolation" !in vars &&
            "isolation.cache" !in vars &&
            m.isolation.cache != first.isolation.cache
        ) {
            reasons += "R3:${m.runId}:isolation.cache"
        }
        if ("toolsetFingerprint" !in vars && m.toolsetFingerprint != first.toolsetFingerprint) {
            reasons += "R7:${m.runId}:toolsetFingerprint"
        }
        if ("buildId" !in vars && m.buildId != first.buildId) {
            reasons += "R8:${m.runId}:buildId"
        }
    }
    return reasons.distinct()
}
