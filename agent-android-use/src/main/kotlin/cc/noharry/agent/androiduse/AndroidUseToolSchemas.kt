package cc.noharry.agent.androiduse

import android.content.res.Resources
import android.os.Build
import cc.noharry.agent.kernel.api.ToolDescriptor
import java.util.Locale

internal fun observeToolDescriptor(): ToolDescriptor =
    ToolDescriptor(
        name = ANDROID_OBSERVE,
        title = "Observe screen",
        // A fallback, said so up front (owner 2026-10-14): models opened apps by tapping while the host had an API.
        description = "Android Use, a fallback: look at the screen only to find something on it, or when no dedicated " +
            "tool fits; for opening or closing an app, going home or opening a page, search_tools for a dedicated tool " +
            "first. Returns observationId, a screenshot and elements (id, text, bounds in screenshot pixels), for " +
            "android_action." + chineseWords("看屏幕 / 截图：返回当前屏幕的截图和可操作元素（id、文字、坐标）。"),
        inputSchemaJson = OBSERVE_SCHEMA,
        effectKind = EFFECT_READ_ONLY,
    )

internal fun actionToolDescriptor(): ToolDescriptor =
    ToolDescriptor(
        name = ANDROID_ACTION,
        title = "Act on screen",
        description = "Android Use, a fallback: open an app (launch_app) or go home (key) only when search_tools finds " +
            "no dedicated tool or that tool fails. One UI action (tap, long press, swipe, scroll a list, type, set a " +
            "slider, back) on the latest observation, then returns the new observation with a screenshot. Prefer " +
            "elementId; x/y are screenshot pixels. To see more of a list use scroll with direction (down shows what is " +
            "below); keep swipe for drags that are not scrolling." +
            chineseWords("操作屏幕：点击、长按、滑动、翻列表、输入文字、调滑块、返回。"),
        inputSchemaJson = ACTION_SCHEMA,
        effectKind = EFFECT_MUTATING,
    )

private val OBSERVE_SCHEMA = """
    {"type":"object","properties":{
      "displayId":{"type":"integer","minimum":0,"description":"Display to observe; 0 is the main screen."}
    },"additionalProperties":false}
""".trimIndent()

private val ACTION_SCHEMA = """
    {"type":"object","required":["observationId","action"],"properties":{
      "observationId":{"type":"string","description":"observationId of the latest observation of this display."},
      "action":{"type":"string","enum":["tap","long_press","swipe","scroll","input_text","set_value","key","launch_app","wait"]},
      "elementId":{"type":"string","description":"Element id such as e3. Preferred over x/y for tap, long_press and input_text; for scroll, the list to scroll (default: the largest scrollable)."},
      "direction":{"type":"string","enum":["up","down","left","right"],"description":"For scroll: where the content you want is; down shows what is below."},
      "x":{"type":"integer","minimum":0,"description":"Screenshot x for tap, long_press, or swipe start."},
      "y":{"type":"integer","minimum":0,"description":"Screenshot y for tap, long_press, or swipe start."},
      "toX":{"type":"integer","minimum":0,"description":"Swipe end x."},
      "toY":{"type":"integer","minimum":0,"description":"Swipe end y."},
      "durationMs":{"type":"integer","minimum":0,"maximum":5000,"description":"Swipe duration, or how long to wait."},
      "text":{"type":"string","description":"Text for input_text; replaces the field's content."},
      "value":{"type":"number","description":"Target value for set_value on a slider element that has a range."},
      "key":{"type":"string","enum":["back","home","recents","notifications"]},
      "app":{"type":"string","description":"App label or package name for launch_app."},
      "displayId":{"type":"integer","minimum":0}
    },"additionalProperties":false}
""".trimIndent()

internal const val ANDROID_USE_SERVER_ID = "android_use"
internal const val ANDROID_OBSERVE = "android_observe"
internal const val ANDROID_ACTION = "android_action"
private const val EFFECT_READ_ONLY = "READ_ONLY"
private const val EFFECT_MUTATING = "MUTATING"

/** Words a Chinese request uses for a tool, where Chinese is in use ([chineseInUse]); nothing elsewhere. */
private fun chineseWords(words: String): String = if (chineseInUse()) " $words" else ""

/**
 * Whether Chinese is in use here: one of the system's languages (the user's own, whatever this app shows) or the app's.
 * Tool search then finds a tool from the Chinese words a request uses.
 */
internal fun chineseInUse(): Boolean =
    Locale.getDefault().language == "zh" || runCatching {
        val configuration = Resources.getSystem().configuration
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            (0 until configuration.locales.size()).any { configuration.locales[it].language == "zh" }
        } else {
            @Suppress("DEPRECATION")
            configuration.locale.language == "zh"
        }
    }.getOrDefault(false)
