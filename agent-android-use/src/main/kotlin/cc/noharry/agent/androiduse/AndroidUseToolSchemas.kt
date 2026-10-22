package cc.noharry.agent.androiduse

import cc.noharry.agent.kernel.api.ToolDescriptor

internal fun observeToolDescriptor(): ToolDescriptor =
    ToolDescriptor(
        name = ANDROID_OBSERVE,
        title = "查看屏幕",
        // A fallback, said so up front (owner 2026-10-14): models opened apps by tapping while the host had an API.
        description = "看屏幕 / 截图：返回当前屏幕的截图和可操作元素（id、文字、坐标）。要在界面上找东西、或没有专用接口时才用；" +
            "打开、退出应用，回到主页，进入某个页面，先用 search_tools 找专用接口。" +
            "Android Use: observe the screen. Returns observationId, a screenshot and elements " +
            "(id, text, bounds in screenshot pixels). Use before android_action when no domain tool fits; for opening or " +
            "closing an app, going home or opening a page, search_tools for a dedicated tool first.",
        inputSchemaJson = OBSERVE_SCHEMA,
        effectKind = EFFECT_READ_ONLY,
    )

internal fun actionToolDescriptor(): ToolDescriptor =
    ToolDescriptor(
        name = ANDROID_ACTION,
        title = "操作屏幕",
        description = "操作屏幕：点击、长按、滑动、翻列表、输入文字、调滑块、返回。打开应用（launch_app）、回主页（key）只在" +
            "search_tools 找不到专用接口或接口失败时用。" +
            "Android Use: one UI action on the latest observation, then returns the new observation " +
            "with a screenshot. Prefer elementId; x/y are screenshot pixels. To see more of a list use scroll " +
            "with direction (down shows what is below); keep swipe for drags that are not scrolling.",
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
