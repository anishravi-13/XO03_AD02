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
import com.aeroglyph.app.playback.ListenState
import com.aeroglyph.app.playback.Listener
import com.aeroglyph.app.playback.Transmitter
import com.aeroglyph.app.session.LogDirection
import com.aeroglyph.app.session.MessageStore
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
import kotlinx.coroutines.isActive
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
    /** Auto-repair and catch-up. On by default -- it is what makes late joiners and partial receptions self-heal. */
    val autoRecovery: Boolean = true,
)

/** What the recovery subsystem is doing, surfaced so a demo can show it working. */
enum class RecoveryState { IDLE, REQUESTING, REPAIRING, RECOVERED }

/**
 * Single place where the acoustic layer becomes app behaviour: dedupe, Echo
 * Relay, ACK tallying, repair, catch-up, logging and feedback all hang off
 * one decode stream.
 */
class AeroglyphViewModel(app: Application) : AndroidViewModel(app) {

    private val transmitter = Transmitter(app)
    private val listener = Listener()
    private val sessionManager = SessionManager()
    private val messageStore = MessageStore()
    val signalLog = SignalLog()
    private val feedback = Feedback(app)

    /** This device's identity in ACK bursts and glyph accents. Per app run; never leaves the device except as one byte. */
    val receiptId: Int = Random.nextInt(1, 256)

    private val _settings = MutableStateFlow(BroadcastSettings())
    val settings: StateFlow<BroadcastSettings> = _settings.asStateFlow()

    private val _draftMessage = MutableStateFlow("")
    val draftMessage: StateFlow<String> = _draftMessage.asStateFlow()

    private val _lastReceived = MutableStateFlow<ReceivedMessage?>(null)
    val lastReceived: StateFlow<ReceivedMessage?> = _lastReceived.asStateFlow()

    private val _duplicateNotice = MutableStateFlow<Int?>(null)
    val duplicateNotice: StateFlow<Int?> = _duplicateNotice.asStateFlow()

    private val _confirmedReceipts = MutableStateFlow<Set<Int>>(emptySet())
    val confirmedReceipts: StateFlow<Set<Int>> = _confirmedReceipts.asStateFlow()

    private val _permissionGranted = MutableStateFlow(hasMicPermission())
    val permissionGranted: StateFlow<Boolean> = _permissionGranted.asStateFlow()

    private val _relayInFlight = MutableStateFlow(false)
    val relayInFlight: StateFlow<Boolean> = _relayInFlight.asStateFlow()

    private val _recoveryState = MutableStateFlow(RecoveryState.IDLE)
    val recoveryState: StateFlow<RecoveryState> = _recoveryState.asStateFlow()

    /** True when the message on screen arrived through repair or catch-up rather than a live broadcast. */
    private val _recoveredViaRecovery = MutableStateFlow(false)
    val recoveredViaRecovery: StateFlow<Boolean> = _recoveredViaRecovery.asStateFlow()

    val requestsAnswered: StateFlow<Int> = messageStore.requestsAnswered
    val heldMessage = messageStore.held

    val isTransmitting: StateFlow<Boolean> = transmitter.isTransmitting
    val transmitProgress: StateFlow<Float> = transmitter.progress
    val isListening: StateFlow<Boolean> = listener.isListening
    val listenState: StateFlow<ListenState> = listener.state
    val peakBandEnergy: StateFlow<Double> = listener.peakBandEnergy
    val noiseFloor: StateFlow<Double> = listener.noiseFloor
    val audioSourceName: StateFlow<String> = listener.sourceName
    val listenerError: StateFlow<String?> = listener.error

