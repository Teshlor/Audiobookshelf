package com.teshlor.abstv

import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/** One household member signed in on this TV. Tokens are null once the session died ("needs sign-in"). */
@Serializable
data class Profile(
    /** Local UUID, stable for the life of the profile; keys the scoped settings. */
    val id: String,
    /** [AbsApi.normalize]d. */
    val server: String,
    /** Audiobookshelf user id; null for a migrated profile until backfilled. */
    val userId: String? = null,
    val username: String,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val lastUsedAt: Long = 0,
) {
    val needsSignIn: Boolean get() = accessToken.isNullOrEmpty()
}

interface ProfileStore {
    fun all(): List<Profile>
    fun get(id: String): Profile?
    val activeId: String?

    /** Adds the profile or, if the same account is already here, refreshes it in place (id and scoped settings kept). */
    fun upsertFromLogin(server: String, r: LoginResult): Profile
    fun updateTokens(id: String, access: String?, refresh: String?, username: String? = null)

    /** Keeps the profile but forgets its tokens. */
    fun clearTokens(id: String)

    /** Deletes the profile and its scoped settings. */
    fun remove(id: String)
    fun setActive(id: String?)
    fun setUserId(id: String, userId: String)
}

/** Per-profile settings live next to the profile in the same file, as `<key>@<profileId>`. */
object ProfileKeys {
    const val PROFILES = "profiles"
    const val ACTIVE = "active_profile"
    const val LAST_SERVER = "last_server"
    val SCOPED = listOf("library_id", "library_sort", "recent_searches")
    fun scoped(key: String, profileId: String) = "$key@$profileId"
}

/** Shared by the in-memory and prefs-backed stores so the matching rules exist once. Subclasses persist via [persist]. */
abstract class BaseProfileStore(
    initial: List<Profile>,
    initialActive: String?,
    private val clock: () -> Long,
    private val newId: () -> String,
) : ProfileStore {
    private val lock = Any()
    private var list: List<Profile> = initial
    private var active: String? = initialActive?.takeIf { id -> initial.any { it.id == id } }

    protected abstract fun persist(profiles: List<Profile>, activeId: String?)
    protected abstract fun removeScopedKeys(id: String)

    override fun all(): List<Profile> = synchronized(lock) { list }
    override fun get(id: String): Profile? = synchronized(lock) { list.firstOrNull { it.id == id } }
    override val activeId: String? get() = synchronized(lock) { active }

    override fun upsertFromLogin(server: String, r: LoginResult): Profile = synchronized(lock) {
        val srv = AbsApi.normalize(server)
        val name = r.username.orEmpty()
        val match = list.firstOrNull { p ->
            AbsApi.normalize(p.server) == srv && when {
                p.userId != null && r.userId != null -> p.userId == r.userId
                else -> p.username.equals(name, ignoreCase = true)
            }
        }
        val result = if (match != null) {
            match.copy(
                userId = r.userId ?: match.userId,
                username = name.ifEmpty { match.username },
                accessToken = r.accessToken,
                refreshToken = r.refreshToken,
                lastUsedAt = clock(),
            ).also { upd -> list = list.map { if (it.id == upd.id) upd else it } }
        } else {
            Profile(newId(), srv, r.userId, name, r.accessToken, r.refreshToken, clock())
                .also { list = list + it }
        }
        persist(list, active)
        result
    }

    override fun updateTokens(id: String, access: String?, refresh: String?, username: String?) = mutate(id) {
        it.copy(accessToken = access, refreshToken = refresh, username = username ?: it.username)
    }

    override fun clearTokens(id: String) = mutate(id) { it.copy(accessToken = null, refreshToken = null) }
    override fun setUserId(id: String, userId: String) = mutate(id) { it.copy(userId = userId) }

    private fun mutate(id: String, f: (Profile) -> Profile) = synchronized(lock) {
        if (list.none { it.id == id }) return@synchronized
        list = list.map { if (it.id == id) f(it) else it }
        persist(list, active)
    }

    override fun remove(id: String) {
        synchronized(lock) {
            list = list.filterNot { it.id == id }
            if (active == id) active = null
            persist(list, active)
        }
        removeScopedKeys(id)
    }

    override fun setActive(id: String?) = synchronized(lock) {
        active = id?.takeIf { want -> list.any { it.id == want } }
        if (active != null) list = list.map { if (it.id == active) it.copy(lastUsedAt = clock()) else it }
        persist(list, active)
    }
}

class MemoryProfileStore(
    initial: List<Profile> = emptyList(),
    initialActive: String? = null,
    clock: () -> Long = System::currentTimeMillis,
    newId: () -> String = { UUID.randomUUID().toString() },
) : BaseProfileStore(initial, initialActive, clock, newId) {
    override fun persist(profiles: List<Profile>, activeId: String?) {}
    override fun removeScopedKeys(id: String) {}
}

