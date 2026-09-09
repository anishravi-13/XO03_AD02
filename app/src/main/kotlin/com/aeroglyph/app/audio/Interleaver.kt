package com.aeroglyph.app.audio

/**
 * Bit-level block interleaver applied between [FecCodec] and the FSK symbol
 * mapper.
 *
 * Without it the FEC is very nearly decorative. [FecCodec] emits one 8-bit
 * SECDED codeword per data nibble, and the symbol mapper takes 4 bits at a
 * time -- so both halves of a codeword travel as two *adjacent* symbols. The
 * error unit on an FSK channel is the symbol: when the Goertzel argmax picks
 * the wrong tone, all 4 of that symbol's bits are suspect at once. Four bad
 * bits in one codeword is far past what single-error correction can fix, so
 * one wrong symbol would fail its codeword, and because a failed codeword
 * aborts the whole block, one wrong symbol discarded the entire message.
 *
 * That is exactly the wrong failure curve for a long-range acoustic link,
 * where the realistic condition is "nearly all symbols are fine, a handful are
 * not". Interleaving spreads each codeword's 8 bits across 8 different symbols,
 * so a single symbol error deposits **one** bit into each of four different
 * codewords -- every one of them correctable. The frame survives.
 *
 * Costs nothing: same bit count, same symbol count, same airtime.
 *
 * The permutation is a plain block transpose. Bits are written row-wise into an
 * N x 8 matrix (one row per codeword, MSB first) and read column-wise, so two
 * bits of one codeword end up N bit-positions apart on the wire.
 */
object Interleaver {

    private const val CODEWORD_BITS = 8

    /** Transposes the codeword bit matrix. Output length equals input length. */
    fun interleave(codewords: ByteArray): ByteArray = permute(codewords, forward = true)

    /** Inverse of [interleave]. */
    fun deinterleave(wire: ByteArray): ByteArray = permute(wire, forward = false)

    /**
     * Both directions are the same transpose walked in opposite directions, so
     * a single routine keeps them provably consistent -- an interleaver whose
     * inverse has drifted out of step corrupts every frame while looking
     * perfectly reasonable in isolation.
     */
    private fun permute(input: ByteArray, forward: Boolean): ByteArray {
        val rows = input.size // one row per codeword byte
        if (rows == 0) return ByteArray(0)

        val out = ByteArray(input.size)
        var wireIndex = 0
        for (col in 0 until CODEWORD_BITS) {
            for (row in 0 until rows) {
                val codewordBit = row * CODEWORD_BITS + col
                if (forward) {
                    setBit(out, wireIndex, getBit(input, codewordBit))
                } else {
                    setBit(out, codewordBit, getBit(input, wireIndex))
                }
                wireIndex++
            }
        }
        return out
    }

    /** Bit [index] of the array, counted MSB-first within each byte. */
    private fun getBit(data: ByteArray, index: Int): Int {
        val b = data[index / 8].toInt() and 0xFF
        return (b shr (7 - index % 8)) and 1
    }

    private fun setBit(data: ByteArray, index: Int, value: Int) {
        val byteIndex = index / 8
        val mask = 1 shl (7 - index % 8)
        val current = data[byteIndex].toInt() and 0xFF
        data[byteIndex] = (if (value != 0) current or mask else current and mask.inv()).toByte()
    }
}
