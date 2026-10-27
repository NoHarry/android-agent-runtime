package cc.noharry.agent.runtime

import cc.noharry.agent.vfs.VfsFileSystem
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** The skill catalog's names and descriptions, read from each SKILL.md header in the workspace. */
class SkillRegistryTest {
    @Test
    fun theDescriptionIsTheHeadersOwnNotAMetadataEntrys() = runBlocking {
        val root = Files.createTempDirectory("skill-registry").toFile()
        File(root, ".agents/skills/car/SKILL.md").apply { parentFile.mkdirs() }.writeText(
            """
            ---
            name: car
            description: 'Reads and sets the car.'
            metadata:
              description: the author's note, not the skill's
              version: "1.0.0"
            ---

            # Car
            """.trimIndent(),
        )

        val catalog = SkillRegistry(emptyList(), VfsFileSystem.singleFileRoot(root)).catalog(scene = null)

        assertEquals(listOf("car" to "Reads and sets the car."), catalog.skills.map { it.name to it.description })
    }
}
