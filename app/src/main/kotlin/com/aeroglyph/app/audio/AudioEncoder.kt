package com.aeroglyph.app.audio

import kotlin.math.PI
import kotlin.math.sin

/**
 * Turns a [Frame] into raw PCM16 mono samples: sync chirp, then the
 * FEC-protected header block, then the FEC-protected payload+CRC block, then
 * the end chirp.
 *
 * The header is sent as its own fixed-size block *first* and on its own so a
 * receiver can decode just those symbols, learn the payload length, and only
 * then know how many more symbols to listen for -- classic
 * "self-describing-length" framing.
 *
 * Each block is FEC-encoded and then bit-interleaved (see [Interleaver]) before
 * being cut into 4-bit FSK symbols. The interleave is what makes the FEC worth
 * having on this channel: it guarantees the bits of any one codeword land in
 * different symbols, so losing a symbol costs one correctable bit per codeword
 * rather than destroying a codeword outright.
 *
 * Because [FecCodec] encodes each 4-bit nibble into a full 8-bit codeword byte,
 * and our alphabet is exactly 16-FSK (4 bits/symbol), every encoded byte maps
 * to precisely two symbols with zero padding -- no bit-packing needed anywhere
 * in this pipeline.
 */
object AudioEncoder {

    /** Symbols per encoded byte: high nibble, then low nibble. */
    private fun bytesToSymbols(bytes: ByteArray): IntArray {
        val symbols = IntArray(bytes.size * 2)
        for (i in bytes.indices) {
            val b = bytes[i].toInt() and 0xFF
            symbols[i * 2] = (b shr 4) and 0x0F
            symbols[i * 2 + 1] = b and 0x0F
        }
        return symbols
    }

    /** FEC then interleave: the exact byte sequence that goes on the wire for one block. */
    internal fun encodeBlock(plain: ByteArray): ByteArray = Interleaver.interleave(FecCodec.encode(plain))

    /** Number of FSK symbols the header block always occupies, regardless of payload length. */
    val HEADER_SYMBOL_COUNT = ModemConfig.HEADER_BYTES * 2 /* FEC bytes-out */ * 2 /* symbols-per-byte */

    fun synthesize(
        frame: Frame,
        sampleRate: Int = ModemConfig.SAMPLE_RATE_HZ,
        symbolRateHz: Double = ModemConfig.DEFAULT_SYMBOL_RATE_HZ,
        band: AcousticBand = ModemConfig.DEFAULT_BAND,
    ): ShortArray {
        val plaintext = frame.toPlaintextBytes()
        val headerPlain = plaintext.copyOfRange(0, ModemConfig.HEADER_BYTES)
        val restPlain = plaintext.copyOfRange(ModemConfig.HEADER_BYTES, plaintext.size)

        val headerSymbols = bytesToSymbols(encodeBlock(headerPlain))
        check(headerSymbols.size == HEADER_SYMBOL_COUNT)
        val restSymbols = bytesToSymbols(encodeBlock(restPlain))

        val syncChirp = ChirpSync.generateSyncChirp(sampleRate, band)
        val endChirp = ChirpSync.generateEndChirp(sampleRate, band)
        val body = synthesizeSymbols(headerSymbols + restSymbols, sampleRate, symbolRateHz, band)

        return syncChirp + body + endChirp
    }

    private fun synthesizeSymbols(
        symbols: IntArray,
        sampleRate: Int,
        symbolRateHz: Double,
        band: AcousticBand,
    ): ShortArray {
        val samplesPerSymbol = (sampleRate / symbolRateHz).toInt()
        val fadeSamples = (sampleRate * ModemConfig.SYMBOL_FADE_MS / 1000.0).toInt().coerceAtMost(samplesPerSymbol / 2)
        val out = ShortArray(symbols.size * samplesPerSymbol)

        for (s in symbols.indices) {
            val freq = band.toneFrequencyHz(symbols[s])
            val base = s * samplesPerSymbol
            for (i in 0 until samplesPerSymbol) {
                val t = i / sampleRate.toDouble()
                val envelope = DspUtil.raisedCosineEnvelope(i, samplesPerSymbol, fadeSamples)
                out[base + i] = (sin(2.0 * PI * freq * t) * Short.MAX_VALUE * envelope).toInt().toShort()
            }
        }
        return out
    }
}
