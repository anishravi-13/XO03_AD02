package com.aeroglyph.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FecCodecTest {

    @Test
    fun `every byte value round-trips cleanly with no corruption`() {
        for (value in 0..255) {
            val input = byteArrayOf(value.toByte())
            val decoded = FecCodec.decode(FecCodec.encode(input))
            assertArrayEquals("byte $value", input, decoded)
        }
    }

    @Test
    fun `multi-byte payload round-trips cleanly`() {
        val input = "Aeroglyph carries this over 17-19.5kHz".toByteArray()
        assertArrayEquals(input, FecCodec.decode(FecCodec.encode(input)))
    }

    @Test
    fun `a single flipped bit anywhere in a codeword is always corrected`() {
        // Every nibble, with every possible single-bit flip within its 8-bit
        // SECDED codeword, must still decode back to the original nibble --
        // this is exactly "within Hamming's correction capacity."
        for (nibble in 0..15) {
            val input = byteArrayOf(((nibble shl 4) or nibble).toByte())
            val encoded = FecCodec.encode(input)
            for (bitPos in 0..7) {
                val corrupted = encoded.copyOf()
                corrupted[0] = (corrupted[0].toInt() xor (1 shl bitPos)).toByte()
                val decoded = FecCodec.decode(corrupted)
                assertEquals("nibble=$nibble bitPos=$bitPos", input[0], decoded?.get(0))
            }
        }
    }

    @Test
    fun `any double-bit flip in a codeword is detected as uncorrectable`() {
        // Extended Hamming(8,4) SECDED guarantees detection (never silent
        // miscorrection) of any 2-bit error -- exhaustively verify that here
        // rather than spot-checking, since it's cheap (16 nibbles x 28 pairs).
        for (nibble in 0..15) {
            val input = byteArrayOf(((nibble shl 4) or nibble).toByte())
            val encoded = FecCodec.encode(input)
            for (bit1 in 0..7) {
                for (bit2 in (bit1 + 1)..7) {
                    val corrupted = encoded.copyOf()
                    corrupted[0] = (corrupted[0].toInt() xor (1 shl bit1) xor (1 shl bit2)).toByte()
                    val decoded = FecCodec.decode(corrupted)
                    assertNull("nibble=$nibble bits=$bit1,$bit2 should be uncorrectable, got $decoded", decoded)
                }
            }
        }
    }

    @Test
    fun `odd-length input is rejected`() {
        assertNull(FecCodec.decode(ByteArray(3)))
    }
}
