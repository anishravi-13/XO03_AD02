package com.aeroglyph.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aeroglyph.app.audio.AudioDecoder
import com.aeroglyph.app.audio.DecodeResult
import com.aeroglyph.app.audio.Frame
import com.aeroglyph.app.audio.FrameType
import com.aeroglyph.app.audio.ModemConfig
import com.aeroglyph.app.audio.RoomProfile
import com.aeroglyph.app.playback.Feedback
import com.aeroglyph.app.playback.Listener
import com.aeroglyph.app.playback.Transmitter
import com.aeroglyph.app.session.LogDirection
import com.aeroglyph.app.session.SessionManager
import com.aeroglyph.app.session.SignalLog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.random.Random

data class ReceivedMessage(
    val message: String,
    val sessionId: Int,
    val frameType: FrameType,
    val hopCount: Int,
    val receivedAtMs: Long,
)

data class BroadcastSettings(
    val roomProfile: RoomProfile = RoomProfile.NORMAL,
    val relayEnabled: Boolean = true,
    val relayTtl: Int = ModemConfig.DEFAULT_RELAY_TTL,
    val confirmationMode: Boolean = false,
    val accessibilityPulse: Boolean = false,
)

/**
 * Single place where the acoustic layer becomes app behavior: dedupe, Echo
 * Relay, ACK tallying, logging, and feedback all hang off one decode stream.
 */
class AeroglyphViewModel(app: Application) : AndroidViewModel(app) {

    private val transmitter = Transmitter(app)
    private val listener = Listener()
    private val sessionManager = SessionManager()
    val signalLog = SignalLog()
    private val feedback = Feedback(app)

    /** This device's identity in ACK bursts and glyph accents. Per app run; never leaves the device except as an 8-bit ACK payload. */
    val receiptId: Int = Random.nextInt(0, 256)

    private val _settings = MutableStateFlow(BroadcastSettings())
    val settings: StateFlow<BroadcastSettings> = _settings.asStateFlow()

    private val _draftMessage = MutableStateFlow("")
    val draftMessage: StateFlow<String> = _draftMessage.asStateFlow()

    private val _lastReceived = MutableStateFlow<ReceivedMessage?>(null)
    val lastReceived: StateFlow<ReceivedMessage?> = _lastReceived.asStateFlow()

    /** Set briefly when a repeat/relay of an already-seen session arrives. */
    private val _duplicateNotice = MutableStateFlow<Int?>(null)
    val duplicateNotice: StateFlow<Int?> = _duplicateNotice.asStateFlow()

    private val _confirmedReceipts = MutableStateFlow<Set<Int>>(emptySet())
    val confirmedReceipts: StateFlow<Set<Int>> = _confirmedReceipts.asStateFlow()

    private val _relayInFlight = MutableStateFlow(false)
    val relayInFlight: StateFlow<Boolean> = _relayInFlight.asStateFlow()

    val isTransmitting: StateFlow<Boolean> = transmitter.isTransmitting
    val transmitProgress: StateFlow<Float> = transmitter.progress
    val isListening: StateFlow<Boolean> = listener.isListening
    val signalPresent: StateFlow<Boolean> = listener.signalPresent
    val peakBandEnergy: StateFlow<Double> = listener.peakBandEnergy
    val listenerError: StateFlow<String?> = listener.error

