package com.teshlor.abstv

import androidx.compose.runtime.Immutable

/** What the profile screens need to draw one person. Never carries tokens or PIN material. */
@Immutable
data class ProfileSummary(
    val id: String,
    /** "Evan", or "Profile 1" when the stored username is still empty ([nameKnown] false: show a person glyph, not an initial). */
    val displayName: String,
    val username: String,
    val nameKnown: Boolean,
    val server: String,
    /** host:port of [server]. */
    val host: String,
    /** Index into the 8 avatar colours. */
    val colorIndex: Int,
    val needsSignIn: Boolean,
    val hasPin: Boolean,
    /** The profile whose library is open right now (null on the picker / login). */
    val isCurrent: Boolean,
    /** The profile used last: where the picker puts focus. */
    val isLastUsed: Boolean,
)

fun profileSummaries(profiles: List<Profile>, currentId: String?, lastUsedId: String?): List<ProfileSummary> =
    profiles.mapIndexed { i, p ->
        ProfileSummary(
            id = p.id,
            displayName = profileDisplayName(p.username, i),
            username = p.username,
            nameKnown = p.username.isNotEmpty(),
            server = p.server,
            host = hostLabel(p.server),
            colorIndex = p.colorIndex.coerceIn(0, AVATAR_COLOR_COUNT - 1),
            needsSignIn = p.needsSignIn,
            hasPin = p.hasPin,
            isCurrent = p.id == currentId,
            isLastUsed = p.id == lastUsedId,
        )
    }

/** The profile to open when the app starts without a picker: the stored active one, else the most recently used. */
fun resolveActive(profiles: List<Profile>, activeId: String?): Profile? =
    profiles.firstOrNull { it.id == activeId } ?: profiles.maxByOrNull { it.lastUsedAt }

/** The picker is for a TV that changes hands: 2+ profiles, or a single one that is PIN-protected. */
fun pickerNeeded(profiles: List<Profile>): Boolean = profiles.size >= 2 || profiles.any { it.hasPin }

/**
 * Where the app lands: no profile -> Login; picker needed -> Profiles; one healthy profile -> Home (exactly as before
 * profiles existed); one profile with a dead session -> Login (the caller prefills it as sign-in-again).
 */
fun startDestination(profiles: List<Profile>): Screen = when {
    profiles.isEmpty() -> Screen.Login
    pickerNeeded(profiles) -> Screen.Profiles
    profiles.single().needsSignIn -> Screen.Login
    else -> Screen.Browse(Tab.HOME)
}

/** Back in the app after this long in the background shows the picker again (the TV may have changed hands). */
const val PICKER_AFTER_BACKGROUND_MS = 60 * 60 * 1000L

/**
 * [backgroundedAtMs] is when onStop last ran (null = never, e.g. cold start), [nowMs] on the same monotonic clock.
 * No picker with fewer than 2 profiles (and no PIN), while audio is playing, or if the picker / a login is already up.
 */
fun shouldShowPickerOnForeground(
    profiles: List<Profile>,
    backgroundedAtMs: Long?,
    nowMs: Long,
    playing: Boolean,
    current: Screen,
    stackSize: Int = 1,
): Boolean {
    if (backgroundedAtMs == null || playing) return false
    if (!pickerNeeded(profiles)) return false
    if (current == Screen.Profiles) return false
    // A Login at the stack root has no profile behind it. One pushed over an open profile does: Back would pop into it, so reset.
    if (current == Screen.Login && stackSize <= 1) return false
    return nowMs - backgroundedAtMs >= PICKER_AFTER_BACKGROUND_MS
}

/** FORGOT_PIN: the account password for a profile whose PIN was forgotten (then the PIN is chosen again). */
enum class LoginMode { FIRST, ADD, REAUTH, FORGOT_PIN }

/** What the Login screen starts with; replaces the old `savedServer`. */
data class LoginPrefill(
    val mode: LoginMode = LoginMode.FIRST,
    val server: String = "",
    /** REAUTH only: the stored username (may be empty for a migrated profile). */
    val username: String = "",
    /** REAUTH only. */
    val profileId: String? = null,
    val displayName: String = "",
    val colorIndex: Int = 0,
    /** REAUTH / FORGOT_PIN: the profile has a PIN (decides whether "Remove from this TV" asks for it). */
    val hasPin: Boolean = false,
)

fun loginPrefillFor(
    mode: LoginMode,
    profiles: List<Profile>,
    currentId: String?,
    lastServer: String,
    targetId: String? = null,
): LoginPrefill = when (mode) {
    LoginMode.FIRST -> LoginPrefill(LoginMode.FIRST, server = lastServer)
    LoginMode.ADD -> LoginPrefill(
        LoginMode.ADD,
        server = profiles.firstOrNull { it.id == currentId }?.server ?: lastServer,
        // The colour the new profile will get (the preview avatar in the header).
        colorIndex = pickColorIndex(profiles.map { it.colorIndex }.filter { it >= 0 }, profiles.size),
    )
    LoginMode.REAUTH, LoginMode.FORGOT_PIN -> {
        val i = profiles.indexOfFirst { it.id == targetId }
        val p = profiles.getOrNull(i)
        if (p == null) LoginPrefill(LoginMode.FIRST, server = lastServer)
        else LoginPrefill(
            mode, p.server, p.username, p.id,
            profileDisplayName(p.username, i), p.colorIndex.coerceIn(0, AVATAR_COLOR_COUNT - 1), p.hasPin,
        )
    }
}

/** The line under the form after a failed sign-in. A rejected password on Sign in again / Forgot PIN gets the designed copy. */
fun loginErrorMessage(mode: LoginMode, e: Throwable): String {
    val rejected = e is AbsHttpException && (e.code == 400 || e.code == 401 || e.code == 403)
    return if (rejected && (mode == LoginMode.REAUTH || mode == LoginMode.FORGOT_PIN)) {
        "That password didn\u2019t work. Check it and try again."
    } else e.message ?: e.toString()
}
