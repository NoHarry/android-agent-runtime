@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.vfs.VfsPathResolver
import cc.noharry.agent.vfs.VfsFileSystem
import cc.noharry.agent.vfs.VfsListResult
import cc.noharry.agent.vfs.VfsReadAttributes
import cc.noharry.agent.vfs.VfsReadResult
import cc.noharry.agent.vfs.VfsSearchResult
import cc.noharry.agent.kernel.api.WorkspaceCallContext
import cc.noharry.agent.kernel.api.WorkspaceExecutionResult
import cc.noharry.agent.kernel.api.WorkspaceExecutionTelemetry
import cc.noharry.agent.kernel.api.WorkspacePort
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import java.io.File
import java.util.concurrent.TimeUnit
import cc.noharry.agent.kernel.core.MaterializedEntry
import cc.noharry.agent.kernel.core.MaterializerContext
import cc.noharry.agent.kernel.core.McpToolSearchHit
import cc.noharry.agent.kernel.core.ToolActivationMetadata
import cc.noharry.agent.kernel.core.ToolActivationStore
import cc.noharry.agent.kernel.core.ToolProviderRegistry
import cc.noharry.agent.kernel.core.rankMcpTools
import cc.noharry.agent.kernel.core.OnDemandToolDiscovery
import cc.noharry.agent.kernel.core.LoopSessionRecorder
import cc.noharry.agent.kernel.core.toMaterializerContext

