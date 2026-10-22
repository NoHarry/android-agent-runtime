package cc.noharry.agent.voice

/** A neutral assistant for the voice tests: 你好助手, with 嗨助手 at every level, 你好主手 at the low one and one decoy. */
internal val TEST_WAKE_PROFILE = WakeWordProfile(
    phrases = listOf(WakePhrase("n ǐ h ǎo zh ù sh ǒu", "你好助手"), WakePhrase("h āi zh ù sh ǒu", "嗨助手")),
    lenientPhrases = listOf(WakePhrase("n ǐ h ǎo zh ǔ sh ǒu", "你好主手")),
    decoys = listOf(WakePhrase("n ǐ h ǎo zh ù sh ù", "${DECOY_MARK}你好助术")),
    heardInTranscript = Regex("(?:你好|[嗨嘿]|hi|hey)[，,、。.！!？?\\s]*助手", RegexOption.IGNORE_CASE),
)

internal val TEST_WAKE_HEARD: Regex = TEST_WAKE_PROFILE.heardInTranscript!!
