package com.aeroglyph.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AudioModemRoundTripTest {

    private fun roundTrip(frame: Frame, symbolRateHz: Double = ModemConfig.DEFAULT_SYMBOL_RATE_HZ): DecodeResult {
        val pcm = AudioEncoder.synthesize(frame, symbolRateHz = symbolRateHz)
        return AudioDecoder.decode(pcm, symbolRateHz = symbolRateHz)
    }

    @Test
    fun `clean channel round trip - DATA frame`() {
        val frame = Frame(sessionId = 42, frameType = FrameType.DATA, hopCount = 2, payload = "Welcome to Aeroglyph".toByteArray())
        val result = roundTrip(frame)
        assertTrue("expected Success, got $result", result is DecodeResult.Success)
        result as DecodeResult.Success
        assertEquals("Welcome to Aeroglyph", result.message)
        assertEquals(42, result.sessionId)
        assertEquals(FrameType.DATA, result.frameType)
        assertEquals(2, result.hopCount)
    }

    @Test
    fun `clean channel round trip - RELAY frame with decremented hop count`() {
        val frame = Frame(sessionId = 7, frameType = FrameType.RELAY, hopCount = 1, payload = "relayed message".toByteArray())
        val result = roundTrip(frame)
        assertTrue(result is DecodeResult.Success)
        result as DecodeResult.Success
        assertEquals(FrameType.RELAY, result.frameType)
        assertEquals(1, result.hopCount)
    }

    @Test
    fun `clean channel round trip - ACK frame`() {
        val frame = Frame(sessionId = 200, frameType = FrameType.ACK, hopCount = 0, payload = byteArrayOf(0x0F))
        val result = roundTrip(frame)
        assertTrue(result is DecodeResult.Success)
        result as DecodeResult.Success
        assertEquals(FrameType.ACK, result.frameType)
    }

    @Test
    fun `empty payload round-trips`() {
        val frame = Frame(sessionId = 1, frameType = FrameType.DATA, hopCount = 0, payload = ByteArray(0))
        val result = roundTrip(frame)
        assertTrue(result is DecodeResult.Success)
        assertEquals("", (result as DecodeResult.Success).message)
    }

    @Test
    fun `near-max-length payload round-trips`() {
        val payload = "x".repeat(ModemConfig.MAX_PAYLOAD_BYTES).toByteArray()
        val frame = Frame(sessionId = 5, frameType = FrameType.DATA, hopCount = 0, payload = payload)
        val result = roundTrip(frame)
        assertTrue(result is DecodeResult.Success)
        assertEquals(ModemConfig.MAX_PAYLOAD_BYTES, (result as DecodeResult.Success).message.length)
    }

    @Test
    fun `moderate background noise within FEC correction capacity still decodes`() {
        val frame = Frame(sessionId = 99, frameType = FrameType.DATA, hopCount = 2, payload = "visit xocode.dev".toByteArray())
        val clean = AudioEncoder.synthesize(frame)
        val noisy = addNoise(clean, amplitude = 4000, seed = 1234L)

        val result = AudioDecoder.decode(noisy)
        assertTrue("expected Success under moderate noise, got $result", result is DecodeResult.Success)
        assertEquals("visit xocode.dev", (result as DecodeResult.Success).message)
    }

    @Test
    fun `noise beyond correction capacity fails safe -- never a wrong Success`() {
        val frame = Frame(sessionId = 99, frameType = FrameType.DATA, hopCount = 2, payload = "visit xocode.dev".toByteArray())
        val clean = AudioEncoder.synthesize(frame)

        // Keep the sync chirp intact (so we actually exercise header/payload
        // decoding rather than just failing chirp detection) and obliterate
        // everything after it with full-scale random noise.
        val syncLength = ChirpSync.generateSyncChirp().size
        val destroyed = clean.copyOf()
        val rnd = Random(5678L)
        for (i in syncLength until destroyed.size) {
            destroyed[i] = rnd.nextInt(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt() + 1).toShort()
        }

        val result = AudioDecoder.decode(destroyed)
        assertTrue(
            "heavy noise must never silently produce a corrupted-but-accepted message, got $result",
            result !is DecodeResult.Success,
        )
    }

    private fun addNoise(samples: ShortArray, amplitude: Int, seed: Long): ShortArray {
        val rnd = Random(seed)
        return ShortArray(samples.size) { i ->
            val noisy = samples[i] + rnd.nextInt(-amplitude, amplitude + 1)
            noisy.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}