    /**
     * One spectrum for the whole app. While this device is transmitting its
     * own listener is muted (a phone cannot decode its own speaker), so the
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

    private var activeBroadcastSessionId: Int? = null
    private var relayJob: Job? = null
    private var answerJob: Job? = null
    private var catchUpJob: Job? = null
    private var beaconJob: Job? = null

    init {
        viewModelScope.launch {
            listener.events.collect { handleDecode(it) }
        }
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    fun setRoomProfile(profile: RoomProfile) {
        _settings.value = _settings.value.copy(roomProfile = profile)
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

    fun setAutoRecovery(enabled: Boolean) {
        _settings.value = _settings.value.copy(autoRecovery = enabled)
        if (enabled) startBeaconLoop() else beaconJob?.cancel()
    }

    fun onDraftChanged(text: String) {
        // Cap by *bytes*, not characters -- an emoji costs more than one byte
        // on the wire and the header's length field is what has to fit.
        var candidate = text
        while (candidate.toByteArray().size > ModemConfig.MAX_PAYLOAD_BYTES && candidate.isNotEmpty()) {
            candidate = candidate.dropLast(1)
        }
        _draftMessage.value = candidate
    }

    fun draftByteLength(): Int = _draftMessage.value.toByteArray().size

    // ── Permission ───────────────────────────────────────────────────────────

    fun refreshPermission() {
        _permissionGranted.value = hasMicPermission()
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(getApplication<Application>(), Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // ── Listening ────────────────────────────────────────────────────────────

    // Lint cannot see through the runtime check on the line below.
    @SuppressLint("MissingPermission")
    fun startListening() {
        if (!hasMicPermission()) return
        listener.start(viewModelScope)
        if (_settings.value.autoRecovery) startBeaconLoop()
    }

    fun stopListening() {
        listener.stop()
        beaconJob?.cancel()
        catchUpJob?.cancel()
    }

    // ── Broadcasting ─────────────────────────────────────────────────────────

    fun broadcast() {
        val message = _draftMessage.value
        if (message.isBlank()) return

        val settings = _settings.value
        val sessionId = sessionManager.newSessionId()
        activeBroadcastSessionId = sessionId
        _confirmedReceipts.value = emptySet()

        val payload = message.toByteArray()
        val frame = Frame(
            sessionId = sessionId,
            frameType = FrameType.DATA,
            hopCount = if (settings.relayEnabled) settings.relayTtl else 0,
            payload = payload,
        )

        // Hold our own message so we can answer repair and catch-up requests
        // for it -- the sender is just another device that happens to have it.
        messageStore.hold(sessionId, payload, message)

        viewModelScope.launch {
            signalLog.add(LogDirection.SENT, message, sessionId, FrameType.DATA, frame.hopCount)
            transmitFrame(frame, settings.roomProfile)
        }
    }

    /** Shared by broadcasts, relays, answers and ACKs -- all of them must mute our own listener while the speaker is busy. */
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

    // ── Decode handling ──────────────────────────────────────────────────────

    private suspend fun handleDecode(result: DecodeResult) {
        when (result) {
            is DecodeResult.Success -> onFrameDecoded(result)
            is DecodeResult.PartialReception -> onPartialReception(result)
            DecodeResult.CrcFailed -> signalLog.recordDecodeFailure()
            DecodeResult.Incomplete -> Unit
        }
    }

    private suspend fun onFrameDecoded(result: DecodeResult.Success) {
        signalLog.recordDecodeSuccess()

        when (result.frameType) {
            FrameType.ACK -> onAckReceived(result)
            FrameType.NACK -> onRepairRequest(result)
            FrameType.REQUEST -> onCatchUpRequest(result)
            FrameType.BEACON -> onBeacon(result)
            FrameType.DATA, FrameType.RELAY, FrameType.ANSWER -> onMessageFrame(result)
            FrameType.RESERVED -> Unit
        }
    }

    private fun onAckReceived(result: DecodeResult.Success) {
        if (result.sessionId != activeBroadcastSessionId) return
        val ackReceiptId = result.payload.firstOrNull()?.toInt()?.and(0xFF) ?: return
        _confirmedReceipts.value = _confirmedReceipts.value + ackReceiptId
    }

