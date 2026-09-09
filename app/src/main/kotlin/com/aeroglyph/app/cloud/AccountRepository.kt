package com.aeroglyph.app.cloud

import android.content.Context
import androidx.core.content.edit
import com.aeroglyph.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Who is signed in, if anyone. */
sealed class AccountState {
    /** No Supabase project configured -- the cloud side is simply switched off. */
    data object NotConfigured : AccountState()

    data object SignedOut : AccountState()

    data class SignedIn(val profile: UserProfile) : AccountState()
}

/**
 * Owns the signed-in session and everything that depends on it.
 *
 * Sign-in is **optional throughout**. The acoustic side of the app -- which is
 * the whole point of it -- never consults this class, never waits on it, and
 * behaves identically whether a user is signed in, signed out, or the app was
 * built with no Supabase project at all. That is deliberate: the problem this
 * app exists to solve is communication where there is no network, so a login
 * wall would make it useless in exactly the conditions it is built for.
 *
 * What signing in adds is durable history and an admin view. Nothing more.
 */
class AccountRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("aeroglyph.account", Context.MODE_PRIVATE)

    private val client = SupabaseClient(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY)

    private val _state = MutableStateFlow<AccountState>(
        if (client.isConfigured) AccountState.SignedOut else AccountState.NotConfigured,
    )
    val state: StateFlow<AccountState> = _state.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Messages that happened while offline or signed out, waiting to be pushed. */
    private val pending = ArrayDeque<JSONObject>()

    private var session: Session? = null

    val isConfigured: Boolean get() = client.isConfigured

    /** Restores a saved session on launch. Safe to call when nothing is stored. */
    suspend fun restore() {
        if (!client.isConfigured) return
        val refreshToken = prefs.getString(KEY_REFRESH, null) ?: return
        _busy.value = true
        client.refresh(refreshToken)
            .onSuccess { adopt(it) }
            .onFailure {
                // A refresh token that no longer works is not worth reporting:
                // the user simply appears signed out, which is the truth.
                clearStoredSession()
            }
        _busy.value = false
    }

    suspend fun signIn(email: String, password: String) = authenticate { client.signIn(email.trim(), password) }

    suspend fun signUp(email: String, password: String) = authenticate { client.signUp(email.trim(), password) }

    private suspend fun authenticate(block: suspend () -> Result<Session>) {
        if (!client.isConfigured) return
        _busy.value = true
        _error.value = null
        block()
            .onSuccess { adopt(it) }
            .onFailure { _error.value = it.message ?: "Couldn't sign in." }
        _busy.value = false
    }

    fun signOut() {
        session = null
        clearStoredSession()
        _state.value = AccountState.SignedOut
    }

    fun clearError() {
        _error.value = null
    }

    private suspend fun adopt(newSession: Session) {
        session = newSession
        prefs.edit {
            putString(KEY_REFRESH, newSession.refreshToken)
            putString(KEY_EMAIL, newSession.email)
        }
        _state.value = AccountState.SignedIn(loadProfile(newSession))
        flushPending()
    }

    /**
     * Reads this user's row from `profiles`, creating it on first sign-in.
     *
     * The role lives in a table rather than in the JWT because it has to be
     * changeable by an admin without the user re-authenticating. If the row
     * cannot be read the user is treated as an ordinary user -- never as an
     * admin, since failing open on a permission check would be the wrong way
     * round.
     */
    private suspend fun loadProfile(newSession: Session): UserProfile {
        val fallback = UserProfile(newSession.userId, newSession.email, UserProfile.ROLE_USER, disabled = false)

        val rows = client.select(
            table = "profiles",
            query = "select=id,email,role,disabled&id=eq.${newSession.userId}",
            accessToken = newSession.accessToken,
        ).getOrNull() ?: return fallback

        if (rows.length() == 0) {
            client.insert(
                table = "profiles",
                row = JSONObject()
                    .put("id", newSession.userId)
                    .put("email", newSession.email)
                    .put("role", UserProfile.ROLE_USER),
                accessToken = newSession.accessToken,
            )
            return fallback
        }

        val row = rows.getJSONObject(0)
        return UserProfile(
            id = row.optString("id", newSession.userId),
            email = row.optString("email", newSession.email),
            role = row.optString("role", UserProfile.ROLE_USER),
            disabled = row.optBoolean("disabled", false),
        )
    }

    // ── Message history ──────────────────────────────────────────────────────

    /**
     * Records one acoustic event. Never blocks and never fails visibly: if
     * there is no session or no network the row is queued and pushed the next
     * time both exist. A dropped log line must never be able to disturb a
     * transmission in progress.
     */
    suspend fun recordMessage(direction: String, body: String, sessionId: Int, hopCount: Int) {
        if (!client.isConfigured) return
        val row = JSONObject()
            .put("direction", direction)
            .put("body", body)
            .put("acoustic_session", sessionId)
            .put("hop_count", hopCount)

        val active = session
        if (active == null || active.isExpired()) {
            queue(row)
            return
        }
        client.insert("messages", row.put("user_id", active.userId), active.accessToken)
            .onFailure { queue(row) }
    }

    private fun queue(row: JSONObject) {
        // Bounded: an app left running offline for a day should not accumulate
        // an unbounded backlog in memory.
        while (pending.size >= MAX_PENDING) pending.removeFirst()
        pending.addLast(row)
    }

    private suspend fun flushPending() {
        val active = session ?: return
        while (pending.isNotEmpty()) {
            val row = pending.first()
            val sent = client.insert("messages", row.put("user_id", active.userId), active.accessToken).isSuccess
            if (!sent) return // still offline; keep the rest for later
            pending.removeFirst()
        }
    }

    /** This user's synced history, newest first. */
    suspend fun history(limit: Int = 100): List<JSONObject> {
        val active = session ?: return emptyList()
        val rows = client.select(
            table = "messages",
            query = "select=*&order=created_at.desc&limit=$limit",
            accessToken = active.accessToken,
        ).getOrNull() ?: return emptyList()
        return List(rows.length()) { rows.getJSONObject(it) }
    }

    // ── Admin ────────────────────────────────────────────────────────────────

    /**
     * Every registered user. Reads the `profiles` table rather than
     * `auth.users`, which would need the service key -- and a service key must
     * never ship inside an app, since anyone can extract it from the APK.
     * Access is gated server-side by row-level security, so a non-admin calling
     * this simply gets their own row back.
     */
    suspend fun allUsers(): Result<List<UserProfile>> {
        val active = session ?: return Result.success(emptyList())
        return client.select("profiles", "select=id,email,role,disabled&order=email.asc", active.accessToken)
            .map { rows ->
                List(rows.length()) { i ->
                    val row = rows.getJSONObject(i)
                    UserProfile(
                        id = row.optString("id"),
                        email = row.optString("email"),
                        role = row.optString("role", UserProfile.ROLE_USER),
                        disabled = row.optBoolean("disabled", false),
                    )
                }
            }
    }

    suspend fun setRole(userId: String, role: String): Result<Unit> = adminPatch(userId, JSONObject().put("role", role))

    suspend fun setDisabled(userId: String, disabled: Boolean): Result<Unit> =
        adminPatch(userId, JSONObject().put("disabled", disabled))

    private suspend fun adminPatch(userId: String, patch: JSONObject): Result<Unit> {
        val active = session ?: return Result.failure(IllegalStateException("Not signed in."))
        return client.update("profiles", "id", userId, patch, active.accessToken)
    }

    private fun clearStoredSession() {
        prefs.edit { remove(KEY_REFRESH).remove(KEY_EMAIL) }
    }

    private companion object {
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EMAIL = "email"
        const val MAX_PENDING = 200
    }
}
