package com.aeroglyph.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Covers the two things that make long range work: the second carrier band,
 * and the interleaver that stops one bad symbol from destroying a whole frame.
 */
class LongRangeTest {

    // ── Interleaving ─────────────────────────────────────────────────────────

    @Test
    fun `interleaving round-trips for every block size the wire uses`() {
        // 6 codewords is the header block; the rest are payload-sized.
        for (codewordCount in listOf(6, 8, 16, 64, 408)) {
            val original = ByteArray(codewordCount) { Random(it).nextInt(256).toByte() }
            val recovered = Interleaver.deinterleave(Interleaver.interleave(original))
            assertTrue("size $codewordCount", original.contentEquals(recovered))
        }
    }

    @Test
    fun `interleaving preserves length and is not the identity`() {
        val original = ByteArray(32) { (it * 7 + 3).toByte() }
        val wire = Interleaver.interleave(original)
        assertEquals(original.size, wire.size)
        assertTrue("a transpose that changes nothing would not spread anything", !wire.contentEquals(original))
    }

    /**
     * The whole point of the interleaver. One wrong FSK symbol means four
     * consecutive wire bits are suspect at once; without interleaving those
     * four bits all land in a single Hamming codeword, which SECDED cannot
     * repair, and one bad symbol therefore threw away the entire message.
     */
    @Test
    fun `a corrupted symbol is fully recoverable once interleaved`() {
        val plain = "range check payload".toByteArray()
        val wire = Interleaver.interleave(FecCodec.encode(plain))

        // Corrupt one symbol's worth of bits: one aligned nibble.
        for (nibbleIndex in listOf(0, 5, 17, 40)) {
            val damaged = wire.copyOf()
            val byteIndex = nibbleIndex / 2
            val mask = if (nibbleIndex % 2 == 0) 0xF0 else 0x0F
            damaged[byteIndex] = (damaged[byteIndex].toInt() xor mask).toByte()

            val recovered = FecCodec.decode(Interleaver.deinterleave(damaged))
            assertNotNull("nibble $nibbleIndex should be recoverable", recovered)
            assertTrue("nibble $nibbleIndex", plain.contentEquals(recovered))
        }
    }

    @Test
    fun `the same damage without interleaving destroys the block`() {
        // Guards the claim above: this is what the old wire format did, so if
        // this ever starts passing the interleaver has stopped being load-bearing.
        val plain = "range check payload".toByteArray()
        val encoded = FecCodec.encode(plain)
        encoded[3] = (encoded[3].toInt() xor 0xF0).toByte()
        assertNull(FecCodec.decode(encoded))
    }

    // ── The long-range band ──────────────────────────────────────────────────

    @Test
    fun `a frame sent long-range round-trips`() {
        val frame = Frame(
            sessionId = 0x2A,
            frameType = FrameType.DATA,
            hopCount = 1,
            payload = "across the hall".toByteArray(),
        )
        val rate = RoomProfile.LONG_RANGE.symbolRateHz
        val band = AcousticBand.LONG_RANGE

        val pcm = AudioEncoder.synthesize(frame, symbolRateHz = rate, band = band)
        val result = AudioDecoder.decode(pcm, symbolRateHz = rate, band = band)

        assertTrue("expected Success, got $result", result is DecodeResult.Success)
        assertEquals("across the hall", (result as DecodeResult.Success).message)
        assertEquals(0x2A, result.sessionId)
    }

    @Test
    fun `long-range tones stay inside the band and are properly spaced`() {
        val band = AcousticBand.LONG_RANGE
        assertEquals(10_000.0, band.toneFrequencyHz(0), 0.001)
        assertEquals(14_500.0, band.toneFrequencyHz(ModemConfig.TONE_COUNT - 1), 0.001)
        // Nyquist headroom: the top tone must sit well under 24kHz.
        assertTrue(band.highToneHz < ModemConfig.SAMPLE_RATE_HZ / 2.0)
    }

    /**
     * The bands are told apart purely by their chirp, with no field on the wire
     * announcing which was used -- so the templates must not be confusable, or
     * a receiver would lock onto the wrong band and decode noise.
     */
    @Test
    fun `each band's chirp is found by its own template and not the other's`() {
        for (band in AcousticBand.entries) {
            val frame = Frame(sessionId = 5, frameType = FrameType.DATA, hopCount = 0, payload = "x".toByteArray())
            val rate = RoomProfile.entries.first { it.band == band }.symbolRateHz
            val pcm = AudioEncoder.synthesize(frame, symbolRateHz = rate, band = band)

            val hit = AudioDecoder.findSyncChirpAnyBand(pcm)
            assertNotNull("no chirp found for $band", hit)
            assertEquals("wrong band identified", band, hit!!.band)

            val wrongBand = AcousticBand.entries.first { it != band }
            val crossTalk = AudioDecoder.findSyncChirp(pcm, band = wrongBand)
            assertNull("$wrongBand template must not match a $band chirp", crossTalk)
        }
    }