internal class VirtualWorkspace(
    private val fileSystem: VfsFileSystem,
    private val materializer: VirtualFileMaterializer? = null,
    private val mcpRegistry: ToolProviderRegistry? = null,
    private val activationStore: ToolActivationStore? = null,
    runId: String = DEFAULT_WORKSPACE_RUN_ID,
    private val sessionRecorder: LoopSessionRecorder = LoopSessionRecorder.noop(),
    private val onDemandDiscovery: OnDemandToolDiscovery = OnDemandToolDiscovery(),
    /** Backend tools are called through call_tool, so each match carries its schema instead of joining the tool list. */
    private val deferBackendTools: Boolean = false,
    /** Runs spawn_agent / wait_agent / cancel_agent when the host configured subagents. */
    private val subagentTools: (suspend (String, String, SubagentCall) -> String)? = null,
) : WorkspacePort {
    constructor(
        root: File,
        materializer: VirtualFileMaterializer? = null,
        runId: String = DEFAULT_WORKSPACE_RUN_ID,
    ) : this(VfsFileSystem.singleFileRoot(root), materializer, null, null, runId)

    private val readGuard = RuntimeVfsReadGuard()
    private val writer = VirtualWorkspaceWriter(fileSystem, runId, readGuard = readGuard)
    private val runtimeMounts = materializer?.let(::RuntimeVfsMounts)
    private val attachedFileSystems = linkedMapOf<MaterializerContextKey, VfsFileSystem>()

    override suspend fun execute(
        toolName: String,
        argumentsJson: String,
        context: WorkspaceCallContext,
    ): WorkspaceExecutionResult =
        executeWithTelemetry(toolName, argumentsJson, context.toMaterializerContext())

    suspend fun executeJson(
        toolName: String,
        argumentsJson: String,
        context: MaterializerContext = MaterializerContext(),
    ): String = executeWithTelemetry(toolName, argumentsJson, context).resultJson

    internal suspend fun executeWithTelemetry(
        toolName: String,
        argumentsJson: String,
        context: MaterializerContext = MaterializerContext(),
    ): WorkspaceExecutionResult =
        when (toolName) {
            "list_dir" -> listDir(argumentsJson, context)
            "read_file" -> readFile(argumentsJson, context)
            "grep" -> grep(argumentsJson, context)
            "search_tools" -> searchTools(argumentsJson, context)
            in SUBAGENT_TOOL_NAMES -> subagentTools?.let {
                WorkspaceExecutionResult(it(toolName, argumentsJson, SubagentCall(context.sessionId, context.runId, context.modelRef)))
            } ?: WorkspaceExecutionResult(errorResult(RuntimeErrorCode.UNSUPPORTED_NATIVE_TOOL.wireCode, "Subagents are not configured."))
            "write_file",
            "edit_file",
            -> WorkspaceExecutionResult(writer.execute(toolName, argumentsJson))
            else -> WorkspaceExecutionResult(
                errorResult(
                    RuntimeErrorCode.UNSUPPORTED_NATIVE_TOOL.wireCode,
                    "Unsupported native tool: $toolName",
                ),
            )
        }

    private suspend fun listDir(argumentsJson: String, context: MaterializerContext): WorkspaceExecutionResult {
        val path = canonicalReadablePath(stringField(argumentsJson, "path") ?: "/")
            ?: return WorkspaceExecutionResult(invalidPath())
        val pageSize = intField(argumentsJson, "page_size") ?: DEFAULT_PAGE_SIZE
        val resolveStart = System.nanoTime()
        val fileSystem = fileSystemFor(context)
        val resolveMs = elapsedMs(resolveStart)
        val listStart = System.nanoTime()
        val result = fileSystem.list(path)
        val listMs = elapsedMs(listStart)
        val serializeStart = System.nanoTime()
        val json = listVfsDir(result, pageSize)
        return WorkspaceExecutionResult(
            resultJson = json,
            telemetry = WorkspaceExecutionTelemetry(
                resolveDurationMs = resolveMs,
                materializeDurationMs = listMs,
                serializeDurationMs = elapsedMs(serializeStart),
            ),
        )
    }

    private fun listVfsDir(result: VfsListResult, pageSize: Int): String {
        val entries = when (result) {
            is VfsListResult.Entries -> result.entries.map { MaterializedEntry(it.name, it.type) }
            is VfsListResult.Error -> return vfsError(result.code, result.message, result.retryable)
            VfsListResult.InvalidPath -> return invalidPath()
            VfsListResult.NotFound -> return errorResult(RuntimeErrorCode.NOT_FOUND.wireCode, "Directory not found.")
        }.sortedBy { it.name }.take(pageSize)
        return """{"ok":true,"entries":${entriesJson(entries)}}"""
    }

    private suspend fun readFile(argumentsJson: String, context: MaterializerContext): WorkspaceExecutionResult {
        val path = canonicalReadablePath(stringField(argumentsJson, "path") ?: "")
            ?: return WorkspaceExecutionResult(invalidPath())
        val maxChars = intField(argumentsJson, "max_chars") ?: DEFAULT_MAX_CHARS
        if (maxChars <= 0 || maxChars > MAX_READ_CHARS) {
            return WorkspaceExecutionResult(
                errorResult(RuntimeErrorCode.READ_BUDGET_EXCEEDED.wireCode, "Read budget exceeded."),
            )
        }
        val offset = longField(argumentsJson, "offset") ?: 0L
        if (offset < 0) {
            return WorkspaceExecutionResult(
                errorResult(RuntimeErrorCode.READ_BUDGET_EXCEEDED.wireCode, "Invalid read offset."),
            )
        }
        val resolveStart = System.nanoTime()
        val fileSystem = fileSystemFor(context)
        val resolveMs = elapsedMs(resolveStart)
        val materializeStart = System.nanoTime()
        val result = fileSystem.read(path, offset, maxChars)
        val materializeMs = elapsedMs(materializeStart)
        val serializeStart = System.nanoTime()
        val json = readVfsFile(path, result, offset)
        val schemaCacheHit = (result as? VfsReadResult.Found)?.attributes?.booleans?.get("cache_hit")
        return WorkspaceExecutionResult(
            resultJson = json,
            telemetry = WorkspaceExecutionTelemetry(
                resolveDurationMs = resolveMs,
                materializeDurationMs = materializeMs,
                serializeDurationMs = elapsedMs(serializeStart),
                schemaCacheHit = schemaCacheHit,
            ),
        )
    }

    private fun readVfsFile(path: String, result: VfsReadResult, offset: Long): String =
        when (result) {
            is VfsReadResult.Found -> {
                val truncated = offset + result.bytesRead < result.totalBytes
                readGuard.record(path, result.revision, fullRead = offset == 0L && !truncated)
                """{"ok":true,"content":"${escape(result.text)}","truncated":$truncated${attributesJson(result.attributes)}}"""
            }
            is VfsReadResult.Error -> vfsError(result.code, result.message, result.retryable)
            VfsReadResult.InvalidPath -> invalidPath()
            VfsReadResult.BinaryUnsupported -> errorResult(
                RuntimeErrorCode.BINARY_FILE_UNSUPPORTED.wireCode,
                "Binary files are metadata-only in this scaffold.",
            )
            VfsReadResult.ReadDenied -> errorResult(
                RuntimeErrorCode.WORKSPACE_WRITE_DENIED.wireCode,
                "Workspace read denied.",
            )
            VfsReadResult.NotFound -> errorResult(RuntimeErrorCode.NOT_FOUND.wireCode, "File not found.")
        }

    private suspend fun grep(argumentsJson: String, context: MaterializerContext): WorkspaceExecutionResult {
        val pattern = stringField(argumentsJson, "pattern")
            ?: return WorkspaceExecutionResult(
                errorResult(RuntimeErrorCode.INVALID_GREP_PATTERN.wireCode, "Missing pattern."),
            )
        if (pattern.length > MAX_GREP_PATTERN_CHARS) {
            return WorkspaceExecutionResult(
                errorResult(RuntimeErrorCode.INVALID_GREP_PATTERN.wireCode, "Pattern is too long."),
            )
        }
        val path = canonicalReadablePath(stringField(argumentsJson, "path") ?: "/")
            ?: return WorkspaceExecutionResult(invalidPath())
        val maxMatches = intField(argumentsJson, "max_matches") ?: DEFAULT_MAX_MATCHES
        val resolveStart = System.nanoTime()
        val fileSystem = fileSystemFor(context)
        val resolveMs = elapsedMs(resolveStart)
        val scanStart = System.nanoTime()
        val result = fileSystem.search(path, pattern, maxMatches, MAX_GREP_SCAN_BYTES)
        val scanMs = elapsedMs(scanStart)
        val serializeStart = System.nanoTime()
        val json = grepResultJson(result)
        return WorkspaceExecutionResult(
            resultJson = json,
            telemetry = WorkspaceExecutionTelemetry(
                resolveDurationMs = resolveMs,
                scanDurationMs = scanMs,
                serializeDurationMs = elapsedMs(serializeStart),
            ),
        )
    }

    private suspend fun searchTools(argumentsJson: String, context: MaterializerContext): WorkspaceExecutionResult {
        val query = stringField(argumentsJson, "query")
            ?: return WorkspaceExecutionResult(
                errorResult(RuntimeErrorCode.INVALID_GREP_PATTERN.wireCode, "Missing query."),
            )
        if (query.isBlank()) {
            return WorkspaceExecutionResult(
                errorResult(RuntimeErrorCode.INVALID_GREP_PATTERN.wireCode, "Query is empty."),
            )
        }
        val maxResults = intField(argumentsJson, "max_results") ?: DEFAULT_SEARCH_TOOLS_RESULTS
        if (maxResults <= 0 || maxResults > MAX_SEARCH_TOOLS_RESULTS) {
            return WorkspaceExecutionResult(
                errorResult(RuntimeErrorCode.GREP_BUDGET_EXCEEDED.wireCode, "max_results out of range."),
            )
        }
        val registry = mcpRegistry
            ?: return WorkspaceExecutionResult(
                errorResult(RuntimeErrorCode.CAPABILITY_BACKEND_NOT_CONFIGURED.wireCode, "MCP registry is not configured."),
            )
        val resolveStart = System.nanoTime()
        val tools = registry.allTools(context)
        val resolveMs = elapsedMs(resolveStart)
        val scanStart = System.nanoTime()
        val local = rankMcpTools(tools, query, maxResults)
        val normalized = query.trim().replace('.', '_')
        val exact = local.any { normalized.equals(it.tool.originalToolName, true) || normalized.equals(it.tool.qualifiedName, true) }
        val remote = if (exact) emptyList() else onDemandDiscovery.select(
            query, context, registry, sessionRecorder,
            activationStore?.activated(context.runId)?.map { it.toolName }?.toSet().orEmpty(), maxResults,
        )
        val scored = (remote + local).distinctBy { it.tool.qualifiedName }.take(maxResults)
        val scanMs = elapsedMs(scanStart)
        // Activate matched tools so the model can call them in the *current*
        // round without an extra read_file(schema.json) round-trip.
        val callable = activateSearchHits(scored, context)
        val serializeStart = System.nanoTime()
        val json = searchToolsResultJson(scored, callable)
        return WorkspaceExecutionResult(
            resultJson = json,
            telemetry = WorkspaceExecutionTelemetry(
                resolveDurationMs = resolveMs,
                scanDurationMs = scanMs,
                serializeDurationMs = elapsedMs(serializeStart),
            ),
        )
    }

    /**
     * Returns the qualified names now in the model's tool list with their full input schema. Every match is activated:
     * activating only the top 5 (2026-10-17, tablet bench) meant more searches in later runs of the session, and each
     * one changes the tool list, so the session history after it is recomputed; the extra matches often serve a later
     * request (searching the AC temperature also brings the seat temperature).
     */
    private fun activateSearchHits(hits: List<McpToolSearchHit>, context: MaterializerContext): Set<String> {
        val store = activationStore ?: return emptySet()
        val activated = linkedSetOf<String>()
        for (hit in hits) {
            val schemaJson = hit.tool.descriptor.inputSchemaJson
            if (schemaJson.isNotBlank() && hit.tool.qualifiedName.isNotBlank()) {
                store.activate(
                    runId = context.runId,
                    tool = hit.tool,
                    schemaJson = schemaJson,
                    metadata = ToolActivationMetadata(source = SEARCH_TOOLS_ACTIVATION_SOURCE),
                )
                activated += hit.tool.qualifiedName
            }
        }
        return activated
    }

    /**
     * Callable matches carry no schema path: their full schema is already in the tool list, and pointing at
     * schema.json invited a redundant read round. Only matches that are not callable yet keep the path.
     */
    private fun searchToolsResultJson(hits: List<McpToolSearchHit>, callable: Set<String>): String {
        if (hits.isEmpty()) return """{"ok":true,"matches":[]}"""
        val entries = hits.joinToString(prefix = "[", postfix = "]") { hit ->
            val tool = hit.tool
            val desc = escape(tool.descriptor.description)
            val name = escape(tool.qualifiedName)
            val origName = escape(tool.originalToolName)
            val access = if (deferBackendTools && tool.qualifiedName in callable) {
                """"via":"call_tool","schema":${compactSchema(tool.descriptor.inputSchemaJson)}"""
            } else if (tool.qualifiedName in callable) {
                """"callable":true"""
            } else {
                """"schemaPath":"${escape("/mcp/tools/${tool.qualifiedName}/schema.json")}""""
            }
            """{"name":"$name","originalName":"$origName","description":"$desc","score":${hit.score},$access}"""
        }
        return """{"ok":true,"matches":$entries}"""
    }

    private fun grepResultJson(result: VfsSearchResult): String {
        val matches = when (result) {
            is VfsSearchResult.Matches -> result.matches
            is VfsSearchResult.Error -> return vfsError(result.code, result.message, result.retryable)
            VfsSearchResult.InvalidPath -> return invalidPath()
            VfsSearchResult.InvalidPattern -> return errorResult(
                RuntimeErrorCode.INVALID_GREP_PATTERN.wireCode,
                "Invalid grep pattern.",
            )
            VfsSearchResult.BudgetExceeded -> return errorResult(
                RuntimeErrorCode.GREP_BUDGET_EXCEEDED.wireCode,
                "Grep budget exceeded.",
            )
            VfsSearchResult.NotFound -> return errorResult(RuntimeErrorCode.NOT_FOUND.wireCode, "Path not found.")
        }.joinToString(prefix = "[", postfix = "]") { match ->
            """{"path":"${escape(match.path)}","line":${match.line},"preview":"${escape(match.preview.boundedGrepPreview())}"}"""
        }
        return """{"ok":true,"matches":$matches}"""
    }

    suspend fun cleanupScratch() {
        writer.cleanupScratch()
    }

    private fun fileSystemFor(context: MaterializerContext): VfsFileSystem {
        val mounts = runtimeMounts ?: return fileSystem
        val key = context.cacheKey()
        return synchronized(attachedFileSystems) {
            attachedFileSystems.getOrPut(key) {
                mounts.attach(fileSystem, context)
            }
        }
    }

    internal companion object {
        suspend fun cleanupScratch(root: File, runId: String) {
            VirtualWorkspaceWriter(VfsFileSystem.singleFileRoot(root), runId).cleanupScratch()
        }

        suspend fun cleanupScratch(fileSystem: VfsFileSystem, runId: String) {
            VirtualWorkspaceWriter(fileSystem, runId).cleanupScratch()
        }
    }
}

