@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.trajectory

internal const val TRAJECTORY_STATUS_UNKNOWN = "UNKNOWN"

/** Bounded limits for the in-memory live projection; eviction never touches the EventLog. */
internal const val TRAJECTORY_INITIAL_VISIBLE_RUNS = 6
internal const val TRAJECTORY_OLDER_RUN_PAGE = 6
internal const val TRAJECTORY_MAX_LIVE_RECORDS_PER_RUN = 1000
internal const val TRAJECTORY_SUMMARY_PREVIEW_CHARS = 2000
internal const val TRAJECTORY_RICH_PREVIEW_CHARS = 12000
internal const val TRAJECTORY_PUBLISH_BATCH_MS = 50L
