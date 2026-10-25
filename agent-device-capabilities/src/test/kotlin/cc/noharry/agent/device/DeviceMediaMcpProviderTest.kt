package cc.noharry.agent.device

import cc.noharry.agent.kernel.api.ToolCallContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceMediaMcpProviderTest {
    @Test
    fun exposesMediaTools() = runBlocking {
        val tools = DeviceMediaMcpProvider(FakeMediaGateway()).listTools().map { it.name }

        assertEquals(listOf("media.status", "media.control", "media.volume", "media.play_from_search"), tools)
    }

    @Test
    fun statusShowsThePlayingSessionFirstAndTheOthers() = runBlocking {
        val gateway = FakeMediaGateway(listOf(podcast(state = "paused"), music()))

        val result = provider(gateway).call("media.status")

        val json = Json.parseToJsonElement(result.resultJson).jsonObject
        val current = json.getValue("current").jsonObject
        assertTrue(result.success)
        assertEquals("网易云音乐", current.getValue("app").jsonPrimitive.content)
        assertEquals(listOf("喜欢", "播放模式"), current.getValue("customActions").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("喜马拉雅", json.getValue("others").jsonArray.single().jsonObject.getValue("app").jsonPrimitive.content)
    }

    @Test
    fun withoutAccessPlayPauseAndSkipGoOutAsMediaKeys() = runBlocking {
        val gateway = FakeMediaGateway(sessions = null)

        val next = provider(gateway).call("media.control", """{"action":"next"}""")
        val seek = provider(gateway).call("media.control", """{"action":"seek","positionMs":1000}""")

        assertTrue(next.success)
        assertTrue(next.resultJson.contains(""""via":"media_key""""))
        assertEquals(listOf(87), gateway.keys)
        assertFalse(seek.success)
        assertTrue(seek.resultJson.contains(DEVICE_PERMISSION_DENIED))
    }

    @Test
    fun controlGoesToTheAppNamedAndWaitsForIt() = runBlocking {
        val gateway = FakeMediaGateway(listOf(music(), podcast(state = "paused")))

        val result = provider(gateway).call("media.control", """{"action":"toggle","app":"喜马拉雅"}""")

        assertTrue(result.success)
        assertEquals(listOf("podcast" to MediaCommand.Play), gateway.sent)
        assertTrue(result.resultJson.contains(""""state":"playing""""))
    }

    @Test
    fun nextReportsTheNewTrack() = runBlocking {
        val gateway = FakeMediaGateway(listOf(music()))

        val result = provider(gateway).call("media.control", """{"action":"next"}""")

        assertTrue(result.resultJson.contains(""""title":"Desert Chant""""))
    }

    @Test
    fun anAppsOwnActionIsFoundByItsLabel() = runBlocking {
        val gateway = FakeMediaGateway(listOf(music()))

        val liked = provider(gateway).call("media.control", """{"action":"custom","customAction":"喜欢"}""")
        val missing = provider(gateway).call("media.control", """{"action":"custom","customAction":"下载"}""")

        assertTrue(liked.success)
        assertEquals(MediaCommand.Custom("like"), gateway.sent.single().second)
        assertFalse(missing.success)
        assertTrue(missing.resultJson.contains("喜欢、播放模式"))
    }

    @Test
    fun playingByNameIsRefusedByAnAppThatCannotSearch() = runBlocking {
        val gateway = FakeMediaGateway(listOf(music(), video()))

        val refused = provider(gateway).call("media.play_from_search", """{"query":"周杰伦 晴天","app":"网易云音乐"}""")
        val played = provider(gateway).call("media.play_from_search", """{"query":"lofi"}""")

        assertFalse(refused.success)
        assertTrue(refused.resultJson.contains("YouTube can"))
        assertTrue(played.success)
        assertEquals("video" to MediaCommand.PlayFromSearch("lofi"), gateway.sent.single())
    }

    @Test
    fun volumeIsSetAndReported() = runBlocking {
        val gateway = FakeMediaGateway(listOf(music()))

        val result = provider(gateway).call("media.volume", """{"action":"set","percent":30}""")

        assertTrue(result.resultJson.contains(""""volumePercent":30"""))
    }

    private fun provider(gateway: FakeMediaGateway) = DeviceMediaMcpProvider(gateway, settleMillis = 0)

    private suspend fun DeviceMediaMcpProvider.call(tool: String, args: String = "{}") =
        callTool(tool, args, ToolCallContext(sessionId = "session-1", runId = "run-1", requestId = "request-1"))
}

private fun music() = MediaSessionSnapshot(
    id = "music", packageName = "com.netease.cloudmusic", app = "网易云音乐", title = "晴天", artist = "周杰伦", state = "playing",
    customActions = listOf(MediaCustomAction("like", "喜欢"), MediaCustomAction("mode", "播放模式")),
)

private fun podcast(state: String) = MediaSessionSnapshot(
    id = "podcast", packageName = "com.ximalaya.ting.android", app = "喜马拉雅", title = "早间新闻", state = state,
)

private fun video() = MediaSessionSnapshot(
    id = "video", packageName = "com.google.android.youtube", app = "YouTube", title = "", state = "paused", canPlayFromSearch = true,
)

/** A device whose apps do at once what they are told. */
private class FakeMediaGateway(private var sessions: List<MediaSessionSnapshot>? = emptyList()) : DeviceMediaGateway {
    val sent = mutableListOf<Pair<String, MediaCommand>>()
    val keys = mutableListOf<Int>()
    private var volume = 50

    override fun sessions(): List<MediaSessionSnapshot>? = sessions

    override fun send(sessionId: String, command: MediaCommand): Boolean {
        sent += sessionId to command
        sessions = sessions?.map { session ->
            if (session.id != sessionId) return@map session
            when (command) {
                MediaCommand.Play, is MediaCommand.PlayFromSearch -> session.copy(state = "playing")
                MediaCommand.Pause, MediaCommand.Stop -> session.copy(state = "paused")
                MediaCommand.Next -> session.copy(title = "Desert Chant")
                else -> session
            }
        }
        return true
    }

    override fun mediaKey(keyCode: Int) {
        keys += keyCode
    }

    override fun volumePercent(): Int = volume

    override fun adjustVolume(raise: Boolean) {
        volume += if (raise) 10 else -10
    }

    override fun setVolume(percent: Int) {
        volume = percent
    }
}