internal fun canonicalRuntimeWorkspacePath(path: String): String? {
    if (path.contains("..")) return null
    return VfsPathResolver.normalize(path)?.value
}

private fun canonicalReadablePath(path: String): String? =
    canonicalRuntimeWorkspacePath(path)?.takeUnless { it.startsWith(RUNTIME_TRACES_PREFIX) }

private data class MaterializerContextKey(
    val sessionId: String,
    val runId: String,
    val requestId: String,
    val scene: String?,
    val modelRef: String?,
)

private fun MaterializerContext.cacheKey(): MaterializerContextKey =
    MaterializerContextKey(
        sessionId = sessionId,
        runId = runId,
        requestId = requestId,
        scene = scene,
        modelRef = modelRef?.wireValue(),
    )

private fun stringField(json: String, name: String): String? =
    Regex(""""$name"\s*:\s*"((?:\\.|[^"])*)"""")
        .find(json)
        ?.groupValues
        ?.get(1)
        ?.unescapeJsonString()

private fun intField(json: String, name: String): Int? =
    Regex(""""$name"\s*:\s*(\d+)""")
        .find(json)
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()

private fun longField(json: String, name: String): Long? =
    Regex(""""$name"\s*:\s*(\d+)""")
        .find(json)
        ?.groupValues
        ?.get(1)
        ?.toLongOrNull()

private fun errorResult(code: String, message: String): String =
    """{"ok":false,"code":"$code","message":"${escape(message)}"}"""

private fun invalidPath(): String =
    errorResult(RuntimeErrorCode.INVALID_WORKSPACE_PATH.wireCode, "Invalid workspace path.")

private fun vfsError(code: String, message: String, retryable: Boolean): String =
    """{"ok":false,"code":"$code","message":"${escape(message)}","retryable":$retryable}"""

private fun attributesJson(attributes: VfsReadAttributes): String =
    attributes.booleans.entries.joinToString("") { (key, value) -> ""","${escape(key)}":$value""" } +
        attributes.strings.entries.joinToString("") { (key, value) -> ""","${escape(key)}":"${escape(value)}"""" } +
        attributes.longs.entries.joinToString("") { (key, value) -> ""","${escape(key)}":$value""" }

private fun entriesJson(entries: List<MaterializedEntry>): String =
    entries.joinToString(prefix = "[", postfix = "]") { entry ->
        """{"name":"${escape(entry.name)}","type":"${escape(entry.type)}"}"""
    }

private fun escape(value: String): String {
    var builder: StringBuilder? = null
    value.forEachIndexed { index, char ->
        val replacement = when (char) {
            '\\' -> """\\"""
            '"' -> "\\\""
            '\n' -> "\\n"
            else -> null
        }
        if (replacement != null) {
            if (builder == null) {
                builder = StringBuilder(value.length + JSON_ESCAPE_EXTRA_CAPACITY).append(value, 0, index)
            }
            builder?.append(replacement)
        } else {
            builder?.append(char)
        }
    }
    return builder?.toString() ?: value
}

private fun String.boundedGrepPreview(): String =
    if (length <= MAX_GREP_PREVIEW_CHARS) {
        this
    } else {
        take(MAX_GREP_PREVIEW_CHARS) + GREP_PREVIEW_TRUNCATED_SUFFIX
    }

private fun elapsedMs(startNanos: Long): Long =
    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos).coerceAtLeast(0L)

private fun String.unescapeJsonString(): String =
    replace("\\\"", "\"").replace("\\\\", "\\")

private const val DEFAULT_PAGE_SIZE = 50
private const val DEFAULT_MAX_CHARS = 8000
private const val DEFAULT_MAX_MATCHES = 100
private const val DEFAULT_SEARCH_TOOLS_RESULTS = 10
private const val MAX_SEARCH_TOOLS_RESULTS = 50

private fun compactSchema(schemaJson: String): String =
    runCatching { kotlinx.serialization.json.Json.parseToJsonElement(schemaJson).toString() }.getOrDefault("{}")
private const val DEFAULT_WORKSPACE_RUN_ID = "default"
private const val SEARCH_TOOLS_ACTIVATION_SOURCE = "search_tools"
private const val RUNTIME_TRACES_PREFIX = "/traces"
private const val MAX_GREP_PREVIEW_CHARS = 300
private const val JSON_ESCAPE_EXTRA_CAPACITY = 16
private const val GREP_PREVIEW_TRUNCATED_SUFFIX = "... [truncated]"
