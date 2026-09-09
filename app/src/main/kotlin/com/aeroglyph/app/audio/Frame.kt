package com.aeroglyph.app.audio

/**
 * The 3-bit frame-type field packed into the header's third byte alongside
 * hop count.
 *
 * Widened from the original 2 bits to make room for the repair and catch-up
 * control frames. Hop count keeps the remaining 5 bits, which is still far
 * more than the TTL of 2-3 anything sane will use.
 */
enum class FrameType(val bits: Int, val label: String) {
    /** A message, straight from whoever composed it. */
    DATA(0, "DATA"),

    /** Echo Relay: somebody else's message, rebroadcast one hop further. */
    RELAY(1, "RELAY"),

    /** "I received session X" -- confirmation mode. */
    ACK(2, "ACK"),

    /** "I got session X's header but its payload was corrupt -- please resend." */
    NACK(3, "NACK"),

    /** "I just arrived and have nothing -- what's the latest?" */
    REQUEST(4, "REQUEST"),

    /** "I am holding session X, in case anyone needs it." */
    BEACON(5, "BEACON"),

    /** A message re-sent specifically to satisfy a NACK or REQUEST. */
    ANSWER(6, "ANSWER"),

    RESERVED(7, "RESERVED");

    /** Frame types that carry an actual user-visible message payload. */
    val carriesMessage: Boolean get() = this == DATA || this == RELAY || this == ANSWER

    companion object {
        fun fromBits(bits: Int): FrameType = entries.first { it.bits == (bits and 0b111) }
    }
}

/**
 * Plaintext contents of one Aeroglyph frame, before FEC/CRC/modulation.
 *
 * Wire layout (all of this is Hamming-protected, see [FecCodec] and
 * [AudioEncoder] -- the base PS02 spec leaves the header and CRC unprotected,
 * but a single flipped bit in either would silently corrupt or falsely reject
 * an otherwise-clean frame, so Aeroglyph protects the whole thing uniformly):
 *
 * ```
 * byte 0        payload length (0-200)
 * byte 1        session ID (0-255)
 * byte 2        (frameType << 5) | hopCount   -- 3-bit type, 5-bit hop count
 * byte 3..3+N   payload (UTF-8 message bytes)
 * last 4 bytes  CRC-32 (big-endian) over bytes [0, 3+N)
 * ```
 */
data class Frame(
    val sessionId: Int,
    val frameType: FrameType,
    val hopCount: Int,
    val payload: ByteArray,
) {
    init {
        require(sessionId in 0..255) { "sessionId out of range: $sessionId" }
        require(hopCount in 0..31) { "hopCount out of range: $hopCount" }
        require(payload.size <= ModemConfig.MAX_PAYLOAD_BYTES) {
            "payload too large: ${payload.size} bytes (max ${ModemConfig.MAX_PAYLOAD_BYTES})"
        }
    }

    fun withHopCount(newHopCount: Int): Frame = copy(hopCount = newHopCount)

    /** Header + payload + CRC32, as plaintext bytes -- this is what gets FEC-encoded. */
    fun toPlaintextBytes(): ByteArray {
        val header = byteArrayOf(
            payload.size.toByte(),
            sessionId.toByte(),
            (((frameType.bits and 0b111) shl 5) or (hopCount and 0x1F)).toByte(),
        )
        val body = header + payload
        val crc = Crc32.compute(body)
        val crcBytes = byteArrayOf(
            (crc ushr 24).toByte(),
            (crc ushr 16).toByte(),
            (crc ushr 8).toByte(),
            crc.toByte(),
        )
        return body + crcBytes
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Frame) return false
        return sessionId == other.sessionId &&
            frameType == other.frameType &&
            hopCount == other.hopCount &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = sessionId
        result = 31 * result + frameType.hashCode()
        result = 31 * result + hopCount
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        /** Parses the header's 3rd byte back into (frameType, hopCount). */
        fun parseTypeAndHop(byte3: Int): Pair<FrameType, Int> {
            val frameType = FrameType.fromBits((byte3 shr 5) and 0b111)
            val hopCount = byte3 and 0x1F
            return frameType to hopCount
        }
    }
}
