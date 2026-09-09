package com.aeroglyph.app.audio

/** The header fields a receiver can read before it knows the rest of the frame's size. */
data class FrameHeader(
    val payloadLength: Int,
    val sessionId: Int,
    val frameType: FrameType,
    val hopCount: Int,
)

/** A located sync chirp, together with the band its template belonged to. */
data class BandDetection(val band: AcousticBand, val detection: ChirpDetection)

/** A decoded header plus how cleanly its tones resolved. See [AudioDecoder.decodeHeaderScored]. */
data class ScoredHeader(val header: FrameHeader, val confidence: Double)

/** What came out of trying to decode one frame from a PCM buffer. */
sealed class DecodeResult {
    class Success(
        val message: String,
        val sessionId: Int,
        val frameType: FrameType,
        val hopCount: Int,
        val payload: ByteArray,
    ) : DecodeResult() {
        override fun toString(): String =
            "Success(session=$sessionId, type=$frameType, hop=$hopCount, message='$message')"
    }

    /**
     * Partial reception: the header survived, so we know *which* message this
     * was, but the payload didn't pass FEC/CRC.
     *
     * This distinction is what makes targeted repair possible -- a receiver in
     * this state can name the exact session it needs re-sent, instead of only
     * knowing that something somewhere went wrong.
     */
    data class PartialReception(
        val sessionId: Int,
        val frameType: FrameType,
        val hopCount: Int,
    ) : DecodeResult()

    /** The header itself was unusable, so we know nothing at all. Drop it silently. */
    data object CrcFailed : DecodeResult()

    /** No sync chirp yet, or not enough samples for the length the header declared. Keep listening. */
    data object Incomplete : DecodeResult()
}

/**
 * Demodulates PCM16 samples back into a [Frame]: find the sync chirp, decode
 * the fixed-size header block to learn the payload length, then decode
 * exactly that many more symbols for payload+CRC.
 *
 * Symbol decoding uses the Goertzel algorithm at the 16 known tone
 * frequencies rather than a full FFT -- we only ever need energy at
 * frequencies we already know about.
 *
 * The stages are exposed individually (find chirp / read header / read the
 * whole frame) because a live receiver wants to lock onto a chirp once and
 * then wait for the rest of the frame to arrive, rather than re-searching the
 * same audio every time a new buffer shows up.
 */
object AudioDecoder {

    /**
     * Locates this band's sync chirp, correlating in the band's own passband.
     *
     * Both the window and the template are band-passed before correlation --
     * see [DspUtil.bandPassNormalized] for why. Briefly: the score's denominator
     * is the window's *total* energy, so low-frequency room noise that cannot
     * possibly correlate with a chirp was still dominating it, and a distant
     * transmission scored 0.02 instead of 1.00 purely because the room was a
     * room. Filtering both sides identically keeps the matched filter matched
     * and leaves only in-band energy in the denominator.
     */
    fun findSyncChirp(
        buffer: ShortArray,
        sampleRate: Int = ModemConfig.SAMPLE_RATE_HZ,
        threshold: Double = DEFAULT_CHIRP_THRESHOLD,
        searchStride: Int = 1,
        maxSearchOffset: Int = Int.MAX_VALUE,
        band: AcousticBand = ModemConfig.DEFAULT_BAND,
    ): ChirpDetection? = ChirpSync.findChirp(
        buffer = bandLimit(buffer, sampleRate, band),
        template = bandLimit(ChirpSync.generateSyncChirp(sampleRate, band), sampleRate, band),
        threshold = threshold,
        maxSearchOffset = maxSearchOffset,
        searchStride = searchStride,
    )

    private fun bandLimit(samples: ShortArray, sampleRate: Int, band: AcousticBand): ShortArray =
        DspUtil.bandPassNormalized(samples, sampleRate, band.chirpCenterHz, band.chirpBandwidthHz)

