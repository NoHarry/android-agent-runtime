package cc.noharry.agent.device.appserver
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaToolHintsTest {
    @Test
    fun playbackWordsBringTheMediaTools() {
        listOf(
            "暂停一下", "快进三十秒", "继续播放", "放点音乐", "下一首", "这首歌叫什么",
            "Next song", "Play some relaxing music", "What song is this?", "pause the video",
        ).forEach {
            assertTrue(it, aboutPlayback(it))
        }
    }

    @Test
    fun otherRequestsDoNot() {
        listOf(
            "导航去莲花山公园", "我有点冷", "帮我用计算器算一下二十三乘以四十七",
            "my fourth is playing badminton", "open the Playground display",
        ).forEach {
            assertFalse(it, aboutPlayback(it))
        }
    }
}
