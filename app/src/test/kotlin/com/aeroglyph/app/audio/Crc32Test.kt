package com.aeroglyph.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Crc32Test {

    @Test
    fun `same input always produces same crc`() {
        val data = "Aeroglyph".toByteArray()
        assertEquals(Crc32.compute(data), Crc32.compute(data.copyOf()))
    }

    @Test
    fun `verify passes for untouched data`() {
        val data = "hop the mesh".toByteArray()
        val crc = Crc32.compute(data)
        assertTrue(Crc32.verify(data, crc))
    }

    @Test
    fun `verify fails when a single byte changes`() {
        val data = "hop the mesh".toByteArray()
        val crc = Crc32.compute(data)
        val corrupted = data.copyOf()
        corrupted[3] = (corrupted[3].toInt() xor 0x01).toByte()
        assertFalse(Crc32.verify(corrupted, crc))
    }

    @Test
    fun `empty input has a stable known crc`() {
        // CRC-32 of the empty string is well known to be 0 -- a useful sanity anchor.
        assertEquals(0, Crc32.compute(ByteArray(0)))
    }
}
