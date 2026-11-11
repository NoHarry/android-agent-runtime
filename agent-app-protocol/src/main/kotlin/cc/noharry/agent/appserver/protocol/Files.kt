package cc.noharry.agent.appserver.protocol

import cc.noharry.agent.appserver.protocol.jsonrpc.RequestMethod
import kotlinx.serialization.Serializable

/** One entry of a workspace folder. */
@Serializable
data class FileEntryView(val name: String, val directory: Boolean, val sizeBytes: Long? = null)

/**
 * A workspace folder as the agent sees it: its [entries], or none because the path names nothing ([missing]: not
 * there, or not a valid path) or the folder could not be read ([error], why).
 */
@Serializable
data class FileListResult(
    val entries: List<FileEntryView> = emptyList(),
    val missing: Boolean = false,
    val error: String? = null,
)

@Serializable
data class PathParams(val path: String)

/** A file's start, up to [maxBytes] (the server reads at most [MAX_FILE_READ_BYTES]). */
@Serializable
data class FileReadParams(val path: String, val maxBytes: Int = MAX_FILE_READ_BYTES)

/** A file's text, or null when it could not be read as text. */
@Serializable
data class FileReadResult(val text: String? = null)

@Serializable
data class FileMountsResult(val targets: List<String> = listOf("/"))

/** The agent's workspace, for the console's file browser. */
object FileRequests {
    /** The folders mounted in the workspace, for shortcuts; "/" at least. */
    val MOUNTS = RequestMethod("files/mounts", Empty.serializer(), FileMountsResult.serializer())
    val LIST = RequestMethod("files/list", PathParams.serializer(), FileListResult.serializer())
    val READ = RequestMethod("files/read", FileReadParams.serializer(), FileReadResult.serializer())
}

const val MAX_FILE_READ_BYTES = 512 * 1024