/** The bits of SharedPreferences the profile code needs, so it runs in plain JUnit. */
interface KeyValueStore {
    fun getString(key: String): String?

    /** [commit] = write synchronously (migration); otherwise apply(). */
    fun edit(commit: Boolean = false, block: (Editor) -> Unit)

    interface Editor {
        fun putString(key: String, value: String)
        fun remove(key: String)
    }
}

class SharedPrefsKeyValue(private val prefs: SharedPreferences) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun edit(commit: Boolean, block: (KeyValueStore.Editor) -> Unit) {
        val e = prefs.edit()
        block(object : KeyValueStore.Editor {
            override fun putString(key: String, value: String) { e.putString(key, value) }
            override fun remove(key: String) { e.remove(key) }
        })
        if (commit) e.commit() else e.apply()
    }
}

/** Map-backed [KeyValueStore] for tests. */
class MapKeyValue(val map: MutableMap<String, String> = linkedMapOf()) : KeyValueStore {
    override fun getString(key: String): String? = map[key]
    override fun edit(commit: Boolean, block: (KeyValueStore.Editor) -> Unit) {
        block(object : KeyValueStore.Editor {
            override fun putString(key: String, value: String) { map[key] = value }
            override fun remove(key: String) { map.remove(key) }
        })
    }
}

private val profileJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val profileListSerializer = ListSerializer(Profile.serializer())

internal fun encodeProfiles(list: List<Profile>): String = profileJson.encodeToString(profileListSerializer, list)
internal fun decodeProfiles(raw: String?): List<Profile> =
    if (raw.isNullOrEmpty()) emptyList()
    else try { profileJson.decodeFromString(profileListSerializer, raw) } catch (e: Exception) { emptyList() }

class PrefsProfileStore(
    private val kv: KeyValueStore,
    clock: () -> Long = System::currentTimeMillis,
    newId: () -> String = { UUID.randomUUID().toString() },
) : BaseProfileStore(decodeProfiles(kv.getString(ProfileKeys.PROFILES)), kv.getString(ProfileKeys.ACTIVE), clock, newId) {
    override fun persist(profiles: List<Profile>, activeId: String?) {
        kv.edit {
            it.putString(ProfileKeys.PROFILES, encodeProfiles(profiles))
            if (activeId == null) it.remove(ProfileKeys.ACTIVE) else it.putString(ProfileKeys.ACTIVE, activeId)
        }
    }

    override fun removeScopedKeys(id: String) {
        kv.edit { e -> ProfileKeys.SCOPED.forEach { e.remove(ProfileKeys.scoped(it, id)) } }
    }
}

private val LEGACY_KEYS = listOf("server", "access", "refresh", "token", "username", "library_id", "library_sort", "recent_searches")

/**
 * One-time move from the single-session layout (`server`, `access`/`token`, `refresh`, `username`, plus the
 * per-user settings) to profile #1. Guard: runs only while the `profiles` key is absent. The new keys are committed
 * in one write BEFORE the old ones are removed, so a crash in between leaves both (never neither). If it finds
 * profiles already present with old keys left behind (crash before cleanup), it only finishes that cleanup.
 */
fun migrateLegacy(
    kv: KeyValueStore,
    clock: () -> Long = System::currentTimeMillis,
    newId: () -> String = { UUID.randomUUID().toString() },
) {
    if (kv.getString(ProfileKeys.PROFILES) != null) {
        if (LEGACY_KEYS.any { kv.getString(it) != null }) kv.edit(commit = true) { e -> LEGACY_KEYS.forEach(e::remove) }
        return
    }
    val server = kv.getString("server")?.takeIf { it.isNotBlank() }?.let { AbsApi.normalize(it) }
    val access = kv.getString("access")?.takeIf { it.isNotEmpty() } ?: kv.getString("token")?.takeIf { it.isNotEmpty() }
    val profile = if (server != null && access != null) {
        Profile(newId(), server, null, kv.getString("username").orEmpty(), access, kv.getString("refresh")?.takeIf { it.isNotEmpty() }, clock())
    } else null
    kv.edit(commit = true) { e ->
        e.putString(ProfileKeys.PROFILES, encodeProfiles(listOfNotNull(profile)))
        if (profile != null) {
            e.putString(ProfileKeys.ACTIVE, profile.id)
            for (k in ProfileKeys.SCOPED) kv.getString(k)?.let { e.putString(ProfileKeys.scoped(k, profile.id), it) }
        }
        if (server != null) e.putString(ProfileKeys.LAST_SERVER, server)
    }
    kv.edit(commit = true) { e -> LEGACY_KEYS.forEach(e::remove) }
}
