package cc.noharry.agent.vfs

import java.io.File

/**
 * Normalized absolute path inside the agent-visible virtual workspace.
 *
 * This type is intentionally semantic-free: it does not know about AGENTS.md,
 * skills, MCP, sessions, or model behavior.
 */
data class VfsPath(val value: String) {
    /** Path segments without the leading slash. */
    val segments: List<String>
        get() = value.trim('/').split('/').filter { it.isNotBlank() }
}

/**
 * Path normalization and host-file containment helpers for virtual workspace paths.
 *
 * The resolver only enforces Linux-like absolute path shape and canonical host
 * containment. Runtime-specific policy remains in the caller.
 */
object VfsPathResolver {
    /** Returns a normalized absolute VFS path, or null when the path is invalid. */
    fun normalize(path: String): VfsPath? {
        if (!isShapeAllowed(path)) return null
        val segments = path.split('/').filter { it.isNotBlank() }
        if (segments.any { it == "." || it == ".." }) return null
        val normalized = if (segments.isEmpty()) {
            ROOT_PATH
        } else {
            segments.joinToString(separator = ROOT_PATH, prefix = ROOT_PATH)
        }
        return VfsPath(normalized)
    }

    /** Resolves a virtual path against a host root and rejects canonical escapes. */
    fun resolveInside(root: File, path: String): File? {
        val normalized = normalize(path) ?: return null
        val canonicalRoot = root.canonicalFile
        val relative = normalized.value.removePrefix(ROOT_PATH)
        val candidate = canonicalRoot.resolve(relative).canonicalFile
        return candidate.takeIf { it.toPath().startsWith(canonicalRoot.toPath()) }
    }

    private fun isShapeAllowed(path: String): Boolean =
        path.startsWith(ROOT_PATH) &&
            !path.contains(BACKSLASH) &&
            !path.contains(NULL_CHAR) &&
            !path.contains(SCHEME_SEPARATOR)
}

private const val ROOT_PATH = "/"
private const val BACKSLASH = "\\"
private const val NULL_CHAR = "\u0000"
private const val SCHEME_SEPARATOR = "://"