    /**
     * Correlates every band's template and keeps the strongest match.
     *
     * The bands' sweeps are disjoint, so a genuine chirp scores well against
     * its own template and near zero against the other -- which makes the
     * chirp itself the band announcement, and means a receiver never has to be
     * told which mode the sender chose.
     */
    fun findSyncChirpAnyBand(
        buffer: ShortArray,
        sampleRate: Int = ModemConfig.SAMPLE_RATE_HZ,
        threshold: Double = DEFAULT_CHIRP_THRESHOLD,
        searchStride: Int = 1,
        maxSearchOffset: Int = Int.MAX_VALUE,
    ): BandDetection? {
        var best: BandDetection? = null
        for (band in AcousticBand.entries) {
            val hit = findSyncChirp(buffer, sampleRate, threshold, searchStride, maxSearchOffset, band)
                ?: continue
            if (best == null || hit.score > best.detection.score) best = BandDetection(band, hit)
        }
        return best
    }

    /** Samples the header block occupies at a given symbol rate. */
    fun headerSampleCount(symbolRateHz: Double, sampleRate: Int = ModemConfig.SAMPLE_RATE_HZ): Int =
        AudioEncoder.HEADER_SYMBOL_COUNT * ModemConfig.samplesPerSymbol(symbolRateHz, sampleRate)

    /** Samples the payload+CRC block occupies for a declared payload length. */
    fun payloadSampleCount(
        payloadLength: Int,
        symbolRateHz: Double,
        sampleRate: Int = ModemConfig.SAMPLE_RATE_HZ,
    ): Int = (payloadLength + ModemConfig.CRC_BYTES) * 4 * ModemConfig.samplesPerSymbol(symbolRateHz, sampleRate)

    /**
     * Reads just the header block, starting at [symbolStart] (the first sample
     * after the sync chirp). Returns null if the header's own FEC couldn't
     * recover it. Cheap: 12 symbols.
     */
    fun decodeHeader(
        buffer: ShortArray,
        symbolStart: Int,
        sampleRate: Int = ModemConfig.SAMPLE_RATE_HZ,
        symbolRateHz: Double = ModemConfig.DEFAULT_SYMBOL_RATE_HZ,
        band: AcousticBand = ModemConfig.DEFAULT_BAND,
    ): FrameHeader? = decodeHeaderScored(buffer, symbolStart, sampleRate, symbolRateHz, band)?.header

    /**
     * As [decodeHeader], but also reports how cleanly the header's tones
     * resolved -- the mean ratio between the winning Goertzel bin and the
     * runner-up, across the twelve header symbols.
     *
     * This exists because "the FEC accepted it" is a much weaker statement than
     * it looks. Extended Hamming(8,4) treats 144 of the 256 possible bytes as
     * valid-or-correctable, so a block of six codewords full of noise is
     * accepted about 3% of the time. A receiver that tries several candidate
     * symbol rates and takes the first one the FEC tolerates will therefore
     * lock onto a phantom header roughly one time in ten, consume the lock, and
     * silently discard a frame that was arriving perfectly well.
     *
     * Confidence separates the cases on physics rather than luck: at the rate
     * the sender actually used, each symbol window contains one steady tone and
     * the winning bin towers over the rest. At a wrong rate the window straddles
     * symbol boundaries, energy smears across bins, and the margin collapses
     * towards 1. The caller picks the highest-confidence candidate instead of
     * the first plausible one.
     */
    fun decodeHeaderScored(
        buffer: ShortArray,
        symbolStart: Int,
        sampleRate: Int = ModemConfig.SAMPLE_RATE_HZ,
        symbolRateHz: Double = ModemConfig.DEFAULT_SYMBOL_RATE_HZ,
        band: AcousticBand = ModemConfig.DEFAULT_BAND,
    ): ScoredHeader? {
        val samplesPerSymbol = ModemConfig.samplesPerSymbol(symbolRateHz, sampleRate)
        val headerSymbolCount = AudioEncoder.HEADER_SYMBOL_COUNT
        if (symbolStart < 0 || symbolStart + headerSymbolCount * samplesPerSymbol > buffer.size) return null

        val demodulated = demodulate(buffer, symbolStart, headerSymbolCount, samplesPerSymbol, sampleRate, band)
        val plain = decodeBlock(symbolsToBytes(demodulated.symbols)) ?: return null

        val payloadLength = plain[0].toInt() and 0xFF
        // A nonsensical length must never drive an allocation or a read past
        // the end of the buffer, even if FEC somehow signed off on it.
        if (payloadLength > ModemConfig.MAX_PAYLOAD_BYTES) return null

        val sessionId = plain[1].toInt() and 0xFF
        val (frameType, hopCount) = Frame.parseTypeAndHop(plain[2].toInt() and 0xFF)
        return ScoredHeader(
            header = FrameHeader(payloadLength, sessionId, frameType, hopCount),
            confidence = demodulated.meanMargin,
        )
    }

