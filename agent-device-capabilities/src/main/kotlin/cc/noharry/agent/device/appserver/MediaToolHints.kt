@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.device.appserver

import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.ToolPrefetchProvider
import cc.noharry.agent.spi.ToolPrefetchRequest
import cc.noharry.agent.spi.ToolPrefetchTrace
import cc.noharry.agent.spi.ToolProvider

/**
 * The media tools on hand from a run's first round when the person's words are about playback, so a pause or a skip goes
 * to the playing app's media session in one call rather than to operating the screen, whose tools may be offered on
 * every turn (tapping a video's pause button can take several tries). Catalogue hints only; the delegate runs
 * everything.
 */
internal class MediaToolHints(private val delegate: ToolProvider) : ToolProvider by delegate, ToolPrefetchProvider {
    override val providerConcurrency: ProviderConcurrencyDeclaration = delegate.providerConcurrency
    override val remotePrefetchEnabled: Boolean get() = false

    override fun localToolNames(request: ToolPrefetchRequest): List<String> =
        if (!aboutPlayback(request.input)) emptyList()
        else request.tools.map { it.name }.filter { name -> MEDIA_HINT_TOOLS.any(name::endsWith) }.take(request.maxTools)

    override suspend fun prefetchTools(request: ToolPrefetchRequest, trace: ToolPrefetchTrace): List<String> = emptyList()
}

/**
 * Whether [text] asks to play, pause, skip or name what plays (music, a video, a podcast): English whole words, or
 * Chinese words, which match only in Chinese text.
 */
internal fun aboutPlayback(text: String): Boolean = PLAYBACK_WORDS.any { it in text } || ENGLISH_PLAYBACK.containsMatchIn(text)

private val MEDIA_HINT_TOOLS = listOf("media.control", "media.status")
private val PLAYBACK_WORDS = listOf(
    "暂停", "播放", "继续放", "接着放", "放首", "放点", "放歌", "快进", "快退", "倒回",
    "下一首", "上一首", "下一集", "上一集", "切歌", "换一首", "这首歌", "什么歌",
)

private val ENGLISH_PLAYBACK = Regex(
    "\\b(pause|play|resume|skip|fast forward|rewind|(next|previous|last) (song|track|episode)|(what|this) song)\\b",
    RegexOption.IGNORE_CASE,
)
