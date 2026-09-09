package com.aeroglyph.app.playback

import com.aeroglyph.app.audio.AcousticBand
import com.aeroglyph.app.audio.AudioEncoder
import com.aeroglyph.app.audio.DecodeResult
import com.aeroglyph.app.audio.Frame
import com.aeroglyph.app.audio.FrameType
import com.aeroglyph.app.audio.ModemConfig
import com.aeroglyph.app.audio.RoomProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * End-to-end tests for the receive path, driven with synthesised audio.
 *
 * These exist because every reception bug this project has had lived here and
 * not in the codec: the codec round-trips a clean buffer perfectly while the
 * live receiver sits on "waiting for a broadcast" forever. Anything that
 * decides *when to look* and *which band to look in* needs to be provable
 * without a phone in the room.
 */
class FrameDetectorTest {

    private val chunkSize = 2048

    /**
     * Plays a transmission past a detector the way a microphone would: some
     * lead-in room tone first so the noise floor settles, then the frame, then
     * more room tone so the tail arrives.
     *
     * [gain] scales the transmission (0.02 ~ a distant, very weak signal),
     * [noiseAmp] is broadband room noise, and [interferenceHz] adds a steady
     * tone to simulate a noisy region of the spectrum.
     */
    private fun run(
        pcm: ShortArray,
        gain: Double = 1.0,
        noiseAmp: Int = 0,
        interferenceHz: Double? = null,
        interferenceAmp: Int = 0,
        leadInChunks: Int = 40,
        tailChunks: Int = 20,
        seed: Int = 7,
    ): List<DecodeResult> {
        val results = mutableListOf<DecodeResult>()
        val detector = FrameDetector(onResult = { results += it })
        val rnd = Random(seed)
        var t = 0L

        fun ambience(n: Int) = ShortArray(n) {
            var v = if (noiseAmp > 0) rnd.nextInt(-noiseAmp, noiseAmp + 1) else 0
            if (interferenceHz != null) {
                v += (sin(2.0 * PI * interferenceHz * (t + it) / ModemConfig.SAMPLE_RATE_HZ) * interferenceAmp).toInt()
            }
            v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        fun feed(buf: ShortArray) {
            var off = 0
            while (off < buf.size) {
                val n = minOf(chunkSize, buf.size - off)
                detector.offer(buf.copyOfRange(off, off + n), n)
                off += n
                t += n
            }
        }

        repeat(leadInChunks) { feed(ambience(chunkSize)) }

        val signal = ShortArray(pcm.size) { i ->
            var v = (pcm[i] * gain).toInt()
            if (noiseAmp > 0) v += rnd.nextInt(-noiseAmp, noiseAmp + 1)
            if (interferenceHz != null) {
                v += (sin(2.0 * PI * interferenceHz * (t + i) / ModemConfig.SAMPLE_RATE_HZ) * interferenceAmp).toInt()
            }
            v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        feed(signal)

        repeat(tailChunks) { feed(ambience(chunkSize)) }
        return results
    }

    private fun frameFor(text: String) = Frame(
        sessionId = 0x2C,
        frameType = FrameType.DATA,
        hopCount = 0,
        payload = text.toByteArray(),
    )

    private fun synthesize(text: String, profile: RoomProfile) =
        AudioEncoder.synthesize(frameFor(text), symbolRateHz = profile.symbolRateHz, band = profile.band)

    private fun List<DecodeResult>.messages() =
        filterIsInstance<DecodeResult.Success>().map { it.message }

    // ── The basics, per profile ──────────────────────────────────────────────

    @Test
    fun `every room profile decodes end to end through the live path`() {
        for (profile in RoomProfile.entries) {
            val results = run(synthesize("hello ${profile.label}", profile))
            assertTrue(
                "${profile.label} produced no decode: $results",
                results.messages().contains("hello ${profile.label}"),
            )
        }
    }

    /**
     * The regression that broke the shipped build.
     *
     * Room noise is far stronger at 10-14.5kHz than at 17-19.5kHz, so a gate
     * that compared raw magnitudes across bands always picked the lower band,
     * correlated the wrong chirp template, and never found an ultrasonic
     * transmission at all -- silently disabling the default mode. Each band
     * must be judged against its own background.
     */
    @Test
    fun `an ultrasonic frame decodes despite much louder noise in the other band`() {
        val results = run(
            synthesize("quiet band wins", RoomProfile.NORMAL),
            gain = 0.25,
            noiseAmp = 150,
            interferenceHz = 12_000.0, // squarely inside the long-range band
            interferenceAmp = 6_000,
        )
        assertTrue(
            "ultrasonic frame lost to unrelated low-band noise: $results",
            results.messages().contains("quiet band wins"),
        )
    }

    @Test
    fun `a long-range frame decodes despite noise in the ultrasonic band`() {
        val results = run(
            synthesize("loud band wins", RoomProfile.LONG_RANGE),
            gain = 0.25,
            noiseAmp = 150,
            interferenceHz = 18_500.0,
            interferenceAmp = 4_000,
        )
        assertTrue(
            "long-range frame lost to unrelated high-band noise: $results",
            results.messages().contains("loud band wins"),
        )
    }

    // ── Weak signals ─────────────────────────────────────────────────────────

    @Test
    fun `a heavily attenuated frame still decodes`() {
        // 2% amplitude with room noise on top: the distance case the trigger
        // thresholds exist to catch.
        val results = run(synthesize("far away", RoomProfile.LONG_RANGE), gain = 0.02, noiseAmp = 60)
        assertTrue("weak frame not decoded: $results", results.messages().contains("far away"))
    }

    @Test
    fun `a frame arriving mid-chunk still decodes`() {
        // The gate only fires on chunk boundaries, so a chirp beginning part
        // way through one must still be complete in the pre-roll.
        val pcm = synthesize("offset start", RoomProfile.NORMAL)
        for (offset in listOf(311, 1024, 1900)) {
            val shifted = ShortArray(offset) + pcm
            val results = run(shifted, noiseAmp = 40)
            assertTrue("offset $offset lost the frame: $results", results.messages().contains("offset start"))
        }
    }

    // ── Not fooled ───────────────────────────────────────────────────────────

    @Test
    fun `noise alone never yields a decoded message`() {
        val results = run(ShortArray(chunkSize * 40), noiseAmp = 2_000, leadInChunks = 10, tailChunks = 10)
        assertTrue("noise produced a message: $results", results.messages().isEmpty())
    }

    @Test
    fun `a muted detector ignores audio entirely`() {
        val results = mutableListOf<DecodeResult>()
        val detector = FrameDetector(onResult = { results += it })
        detector.muted = true
        val pcm = synthesize("should not appear", RoomProfile.NORMAL)
        var off = 0
        while (off < pcm.size) {
            val n = minOf(chunkSize, pcm.size - off)
            detector.offer(pcm.copyOfRange(off, off + n), n)
            off += n
        }
        assertTrue("muted detector decoded something: $results", results.messages().isEmpty())
    }

    // ── Behaviour the recovery layer depends on ──────────────────────────────

    @Test
    fun `back-to-back repetitions are each reported`() {
        val pcm = synthesize("twice", RoomProfile.QUIET)
        val gap = ShortArray(ModemConfig.SAMPLE_RATE_HZ / 3)
        val results = run(pcm + gap + pcm, noiseAmp = 40)
        assertEquals(
            "each repetition should surface separately",
            2,
            results.messages().count { it == "twice" },
        )
    }

    @Test
    fun `payload damage is reported as partial reception naming the session`() {
        // Surprise challenge 1, through the live path rather than the codec:
        // the header must survive so the receiver can name what to repair.
        val profile = RoomProfile.NORMAL
        val pcm = synthesize("this payload gets wrecked", profile)
        val chirp = profile.band.chirpSamples()
        val headerSamples = com.aeroglyph.app.audio.AudioDecoder.headerSampleCount(profile.symbolRateHz)
        val rnd = Random(11)
        for (i in (chirp + headerSamples) until pcm.size) {
            pcm[i] = rnd.nextInt(-20_000, 20_000).toShort()
        }

        val results = run(pcm)
        val partial = results.filterIsInstance<DecodeResult.PartialReception>().firstOrNull()
        assertNotNull("expected a PartialReception, got $results", partial)
        assertEquals(0x2C, partial!!.sessionId)
        assertTrue("must never surface a corrupted message", results.messages().isEmpty())
    }

    @Test
    fun `the detector recovers and decodes after a false onset`() {
        // A door slam followed by a real transmission: the cooldown must not
        // swallow the frame that follows it.
        val bang = ShortArray(chunkSize * 2) { Random(3).nextInt(-25_000, 25_000).toShort() }
        val pcm = synthesize("after the bang", RoomProfile.NORMAL)
        val gap = ShortArray(ModemConfig.SAMPLE_RATE_HZ)
        val results = run(bang + gap + pcm, noiseAmp = 50)
        assertTrue("frame after a false onset was lost: $results", results.messages().contains("after the bang"))
    }

    @Test
    fun `a band with no transmission never reports a lock`() {
        // Guards the band-selection logic from the opposite direction: steady
        // tones inside a band are background, not a transmission.
        val results = run(
            ShortArray(chunkSize * 30),
            noiseAmp = 100,
            interferenceHz = 11_000.0,
            interferenceAmp = 8_000,
            leadInChunks = 10,
            tailChunks = 5,
        )
        assertNull(results.filterIsInstance<DecodeResult.Success>().firstOrNull())
    }

    @Test
    fun `both bands round-trip at their own profile through the live path`() {
        for (band in AcousticBand.entries) {
            val profile = RoomProfile.entries.first { it.band == band }
            val results = run(synthesize("band ${band.label}", profile), gain = 0.1, noiseAmp = 80)
            assertTrue("${band.label} failed: $results", results.messages().contains("band ${band.label}"))
        }
    }
}