    /**
     * Decodes a full frame whose sync chirp has already been located, with
     * [symbolStart] pointing at the first symbol after that chirp.
     */
    fun decodeFromSymbolStart(
        buffer: ShortArray,
        symbolStart: Int,
        sampleRate: Int = ModemConfig.SAMPLE_RATE_HZ,
        symbolRateHz: Double = ModemConfig.DEFAULT_SYMBOL_RATE_HZ,
        band: AcousticBand = ModemConfig.DEFAULT_BAND,
    ): DecodeResult {
        val samplesPerSymbol = ModemConfig.samplesPerSymbol(symbolRateHz, sampleRate)
        val headerSymbolCount = AudioEncoder.HEADER_SYMBOL_COUNT
        if (symbolStart + headerSymbolCount * samplesPerSymbol > buffer.size) return DecodeResult.Incomplete

        val header = decodeHeader(buffer, symbolStart, sampleRate, symbolRateHz, band)
            ?: return DecodeResult.CrcFailed

        val restSymbolCount = (header.payloadLength + ModemConfig.CRC_BYTES) * 4
        val restStart = symbolStart + headerSymbolCount * samplesPerSymbol
        if (restStart + restSymbolCount * samplesPerSymbol > buffer.size) return DecodeResult.Incomplete

        val restSymbols = decodeSymbols(buffer, restStart, restSymbolCount, samplesPerSymbol, sampleRate, band)
        val restPlain = decodeBlock(symbolsToBytes(restSymbols))
            ?: return DecodeResult.PartialReception(header.sessionId, header.frameType, header.hopCount)

        val payloadBytes = restPlain.copyOfRange(0, header.payloadLength)
        val crcBytes = restPlain.copyOfRange(header.payloadLength, header.payloadLength + ModemConfig.CRC_BYTES)
        val receivedCrc = ((crcBytes[0].toInt() and 0xFF) shl 24) or
            ((crcBytes[1].toInt() and 0xFF) shl 16) or
            ((crcBytes[2].toInt() and 0xFF) shl 8) or
            (crcBytes[3].toInt() and 0xFF)

        val headerPlain = byteArrayOf(
            header.payloadLength.toByte(),
            header.sessionId.toByte(),
            (((header.frameType.bits and 0b111) shl 5) or (header.hopCount and 0x1F)).toByte(),
        )
        if (!Crc32.verify(headerPlain + payloadBytes, receivedCrc)) {
            return DecodeResult.PartialReception(header.sessionId, header.frameType, header.hopCount)
        }

        return DecodeResult.Success(
            message = String(payloadBytes, Charsets.UTF_8),
            sessionId = header.sessionId,
            frameType = header.frameType,
            hopCount = header.hopCount,
            payload = payloadBytes,
        )
    }

