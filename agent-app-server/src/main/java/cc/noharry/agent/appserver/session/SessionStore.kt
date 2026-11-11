@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.session
import android.content.Context
import cc.noharry.agent.appserver.evidence.RAW_STATUS_FAILED
import cc.noharry.agent.appserver.evidence.RAW_STATUS_OK
import cc.noharry.agent.appserver.evidence.RAW_STATUS_UNKNOWN
import cc.noharry.agent.appserver.protocol.ConversationItem
import cc.noharry.agent.appserver.protocol.NEW_SESSION_TITLE
import cc.noharry.agent.appserver.protocol.SessionSummary
import cc.noharry.agent.appserver.protocol.SessionToolCall
import cc.noharry.agent.appserver.protocol.SessionTurn
import cc.noharry.agent.appserver.protocol.ToolCallStatus
import cc.noharry.agent.appserver.protocol.TranscriptRole
import cc.noharry.agent.appserver.protocol.UNTITLED_SESSION_TITLE
import cc.noharry.agent.appserver.runtime.appWorkspaceRoot
import cc.noharry.agent.appserver.runtime.append
import cc.noharry.agent.appserver.runtime.encodeSessionId
import cc.noharry.agent.spi.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.spi.reliability.RuntimeCompletionSummary
import cc.noharry.agent.spi.reliability.RuntimeRunStatus
import java.io.File

internal class SessionStore(private val root: File) {
    init {
        root.mkdirs()
        refreshQueryFiles()
    }

    internal fun ensureSession(sessionId: String = newSessionId(), title: String = NEW_SESSION_TITLE): SessionSummary {
        readSummary(root, sessionId)?.let { return it }
        return ensureSessionDir(root, sessionId, title).also { refreshQueryFiles() }
    }

    internal fun listSessions(): List<SessionSummary> =
        root.listFiles { file -> file.isDirectory }.orEmpty()
            .mapNotNull { dir -> File(dir, META_FILE).takeIf { it.isFile }?.readText()?.let(AppSessionJson::decodeSummary) }
            .sortedByDescending { it.updatedAtEpochMillis }

    internal fun readContext(sessionId: String, limit: Int = RECENT_TURN_LIMIT): SessionContext {
        val summary = ensureSession(sessionId)
        val turns = readTurns(sessionId).takeLast(limit)
        return SessionContext(summary, turns)
    }

    fun readTurns(sessionId: String): List<SessionTurn> {
        val file = turnsFile(root, sessionId)
        if (!file.isFile) return emptyList()
        return file.readLines(Charsets.UTF_8).mapNotNull(AppSessionJson::decodeTurn)
    }

    fun appendTurn(turn: SessionTurn): SessionSummary =
        appendSessionTurn(root, turn, readTurns(turn.sessionId)).also { refreshQueryFiles() }

    internal fun deleteSession(sessionId: String): Boolean {
        val deleted = sessionDir(root, sessionId).takeIf { it.exists() }?.deleteRecursively() == true
        refreshQueryFiles()
        return deleted
    }

    private fun refreshQueryFiles() {
        publishSessionQueryFiles(root, listSessions(), ::readTurns)
    }
}

private fun ensureSessionDir(root: File, sessionId: String, title: String): SessionSummary {
    readSummary(root, sessionId)?.let { return it }
    val summary = SessionSummary(sessionId, title, now(), turnCount = 0, compactedSummary = "")
    writeSummary(root, summary)
    return summary
}

private fun appendSessionTurn(
    root: File,
    turn: SessionTurn,
    turnsBeforeAppend: List<SessionTurn>,
): SessionSummary {
    val dir = sessionDir(root, turn.sessionId).apply { mkdirs() }
    File(dir, TURNS_FILE).appendText(AppSessionJson.encodeTurn(turn) + "\n", Charsets.UTF_8)
    val turns = turnsBeforeAppend + turn
    val previous = readSummary(root, turn.sessionId)
    val title = previous?.title?.takeIf { it != NEW_SESSION_TITLE } ?: turn.userText.titleFromUserText()
    val summary = SessionSummary(turn.sessionId, title, turn.completedAtEpochMillis, turns.size, compactTurns(turns))
    writeSummary(root, summary)
    return summary
}

private fun writeSummary(root: File, summary: SessionSummary) {
    val dir = sessionDir(root, summary.sessionId).apply { mkdirs() }
    File(dir, META_FILE).writeText(AppSessionJson.encodeSummary(summary), Charsets.UTF_8)
}

private fun readSummary(root: File, sessionId: String): SessionSummary? {
    val file = File(sessionDir(root, sessionId), META_FILE)
    return if (file.isFile) AppSessionJson.decodeSummary(file.readText(Charsets.UTF_8)) else null
}

private fun turnsFile(root: File, sessionId: String): File = File(sessionDir(root, sessionId), TURNS_FILE)

private fun sessionDir(root: File, sessionId: String): File = File(root, encodeSessionId(sessionId))

