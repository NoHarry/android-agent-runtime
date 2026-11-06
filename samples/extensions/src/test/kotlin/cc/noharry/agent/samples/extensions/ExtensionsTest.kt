package cc.noharry.agent.samples.extensions

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionsTest {
    @Test
    fun theHookKeepsTheNotesTheModelTriedToClear() = runBlocking<Unit> {
        val transcript = runSample()

        assertTrue(transcript.toString(), "model called mcp__notes__save" in transcript)
        assertTrue(transcript.toString(), "refused mcp__notes__clear: Deleting notes is not allowed here." in transcript)
        assertTrue(transcript.toString(), "answer: Saved \"buy milk\"; clearing the notes was refused." in transcript)
        assertEquals("notes: [call the dentist, buy milk]", transcript.last())
    }
}
