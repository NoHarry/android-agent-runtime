package cc.noharry.agent.androiduse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveNodeTablesTest {
    @Test
    fun readingAnotherDisplayKeepsThisDisplaysHandles() {
        val tables = LiveNodeTables<String>()
        val play = tables.nextHandle()
        tables.publish(MAIN_DISPLAY, mapOf(play to "main:play"))
        // The cluster's first node must not reuse the main screen's handle.
        val seat = tables.nextHandle()
        tables.publish(CLUSTER_DISPLAY, mapOf(seat to "cluster:seat"))

        assertNotEquals(play, seat)
        assertEquals("main:play", tables.resolve(play))
        assertEquals("cluster:seat", tables.resolve(seat))
    }

    @Test
    fun readingADisplayAgainRetiresItsOldHandles() {
        val tables = LiveNodeTables<String>()
        val old = tables.nextHandle()
        tables.publish(MAIN_DISPLAY, mapOf(old to "play"))
        val fresh = tables.nextHandle()
        tables.publish(MAIN_DISPLAY, mapOf(fresh to "pause"))

        assertNull(tables.resolve(old))
        assertEquals("pause", tables.resolve(fresh))
    }
}

private const val MAIN_DISPLAY = 0
private const val CLUSTER_DISPLAY = 1
