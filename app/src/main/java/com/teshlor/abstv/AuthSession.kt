package com.teshlor.abstv

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.Authenticator
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Where the server URL and the token pair live. Tests use an in-memory one. */
interface TokenStore {
    var server: String

    /** Short-lived JWT access token. For an install that predates Auth v2 this is the legacy `user.token`. */
    var accessToken: String?

    /** Null for a legacy-only install (no refresh possible; the next 401 means "sign in again"). */
    var refreshToken: String?
    var username: String?

    /** Replaces the pair (also drops any legacy token). */
    fun saveTokens(access: String, refresh: String?, username: String? = this.username)

    /** Forgets the tokens but keeps the server URL so the login form stays prefilled. */
    fun clearTokens()
}

/** SharedPreferences "abs": keys `server` and `token` (legacy) already existed; `access`, `refresh`, `username` are new. */
class SharedPrefsTokenStore(private val prefs: SharedPreferences) : TokenStore {
    constructor(context: Context) : this(context.getSharedPreferences("abs", Context.MODE_PRIVATE))

    override var server: String
        get() = prefs.getString("server", "").orEmpty()
        set(v) { prefs.edit().putString("server", v).apply() }
    override var accessToken: String?
        // Migration: fall back to the legacy non-expiring token so an existing install keeps working untouched.
        get() = prefs.getString("access", null) ?: prefs.getString("token", null)
        set(v) { prefs.edit().putString("access", v).apply() }
    override var refreshToken: String?
        get() = prefs.getString("refresh", null)
        set(v) { prefs.edit().putString("refresh", v).apply() }
    override var username: String?
        get() = prefs.getString("username", null)
        set(v) { prefs.edit().putString("username", v).apply() }

    override fun saveTokens(access: String, refresh: String?, username: String?) {
        prefs.edit().putString("access", access).putString("refresh", refresh).putString("username", username)
            .remove("token").apply()
    }

    override fun clearTokens() {
        prefs.edit().remove("access").remove("refresh").remove("token").apply()
    }
}

class MemoryTokenStore(
    override var server: String = "",
    override var accessToken: String? = null,
    override var refreshToken: String? = null,
    override var username: String? = null,
) : TokenStore {
    @Synchronized
    override fun saveTokens(access: String, refresh: String?, username: String?) {
        accessToken = access; refreshToken = refresh; this.username = username
    }

    @Synchronized
    override fun clearTokens() { accessToken = null; refreshToken = null }
}

/**
 * After the session expired the UI is already on Login with "Please sign in again"; the 401 that triggered it
 * must not replace that message with "Server returned HTTP 401".
 */
fun shouldShowError(authExpired: Boolean, e: Throwable): Boolean =
    !(authExpired && e is AbsHttpException && e.code == 401)

data class LoginResult(val accessToken: String, val refreshToken: String?, val username: String?)

/**
 * Adds the Bearer access token to every request and, on a 401, refreshes it once (single-flight) and retries.
 * Failure handling: a 401/403 from the refresh call means the session is dead (tokens cleared, [onExpired] fired);
 * a network error or 5xx just fails that one request and leaves the user signed in.
 */
class AuthSession(val store: TokenStore, private val onExpired: () -> Unit = {}) {
    private val lock = Any()

    /** Client with auth. The refresh call itself uses [plain], so it can never recurse into the authenticator. */
    fun newClient(baseUrl: String, plain: OkHttpClient = OkHttpClient()): OkHttpClient {
        val base = baseUrl.toHttpUrl()
        return plain.newBuilder()
            .addInterceptor(Interceptor { chain ->
                val t = store.accessToken
                val req = chain.request()
                // Only ever send the token to our own server.
                if (t.isNullOrEmpty() || req.url.host != base.host || req.url.port != base.port) chain.proceed(req)
                else chain.proceed(req.newBuilder().header("Authorization", "Bearer $t").build())
            })
            .authenticator(object : Authenticator {
                override fun authenticate(route: Route?, response: Response): Request? =
                    onUnauthorized(response, baseUrl, plain)
            })
            .build()
    }

    private fun onUnauthorized(response: Response, baseUrl: String, plain: OkHttpClient): Request? {
        val sent = response.request.header("Authorization")?.removePrefix("Bearer ") ?: return null
        if (response.priorResponse != null) return null // already retried once
        synchronized(lock) {
            val current = store.accessToken ?: return null // signed out meanwhile
            if (current != sent) return retry(response, current) // another call already refreshed
            val refresh = store.refreshToken
            if (refresh.isNullOrEmpty()) { // legacy token rejected: nothing to refresh with
                expireIfUnchanged(current, null)
                return null
            }
            return try {
                val r = refresh(plain, baseUrl, refresh)
                store.saveTokens(r.accessToken, r.refreshToken ?: refresh)
                retry(response, r.accessToken)
            } catch (e: AbsHttpException) {
                if (e.code == 401 || e.code == 403) expireIfUnchanged(current, refresh)
                null
            } catch (e: Exception) {
                null // offline / timeout / unparseable reply: stay signed in
            }
        }
    }

    private fun retry(response: Response, access: String): Request =
        response.request.newBuilder().header("Authorization", "Bearer $access").build()

    /** Only signs out if the stored pair is still the one that failed, so a stale failure can't wipe a newer login. */
    private fun expireIfUnchanged(access: String, refresh: String?) {
        if (store.accessToken != access || store.refreshToken != refresh) return
        store.clearTokens()
        onExpired()
    }

    private fun refresh(plain: OkHttpClient, baseUrl: String, refreshToken: String): LoginResult {
        val req = Request.Builder().url("$baseUrl/auth/refresh")
            .header("x-refresh-token", refreshToken)
            .header("x-return-tokens", "true")
            .post("".toRequestBody())
            .build()
        val short = plain.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
        short.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw AbsHttpException(r.code)
            return parseTokens(r.body?.string().orEmpty())
        }
    }

    companion object {
        /** `{"user": {"accessToken", "refreshToken"?, "username"}}` as returned by /login and /auth/refresh. */
        fun parseTokens(body: String): LoginResult {
            val user = AbsParse.json.parseToJsonElement(body).jsonObject["user"]?.jsonObject
                ?: throw IOException("Response had no user")
            fun s(k: String) = (user[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val access = s("accessToken") ?: throw IOException("Response had no access token")
            return LoginResult(access, s("refreshToken"), s("username"))
        }
    }
}
