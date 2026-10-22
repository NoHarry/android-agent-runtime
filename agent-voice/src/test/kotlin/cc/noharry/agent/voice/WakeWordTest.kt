package cc.noharry.agent.voice

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTest {
    @Test
    fun theWakePhraseIsItsPinyinTokensThenThePhrase() {
        assertEquals("n ǐ h ǎo zh ù sh ǒu @你好助手", wakeKeyword("n ǐ h ǎo zh ù sh ǒu", "你好助手"))
        assertEquals("zh ù sh ǒu @助手", wakeKeyword("  zh  ù sh ǒu ", "助手"))
    }

    @Test
    fun everyLevelHearsTheProfilesPhrasesAndDecoysAndOnlyTheLowOneTakesItsLenientOnes() {
        val standard = WakeSensitivity.STANDARD.keywords(TEST_WAKE_PROFILE).split("/")
        assertEquals(listOf("n ǐ h ǎo zh ù sh ǒu @你好助手", "h āi zh ù sh ǒu @嗨助手", "n ǐ h ǎo zh ù sh ù @${DECOY_MARK}你好助术"), standard)
        assertEquals(standard, WakeSensitivity.HIGH.keywords(TEST_WAKE_PROFILE).split("/"))
        val low = WakeSensitivity.LOW.keywords(TEST_WAKE_PROFILE).split("/")
        assertTrue(low.containsAll(standard) && "n ǐ h ǎo zh ǔ sh ǒu @你好主手" in low)
        assertTrue(WakeSensitivity.HIGH.paths < WakeSensitivity.STANDARD.paths)
        assertTrue(WakeSensitivity.entries.none { level -> TEST_WAKE_PROFILE.phrasesFor(level).any { it.phrase.startsWith(DECOY_MARK) } })
        assertEquals(WakeSensitivity.LOW, WakeSensitivity.of("low"))
    }

    @Test
    fun aProfileRefusesDecoysThatWouldWakeAndPhrasesThatWouldNot() {
        assertTrue(runCatching { WakeWordProfile(emptyList()) }.isFailure)
        assertTrue(runCatching { WakeWordProfile(TEST_WAKE_PROFILE.phrases, decoys = listOf(WakePhrase("a b", "你好"))) }.isFailure)
        assertTrue(runCatching { WakeWordProfile(listOf(WakePhrase("a b", "${DECOY_MARK}你好"))) }.isFailure)
    }

    @Test
    fun theNextMicrophoneUserOpensOnlyAfterThePreviousClosedItsRecording() = runTest {
        val lease = Mutex()
        val events = mutableListOf<String>()
        fun source(name: String) = VoiceAudioSource {
            flow {
                events += "$name open"
                try {
                    while (true) {
                        emit(ShortArray(VOICE_FRAME_SAMPLES))
                        delay(100)
                    }
                } finally {
                    events += "$name closed"
                }
            }
        }
        val wake = launch { LeasedAudioSource(lease, source("wake")).frames().collect {} }
        advanceTimeBy(250)
        val session = launch { LeasedAudioSource(lease, source("session")).frames().take(1).collect {} }
        runCurrent()
        assertEquals("the session waits while the wake word records", listOf("wake open"), events)

        wake.cancel()
        session.join()
        assertEquals(listOf("wake open", "wake closed", "session open", "session closed"), events)
    }

    @Test
    fun aTeeHandsTheSpotterACopyOfEachFrameTheSessionRecords() = runTest {
        val tee = VoiceAudioTee(VoiceAudioSource { flow { for (i in 1..3) { delay(100); emit(ShortArray(4) { i.toShort() }) } } })
        val copied = mutableListOf<Short>()
        val spotter = launch { tee.copies.frames().collect { copied += it[0] } }
        runCurrent()
        val recorded = mutableListOf<ShortArray>()
        tee.frames().collect { frame -> recorded += frame; frame.fill(0) }
        runCurrent()
        spotter.cancel()

        assertEquals(3, recorded.size)
        assertEquals("every frame, untouched by what the session did to its own", listOf<Short>(1, 2, 3), copied)
    }

    @Test
    fun aTeeWithoutCopiesHandsTheSpotterOnlyWhatTheRecordingOffers() = runTest {
        lateinit var tee: VoiceAudioTee
        // The session hears the caller's seat (1, 2, 3); every seat (-1, -2, -3) goes to the spotter.
        val source = VoiceAudioSource {
            flow { for (i in 1..3) { delay(100); tee.offer(ShortArray(4) { (-i).toShort() }); emit(ShortArray(4) { i.toShort() }) } }
        }
        tee = VoiceAudioTee(source, copySource = false)
        val copied = mutableListOf<Short>()
        val spotter = launch { tee.copies.frames().collect { copied += it[0] } }
        runCurrent()
        val recorded = mutableListOf<Short>()
        tee.frames().collect { frame -> recorded += frame[0] }
        runCurrent()
        spotter.cancel()

        assertEquals(listOf<Short>(1, 2, 3), recorded)
        assertEquals(listOf<Short>(-1, -2, -3), copied)
    }
}
