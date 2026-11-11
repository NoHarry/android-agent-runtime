package cc.noharry.agent.appserver.protocol.view

import java.util.Locale
import cc.noharry.agent.appserver.protocol.TrajectoryRecordId
import cc.noharry.agent.appserver.protocol.TrajectoryRecord
import cc.noharry.agent.appserver.protocol.TrajectoryRunGroup

/**
 * Pure search over the display-safe fields of the current snapshot.
 *
 * The index only sees what the ledger and inspector are already allowed to render: title,
 * summary, safe metadata and redacted rich previews. Hashes and hidden raw data are never
 * indexed, so a query can never surface something the UI would refuse to show.
 */
fun TrajectoryRecord.searchIndexText(shownTitle: String = title): String =
    buildString {
        append(title)
        append(' ')
        // The title as the screen words it, so what is read there is found too.
        if (shownTitle != title) append(shownTitle).append(' ')
        append(summary)
        safeMetadata.forEach { (key, value) ->
            append(' ')
            append(key)
            append('=')
            append(value)
        }
        rich?.let { details ->
            listOfNotNull(
                details.inputPreview,
                details.payloadPreview,
                details.resultPreview,
                details.schemaPreview,
                details.requestText,
                details.thinkingText,
                details.outputText,
                details.usageText,
            ).forEach { preview ->
                append(' ')
                append(preview)
            }
        }
    }.lowercase(Locale.ROOT)

/** Whitespace-separated tokens with AND semantics; a blank query matches everything. */
fun trajectorySearchTokens(query: String): List<String> =
    query.lowercase(Locale.ROOT).split(WHITESPACE).filter { it.isNotBlank() }

fun TrajectoryRecord.matchesTrajectorySearch(tokens: List<String>, shownTitle: String = title): Boolean {
    if (tokens.isEmpty()) return true
    val indexed = searchIndexText(shownTitle)
    return tokens.all { indexed.contains(it) }
}

/**
 * Hit ids in ledger order. Non-matching rows keep their place in the list: search highlights
 * and lets the user step between hits, it never destructively filters the trajectory.
 */
fun trajectorySearchHits(
    groups: List<TrajectoryRunGroup>,
    query: String,
    shownTitle: (TrajectoryRecord) -> String = { it.title },
): List<TrajectoryRecordId> {
    val tokens = trajectorySearchTokens(query)
    if (tokens.isEmpty()) return emptyList()
    return groups.flatMap { group -> group.records }
        .filter { it.matchesTrajectorySearch(tokens, shownTitle(it)) }
        .map { it.id }
}

/** Cyclic next/previous stepping so long trajectories stay navigable from either end. */
fun nextTrajectoryHit(
    hits: List<TrajectoryRecordId>,
    current: TrajectoryRecordId?,
    forward: Boolean = true,
): TrajectoryRecordId? {
    if (hits.isEmpty()) return null
    val index = current?.let(hits::indexOf) ?: -1
    if (index < 0) return if (forward) hits.first() else hits.last()
    val next = if (forward) index + 1 else index - 1
    return hits[(next + hits.size) % hits.size]
}

private val WHITESPACE = Regex("""\s+""")
