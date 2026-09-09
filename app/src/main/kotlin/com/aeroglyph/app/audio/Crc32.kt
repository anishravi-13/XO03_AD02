package com.aeroglyph.app.audio

/** Standard CRC-32 (IEEE 802.3 polynomial 0xEDB88320), the final integrity gate
 *  a frame must pass before Aeroglyph ever shows it to a human. */
object Crc32 {
    private val table: IntArray = IntArray(256).also { t ->
        for (n in 0 until 256) {
            var c = n
            repeat(8) {
                c = if (c and 1 != 0) (0xEDB88320.toInt() xor (c ushr 1)) else (c ushr 1)
            }
            t[n] = c
        }
    }

    fun compute(data: ByteArray): Int {
        var crc = 0xFFFFFFFF.toInt()
        for (b in data) {
            val idx = (crc xor b.toInt()) and 0xFF
            crc = table[idx] xor (crc ushr 8)
        }
        return crc.inv()
    }

    fun verify(data: ByteArray, expected: Int): Boolean = compute(data) == expected
}
