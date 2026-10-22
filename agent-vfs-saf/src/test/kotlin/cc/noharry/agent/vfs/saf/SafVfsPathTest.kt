package cc.noharry.agent.vfs.saf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SafVfsPathTest {
    @Test
    fun blankPathResolvesToRoot() {
        assertEquals(emptyList<String>(), SafVfsPath.segments(""))
        assertEquals(emptyList<String>(), SafVfsPath.segments("/"))
    }

    @Test
    fun relativePathSplitsIntoSafeSegments() {
        assertEquals(listOf("docs", "note.txt"), SafVfsPath.segments("docs/note.txt"))
        assertEquals(listOf("docs", "note.txt"), SafVfsPath.segments("/docs/note.txt"))
    }

    @Test
    fun escapingSegmentsAreRejected() {
        assertNull(SafVfsPath.segments("../secret.txt"))
        assertNull(SafVfsPath.segments("docs/../secret.txt"))
        assertNull(SafVfsPath.segments("docs\\.\\secret.txt"))
    }
}
