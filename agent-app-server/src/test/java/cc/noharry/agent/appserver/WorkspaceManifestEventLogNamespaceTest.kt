package cc.noharry.agent.appserver
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceManifestEventLogNamespaceTest {
    @Test
    fun manifestDoesNotShadowLegacyEventLogSessionsNamespace() {
        val mounts = parseMounts(readWorkspaceManifest())
        val sessionIndexMount = mounts.single { it.source == DEBUG_SESSION_INDEX_SOURCE }
        assertEquals(DEBUG_SESSION_INDEX_TARGET, sessionIndexMount.target)
        mounts.forEach { mount ->
            assertFalse(
                "Sample mount ${mount.source} -> ${mount.target} shadows /sessions/**/events.jsonl",
                mount.target == "/sessions" || mount.target.startsWith("/sessions/"),
            )
        }
    }

    @Test
    fun missingEventLogEvidenceCannotBeTreatedAsComplete() {
        assertEquals(
            "UNKNOWN",
            classifyOfflineEventLogHealth(
                streamExists = false,
                sidecarExists = false,
                eventsEmpty = true,
                health = null,
            ),
        )
        assertEquals(
            "UNKNOWN",
            classifyOfflineEventLogHealth(
                streamExists = true,
                sidecarExists = true,
                eventsEmpty = true,
                health = "COMPLETE",
            ),
        )
        assertEquals(
            "UNKNOWN",
            classifyOfflineEventLogHealth(
                streamExists = true,
                sidecarExists = null,
                eventsEmpty = false,
                health = "COMPLETE",
            ),
        )
        assertTrue(
            classifyOfflineEventLogHealth(
                streamExists = false,
                sidecarExists = false,
                eventsEmpty = true,
                health = null,
            )
                != "COMPLETE",
        )
    }

    private fun readWorkspaceManifest(): String {
        val candidates = listOf(
            File("src/main/assets/runtime-workspace/workspace.json"),
            File("workspace/apps/android/agent-app-server/src/main/assets/runtime-workspace/workspace.json"),
        )
        val file = candidates.firstOrNull { it.isFile }
        requireNotNull(file) { "workspace.json not found from ${candidates.map { it.path }}" }
        return file.readText()
    }

    private fun parseMounts(manifest: String): List<Mount> {
        val block = Regex(""""mounts"\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(manifest)
            ?.groupValues
            ?.get(1)
            .orEmpty()
        return Regex("""\{(.*?)}""", RegexOption.DOT_MATCHES_ALL)
            .findAll(block)
            .map { match ->
                Mount(
                    source = stringField(match.groupValues[1], "source"),
                    target = stringField(match.groupValues[1], "target"),
                )
            }
            .filter { it.source.isNotEmpty() && it.target.isNotEmpty() }
            .toList()
    }

    private fun stringField(body: String, name: String): String {
        return Regex(""""$name"\s*:\s*"([^"]+)"""")
            .find(body)
            ?.groupValues
            ?.get(1)
            .orEmpty()
    }

    private fun classifyOfflineEventLogHealth(
        streamExists: Boolean?,
        sidecarExists: Boolean?,
        eventsEmpty: Boolean,
        health: String?,
    ): String {
        if (streamExists != true || sidecarExists != true || eventsEmpty || health.isNullOrBlank() || health == "UNKNOWN") {
            return "UNKNOWN"
        }
        return health
    }

    private data class Mount(val source: String, val target: String)

    private companion object {
        const val DEBUG_SESSION_INDEX_SOURCE = "database://debug-session-index"
        const val DEBUG_SESSION_INDEX_TARGET = "/context/debug-session-index"
    }
}