    /**
     * One spectrum for the whole app. While this device is transmitting its
     * own listener is muted (a phone can't decode its own speaker), so the
     * bars come from the outgoing PCM instead -- either way, every bar on
     * screen is measured from real audio, never faked.
     */
    val spectrumEnergies: StateFlow<DoubleArray> = combine(
        transmitter.isTransmitting,
        transmitter.outgoingChunk,
        listener.binEnergies,
    ) { transmitting, outgoing, incoming ->
        if (transmitting && outgoing.isNotEmpty()) {
            AudioDecoder.binEnergies(outgoing, 0, outgoing.size)
        } else {
            incoming
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DoubleArray(ModemConfig.TONE_COUNT))

    /** Currently-broadcasting session, so ACKs for it can be matched. */
    private var activeBroadcastSessionId: Int? = null
    private var relayJob: Job? = null

    init {
        viewModelScope.launch {
            listener.events.collect { handleDecode(it) }
        }
        viewModelScope.launch {
            var wasPresent = false
            listener.signalPresent.collect { present ->
                if (present && !wasPresent) feedback.signalDetected()
                wasPresent = present
            }
        }
        viewModelScope.launch {
            listener.error.collect { message -> if (message != null) feedback.error() }
        }
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    fun setRoomProfile(profile: RoomProfile) {
        _settings.value = _settings.value.copy(roomProfile = profile)
        listener.symbolRateHz = profile.symbolRateHz
    }

    fun setRelayEnabled(enabled: Boolean) {
        _settings.value = _settings.value.copy(relayEnabled = enabled)
    }

    fun setRelayTtl(ttl: Int) {
        _settings.value = _settings.value.copy(relayTtl = ttl.coerceIn(0, 4))
    }

    fun setConfirmationMode(enabled: Boolean) {
        _settings.value = _settings.value.copy(confirmationMode = enabled)
    }

    fun setAccessibilityPulse(enabled: Boolean) {
        _settings.value = _settings.value.copy(accessibilityPulse = enabled)
    }

    fun onDraftChanged(text: String) {
        // Cap by *bytes*, not characters -- an emoji or accented character
        // costs more than one byte on the wire and the header length field
        // is what actually has to fit.
        var candidate = text
        while (candidate.toByteArray().size > ModemConfig.MAX_PAYLOAD_BYTES && candidate.isNotEmpty()) {
            candidate = candidate.dropLast(1)
        }
        _draftMessage.value = candidate
    }

    fun draftByteLength(): Int = _draftMessage.value.toByteArray().size


    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(getApplication<Application>(), Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // ── Listening ────────────────────────────────────────────────────────────

    // Lint can't see through the runtime check on the line below.
    @SuppressLint("MissingPermission")
    fun startListening() {
        if (!hasMicPermission()) return
        listener.symbolRateHz = _settings.value.roomProfile.symbolRateHz
        listener.start(viewModelScope)
    }

    fun stopListening() = listener.stop()

    // ── Broadcasting ─────────────────────────────────────────────────────────

    fun broadcast() {
        val message = _draftMessage.value
        if (message.isBlank()) return

        val settings = _settings.value
        val sessionId = sessionManager.newSessionId()
        activeBroadcastSessionId = sessionId
        _confirmedReceipts.value = emptySet()

        val frame = Frame(
            sessionId = sessionId,
            frameType = FrameType.DATA,
            hopCount = if (settings.relayEnabled) settings.relayTtl else 0,
            payload = message.toByteArray(),
        )

        viewModelScope.launch {
            signalLog.add(LogDirection.SENT, message, sessionId, FrameType.DATA, frame.hopCount)
            transmitFrame(frame, settings.roomProfile)
        }
    }

    /** Shared by broadcasts, relays, and ACKs -- all of them must mute our own listener while the speaker is busy. */
    private suspend fun transmitFrame(frame: Frame, profile: RoomProfile, repeatCount: Int = profile.repeatCount) {
        listener.muted = true
        try {
            transmitter.transmit(
                frame = frame,
                symbolRateHz = profile.symbolRateHz,
                repeatCount = repeatCount,
            )
        } finally {
            listener.muted = false
        }
    }

    fun cancelTransmission() = transmitter.cancel()

    // ── Decode handling: dedupe, Echo Relay, ACK ─────────────────────────────

    private suspend fun handleDecode(result: DecodeResult) {
        when (result) {
            is DecodeResult.Success -> onFrameDecoded(result)
            DecodeResult.CrcFailed -> signalLog.recordDecodeFailure()
            DecodeResult.Incomplete -> Unit
        }
    }

    private suspend fun onFrameDecoded(result: DecodeResult.Success) {
        signalLog.recordDecodeSuccess()

        when (result.frameType) {
            FrameType.ACK -> onAckReceived(result)
            FrameType.DATA, FrameType.RELAY -> onMessageFrame(result)
            FrameType.RESERVED -> Unit
        }
    }

    private fun onAckReceived(result: DecodeResult.Success) {
        // Only meaningful to the device that sent the session being ACKed.
        if (result.sessionId != activeBroadcastSessionId) return
        val ackReceiptId = result.payload.firstOrNull()?.toInt()?.and(0xFF) ?: return
        _confirmedReceipts.value = _confirmedReceipts.value + ackReceiptId
    }

    private suspend fun onMessageFrame(result: DecodeResult.Success) {
        // One check does dedupe, repeat-suppression, and relay-loop prevention.
        if (!sessionManager.markSeenIfNew(result.sessionId)) {
            _duplicateNotice.value = result.sessionId
            return
        }

        _lastReceived.value = ReceivedMessage(
            message = result.message,
            sessionId = result.sessionId,
            frameType = result.frameType,
            hopCount = result.hopCount,
            receivedAtMs = System.currentTimeMillis(),
        )
        signalLog.add(
            direction = if (result.frameType == FrameType.RELAY) LogDirection.RELAYED else LogDirection.RECEIVED,
            message = result.message,
            sessionId = result.sessionId,
            frameType = result.frameType,
            hopCount = result.hopCount,
        )

        feedback.messageReceived()
        feedback.playChime()
        if (_settings.value.accessibilityPulse) {
            feedback.accessibilityPulse(result.message.length)
        }

        val settings = _settings.value
        if (settings.confirmationMode) sendAck(result.sessionId)
        if (settings.relayEnabled && result.hopCount > 0) scheduleRelay(result)
    }

    private fun sendAck(sessionId: Int) {
        viewModelScope.launch {
            // Stagger ACKs too -- if six phones all ACK the instant they
            // decode, they collide into noise and the broadcaster hears none.
            delay(Random.nextLong(80, 400))
            val ackFrame = Frame(
                sessionId = sessionId,
                frameType = FrameType.ACK,
                hopCount = 0,
                payload = byteArrayOf(receiptId.toByte()),
            )
            transmitFrame(ackFrame, _settings.value.roomProfile, repeatCount = 1)
        }
    }

    /**
     * Echo Relay. A device that just decoded something rebroadcasts it once
     * with TTL-1, after a random delay so that several devices relaying the
     * same frame don't talk over each other.
     */
    private fun scheduleRelay(result: DecodeResult.Success) {
        relayJob?.cancel()
        relayJob = viewModelScope.launch {
            _relayInFlight.value = true
            try {
                delay(Random.nextLong(ModemConfig.RELAY_JITTER_MIN_MS, ModemConfig.RELAY_JITTER_MAX_MS))
                val relayFrame = Frame(
                    sessionId = result.sessionId,
                    frameType = FrameType.RELAY,
                    hopCount = result.hopCount - 1,
                    // Relay the exact bytes we received, not a re-encoding of
                    // the decoded string -- a relay must be byte-faithful.
                    payload = result.payload,
                )
                transmitFrame(relayFrame, _settings.value.roomProfile)
            } finally {
                _relayInFlight.value = false
            }
        }
    }

    fun clearDuplicateNotice() {
        _duplicateNotice.value = null
    }

    fun clearReceived() {
        _lastReceived.value = null
    }

    fun resetSession() {
        sessionManager.reset()
        signalLog.clear()
        _lastReceived.value = null
        _confirmedReceipts.value = emptySet()
        activeBroadcastSessionId = null
    }

    override fun onCleared() {
        super.onCleared()
        listener.stop()
        transmitter.cancel()
    }
}
