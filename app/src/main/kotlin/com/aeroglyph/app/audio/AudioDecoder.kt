package com.aeroglyph.app.audio

/** The header fields a receiver can read before it knows the rest of the frame's size. */
data class FrameHeader(
    val payloadLength: Int,
    val sessionId: Int,
    val frameType: FrameType,
    val hopCount: Int,
)

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
    ): ChirpDetection? = ChirpSync.findChirp(
        buffer = buffer,
        template = ChirpSync.generateSyncChirp(sampleRate),
        threshold = threshold,
        maxSearchOffset = maxSearchOffset,
        searchStride = searchStride,
    )

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
    ): FrameHeader? {
        val samplesPerSymbol = ModemConfig.samplesPerSymbol(symbolRateHz, sampleRate)
        val headerSymbolCount = AudioEncoder.HEADER_SYMBOL_COUNT
        if (symbolStart < 0 || symbolStart + headerSymbolCount * samplesPerSymbol > buffer.size) return null

        val symbols = decodeSymbols(buffer, symbolStart, headerSymbolCount, samplesPerSymbol, sampleRate)
        val plain = FecCodec.decode(symbolsToBytes(symbols)) ?: return null

        val payloadLength = plain[0].toInt() and 0xFF
        // A nonsensical length must never drive an allocation or a read past
        // the end of the buffer, even if FEC somehow signed off on it.
        if (payloadLength > ModemConfig.MAX_PAYLOAD_BYTES) return null

        val sessionId = plain[1].toInt() and 0xFF
        val (frameType, hopCount) = Frame.parseTypeAndHop(plain[2].toInt() and 0xFF)
        return FrameHeader(payloadLength, sessionId, frameType, hopCount)
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
    ): DecodeResult {
        val samplesPerSymbol = ModemConfig.samplesPerSymbol(symbolRateHz, sampleRate)
        val headerSymbolCount = AudioEncoder.HEADER_SYMBOL_COUNT
        if (symbolStart + headerSymbolCount * samplesPerSymbol > buffer.size) return DecodeResult.Incomplete

        val header = decodeHeader(buffer, symbolStart, sampleRate, symbolRateHz)
            ?: return DecodeResult.CrcFailed

        val restSymbolCount = (header.payloadLength + ModemConfig.CRC_BYTES) * 4
        val restStart = symbolStart + headerSymbolCount * samplesPerSymbol
        if (restStart + restSymbolCount * samplesPerSymbol > buffer.size) return DecodeResult.Incomplete

        val restSymbols = decodeSymbols(buffer, restStart, restSymbolCount, samplesPerSymbol, sampleRate)
        val restPlain = FecCodec.decode(symbolsToBytes(restSymbols))
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
    ): DecodeResult {
        val template = ChirpSync.generateSyncChirp(sampleRate)
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
        )
    }

    /** Live magnitude at all 16 tone bins -- feeds both the spectrum visualizer and the energy gate. */
    fun binEnergies(
        buffer: ShortArray,
        offset: Int,
        length: Int,
        sampleRate: Int = ModemConfig.SAMPLE_RATE_HZ,
    ): DoubleArray = DoubleArray(ModemConfig.TONE_COUNT) { bin ->
        DspUtil.goertzelMagnitude(buffer, offset, length, sampleRate, ModemConfig.toneFrequencyHz(bin))
    }

    private fun decodeSymbols(buffer: ShortArray, start: Int, count: Int, samplesPerSymbol: Int, sampleRate: Int): IntArray =
        IntArray(count) { s -> strongestBin(buffer, start + s * samplesPerSymbol, samplesPerSymbol, sampleRate) }

    private fun strongestBin(buffer: ShortArray, offset: Int, length: Int, sampleRate: Int): Int {
        var bestBin = 0
        var bestMagnitude = -1.0
        for (bin in 0 until ModemConfig.TONE_COUNT) {
            val magnitude = DspUtil.goertzelMagnitude(buffer, offset, length, sampleRate, ModemConfig.toneFrequencyHz(bin))
            if (magnitude > bestMagnitude) {
                bestMagnitude = magnitude
                bestBin = bin
            }
        }
        return bestBin
    }

    private fun symbolsToBytes(symbols: IntArray): ByteArray {
        require(symbols.size % 2 == 0)
        return ByteArray(symbols.size / 2) { i ->
            (((symbols[i * 2] and 0x0F) shl 4) or (symbols[i * 2 + 1] and 0x0F)).toByte()
        }
    }

    /**
     * Correlation score a candidate must beat to count as the sync chirp.
     * Lowered from the original 0.6: a real room adds reverb and the mic's
     * response across 16.5-19.5kHz is not flat, so a genuine chirp routinely
     * scores in the 0.5-0.7 range rather than near 1.0. A false positive here
     * is cheap (the header FEC rejects it a few milliseconds later); a missed
     * chirp means the message is never seen at all.
     */
    const val DEFAULT_CHIRP_THRESHOLD = 0.45
}
