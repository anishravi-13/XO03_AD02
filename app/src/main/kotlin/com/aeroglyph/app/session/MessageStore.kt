package com.aeroglyph.app.session

import com.aeroglyph.app.audio.FrameType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A message this device is holding and could re-send on request. */
data class HeldMessage(
    val sessionId: Int,
    val payload: ByteArray,
    val text: String,
    val receivedAtMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HeldMessage) return false
        return sessionId == other.sessionId && payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = 31 * sessionId + payload.contentHashCode()
}

/**
 * Whatever this device currently knows, plus the bookkeeping that stops a room
 * full of phones from all shouting the same answer at once.
 *
 * This one object backs both surprise challenges, because both reduce to the
 * same question -- *somebody is missing something; who answers, and when?*
 *
 *  - **Partial reception (challenge 1).** A receiver that got a header but a
 *    corrupt payload names that exact session in a NACK. Anyone holding it can
 *    repair it: the original sender has no special role, which is what lets
 *    recovery happen without the sender doing anything by hand.
 *
 *  - **Dynamic group (challenge 2).** A device that arrives after the fact
 *    asks for "the latest, whatever it is". Same answering rule, same
 *    suppression, so a late joiner is served by whichever neighbour is
 *    nearest rather than by the original broadcaster.
 *
 * The suppression rule is what keeps this from melting down: an answer is
 * scheduled after a random delay, and cancelled outright if someone else is
 * heard answering first. With N devices holding the message, the channel
 * carries roughly one answer instead of N.
 */
class MessageStore {

    private val _held = MutableStateFlow<HeldMessage?>(null)
    val held: StateFlow<HeldMessage?> = _held.asStateFlow()

    /** How many repair/catch-up requests this device has answered -- shown in the UI as proof the mechanism fires. */
    private val _requestsAnswered = MutableStateFlow(0)
    val requestsAnswered: StateFlow<Int> = _requestsAnswered.asStateFlow()

    /** Sessions we have heard someone else answer very recently, so we stay quiet. */
    private val recentlyAnswered = mutableMapOf<Int, Long>()

    /** Requests we have already served, to avoid answering the same device forever. */
    private val servedRecently = mutableMapOf<Int, Long>()

    @Synchronized
    fun hold(sessionId: Int, payload: ByteArray, text: String) {
        _held.value = HeldMessage(sessionId, payload, text, System.currentTimeMillis())
    }

    @Synchronized
    fun heldFor(sessionId: Int): HeldMessage? {
        val current = _held.value ?: return null
        return if (current.sessionId == sessionId) current else null
    }

    /** Resolves what to send for a request: a named session, or the latest if the request asked for "whatever you have". */
    @Synchronized
    fun resolveRequest(requestedSessionId: Int, latestSentinel: Int): HeldMessage? {
        val current = _held.value ?: return null
        return if (requestedSessionId == latestSentinel || requestedSessionId == current.sessionId) current else null
    }

    /** Someone else answered this session; stand down if we were about to. */
    @Synchronized
    fun noteAnsweredByOther(sessionId: Int) {
        recentlyAnswered[sessionId] = System.currentTimeMillis()
        prune()
    }

    @Synchronized
    fun wasAnsweredRecently(sessionId: Int): Boolean {
        val at = recentlyAnswered[sessionId] ?: return false
        return System.currentTimeMillis() - at < SUPPRESSION_WINDOW_MS
    }

    /** True if we should answer, and records that we are doing so. Rate-limits repeats. */
    @Synchronized
    fun claimAnswer(sessionId: Int): Boolean {
        val now = System.currentTimeMillis()
        val lastServed = servedRecently[sessionId]
        if (lastServed != null && now - lastServed < SERVE_COOLDOWN_MS) return false
        if (wasAnsweredRecently(sessionId)) return false
        servedRecently[sessionId] = now
        _requestsAnswered.value += 1
        prune()
        return true
    }

    @Synchronized
    fun clear() {
        _held.value = null
        recentlyAnswered.clear()
        servedRecently.clear()
        _requestsAnswered.value = 0
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        recentlyAnswered.entries.removeAll { now - it.value > SUPPRESSION_WINDOW_MS * 4 }
        servedRecently.entries.removeAll { now - it.value > SERVE_COOLDOWN_MS * 4 }
    }

    private companion object {
        /** Long enough to cover another device's jittered answer plus its air time. */
        const val SUPPRESSION_WINDOW_MS = 15_000L

        /** Don't re-answer the same session more often than this, however many requests arrive. */
        const val SERVE_COOLDOWN_MS = 8_000L
    }
}