    /** Find-then-decode, for one-shot buffers (this is what the unit tests use). */
    fun decode(
        buffer: ShortArray,
        sampleRate: Int = ModemConfig.SAMPLE_RATE_HZ,
        symbolRateHz: Double = ModemConfig.DEFAULT_SYMBOL_RATE_HZ,
        chirpThreshold: Double = DEFAULT_CHIRP_THRESHOLD,
        chirpSearchStride: Int = 1,
        band: AcousticBand = ModemConfig.DEFAULT_BAND,
    ): DecodeResult {
        val detection = findSyncChirp(
            buffer = buffer,
            sampleRate = sampleRate,
            threshold = chirpThreshold,
            searchStride = chirpSearchStride,
            band = band,
        ) ?: return DecodeResult.Incomplete

        return decodeFromSymbolStart(
            buffer = buffer,
            symbolStart = detection.offsetSamples + band.leadInSamples(sampleRate),
            sampleRate = sampleRate,
            symbolRateHz = symbolRateHz,
            band = band,
        )
    }

    /** Live magnitude at all 16 tone bins -- feeds both the spectrum visualizer and the energy gate. */
    fun binEnergies(
        buffer: ShortArray,
        offset: Int,
        length: Int,
        sampleRate: Int = ModemConfig.SAMPLE_RATE_HZ,
        band: AcousticBand = ModemConfig.DEFAULT_BAND,
    ): DoubleArray = DoubleArray(ModemConfig.TONE_COUNT) { bin ->
        DspUtil.goertzelMagnitude(buffer, offset, length, sampleRate, band.toneFrequencyHz(bin))
    }

    /** Deinterleave then FEC-decode: the inverse of [AudioEncoder.encodeBlock]. */
    private fun decodeBlock(wire: ByteArray): ByteArray? = FecCodec.decode(Interleaver.deinterleave(wire))

    private class Demodulated(val symbols: IntArray, val meanMargin: Double)

    private fun decodeSymbols(
        buffer: ShortArray,
        start: Int,
        count: Int,
        samplesPerSymbol: Int,
        sampleRate: Int,
        band: AcousticBand,
    ): IntArray = demodulate(buffer, start, count, samplesPerSymbol, sampleRate, band).symbols

    /**
     * Turns a run of symbol windows back into tone indices, with two defences
     * against reverberation.
     *
     * Reverberation, not weak signal, is what actually stops a distant
     * transmission decoding. Measured against a simulated room, attenuating the
     * signal from 30% to 3% changed the symbol error rate not at all, while
     * introducing a reverberant field as strong as the direct path took it from
     * zero to 10%, and four times the direct path took it to 25%. The sync
     * chirp survives all of this because a matched filter integrates an entire
     * sweep; a single 16-way frequency decision has no such protection.
     *
     * The mechanism is simple: the previous symbol's tone is still ringing in
     * the room while this symbol's window is being measured, and past critical
     * distance that echo can be louder than the direct sound of the current
     * tone. So:
     *
     *  1. **Integrate the tail of each window, not all of it.** The previous
     *     symbol's echo decays across the window, so its later part is cleaner.
     *     The window is only shortened as far as tone spacing allows -- too
     *     short and neighbouring tones stop being resolvable, which trades one
     *     failure mode for a worse one.
     *  2. **Subtract the echo we can predict.** Once a symbol is decided, its
     *     contribution to the *next* window's bin for that same tone is
     *     estimated from its own measured strength and removed. Only that one
     *     bin is touched, so a wrong guess cannot corrupt the others.
     *
     * Together these took the simulated error rate from 10.6% to 1.5% at
     * equal direct and reverberant energy, and from 25% to 10.6% at four times
     * reverberant.
     */
    private fun demodulate(
        buffer: ShortArray,
        start: Int,
        count: Int,
        samplesPerSymbol: Int,
        sampleRate: Int,
        band: AcousticBand,
    ): Demodulated {
        // Goertzel needs enough samples to tell neighbouring tones apart: at
        // least two of its bins per tone spacing. That floor wins over the
        // preferred trim, so fast profiles simply trim less.
        val minLength = kotlin.math.ceil(2.0 * sampleRate / band.toneSpacingHz).toInt()
        val length = maxOf(minLength, (samplesPerSymbol * SYMBOL_TAIL_FRACTION).toInt())
            .coerceAtMost(samplesPerSymbol)
        val lead = samplesPerSymbol - length

        val symbols = IntArray(count)
        var marginSum = 0.0
        var previousSymbol = -1
        var previousMagnitude = 0.0

        for (s in 0 until count) {
            val offset = start + s * samplesPerSymbol + lead
            val bins = DoubleArray(ModemConfig.TONE_COUNT) { bin ->
                DspUtil.goertzelMagnitude(buffer, offset, length, sampleRate, band.toneFrequencyHz(bin))
            }

            if (previousSymbol >= 0) {
                bins[previousSymbol] =
                    (bins[previousSymbol] - REVERB_TAIL_CANCEL * previousMagnitude).coerceAtLeast(0.0)
            }

            var best = 0
            var bestMagnitude = -1.0
            var second = -1.0
            for (bin in 0 until ModemConfig.TONE_COUNT) {
                val magnitude = bins[bin]
                if (magnitude > bestMagnitude) {
                    second = bestMagnitude
                    bestMagnitude = magnitude
                    best = bin
                } else if (magnitude > second) {
                    second = magnitude
                }
            }

            symbols[s] = best
            marginSum += if (second <= 0.0) {
                MAX_SYMBOL_MARGIN
            } else {
                (bestMagnitude / second).coerceAtMost(MAX_SYMBOL_MARGIN)
            }
            previousSymbol = best
            previousMagnitude = bestMagnitude
        }

        return Demodulated(symbols, if (count == 0) 0.0 else marginSum / count)
    }

