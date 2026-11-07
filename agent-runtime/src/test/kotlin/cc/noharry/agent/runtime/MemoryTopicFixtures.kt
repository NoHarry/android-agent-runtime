package cc.noharry.agent.runtime

import cc.noharry.agent.runtime.MemoryTopic

/** Explicit host topics for memory-routing tests. */
internal val TEST_MEMORY_TOPICS: List<MemoryTopic> = listOf(
            MemoryTopic(
                "hvac",
                strongTerms = setOf(
                    "hvac", "ac", "climate", "cooling", "heating", "air conditioner", "空调", "内循环", "外循环",
                    "有点热", "太热", "很热", "好热", "热了", "屋里热", "室内热", "制冷", "降温", "凉快",
                    "有点冷", "太冷", "很冷", "好冷", "冷了", "屋里冷", "室内冷", "制热", "升温", "暖和",
                ),
                weakTerms = setOf("temperature", "hot", "cold", "fan speed", "温度", "风量", "风速"),
                excludedPhrases = setOf("冷笑话", "温度计", "温度传感器", "风速仪"),
            ),
            MemoryTopic(
                "navigation",
                strongTerms = setOf("navigation", "navigate", "navi", "destination", "poi", "map", "导航", "目的地", "地图"),
                weakTerms = setOf("route", "highway", "toll", "路线", "高速", "收费"),
                excludedPhrases = setOf("技术路线", "产品路线", "路线图", "导航栏", "高速缓存", "收费标准"),
            ),
            MemoryTopic(
                "media",
                strongTerms = setOf("music", "audio", "song", "playlist", "volume", "音乐", "歌曲", "音量"),
                weakTerms = setOf("playback", "pause", "播放", "暂停"),
            ),
            MemoryTopic(
                "web",
                strongTerms = setOf(
                    "browser", "web", "url", "http", "fetch", "网页", "浏览器", "网址", "联网", "上网", "访问网页", "打开网页",
                ),
            ),
            MemoryTopic("display", strongTerms = setOf("brightness", "screen", "display", "亮度", "屏幕")),
            MemoryTopic(
                "phone",
                strongTerms = setOf("phone", "电话", "通话", "拨号"),
                weakTerms = setOf("call", "contact", "联系人"),
            ),
        )
