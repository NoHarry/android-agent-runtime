package cc.noharry.agent.voice

/**
 * A judge that needs no service: every turn is for the assistant, and it is over once the recognizer closes the
 * sentence. For hosts without Jev, at the cost of what Jev adds: side talk nearby is handed over too, and only a few
 * plain words ([STOP_WORDS]) cut in while the assistant speaks, so its own voice never interrupts it.
 *
 * A sentence the recognizer has not closed, or one that ends on a word that leads on ([DANGLING]), waits for more: the
 * session's silence cap ends it if nothing comes.
 */
class DirectTurnJudge : VoiceTurnJudge {
    override suspend fun judge(request: VoiceJudgeRequest): VoiceJudgment {
        val text = request.snapshot.transcript.trim()
        val bare = text.trimEnd { !it.isLetterOrDigit() }
        val dangling = DANGLING.any { bare.endsWith(it) } || text.lastOrNull()?.let { it in LEADING_MARKS } == true
        val complete = when {
            dangling -> NOT_COMPLETE
            request.snapshot.asrFinal -> SURE_COMPLETE
            else -> LIKELY_COMPLETE
        }
        val short = bare.length <= SHORT_CHARS
        return VoiceJudgment(
            turnComplete = complete,
            holdingFloor = 0.0,
            addressedToAgent = 1.0,
            takingFloor = (if (short && STOP_WORDS.any(bare::contains)) 1.0 else 0.0)
                .takeIf { request.assistantState == ASSISTANT_SPEAKING },
            replacesTask = 0.0.takeIf { request.currentTask.isNotBlank() },
            endsSession = (if (short && END_WORDS.any(bare::contains)) 1.0 else 0.0).takeIf { request.canEndSession },
        )
    }
}

private const val SURE_COMPLETE = 0.95
private const val LIKELY_COMPLETE = 0.75
private const val NOT_COMPLETE = 0.2
/** Stop and goodbye words count only in a short turn: inside a longer one they are part of a request. */
private const val SHORT_CHARS = 8
private val DANGLING = listOf("然后", "还有", "而且", "或者", "和", "跟", "再", "以及", "就是", "那个")
private const val LEADING_MARKS = "，,、：:"
private val STOP_WORDS = listOf("等一下", "停", "别说了", "不对", "算了")
private val END_WORDS = listOf("再见", "拜拜", "退下", "不聊了", "退出")