internal fun SessionStore.transcriptItems(sessionId: String): List<ConversationItem> =
    readTurns(sessionId).flatMapIndexed { index, turn -> turn.toConversationItems(index + 1) }

internal fun SessionStore.modelContext(sessionId: String): String {
    val context = readContext(sessionId)
    val semanticTurns = context.recentTurns.filter { it.isSemanticConversationHistory() }
    // Only the latest turn, even a failed or cancelled one, can hold an offer a bare reply may accept.
    val latestTurnId = context.recentTurns.lastOrNull()?.turnId
    val recentLines = semanticTurns.asReversed().map { turn -> turn.semanticContextLine(offerIsCurrent = turn.turnId == latestTurnId) }
    val lastAction = context.recentTurns.filter { it.isFirstPartyReusableHistory() }
        .asReversed().flatMap { it.tools.asReversed() }
        .firstOrNull { it.isSuccessfulMeaningfulAction() }
    val compactedSummary = compactTurns(readTurns(sessionId)).ifBlank { "none" }
    return boundedModelContext(
        sessionId = context.summary.sessionId,
        lastSuccessfulAction = lastAction?.toolName ?: "none",
        recentLines = recentLines,
        compactedSummary = compactedSummary,
    )
}

private fun boundedModelContext(
    sessionId: String,
    lastSuccessfulAction: String,
    recentLines: List<String>,
    compactedSummary: String,
): String {
    val output = mutableListOf<String>()
    var usedBytes = 0
    fun append(line: String): Boolean {
        val addedBytes = line.toByteArray(Charsets.UTF_8).size + if (output.isEmpty()) 0 else 1
        if (usedBytes + addedBytes > MAX_DEBUG_SESSION_CONTEXT_BYTES) return false
        output += line
        usedBytes += addedBytes
        return true
    }
    listOf(
        "Current debug session:",
        "sessionId=$sessionId",
        "historyPolicy=conversation semantics only; action success requires COMPLETED plus SUCCEEDED",
        OFFER_POLICY,
        "lastSuccessfulAction=$lastSuccessfulAction",
        "recentTurnsNewestFirst:",
    ).forEach { check(append(it)) }
    for (line in recentLines.ifEmpty { listOf("- none") }) {
        if (!append(line)) break
    }
    if (append("olderConversationSummary:")) {
        for (line in compactedSummary.lineSequence()) {
            if (!append(line)) break
        }
    }
    return output.joinToString("\n")
}

private fun compactTurns(turns: List<SessionTurn>): String {
    val older = turns.dropLast(RECENT_TURN_LIMIT).filter { it.isSemanticConversationHistory() }
    if (older.isEmpty()) return ""
    return older.takeLast(COMPACTED_TURN_LIMIT).joinToString("\n") { turn ->
        val lastTool = turn.tools.lastOrNull()?.toolName ?: "none"
        "- ${turn.userText.oneLine()} -> $lastTool -> ${turn.finalResponse.oneLine()}"
    }.take(MAX_COMPACTED_CHARS)
}

private fun SessionTurn.semanticContextLine(offerIsCurrent: Boolean): String {
    val actionEvidence = if (isFirstPartyReusableHistory()) {
        tools.filter { it.isSuccessfulMeaningfulAction() }
            .joinToString(", ") { "${it.toolName}:${it.status}" }
            .ifBlank { "none" }
            .contextPreview(MAX_CONTEXT_ACTION_EVIDENCE_CHARS)
    } else {
        "unverified"
    }
    return "- user=${userText.contextPreview(MAX_CONTEXT_USER_CHARS)} | " +
        "assistant=${finalResponse.contextPreview(MAX_CONTEXT_ASSISTANT_CHARS)} | " +
        "runStatus=$finalStatus | completion=$completionOutcome | replay=$replayStatus | " +
        "offers=${if (offerIsCurrent) "current" else "superseded"} | successfulActionEvidence=$actionEvidence"
}

private fun SessionToolCall.isSuccessfulMeaningfulAction(): Boolean =
    status == RAW_STATUS_OK && isMeaningfulAction()

private fun String.contextPreview(maxChars: Int): String {
    val normalized = oneLine()
    if (normalized.length <= maxChars) return normalized
    val suffixChars = maxChars / 2
    val prefixChars = maxChars - suffixChars - CONTEXT_ELLIPSIS.length
    return normalized.take(prefixChars) + CONTEXT_ELLIPSIS + normalized.takeLast(suffixChars)
}

internal fun SessionTurn.isSemanticConversationHistory(): Boolean {
    val status = runCatching { RuntimeRunStatus.valueOf(finalStatus) }.getOrNull() ?: return false
    val outcome = runCatching { RuntimeCompletionOutcome.valueOf(completionOutcome) }
        .getOrDefault(RuntimeCompletionOutcome.UNKNOWN)
    // A turn that stopped to ask is conversation too: its question is what the next short reply answers.
    return finalResponse.isNotBlank() && (
        status == RuntimeRunStatus.WAITING_USER_INPUT ||
            status == RuntimeRunStatus.COMPLETED && outcome != RuntimeCompletionOutcome.UNKNOWN
        )
}

