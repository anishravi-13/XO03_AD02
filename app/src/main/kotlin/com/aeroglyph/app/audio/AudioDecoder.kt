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

    fun findSyncChirp(
        buffer: ShortArray,
        sampleRate: Int = ModemConfig.SAMPLE_RATE_HZ,
        threshold: Double = DEFAULT_CHIRP_THRESHOLD,
        searchStride: Int = 1,
        maxSearchOffset: Int = Int.MAX_VALUE,
        band: AcousticBand = ModemConfig.DEFAULT_BAND,
    ): ChirpDetection? = ChirpSync.findChirp(
        buffer = buffer,
        template = ChirpSync.generateSyncChirp(sampleRate, band),
        threshold = threshold,
        maxSearchOffset = maxSearchOffset,
        searchStride = searchStride,
    )

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

        var marginSum = 0.0
        val symbols = IntArray(headerSymbolCount) { s ->
            val (bin, margin) = strongestBinScored(
                buffer,
                symbolStart + s * samplesPerSymbol,
                samplesPerSymbol,
                sampleRate,
                band,
            )
            marginSum += margin
            bin
        }

        val plain = decodeBlock(symbolsToBytes(symbols)) ?: return null

        val payloadLength = plain[0].toInt() and 0xFF
        // A nonsensical length must never drive an allocation or a read past
        // the end of the buffer, even if FEC somehow signed off on it.
        if (payloadLength > ModemConfig.MAX_PAYLOAD_BYTES) return null

        val sessionId = plain[1].toInt() and 0xFF
        val (frameType, hopCount) = Frame.parseTypeAndHop(plain[2].toInt() and 0xFF)
        return ScoredHeader(
            header = FrameHeader(payloadLength, sessionId, frameType, hopCount),
            confidence = marginSum / headerSymbolCount,
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
        val template = ChirpSync.generateSyncChirp(sampleRate, band)
        val detection = ChirpSync.findChirp(
            buffer = buffer,
            template = template,
            threshold = chirpThreshold,
            searchStride = chirpSearchStride,
        ) ?: return DecodeResult.Incomplete

        return decodeFromSymbolStart(
            buffer = buffer,
            symbolStart = detection.offsetSamples + template.size,
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

    private fun decodeSymbols(
        buffer: ShortArray,
        start: Int,
        count: Int,
        samplesPerSymbol: Int,
        sampleRate: Int,
        band: AcousticBand,
    ): IntArray = IntArray(count) { s ->
        strongestBin(buffer, start + s * samplesPerSymbol, samplesPerSymbol, sampleRate, band)
    }

    private fun strongestBin(
        buffer: ShortArray,
        offset: Int,
        length: Int,
        sampleRate: Int,
        band: AcousticBand,
    ): Int = strongestBinScored(buffer, offset, length, sampleRate, band).first

    /**
     * The winning tone bin, plus how far it stands above the runner-up.
     *
     * The margin is capped because one exceptionally clean symbol should not be
     * able to vouch for eleven poor ones when these are averaged.
     */
    private fun strongestBinScored(
        buffer: ShortArray,
        offset: Int,
        length: Int,
        sampleRate: Int,
        band: AcousticBand,
    ): Pair<Int, Double> {
        var bestBin = 0
        var best = -1.0
        var second = -1.0
        for (bin in 0 until ModemConfig.TONE_COUNT) {
            val magnitude = DspUtil.goertzelMagnitude(buffer, offset, length, sampleRate, band.toneFrequencyHz(bin))
            if (magnitude > best) {
                second = best
                best = magnitude
                bestBin = bin
            } else if (magnitude > second) {
                second = magnitude
            }
        }
        val margin = if (second <= 0.0) MAX_SYMBOL_MARGIN else (best / second).coerceAtMost(MAX_SYMBOL_MARGIN)
        return bestBin to margin
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
     * Lowered twice, for two different reasons. First from the original 0.6 to
     * 0.45: a real room adds reverb and the mic's response across the sweep is
     * not flat, so a genuine chirp scores 0.5-0.7 rather than near 1.0. Then to
     * 0.28 for long range, where the direct path is weak relative to the
     * reverberant tail and correlation scores fall further still.
     *
     * The asymmetry justifies being generous: a false positive costs a few
     * milliseconds of wasted Goertzel work and is thrown out by the header FEC
     * immediately afterwards, while a missed chirp means the message is never
     * seen at all.
     */
    const val DEFAULT_CHIRP_THRESHOLD = 0.28

    /** Ceiling on one symbol's bin-dominance ratio, so a single clean symbol cannot carry an average. */
    private const val MAX_SYMBOL_MARGIN = 10.0

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