    private fun symbolsToBytes(symbols: IntArray): ByteArray {
        require(symbols.size % 2 == 0)
        return ByteArray(symbols.size / 2) { i ->
            (((symbols[i * 2] and 0x0F) shl 4) or (symbols[i * 2 + 1] and 0x0F)).toByte()
        }
    }

    /**
     * Correlation score a candidate must beat to count as the sync chirp.
     *
     * This was progressively lowered -- 0.6, then 0.45, then 0.28 -- chasing
     * weak signals that were failing to detect. That was treating the symptom:
     * the scores were low because room rumble dominated the normalisation
     * denominator, not because the chirps were faint. With the correlation now
     * done in-band (see [findSyncChirp]), a distant transmission buried in
     * heavy room noise measures 0.62-0.73 rather than 0.02, so the threshold
     * can go back up and reject far more noise.
     *
     * The asymmetry still argues for generosity: a false positive costs a few
     * milliseconds of wasted work and is thrown out by the header check
     * immediately afterwards, while a missed chirp means the message is never
     * seen at all.
     */
    const val DEFAULT_CHIRP_THRESHOLD = 0.35

    /** Ceiling on one symbol's bin-dominance ratio, so a single clean symbol cannot carry an average. */
    private const val MAX_SYMBOL_MARGIN = 10.0

    /**
     * Portion of each symbol window that is actually integrated, taken from the
     * end. The earlier part carries the loudest remains of the previous
     * symbol's echo, plus the encoder's raised-cosine ramp, and contributes
     * more interference than signal. Subject to a hard floor on sample count
     * for tone resolution -- see [demodulate].
     */
    private const val SYMBOL_TAIL_FRACTION = 0.6

    /**
     * How much of the previous symbol's measured strength is treated as echo
     * still present in the next window's bin for that tone.
     *
     * Corresponds to roughly a 0.9s reverberation time at these symbol rates.
     * It is deliberately not tuned finer: the value came from a simulated room
     * whose parameters are themselves estimates, and the structural fix -- that
     * the previous tone's tail is predictable and worth removing -- is what
     * carries the benefit, not the third decimal place.
     */
    private const val REVERB_TAIL_CANCEL = 0.6

    /**
     * Least symbol-dominance a header must show before it is believed at all.
     *
     * A header decoded at the wrong symbol rate smears energy across bins and
     * lands near 1.0; a correctly-aligned one is several times its runner-up.
     * Set low enough to keep weak-but-real signals, high enough that pure noise
     * which happens to satisfy the FEC is still thrown out.
     */
    const val MIN_HEADER_CONFIDENCE = 1.6
}
