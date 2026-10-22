package cc.noharry.agent.vfs

/**
 * Priority-ordered static and dynamic mount resolver.
 *
 * Higher priority wins. For equal priority, the most specific target path wins.
 */
class VfsMountTable(mounts: List<VfsMount>) {
    private val orderedMounts = mounts.sortedWith(
        compareByDescending<VfsMount> { it.priority }
            .thenByDescending { it.target.value.length }
            .thenBy { it.id },
    )

    /** Resolves a path to the mount that should serve it. */
    fun resolve(path: String, forWrite: Boolean = false): VfsResolvedMount? {
        return resolveAll(path, forWrite).firstOrNull()
    }

    /** Resolves every matching mount in priority order. */
    fun resolveAll(path: String, forWrite: Boolean = false): List<VfsResolvedMount> {
        val normalized = VfsPathResolver.normalize(path) ?: return emptyList()
        return orderedMounts.filter { mount ->
            mount.contains(normalized) &&
                (!forWrite || mount.mode == VfsMountMode.READ_WRITE)
        }.map { mount -> mount.toResolved(normalized) }
    }

    /** Resolves mounts visible from a subtree path in priority order. */
    fun resolveVisible(path: String): List<VfsResolvedMount> {
        val normalized = VfsPathResolver.normalize(path) ?: return emptyList()
        return orderedMounts.filter { mount ->
            mount.contains(normalized) || pathContains(normalized, mount.target)
        }.map { mount -> mount.toVisibleResolved(normalized) }
    }

    /** Returns all configured mounts in resolver order. */
    fun mounts(): List<VfsMount> = orderedMounts
}

private fun VfsMount.contains(path: VfsPath): Boolean =
    pathContains(target, path)

private fun VfsMount.toResolved(path: VfsPath): VfsResolvedMount =
    VfsResolvedMount(mount = this, path = path, relativePath = relativePath(target, path))

private fun VfsMount.toVisibleResolved(path: VfsPath): VfsResolvedMount =
    VfsResolvedMount(
        mount = this,
        path = path,
        relativePath = if (contains(path)) relativePath(target, path) else "",
    )

private fun pathContains(target: VfsPath, path: VfsPath): Boolean =
    target.value == ROOT_PATH ||
        path.value == target.value ||
        path.value.startsWith(target.value + ROOT_PATH)

private fun relativePath(target: VfsPath, path: VfsPath): String =
    if (target.value == ROOT_PATH) {
        path.value.removePrefix(ROOT_PATH)
    } else {
        path.value.removePrefix(target.value).trimStart('/')
    }

/** Result of resolving a VFS path to a mount. */
data class VfsResolvedMount(
    val mount: VfsMount,
    val path: VfsPath,
    val relativePath: String,
)

private const val ROOT_PATH = "/"
