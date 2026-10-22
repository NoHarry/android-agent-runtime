package cc.noharry.agent.release

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExecutePlanCompatibilityDeletedInventoryTest {
    @Test
    fun productionCatalogHasNoExecutePlanCompatibilityPath() {
        val roots = listOf(
            File("../agent-kernel-api/src/main"),
            File("../agent-kernel-core/src/main"),
            File("src/main"),
            File("../agent-minimal/src/main"),
            File("src/test/resources/fixtures/protocol"),
        )
        roots.forEach { root ->
            assertTrue("scan root must exist: ${root.canonicalPath}", root.isDirectory)
        }
        val leftoverPackages = roots.flatMap { root ->
            listOf(
                File(root, "kotlin/cc/noharry/agent/plan"),
                File(root, "java/cc/noharry/agent/plan"),
            )
        }.filter { it.exists() }
            .map { "plan package remains: ${it.path.replace('\\', '/')}" }
        val hits = leftoverPackages + roots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension in SCANNED_EXTENSIONS }
                .flatMap { file -> forbiddenHits(file) }
        }
        assertEquals(emptyList<String>(), hits)
    }

    private fun forbiddenHits(file: File): List<String> {
        val relative = file.path.replace('\\', '/')
        return file.readLines().mapIndexedNotNull { index, raw ->
            val hit = FORBIDDEN_TOKENS.firstOrNull { token -> raw.contains(token) } ?: return@mapIndexedNotNull null
            "$relative:${index + 1}:$hit"
        }
    }
}

private val SCANNED_EXTENSIONS = setOf("kt", "java", "json", "md", "txt", "xml")

private val FORBIDDEN_TOKENS = listOf(
    "execute_plan",
    "COMPAT_PLAN",
    "PlanExecutor",
    "PendingPlan",
)