internal fun SessionTurn.isFirstPartyReusableHistory(): Boolean {
    val status = runCatching { RuntimeRunStatus.valueOf(finalStatus) }.getOrNull() ?: return false
    val outcome = runCatching { RuntimeCompletionOutcome.valueOf(completionOutcome) }
        .getOrDefault(RuntimeCompletionOutcome.UNKNOWN)
    return RuntimeCompletionSummary.firstPartyPass(status, RuntimeCompletionSummary(outcome = outcome))
}

internal fun sessionStore(context: Context): SessionStore =
    SessionStore(File(appWorkspaceRoot(context), SESSIONS_DIR))

internal fun newSessionId(): String = "debug-session-${System.currentTimeMillis()}"

private fun SessionTurn.toConversationItems(turnIndex: Int): List<ConversationItem> {
    val baseId = turnIndex * PERSISTED_ITEM_ID_BLOCK
    return listOf(
            ConversationItem.TurnHeader(
                id = baseId.toLong(),
                label = turnLabel(userText),
                preview = userText.take(TURN_PREVIEW_CHARS).replace("\n", " "),
                fullText = userText,
            ),
    ) + tools.map { tool ->
        ConversationItem.ToolCallItem(
            id = (baseId + tool.sequence).toLong(),
            toolCallId = tool.toolCallId,
            toolName = tool.toolName,
            argumentsJson = tool.argumentsPreview,
            resultJson = tool.resultPreview,
            status = tool.status.toToolStatus(),
            startedAt = startedAtEpochMillis,
            finishedAt = completedAtEpochMillis,
        )
    } + finalResponseConversationItems(baseId, finalStatus, finalResponse)
}

private fun finalResponseConversationItems(
    baseId: Int,
    finalStatus: String,
    finalResponse: String,
): List<ConversationItem> =
    listOfNotNull(
        // Stored as the model wrote it; shown, like the live reply, without the voice's progress lines.
        finalResponse.trim().takeIf { it.isNotBlank() }?.let { response ->
            ConversationItem.MessageItem(
                id = (baseId + PERSISTED_FINAL_RESPONSE_OFFSET).toLong(),
                role = TranscriptRole.ASSISTANT,
                content = response.trimForDisplay(),
                final = true,
            )
        },
        ConversationItem.RuntimePhaseItem(
            id = (baseId + PERSISTED_RESULT_OFFSET).toLong(),
            title = "Runtime result $finalStatus",
            body = finalResponse,
            markdown = true,
        ),
    )

private fun String.toToolStatus(): ToolCallStatus =
    when (this) {
        RAW_STATUS_OK -> ToolCallStatus.DONE
        RAW_STATUS_FAILED -> ToolCallStatus.FAILED
        RAW_STATUS_UNKNOWN -> ToolCallStatus.UNKNOWN
        else -> ToolCallStatus.UNKNOWN
    }

private fun String.titleFromUserText(): String =
    oneLine().take(SESSION_TITLE_CHARS).ifBlank { UNTITLED_SESSION_TITLE }

private fun String.oneLine(): String =
    replace("\n", " ").replace(Regex("""\s+"""), " ").trim()

private fun now(): Long = System.currentTimeMillis()

/** The workspace's directory of the sessions' records, which the model reads as `/sessions` ([SESSION_QUERY_INDEX_PATH]). */
internal const val SESSIONS_DIR = "sessions"
private const val META_FILE = "meta.json"
internal const val TURNS_FILE = "turns.jsonl"
private const val OFFER_POLICY =
    "offerPolicy=a bare reply may accept only an offer in the turn marked offers=current; offers in superseded turns " +
        "and olderConversationSummary were replaced by later turns and need a new explicit request"
private const val RECENT_TURN_LIMIT = 6
private const val COMPACTED_TURN_LIMIT = 20
private const val MAX_COMPACTED_CHARS = 1800
internal const val MAX_DEBUG_SESSION_CONTEXT_BYTES = 8 * 1024
private const val MAX_CONTEXT_USER_CHARS = 160
private const val MAX_CONTEXT_ASSISTANT_CHARS = 360
private const val MAX_CONTEXT_ACTION_EVIDENCE_CHARS = 480
private const val CONTEXT_ELLIPSIS = " … "
private const val SESSION_TITLE_CHARS = 32
/** Stored titles that stand for "no title yet"; the screens show them in their own language ([sessionTitleText]). */
private const val TURN_PREVIEW_CHARS = 60
private const val PERSISTED_ITEM_ID_BLOCK = 1000
private const val PERSISTED_FINAL_RESPONSE_OFFSET = 899
private const val PERSISTED_RESULT_OFFSET = 900
