package cc.noharry.agent.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTurnPolicyTest {
    private val defaults = VoiceTurnThresholds()

    @Test
    fun theEndOfTurnBarRelaxesLinearlyToItsFloor() {
        assertEquals(0.72, VoiceTurnPolicy.eosBar(defaults, 0), 1e-9)
        assertEquals(0.635, VoiceTurnPolicy.eosBar(defaults, 1_500), 1e-9)
        assertEquals(0.55, VoiceTurnPolicy.eosBar(defaults, 3_000), 1e-9)
        assertEquals(0.55, VoiceTurnPolicy.eosBar(defaults, 9_000), 1e-9)
    }

    @Test
    fun addresseeScoresSplitThreeWays() {
        assertEquals(VoiceSegmentLabel.AGENT, VoiceTurnPolicy.label(defaults, judgment(addressed = 0.6)))
        assertEquals(VoiceSegmentLabel.UNCERTAIN, VoiceTurnPolicy.label(defaults, judgment(addressed = 0.5)))
        assertEquals(VoiceSegmentLabel.SIDE, VoiceTurnPolicy.label(defaults, judgment(addressed = 0.39)))
        assertEquals(VoiceSegmentLabel.UNCERTAIN, VoiceTurnPolicy.label(defaults, null))
    }

    @Test
    fun holdingBlocksTheEndAndLengthensTheCap() {
        val holding = judgment(complete = 0.99, holding = 0.5)

        assertFalse(VoiceTurnPolicy.shouldEnd(defaults, holding, 4_000))
        assertEquals(12_000, VoiceTurnPolicy.silenceCap(defaults, holding))
        assertEquals(5_000, VoiceTurnPolicy.silenceCap(defaults, judgment(holding = 0.49)))
    }

    @Test
    fun nothingEndsBeforeTheQuietWindow() {
        assertFalse(VoiceTurnPolicy.shouldEnd(defaults, judgment(complete = 1.0), 349))
        assertTrue(VoiceTurnPolicy.shouldEnd(defaults, judgment(complete = 1.0), 350))
    }

    @Test
    fun anUnsureTurnWaitsLongerWhileTheRecognizerHasNotClosedTheSentence() {
        val half = judgment(complete = 0.68)
        assertFalse("mid-sentence pause", VoiceTurnPolicy.shouldEnd(defaults, half, 800, recognizerSettled = false))
        assertTrue(VoiceTurnPolicy.shouldEnd(defaults, half, 1_200, recognizerSettled = false))
        assertTrue("a closed sentence ends as before", VoiceTurnPolicy.shouldEnd(defaults, half, 800, recognizerSettled = true))
        assertTrue("a sure turn ends at once", VoiceTurnPolicy.shouldEnd(defaults, judgment(complete = 0.9), 350, recognizerSettled = false))
    }

    @Test
    fun thresholdsAreValidated() {
        assertNull(defaults.validationError())
        assertNotNull(defaults.copy(eosFloor = 0.8).validationError())
        assertNotNull(defaults.copy(addresseeSide = 0.7).validationError())
        assertNotNull(defaults.copy(eos = Double.NaN).validationError())
        assertNotNull(defaults.copy(silenceCapMillis = 300).validationError())
    }

    @Test
    fun theMicGateFollowsTheQuietestRecentFrame() {
        val mic = MicActivity()
        assertTrue("speech at the very start counts as speech", mic.update(tone(3_000)))
        repeat(40) { mic.update(tone(900)) } // a steady cabin hum, about 0.027 full scale
        assertFalse("steady noise is not speech once the window has seen it", mic.update(tone(900)))
        assertTrue(mic.update(tone(6_000)))
    }

    @Test
    fun theAssistantsOwnWordsComingBackAreEcho() {
        val reply = "好的，正在为你导航到广州塔，全程大约二十分钟。"

        assertTrue(looksLikeEcho("正在为你导航到广州塔", reply))
        assertTrue("recognition drops punctuation and mishears a little", looksLikeEcho("为你导航到广州他全程大约", reply))
        assertFalse(looksLikeEcho("等一下不去了", reply))
        assertFalse(looksLikeEcho("打开窗帘", reply))
        assertTrue("a single character the reply contains is residue", looksLikeEcho("好", reply))
        assertFalse(looksLikeEcho("停", reply))
    }

    @Test
    fun theLevelMeterMapsDbfsToZeroOne() {
        val silent = MicActivity.levels(ShortArray(VOICE_FRAME_SAMPLES))
        val loud = MicActivity.levels(tone(16_000))
        val quiet = MicActivity.levels(tone(33)) // about -60 dBFS

        assertEquals(5, silent.size)
        assertEquals(0f, silent.max(), 0f)
        assertTrue(loud.min() > 0.85f)
        assertTrue(quiet.max() < 0.05f)
    }

    private fun judgment(complete: Double = 0.5, holding: Double = 0.0, addressed: Double = 0.5) =
        VoiceJudgment(complete, holding, addressed)

    private fun tone(amplitude: Int) = ShortArray(VOICE_FRAME_SAMPLES) { if (it % 2 == 0) amplitude.toShort() else (-amplitude).toShort() }
}
