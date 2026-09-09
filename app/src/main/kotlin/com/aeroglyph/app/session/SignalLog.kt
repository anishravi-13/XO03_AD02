package com.aeroglyph.app.session

import com.aeroglyph.app.audio.FrameType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What a log row represents. The recovery entries exist so a demo can point
 * at the moment a repair or a catch-up actually fired, rather than asking
 * anyone to take it on trust.
 */
enum class LogDirection(val label: String) {
    SENT("SENT"),
    RECEIVED("DIRECT"),
    RELAYED("VIA RELAY"),

    /** We noticed a damaged frame and asked for it by name. */
    REPAIR_REQUESTED("REPAIR ASKED"),

    /** We held a message someone else had damaged, and re-sent it. */
    REPAIR_ANSWERED("REPAIR SENT"),

    /** We arrived late and asked the room what we had missed. */
    CATCH_UP_REQUESTED("CATCH-UP ASKED"),

    /** We answered a late arrival. */
    CATCH_UP_ANSWERED("CATCH-UP SENT"),

    /** A message that reached us through repair or catch-up rather than live. */
    RECOVERED("RECOVERED"),
}

data class SignalLogEntry(
    val id: Long,
    val timestampMs: Long,
    val direction: LogDirection,
    val message: String,
    val sessionId: Int,
    val frameType: FrameType,
    val hopCount: Int,
)

/**
 * In-memory history for the current app session. Deliberately not persisted:
 * Aeroglyph never writes audio or message content to disk, and a demo's log
 * has no reason to outlive the demo.
 */
class SignalLog {

    private val _entries = MutableStateFlow<List<SignalLogEntry>>(emptyList())
    val entries: StateFlow<List<SignalLogEntry>> = _entries.asStateFlow()

    /** Frames that passed CRC, over frames we attempted -- the live success rate shown to judges. */
    private val _decodeAttempts = MutableStateFlow(0)
    val decodeAttempts: StateFlow<Int> = _decodeAttempts.asStateFlow()

    private val _decodeSuccesses = MutableStateFlow(0)
    val decodeSuccesses: StateFlow<Int> = _decodeSuccesses.asStateFlow()

    private var nextId = 1L

    @Synchronized
    fun add(
        direction: LogDirection,
        message: String,
        sessionId: Int,
        frameType: FrameType,
        hopCount: Int,
    ): SignalLogEntry {
        val entry = SignalLogEntry(
            id = nextId++,
            timestampMs = System.currentTimeMillis(),
            direction = direction,
            message = message,
            sessionId = sessionId,
            frameType = frameType,
            hopCount = hopCount,
        )
        _entries.value = listOf(entry) + _entries.value
        return entry
    }

    fun recordDecodeSuccess() {
        _decodeAttempts.value += 1
        _decodeSuccesses.value += 1
    }

    fun recordDecodeFailure() {
        _decodeAttempts.value += 1
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
        _decodeAttempts.value = 0
        _decodeSuccesses.value = 0
    }
}