    private suspend fun onMessageFrame(result: DecodeResult.Success) {
        // Somebody answered a request we might also have been about to answer.
        if (result.frameType == FrameType.ANSWER) {
            messageStore.noteAnsweredByOther(result.sessionId)
        }

        // One check does dedupe, repeat-suppression and relay-loop prevention.
        if (!sessionManager.markSeenIfNew(result.sessionId)) {
            _duplicateNotice.value = result.sessionId
            return
        }

        // We now hold this message, so we can serve it to anyone who asks.
        messageStore.hold(result.sessionId, result.payload, result.message)

        // Whatever we were chasing, we have it now.
        catchUpJob?.cancel()
        val wasRecovering = _recoveryState.value != RecoveryState.IDLE
        _recoveredViaRecovery.value = wasRecovering || result.frameType == FrameType.ANSWER
        _recoveryState.value = RecoveryState.RECOVERED

        _lastReceived.value = ReceivedMessage(
            message = result.message,
            sessionId = result.sessionId,
            frameType = result.frameType,
            hopCount = result.hopCount,
            receivedAtMs = System.currentTimeMillis(),
        )
        signalLog.add(
            direction = when (result.frameType) {
                FrameType.RELAY -> LogDirection.RELAYED
                FrameType.ANSWER -> LogDirection.RECOVERED
                else -> LogDirection.RECEIVED
            },
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

    // ── Surprise challenge 1: partial reception -> targeted repair ───────────

    /**
     * The header arrived but the payload did not. We know exactly which
     * session is damaged, so we ask for that one by name rather than hoping
     * a later repetition happens to be cleaner.
     */
    private suspend fun onPartialReception(result: DecodeResult.PartialReception) {
        signalLog.recordDecodeFailure()

        if (sessionManager.hasSeen(result.sessionId)) return // already have it intact
        if (!_settings.value.autoRecovery) return

        _recoveryState.value = RecoveryState.REPAIRING
        signalLog.add(
            direction = LogDirection.REPAIR_REQUESTED,
            message = "Partial reception - requested repair",
            sessionId = result.sessionId,
            frameType = FrameType.NACK,
            hopCount = 0,
        )
        feedback.error()

        // Jitter so several damaged receivers don't all NACK simultaneously.
        delay(Random.nextLong(200, 700))
        val nack = Frame(
            sessionId = result.sessionId,
            frameType = FrameType.NACK,
            hopCount = 0,
            payload = byteArrayOf(receiptId.toByte()),
        )
        transmitFrame(nack, _settings.value.roomProfile, repeatCount = 2)
    }

    /** Someone is missing a session we may be holding. Answer it, unless a neighbour beats us to it. */
    private fun onRepairRequest(result: DecodeResult.Success) {
        if (!_settings.value.autoRecovery) return
        val held = messageStore.heldFor(result.sessionId) ?: return
        scheduleAnswer(held.sessionId, held.payload, held.text, LogDirection.REPAIR_ANSWERED)
    }

    // ── Surprise challenge 2: dynamic group -> catch-up ──────────────────────

    /** A device that just arrived is asking for the latest message. */
    private fun onCatchUpRequest(result: DecodeResult.Success) {
        if (!_settings.value.autoRecovery) return
        val held = messageStore.resolveRequest(result.sessionId, ModemConfig.SESSION_ID_LATEST) ?: return
        scheduleAnswer(held.sessionId, held.payload, held.text, LogDirection.CATCH_UP_ANSWERED)
    }

    /** Someone announced they are holding a session. If it is new to us, ask for it. */
    private fun onBeacon(result: DecodeResult.Success) {
        if (!_settings.value.autoRecovery) return
        if (sessionManager.hasSeen(result.sessionId)) return
        requestCatchUp(specificSession = result.sessionId)
    }

    /**
     * Ask the room for a message. Called on entering Listen mode with nothing
     * in hand, and whenever a beacon advertises something we don't have.
     *
     * This is the whole of "a newly joined device obtains the latest message
     * without the sender doing anything": the newcomer asks, and any
     * neighbour that has it replies.
     */
    fun requestCatchUp(specificSession: Int? = null) {
        if (!_settings.value.autoRecovery) return
        if (messageStore.held.value != null && specificSession == null) return

        catchUpJob?.cancel()
        catchUpJob = viewModelScope.launch {
            _recoveryState.value = RecoveryState.REQUESTING
            val wanted = specificSession ?: ModemConfig.SESSION_ID_LATEST

            repeat(ModemConfig.CATCH_UP_ATTEMPTS) { attempt ->
                if (!isActive) return@launch
                if (sessionManager.hasSeen(wanted) && specificSession != null) return@launch

                signalLog.add(
                    direction = LogDirection.CATCH_UP_REQUESTED,
                    message = if (specificSession == null) "Asked the room for the latest message" else "Asked for session",
                    sessionId = wanted,
                    frameType = FrameType.REQUEST,
                    hopCount = 0,
                )

                val request = Frame(
                    sessionId = wanted,
                    frameType = FrameType.REQUEST,
                    hopCount = 0,
                    payload = byteArrayOf(receiptId.toByte()),
                )
                transmitFrame(request, _settings.value.roomProfile, repeatCount = 2)

                // Wait for an answer before asking again.
                delay(ModemConfig.CATCH_UP_RETRY_MS)
                if (messageStore.held.value != null) return@launch
                if (attempt == ModemConfig.CATCH_UP_ATTEMPTS - 1) {
                    // Give up asking; a beacon from a neighbour will still reach us.
                    _recoveryState.value = RecoveryState.IDLE
                }
            }
        }
    }

    /**
     * Answer a repair or catch-up request after a random wait, standing down
     * if somebody else answers first. This suppression is what keeps a room of
     * twenty phones from transmitting twenty identical answers.
     */
    private fun scheduleAnswer(sessionId: Int, payload: ByteArray, text: String, logAs: LogDirection) {
        answerJob?.cancel()
        answerJob = viewModelScope.launch {
            delay(Random.nextLong(ModemConfig.REPAIR_JITTER_MIN_MS, ModemConfig.REPAIR_JITTER_MAX_MS))
            if (!messageStore.claimAnswer(sessionId)) return@launch

            signalLog.add(
                direction = logAs,
                message = text,
                sessionId = sessionId,
                frameType = FrameType.ANSWER,
                hopCount = 0,
            )

            val answer = Frame(
                sessionId = sessionId,
                frameType = FrameType.ANSWER,
                hopCount = 1, // one hop, so an answer can still reach a neighbour of the asker
                payload = payload,
            )
            transmitFrame(answer, _settings.value.roomProfile)
        }
    }

    /** Periodically announce that we hold something, so a late arrival learns of it even if its own request went unheard. */
    private fun startBeaconLoop() {
        if (beaconJob?.isActive == true) return
        beaconJob = viewModelScope.launch {
            while (isActive) {
                delay(ModemConfig.BEACON_INTERVAL_MS)
                if (!_settings.value.autoRecovery) continue
                val held = messageStore.held.value ?: continue
                if (transmitter.isTransmitting.value) continue

                val beacon = Frame(
                    sessionId = held.sessionId,
                    frameType = FrameType.BEACON,
                    hopCount = 0,
                    payload = ByteArray(0),
                )
                transmitFrame(beacon, _settings.value.roomProfile, repeatCount = 1)
            }
        }
    }

    // ── Confirmation + relay ─────────────────────────────────────────────────

    private fun sendAck(sessionId: Int) {
        viewModelScope.launch {
            // Stagger ACKs: if six phones ACK the instant they decode, they
            // collide into noise and the broadcaster hears none of them.
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

    // ── Housekeeping ─────────────────────────────────────────────────────────

    fun clearDuplicateNotice() {
        _duplicateNotice.value = null
    }

    fun clearReceived() {
        _lastReceived.value = null
        _recoveryState.value = RecoveryState.IDLE
        _recoveredViaRecovery.value = false
    }

    fun resetSession() {
        sessionManager.reset()
        signalLog.clear()
        messageStore.clear()
        _lastReceived.value = null
        _confirmedReceipts.value = emptySet()
        _recoveryState.value = RecoveryState.IDLE
        _recoveredViaRecovery.value = false
        activeBroadcastSessionId = null
    }

    override fun onCleared() {
        super.onCleared()
        listener.stop()
        transmitter.cancel()
    }
}
