package com.aeroglyph.app.audio

/** What came out of trying to decode one frame from a PCM buffer. */
sealed class DecodeResult {
    /**
     * [payload] is kept alongside [message] because not every frame is text:
     * an ACK carries a raw device ID byte, and running that through a UTF-8
     * String would mangle any value above 127 into a replacement character.
     */
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

    /** A full frame's worth of symbols was read, but FEC/CRC didn't check out. Drop it silently and wait for a clean repetition. */
    data object CrcFailed : DecodeResult()

    /** No sync chirp found yet, or the buffer doesn't yet contain enough samples for the frame length the header declared. Keep listening. */
    data object Incomplete : DecodeResult()
}

/**
 * Demodulates PCM16 samples back into a [Frame]: find the sync chirp, decode
 * the fixed-size header block to learn the payload length, then decode
 * exactly that many more symbols for payload+CRC.
 *
 * Symbol decoding uses the Goertzel algorithm at the 16 known tone
 * frequencies (see [DspUtil.goertzelMagnitude]) rather than a full FFT --
 * we only ever need energy at frequencies we already know about.
 */
object AudioDecoder {

    fun decode(
        buffer: ShortArray,
        sampleRate: Int = ModemConfig.SAMPLE_RATE_HZ,
        symbolRateHz: Double = ModemConfig.DEFAULT_SYMBOL_RATE_HZ,
        chirpThreshold: Double = 0.6,
        chirpSearchStride: Int = 1,
    ): DecodeResult {
        val syncTemplate = ChirpSync.generateSyncChirp(sampleRate)
        val syncDetection = ChirpSync.findChirp(
            buffer = buffer,
            template = syncTemplate,
            threshold = chirpThreshold,
            searchStride = chirpSearchStride,
        ) ?: return DecodeResult.Incomplete

        val samplesPerSymbol = (sampleRate / symbolRateHz).toInt()
        val symbolStart = syncDetection.offsetSamples + syncTemplate.size

        val headerSymbolCount = AudioEncoder.HEADER_SYMBOL_COUNT
        if (symbolStart + headerSymbolCount * samplesPerSymbol > buffer.size) return DecodeResult.Incomplete

        val headerSymbols = decodeSymbols(buffer, symbolStart, headerSymbolCount, samplesPerSymbol, sampleRate)
        val headerPlain = FecCodec.decode(symbolsToBytes(headerSymbols)) ?: return DecodeResult.CrcFailed

        val payloadLen = headerPlain[0].toInt() and 0xFF
        val sessionId = headerPlain[1].toInt() and 0xFF
        val (frameType, hopCount) = Frame.parseTypeAndHop(headerPlain[2].toInt() and 0xFF)

        // The header's own FEC should catch almost all corruption, but a
        // nonsensical length must never be allowed to drive a huge allocation
        // or run past the end of the buffer.
        if (payloadLen > ModemConfig.MAX_PAYLOAD_BYTES) return DecodeResult.CrcFailed

        val restByteCount = payloadLen + ModemConfig.CRC_BYTES
        val restSymbolCount = restByteCount * 4 // FEC doubles bytes, then 2 symbols/byte
        val restStart = symbolStart + headerSymbolCount * samplesPerSymbol
        if (restStart + restSymbolCount * samplesPerSymbol > buffer.size) return DecodeResult.Incomplete

        val restSymbols = decodeSymbols(buffer, restStart, restSymbolCount, samplesPerSymbol, sampleRate)
        val restPlain = FecCodec.decode(symbolsToBytes(restSymbols)) ?: return DecodeResult.CrcFailed

        val payloadBytes = restPlain.copyOfRange(0, payloadLen)
        val crcBytes = restPlain.copyOfRange(payloadLen, payloadLen + ModemConfig.CRC_BYTES)
        val receivedCrc = ((crcBytes[0].toInt() and 0xFF) shl 24) or
            ((crcBytes[1].toInt() and 0xFF) shl 16) or
            ((crcBytes[2].toInt() and 0xFF) shl 8) or
            (crcBytes[3].toInt() and 0xFF)

        val bodyForCrc = headerPlain + payloadBytes
        if (!Crc32.verify(bodyForCrc, receivedCrc)) return DecodeResult.CrcFailed

        return DecodeResult.Success(
            message = String(payloadBytes, Charsets.UTF_8),
            sessionId = sessionId,
            frameType = frameType,
            hopCount = hopCount,
            payload = payloadBytes,
        )
    }

    /** Live magnitude at all 16 known tone bins for one window -- feeds the spectrum visualizer. */
    fun binEnergies(buffer: ShortArray, offset: Int, length: Int, sampleRate: Int = ModemConfig.SAMPLE_RATE_HZ): DoubleArray =
        DoubleArray(ModemConfig.TONE_COUNT) { bin ->
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
}
