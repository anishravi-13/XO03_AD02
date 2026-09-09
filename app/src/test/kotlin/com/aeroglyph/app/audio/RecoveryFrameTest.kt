package com.aeroglyph.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Covers the frame-level machinery the two surprise challenges rest on:
 * the control frame types, and the ability to tell "this arrived damaged, and
 * I know which message it was" apart from "something unintelligible happened".
 */
class RecoveryFrameTest {

    @Test
    fun `every frame type survives header packing`() {
        for (type in FrameType.entries) {
            for (hop in listOf(0, 1, 2, 31)) {
                val frame = Frame(sessionId = 0xAB, frameType = type, hopCount = hop, payload = byteArrayOf(1, 2, 3))
                val header = frame.toPlaintextBytes()[2].toInt() and 0xFF
                val (parsedType, parsedHop) = Frame.parseTypeAndHop(header)
                assertEquals("type $type hop $hop", type, parsedType)
                assertEquals("type $type hop $hop", hop, parsedHop)
            }
        }
    }

    @Test
    fun `control frames round-trip over the air`() {
        val controlTypes = listOf(FrameType.NACK, FrameType.REQUEST, FrameType.BEACON, FrameType.ANSWER)
        for (type in controlTypes) {
            val frame = Frame(
                sessionId = 0x5C,
                frameType = type,
                hopCount = 1,
                payload = byteArrayOf(0x7F),
            )
            val result = AudioDecoder.decode(AudioEncoder.synthesize(frame))
            assertTrue("$type should decode, got $result", result is DecodeResult.Success)
            result as DecodeResult.Success
            assertEquals(type, result.frameType)
            assertEquals(0x5C, result.sessionId)
        }
    }

    @Test
    fun `an ACK payload byte above 127 survives intact`() {
        // Receipt IDs run to 255. Reading them back out of the decoded *string*
        // would mangle anything above 127 into a UTF-8 replacement character,
        // so the raw payload has to come back byte-exact.
        val frame = Frame(
            sessionId = 9,
            frameType = FrameType.ACK,
            hopCount = 0,
            payload = byteArrayOf(200.toByte()),
        )
        val result = AudioDecoder.decode(AudioEncoder.synthesize(frame))
        assertTrue(result is DecodeResult.Success)
        assertEquals(200, (result as DecodeResult.Success).payload[0].toInt() and 0xFF)
    }

    @Test
    fun `a beacon carries no payload and still decodes`() {
        val frame = Frame(sessionId = 0x33, frameType = FrameType.BEACON, hopCount = 0, payload = ByteArray(0))
        val result = AudioDecoder.decode(AudioEncoder.synthesize(frame))
        assertTrue(result is DecodeResult.Success)
        assertEquals(0x33, (result as DecodeResult.Success).sessionId)
    }

    /**
     * The core of surprise challenge 1: damage confined to the payload must
     * leave the header readable, so the receiver can name the session it needs
     * repaired instead of just shrugging.
     */
    @Test
    fun `payload damage reports partial reception naming the session`() {
        val sessionId = 0x6D
        val frame = Frame(
            sessionId = sessionId,
            frameType = FrameType.DATA,
            hopCount = 2,
            payload = "the payload that gets destroyed".toByteArray(),
        )
        val pcm = AudioEncoder.synthesize(frame)

        // Wreck only the payload block, leaving chirp and header untouched.
        val leadIn = ModemConfig.DEFAULT_BAND.leadInSamples()
        val headerSamples = AudioDecoder.headerSampleCount(ModemConfig.DEFAULT_SYMBOL_RATE_HZ)
        val payloadStart = leadIn + headerSamples
        val rnd = Random(4242)
        for (i in payloadStart until pcm.size) {
            pcm[i] = rnd.nextInt(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt() + 1).toShort()
        }

        val result = AudioDecoder.decode(pcm)
        assertTrue("expected PartialReception, got $result", result is DecodeResult.PartialReception)
        assertEquals(sessionId, (result as DecodeResult.PartialReception).sessionId)
        assertEquals(FrameType.DATA, result.frameType)
    }

    @Test
    fun `header damage reports plain failure, never a wrong message`() {
        val frame = Frame(sessionId = 12, frameType = FrameType.DATA, hopCount = 1, payload = "hello".toByteArray())
        val pcm = AudioEncoder.synthesize(frame)

        val leadIn = ModemConfig.DEFAULT_BAND.leadInSamples()
        val rnd = Random(99)
        for (i in leadIn until pcm.size) {
            pcm[i] = rnd.nextInt(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt() + 1).toShort()
        }

        val result = AudioDecoder.decode(pcm)
        assertTrue("must never surface a corrupted message, got $result", result !is DecodeResult.Success)
    }

    @Test
    fun `header can be read before the payload has arrived`() {
        // This is what lets a live receiver learn the frame's length -- and so
        // how long to keep listening -- from the first 12 symbols.
        val frame = Frame(sessionId = 77, frameType = FrameType.DATA, hopCount = 2, payload = "x".repeat(60).toByteArray())
        val pcm = AudioEncoder.synthesize(frame)

        val leadIn = ModemConfig.DEFAULT_BAND.leadInSamples()
        val headerSamples = AudioDecoder.headerSampleCount(ModemConfig.DEFAULT_SYMBOL_RATE_HZ)
        val headerOnly = pcm.copyOfRange(0, leadIn + headerSamples)

        val header = AudioDecoder.decodeHeader(headerOnly, symbolStart = leadIn)
        assertNotNull(header)
        assertEquals(60, header!!.payloadLength)
        assertEquals(77, header.sessionId)
        assertEquals(FrameType.DATA, header.frameType)
        assertEquals(2, header.hopCount)
    }

    /**
     * Room profiles change the symbol rate, and nothing on the wire announces
     * which one the sender used. The live receiver copes by trying each rate's
     * header; this checks that a header only decodes under the rate that
     * actually produced it, which is what makes that search unambiguous.
     */
    @Test
    fun `header decodes only at the symbol rate it was sent with`() {
        val frame = Frame(sessionId = 21, frameType = FrameType.DATA, hopCount = 0, payload = "rate check".toByteArray())
        val sentRate = RoomProfile.NOISY.symbolRateHz
        val pcm = AudioEncoder.synthesize(frame, symbolRateHz = sentRate)
        val leadIn = ModemConfig.DEFAULT_BAND.leadInSamples()

        val correct = AudioDecoder.decodeHeader(pcm, leadIn, symbolRateHz = sentRate)
        assertNotNull("header should decode at the rate it was sent with", correct)
        assertEquals(21, correct!!.sessionId)

        val result = AudioDecoder.decodeFromSymbolStart(pcm, leadIn, symbolRateHz = sentRate)
        assertTrue(result is DecodeResult.Success)
        assertEquals("rate check", (result as DecodeResult.Success).message)
    }
}