    @Test
    fun `an unconfigured receiver decodes a long-range frame end to end`() {
        // Mirrors the live path: the receiver is told nothing, works out the
        // band from the chirp, and only then picks the symbol rate.
        val frame = Frame(
            sessionId = 0x51,
            frameType = FrameType.DATA,
            hopCount = 0,
            payload = "found me".toByteArray(),
        )
        val pcm = AudioEncoder.synthesize(
            frame,
            symbolRateHz = RoomProfile.LONG_RANGE.symbolRateHz,
            band = AcousticBand.LONG_RANGE,
        )

        val hit = AudioDecoder.findSyncChirpAnyBand(pcm)
        assertNotNull(hit)
        val symbolStart = hit!!.detection.offsetSamples + hit.band.chirpSamples()

        val header = AudioDecoder.decodeHeader(
            pcm,
            symbolStart = symbolStart,
            symbolRateHz = RoomProfile.LONG_RANGE.symbolRateHz,
            band = hit.band,
        )
        assertNotNull("header must decode once the band is known", header)
        assertEquals(0x51, header!!.sessionId)
        assertEquals("found me".length, header.payloadLength)
    }

    /**
     * The receiver tries several symbol rates against the header because
     * nothing on the wire announces which one the sender used. That search is
     * only safe if a wrong rate can be told from the right one, and "the FEC
     * accepted it" cannot do that job: extended Hamming(8,4) treats 144 of 256
     * bytes as valid-or-correctable, so a six-codeword header made of noise
     * passes about 3% of the time. Symbol confidence is what separates them.
     */
    @Test
    fun `the correct symbol rate resolves far more confidently than the wrong ones`() {
        val sentRate = RoomProfile.NORMAL.symbolRateHz
        val frame = Frame(sessionId = 0x3B, frameType = FrameType.DATA, hopCount = 1, payload = "rate".toByteArray())
        val pcm = AudioEncoder.synthesize(frame, symbolRateHz = sentRate, band = AcousticBand.ULTRASONIC)
        val symbolStart = AcousticBand.ULTRASONIC.chirpSamples()

        val correct = AudioDecoder.decodeHeaderScored(pcm, symbolStart, symbolRateHz = sentRate)
        assertNotNull("the true rate must decode", correct)
        assertEquals(0x3B, correct!!.header.sessionId)
        assertTrue(
            "a correctly aligned header should be well clear of the accept threshold, was ${correct.confidence}",
            correct.confidence > AudioDecoder.MIN_HEADER_CONFIDENCE,
        )

        for (wrongRate in RoomProfile.entries.map { it.symbolRateHz }.filter { it != sentRate }) {
            val scored = AudioDecoder.decodeHeaderScored(pcm, symbolStart, symbolRateHz = wrongRate)
            // A wrong rate may or may not satisfy the FEC; what must never
            // happen is it looking *more* convincing than the true one.
            if (scored != null) {
                assertTrue(
                    "rate $wrongRate scored ${scored.confidence} vs ${correct.confidence} for the true rate",
                    scored.confidence < correct.confidence,
                )
            }
        }
    }

    /**
     * Pins the in-band correlation fix at the level it operates on. Without it
     * these same buffers scored 0.02-0.07 -- far below any usable threshold --
     * purely because low-frequency room energy sat in the denominator of a
     * normalised correlation.
     */
    @Test
    fun `a weak chirp scores well despite dominant out-of-band rumble`() {
        for (band in AcousticBand.entries) {
            val profile = RoomProfile.entries.first { it.band == band }
            val frame = Frame(sessionId = 9, frameType = FrameType.DATA, hopCount = 0, payload = "hi".toByteArray())
            val clean = AudioEncoder.synthesize(frame, symbolRateHz = profile.symbolRateHz, band = band)

            val rumble = 20_000
            val noisy = ShortArray(clean.size) { i ->
                var v = (clean[i] * 0.02).toInt()
                v += (sin(2.0 * PI * 180.0 * i / ModemConfig.SAMPLE_RATE_HZ) * rumble).toInt()
                v += (sin(2.0 * PI * 55.0 * i / ModemConfig.SAMPLE_RATE_HZ) * rumble * 0.7).toInt()
                v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }

            val hit = AudioDecoder.findSyncChirp(noisy, searchStride = 4, maxSearchOffset = 4_000, band = band)
            assertNotNull("$band: weak chirp lost under rumble", hit)
            assertEquals("$band: chirp located at the wrong offset", 0, hit!!.offsetSamples)
            assertTrue(
                "$band: scored only ${hit.score}, too close to the accept threshold to be safe",
                hit.score > AudioDecoder.DEFAULT_CHIRP_THRESHOLD * 1.5,
            )
        }
    }

    @Test
    fun `the ring buffer is sized for the slowest profile's largest frame`() {
        // A long-range frame that outgrew the receiver's buffer would decode up
        // close and vanish at distance, which is the worst possible symptom.
        val slowest = RoomProfile.entries.minBy { it.symbolRateHz }
        val worstCase = ModemConfig.frameSymbolCount(ModemConfig.MAX_PAYLOAD_BYTES) *
            ModemConfig.samplesPerSymbol(slowest.symbolRateHz)
        assertTrue(
            "maxFrameSamples must cover the worst case",
            ModemConfig.maxFrameSamples() >= worstCase,
        )
    }
}
