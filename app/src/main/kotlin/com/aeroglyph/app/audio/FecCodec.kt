package com.aeroglyph.app.audio

/**
 * Forward error correction for the acoustic channel.
 *
 * Baseline is Hamming(7,4), as the spec allows for a time-constrained build --
 * but plain Hamming(7,4) has no way to *detect* a double-bit error, so it will
 * silently "correct" some bad nibbles into the wrong value rather than ever
 * reporting failure. Since the contract here is "return null on uncorrectable
 * error," Aeroglyph ships the one-line-richer extended form instead:
 * Hamming(8,4) SECDED -- the same (7,4) codeword plus one overall parity bit,
 * giving single-error-correction *and* double-error-detection for free. Each
 * nibble still maps to exactly one output byte, so callers can treat this as
 * a drop-in Hamming(7,4) replacement.
 *
 * TODO(reed-solomon): if a future revision needs to survive burst errors
 * longer than ~1 bit per nibble, swap this object out for a Reed-Solomon
 * codec behind the same encode/decode signature -- nothing above this layer
 * (AudioEncoder/AudioDecoder) needs to change.
 */
object FecCodec {

    /** Encodes each nibble of [data] into one SECDED-protected byte. Output is
     *  exactly twice the input length. */
    fun encode(data: ByteArray): ByteArray {
        val out = ByteArray(data.size * 2)
        for (i in data.indices) {
            val b = data[i].toInt() and 0xFF
            out[i * 2] = encodeNibble((b shr 4) and 0x0F)
            out[i * 2 + 1] = encodeNibble(b and 0x0F)
        }
        return out
    }

    /** Decodes [encoded] back to the original bytes, correcting any single-bit
     *  error per nibble. Returns null if a double-bit error is detected in any
     *  nibble (uncorrectable), or if [encoded] isn't an even number of bytes. */
    fun decode(encoded: ByteArray): ByteArray? {
        if (encoded.size % 2 != 0) return null
        val out = ByteArray(encoded.size / 2)
        for (i in out.indices) {
            val hi = decodeNibble(encoded[i * 2].toInt() and 0xFF) ?: return null
            val lo = decodeNibble(encoded[i * 2 + 1].toInt() and 0xFF) ?: return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /** Layout (MSB to LSB): p1 p2 d1 p3 d2 d3 d4 p0 */
    private fun encodeNibble(nibble: Int): Byte {
        val d1 = (nibble shr 3) and 1
        val d2 = (nibble shr 2) and 1
        val d3 = (nibble shr 1) and 1
        val d4 = nibble and 1

        val p1 = d1 xor d2 xor d4
        val p2 = d1 xor d3 xor d4
        val p3 = d2 xor d3 xor d4
        val core = (p1 shl 6) or (p2 shl 5) or (d1 shl 4) or (p3 shl 3) or (d2 shl 2) or (d3 shl 1) or d4
        val p0 = Integer.bitCount(core) and 1 // overall even parity across the 7 core bits

        return ((core shl 1) or p0).toByte()
    }

    private fun decodeNibble(codewordByte: Int): Int? {
        var core = (codewordByte shr 1) and 0x7F
        val p0 = codewordByte and 1

        var p1 = (core shr 6) and 1
        var p2 = (core shr 5) and 1
        var d1 = (core shr 4) and 1
        var p3 = (core shr 3) and 1
        var d2 = (core shr 2) and 1
        var d3 = (core shr 1) and 1
        var d4 = core and 1

        val c1 = p1 xor d1 xor d2 xor d4
        val c2 = p2 xor d1 xor d3 xor d4
        val c3 = p3 xor d2 xor d3 xor d4
        val syndrome = c1 or (c2 shl 1) or (c3 shl 2) // 0 = no error in the 7 core bits, else 1-indexed bit position

        val overallParityBad = (Integer.bitCount(core) and 1) != p0

        when {
            syndrome == 0 && !overallParityBad -> {
                // clean codeword, nothing to do
            }
            syndrome != 0 && overallParityBad -> {
                // single-bit error inside the 7 core bits -- flip it and re-extract
                val bitIndexFromLsb = 7 - syndrome
                core = core xor (1 shl bitIndexFromLsb)
                p1 = (core shr 6) and 1
                p2 = (core shr 5) and 1
                d1 = (core shr 4) and 1
                p3 = (core shr 3) and 1
                d2 = (core shr 2) and 1
                d3 = (core shr 1) and 1
                d4 = core and 1
            }
            syndrome == 0 && overallParityBad -> {
                // the overall parity bit itself flipped; core data bits are still correct
            }
            else -> {
                // syndrome != 0 but overall parity checks out: two bits flipped.
                // SECDED can tell something is wrong but cannot safely correct it.
                return null
            }
        }

        return (d1 shl 3) or (d2 shl 2) or (d3 shl 1) or d4
    }
}
