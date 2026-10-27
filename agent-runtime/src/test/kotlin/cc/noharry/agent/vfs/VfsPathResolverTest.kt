package cc.noharry.agent.vfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VfsPathResolverTest {
    @Test
    fun normalizesAbsoluteLinuxLikePaths() {
        val path = VfsPathResolver.normalize("//.agents//skills/demo/SKILL.md")

        assertEquals("/.agents/skills/demo/SKILL.md", path?.value)
        assertEquals(listOf(".agents", "skills", "demo", "SKILL.md"), path?.segments)
    }

    @Test
    fun rejectsEscapesAndHostSpecificPaths() {
        assertNull(VfsPathResolver.normalize("../secret"))
        assertNull(VfsPathResolver.normalize("/../secret"))
        assertNull(VfsPathResolver.normalize("/tmp\\secret"))
        assertNull(VfsPathResolver.normalize("file:///tmp/secret"))
    }

    @Test
    fun resolveInsideRejectsCanonicalSymlinkEscape() {
        val root = Files.createTempDirectory("agent-vfs-root-").toFile().canonicalFile
        val outside = File(root.parentFile, "${root.name}-outside").canonicalFile
        outside.mkdirs()
        File(outside, "secret.txt").writeText("secret")
        val link = root.resolve("outside-link").toPath()
        try {
            Files.createSymbolicLink(link, outside.toPath())
        } catch (_: Exception) {
            return
        }

        val resolved = VfsPathResolver.resolveInside(root, "/outside-link/secret.txt")

        assertNull(resolved)
    }

    @Test
    fun resolveInsideAllowsRegularDescendant() {
        val root = Files.createTempDirectory("agent-vfs-root-").toFile().canonicalFile
        val file = File(root, "notes.txt").apply { writeText("ok") }

        val resolved = VfsPathResolver.resolveInside(root, "/notes.txt")

        assertTrue(resolved?.canonicalFile == file.canonicalFile)
    }
}
