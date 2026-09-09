package com.aeroglyph.app.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** An authenticated Supabase session. */
data class Session(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long,
    val userId: String,
    val email: String,
) {
    fun isExpired(nowEpochSeconds: Long = System.currentTimeMillis() / 1000): Boolean =
        nowEpochSeconds >= expiresAtEpochSeconds - EXPIRY_SKEW_SECONDS

    private companion object {
        /** Refresh a little early rather than discovering expiry mid-request. */
        const val EXPIRY_SKEW_SECONDS = 60
    }
}

/** A row of the `profiles` table: one per registered user. */
data class UserProfile(
    val id: String,
    val email: String,
    val role: String,
    val disabled: Boolean,
) {
    val isAdmin: Boolean get() = role == ROLE_ADMIN

    companion object {
        const val ROLE_ADMIN = "admin"
        const val ROLE_USER = "user"
    }
}

/**
 * Minimal Supabase client over GoTrue and PostgREST.
 *
 * Written directly against the REST API with [HttpURLConnection] and
 * `org.json` -- both already in the Android platform -- rather than pulling in
 * the Supabase SDK. Three reasons, in order of weight:
 *
 *  1. The whole premise of this app is that it works with no infrastructure.
 *     Cloud sync is an optional extra bolted to the side, and it would be
 *     perverse for it to drag a large dependency tree into an APK whose selling
 *     point is that it needs nothing.
 *  2. Every call the app makes is a plain POST, GET or PATCH with two headers.
 *     The SDK's value is in the parts we do not use.
 *  3. Nothing here is on a hot path -- these are user-initiated actions and a
 *     background log flush -- so hand-rolled request building costs nothing.
 *
 * All calls are suspending and run on IO. Failures come back as
 * [Result.failure] rather than thrown, because every caller here treats a
 * network problem as "stay offline and carry on", never as an error state worth
 * interrupting the user over.
 */
class SupabaseClient(
    private val baseUrl: String,
    private val anonKey: String,
) {

    /** False when no project has been configured; the app then never attempts a request. */
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && anonKey.isNotBlank() && baseUrl.startsWith("https://")

    // ── Auth ─────────────────────────────────────────────────────────────────

    suspend fun signUp(email: String, password: String): Result<Session> =
        authRequest("/auth/v1/signup", JSONObject().put("email", email).put("password", password))

    suspend fun signIn(email: String, password: String): Result<Session> =
        authRequest(
            "/auth/v1/token?grant_type=password",
            JSONObject().put("email", email).put("password", password),
        )

    suspend fun refresh(refreshToken: String): Result<Session> =
        authRequest(
            "/auth/v1/token?grant_type=refresh_token",
            JSONObject().put("refresh_token", refreshToken),
        )

    private suspend fun authRequest(path: String, body: JSONObject): Result<Session> = runCatching {
        val json = JSONObject(request("POST", path, body.toString(), accessToken = null))
        // A fresh sign-up on a project with email confirmation enabled returns
        // a user but no token; that is a legitimate outcome, not a crash.
        val token = json.optString("access_token").takeIf { it.isNotBlank() }
            ?: throw IOException("Check your email to confirm the account, then sign in.")
        val user = json.optJSONObject("user") ?: JSONObject()
        Session(
            accessToken = token,
            refreshToken = json.optString("refresh_token"),
            expiresAtEpochSeconds = System.currentTimeMillis() / 1000 + json.optLong("expires_in", 3600),
            userId = user.optString("id"),
            email = user.optString("email").ifBlank { body.optString("email") },
        )
    }

    // ── PostgREST ────────────────────────────────────────────────────────────

    /** Rows of [table] matching a raw PostgREST [query] such as `select=*&order=created_at.desc`. */
    suspend fun select(table: String, query: String, accessToken: String): Result<JSONArray> =
        runCatching { JSONArray(request("GET", "/rest/v1/$table?$query", null, accessToken)) }

    suspend fun insert(table: String, row: JSONObject, accessToken: String): Result<Unit> =
        runCatching { request("POST", "/rest/v1/$table", row.toString(), accessToken, minimal = true) }
            .map { }

    /** Updates rows of [table] where [column] equals [value]. */
    suspend fun update(
        table: String,
        column: String,
        value: String,
        patch: JSONObject,
        accessToken: String,
    ): Result<Unit> = runCatching {
        val filter = "$column=eq.${URLEncoder.encode(value, "UTF-8")}"
        request("PATCH", "/rest/v1/$table?$filter", patch.toString(), accessToken, minimal = true)
    }.map { }

    // ── Transport ────────────────────────────────────────────────────────────

    private suspend fun request(
        method: String,
        path: String,
        body: String?,
        accessToken: String?,
        minimal: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("apikey", anonKey)
            // Supabase authorises anonymous calls with the anon key itself, and
            // signed-in calls with the user's token -- which is what makes the
            // row-level security policies apply as that user.
            setRequestProperty("Authorization", "Bearer ${accessToken ?: anonKey}")
            setRequestProperty("Content-Type", "application/json")
            if (minimal) setRequestProperty("Prefer", "return=minimal")
            if (body != null) doOutput = true
        }

        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IOException(describe(code, detail))
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Turns a Supabase error body into something worth showing a person.
     * The raw JSON is never surfaced -- it can carry request details, and a
     * user reading "invalid_grant" learns nothing useful.
     */
    private fun describe(code: Int, detail: String): String {
        val message = runCatching {
            val json = JSONObject(detail)
            json.optString("msg").ifBlank {
                json.optString("error_description").ifBlank { json.optString("message") }
            }
        }.getOrNull().orEmpty()

        return when {
            message.isNotBlank() -> message
            code == 400 || code == 401 -> "That email and password combination wasn't accepted."
            code == 403 -> "Your account doesn't have permission to do that."
            code == 404 -> "The cloud tables aren't set up yet — see the README for the schema."
            code == 422 -> "That email is already registered."
            code >= 500 -> "Supabase is unavailable right now."
            else -> "Request failed ($code)."
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000
    }
}
