package com.aeroglyph.app.session

import kotlin.random.Random

/**
 * Tracks which sessions this device has already handled.
 *
 * This one set does double duty, which is exactly why Echo Relay was cheap to
 * add: the same "have I seen session X?" check that stops a receiver showing
 * the same message four times (once per repetition) also stops it relaying
 * that message more than once, and stops relay traffic looping back and forth
 * between two devices forever.
 */
class SessionManager(private val random: Random = Random.Default) {

    private val seen = LinkedHashSet<Int>()

    /** A fresh 8-bit session ID for an outgoing broadcast, avoiding one we've recently seen. */
    @Synchronized
    fun newSessionId(): Int {
        repeat(32) {
            val candidate = random.nextInt(0, 256)
            if (candidate !in seen) {
                seen.add(candidate)
                trim()
                return candidate
            }
        }
        // Every attempt collided (only possible once the 256-value space is
        // nearly exhausted in one run). Reuse the oldest ID rather than spin.
        val recycled = seen.firstOrNull() ?: 0
        seen.remove(recycled)
        seen.add(recycled)
        return recycled
    }

    /**
     * Records [sessionId] as handled. Returns true only the first time a given
     * session is offered -- so callers can write `if (markSeenIfNew(id)) { ...act... }`
     * and get dedupe, relay-loop prevention, and repeat-suppression in one call.
     */
    @Synchronized
    fun markSeenIfNew(sessionId: Int): Boolean {
        val isNew = seen.add(sessionId)
        if (isNew) trim()
        return isNew
    }

    /**
     * Read-only check, for deciding whether to chase a session we heard about
     * but never received -- a repair request or a beacon we might want to
     * answer. Unlike [markSeenIfNew] this records nothing.
     */
    @Synchronized
    fun hasSeen(sessionId: Int): Boolean = sessionId in seen

    @Synchronized
    fun reset() = seen.clear()

    /** Bounded so a long-running demo can't grow this without limit; oldest-first. */
    private fun trim() {
        while (seen.size > MAX_TRACKED_SESSIONS) {
            val oldest = seen.first()
            seen.remove(oldest)
        }
    }

    private companion object {
        // The ID space is only 8 bits, so tracking more than half of it starts
        // to make collisions likely; 128 is a deliberate ceiling, not a guess.
        const val MAX_TRACKED_SESSIONS = 128
    }
}
