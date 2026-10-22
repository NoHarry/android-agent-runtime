package cc.noharry.agent.voice

import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NearSpeechGateTest {
    @Test
    fun withoutAReferenceEveryFrameGoesStraightThrough() {
        val gate = NearSpeechGate(referenceDb = null)
        val frame = tone(-50.0)

        assertSame(frame, gate.process(frame))
        assertNull(gate.thresholdDb)
        assertNull(gate.stats())
    }

    @Test
    fun quieterTalkBecomesSilenceAndTheSpeakerPassesWithOnsetAndFadingEnd() {
        // Levels measured on the TB323FU: a talk video at 1–2 m around -53 dBFS, the owner at 40–50 cm around -32.
        val gate = NearSpeechGate(referenceDb = -30.0)
        val levels = List(20) { -53.0 } + List(5) { -32.0 } + List(10) { -53.0 }
        val frames = levels.map(::tone)

        val out = frames.map(gate::process)

        // Output runs one frame behind: out[k] carries frames[k - 1] or silence.
        val passed = (1 until frames.size).filter { out[it] === frames[it - 1] }.map { it - 1 }
        // The frame before the speaker (onset), the five spoken frames and two after (fading end).
        assertEquals((19..26).toList(), passed)
        assertTrue((1 until frames.size).filter { (it - 1) !in passed }.all { out[it].all { sample -> sample.toInt() == 0 } })
        assertEquals(-40.0, gate.thresholdDb!!, 1e-9)
        assertTrue(gate.active)
    }

    @Test
    fun theGateStandsAsideWhileTheOtherSoundIsAsLoudAsTheThreshold() {
        // The speaker said the wake phrase softly (-38): the threshold -48 leaves no room over a -50 room.
        val gate = NearSpeechGate(referenceDb = -38.0)
        val frames = List(40) { tone(-50.0) }

        val out = frames.map(gate::process)

        assertFalse(gate.active)
        assertTrue("the last frames pass once the gate stands aside", (30 until 40).all { out[it] === frames[it - 1] })
    }

    @Test
    fun theGateFiltersAgainOnceTheRoomQuietsDown() {
        val gate = NearSpeechGate(referenceDb = -38.0)
        List(40) { tone(-50.0) }.forEach { gate.process(it) }
        assertFalse(gate.active)

        // Under 100 frames of background are kept; a quiet stretch pulls the median well under the threshold.
        List(100) { tone(-62.0) }.forEach { gate.process(it) }

        assertTrue(gate.active)
        assertEquals(-62.0, gate.backgroundDb!!, 0.5)
    }

    @Test
    fun theNoiseSuppressorsDigitalSilenceIsNotCountedAsBackground() {
        val gate = NearSpeechGate(referenceDb = -30.0)
        repeat(50) { gate.process(ShortArray(VOICE_FRAME_SAMPLES)) }

        assertNull(gate.backgroundDb)
        assertTrue(gate.active)
    }

    @Test
    fun theWakeLevelIsTheMedianOfThePhrasesLoudestFrames() {
        val room = List(8) { -55.0 }
        val phrase = listOf(-31.0, -29.0, -27.0, -30.0, -33.0, -35.0)

        assertEquals(-31.0, wakeLevelDb(room + phrase + listOf(-120.0))!!, 1e-9)
    }

    @Test
    fun aWakeWithTooFewVoicedFramesGivesNoLevel() {
        assertNull(wakeLevelDb(List(10) { -120.0 } + listOf(-30.0, -31.0)))
    }

    @Test
    fun frameLevelIsRmsInDbfs() {
        assertEquals(-20.0, NearSpeechGate.frameDb(tone(-20.0)), 0.01)
    }

    private companion object {
        /** A square wave whose RMS is [db] dBFS. */
        fun tone(db: Double): ShortArray {
            val amplitude = (32_768 * 10.0.pow(db / 20)).toInt().toShort()
            return ShortArray(VOICE_FRAME_SAMPLES) { if (it % 2 == 0) amplitude else (-amplitude).toShort() }
        }
    }
}
