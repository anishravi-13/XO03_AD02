package com.aeroglyph.app.session

import com.aeroglyph.app.audio.FrameType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LogDirection { SENT, RECEIVED, RELAYED }

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
