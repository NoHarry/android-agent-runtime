package cc.noharry.agent.androiduse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidUseAccessibilityTest {
    @Test
    fun enablingAppendsAndKeepsEveryOtherService() {
        val existing = "com.example.voice/com.example.voice.HotWordsAccessibilityService"

        assertEquals("$existing:$OURS", withService(existing, OURS))
        assertEquals(OURS, withService(null, OURS))
    }

    @Test
    fun enablingTwiceOrInShortFormChangesNothing() {
        val short = "com.example.host/.Other:com.example.host/cc.noharry.agent.androiduse.AndroidUseAccessibilityService"

        assertEquals(short, withService(short, OURS))
    }

    @Test
    fun disablingRemovesOnlyThisService() {
        val value = "a.b/a.b.Svc:$OURS:c.d/.Svc"

        assertEquals("a.b/a.b.Svc:c.d/.Svc", withoutService(value, OURS))
        assertEquals(listOf("a.b/a.b.Svc", OURS, "c.d/c.d.Svc"), enabledServices(value))
    }

    @Test
    fun launcherMatchPrefersExactLabelThenContainment() {
        val apps = listOf(
            LauncherApp("音乐助手", "com.example.helper", "Main"),
            LauncherApp("音乐", "com.example.music", "Main"),
            LauncherApp("设置", "com.android.settings", "Settings"),
        )

        assertEquals(listOf("com.example.music"), matchLauncherApps(apps, "音乐").map { it.packageName })
        assertEquals(listOf("com.example.helper"), matchLauncherApps(apps, "助手").map { it.packageName })
        assertEquals(listOf("com.android.settings"), matchLauncherApps(apps, "打开设置").map { it.packageName })
        assertEquals(listOf("com.android.settings"), matchLauncherApps(apps, "com.android.settings").map { it.packageName })
        assertTrue(matchLauncherApps(apps, "导航").isEmpty())
    }

    @Test
    fun aNameSeveralAppsShareIsAmbiguous() {
        val apps = listOf(LauncherApp("QQ音乐", "com.tencent.qqmusiccar", "Main"), LauncherApp("酷我音乐", "cn.kuwo.kwmusiccar", "Main"))

        assertEquals(listOf("QQ音乐", "酷我音乐"), matchLauncherApps(apps, "音乐").map { it.label })
        assertEquals(listOf("QQ音乐"), matchLauncherApps(apps, "qq音乐").map { it.label })
    }

    @Test
    fun elementsSkipTheHostOverlayAndUnlabelledText() {
        val nodes = listOf(
            screenNode(1, "标题", PixelRect(0, 0, 100, 50)),
            screenNode(2, "确定", PixelRect(0, 100, 100, 150), clickable = true),
            screenNode(3, "助手", PixelRect(0, 200, 100, 250), clickable = true, packageName = "host"),
            screenNode(4, "", PixelRect(0, 300, 100, 350), clickable = true),
        )

        assertEquals(listOf(2), selectElements(nodes, "host").map { it.handle })
    }

    @Test
    fun elementIdsFollowUniqueViewIdsSoTheyStayStable() {
        val nodes = listOf(
            screenNode(1, "", PixelRect(0, 0, 10, 10), viewId = "com.music:id/iv_play"),
            screenNode(2, "", PixelRect(20, 0, 30, 10), viewId = "com.music:id/next_image"),
            screenNode(3, "a", PixelRect(40, 0, 50, 10), viewId = "com.music:id/item"),
            screenNode(4, "b", PixelRect(60, 0, 70, 10), viewId = "com.music:id/item"),
            screenNode(5, "c", PixelRect(80, 0, 90, 10)),
        )

        assertEquals(listOf("iv_play", "next_image", "e3", "e4", "e5"), elementIds(nodes).map { it.id })
        assertEquals(listOf("next_image", "item"), elementIds(nodes.drop(1).take(2)).map { it.id })
    }

    @Test
    fun jsonDescriptionsKeepOnlyTheirActionLabel() {
        assertEquals("收藏", readableDescription("{\"action\":\"收藏\",\"index\":10,\"needClickAnimation\":true}"))
        assertEquals("", readableDescription("{\"index\":-1,\"needClickAnimation\":true}"))
        assertEquals("下一首", readableDescription(" 下一首 "))
    }

    @Test
    fun aSliderTakesTheCaptionAboveIt() {
        val meter = screenNode(1, "仪表", PixelRect(600, 900, 700, 940))
        val meterBar = screenNode(2, "", PixelRect(570, 950, 1530, 1000))
        val screen = screenNode(3, "中控屏", PixelRect(600, 1080, 720, 1120))
        val screenBar = screenNode(4, "", PixelRect(570, 1130, 1530, 1180))
        val nodes = listOf(meter, meterBar, screen, screenBar)

        assertEquals("中控屏", sliderLabel(screenBar, nodes))
        assertEquals("仪表", sliderLabel(meterBar, nodes))
    }

    @Test
    fun onlyEdgeBarsAreSystemAreas() {
        assertTrue(isSystemBar(PixelRect(0, 1320, 2560, 1440), 2560, 1440))
        assertTrue(isSystemBar(PixelRect(0, 0, 2560, 104), 2560, 1440))
        assertFalse(isSystemBar(PixelRect(60, 1100, 2500, 1300), 2560, 1440))
    }

    @Test
    fun screenshotScaleFitsTheBudgetWithoutEnlarging() {
        val scale = screenshotScale(2560, 1216, 640_000)

        assertEquals(640_000.0, 2560 * scale * 1216 * scale, 1.0)
        assertEquals(1.0, screenshotScale(800, 400, 640_000), 0.0)
    }

    private fun screenNode(
        handle: Int,
        text: String,
        bounds: PixelRect,
        clickable: Boolean = false,
        packageName: String = "app",
        viewId: String = "",
    ) = ScreenNode(handle, text, "", viewId, packageName, bounds, clickable, inClickableContext = false, editable = false, scrollable = false)
}

private const val OURS = "com.example.host/cc.noharry.agent.androiduse.AndroidUseAccessibilityService"
