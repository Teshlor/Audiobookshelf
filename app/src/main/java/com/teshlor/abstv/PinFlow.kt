package com.teshlor.abstv

import kotlin.math.ceil

// Pure logic behind the PIN screens (no Android, no Compose): the key pad state, the countdown and the step machine.
// Design: design/profiles/HANDOFF.md section 13.

// ---- Pad state ----

/** What the pad shows: the digits typed so far, whether a check is running, and the end of a cooldown (wall-clock ms, 0 = none). */
data class PinPadState(
    val digits: String = "",
    /** True from the 4th digit until the result is back: input is locked. */
    val checking: Boolean = false,
    val cooldownUntilMs: Long = 0,
) {
    fun coolingDown(nowMs: Long): Boolean = cooldownUntilMs > nowMs
    fun accepts(nowMs: Long): Boolean = !checking && !coolingDown(nowMs)

    /** A digit key. Ignored while checking, cooling down or for anything but 0-9. The 4th digit locks input (submit with [complete]). */
    fun press(d: Char, nowMs: Long): PinPadState {
        if (d !in '0'..'9' || !accepts(nowMs) || digits.length >= PIN_LENGTH) return this
        val next = digits + d
        return copy(digits = next, checking = next.length == PIN_LENGTH)
    }

    /** The backspace key / DEL. */
    fun delete(nowMs: Long): PinPadState =
        if (!accepts(nowMs) || digits.isEmpty()) this else copy(digits = digits.dropLast(1))

    /** The PIN to check, once the 4th digit is in. */
    val complete: String? get() = digits.takeIf { checking && it.length == PIN_LENGTH }

    /** Result is back and it was wrong (or a mismatch): empty dots, input open again. */
    fun cleared(): PinPadState = copy(digits = "", checking = false)

    fun cooling(untilMs: Long): PinPadState = copy(digits = "", checking = false, cooldownUntilMs = untilMs)
    fun cooldownOver(): PinPadState = copy(cooldownUntilMs = 0)
}

/** Whole seconds left of a cooldown, rounded up so it reads 0:30 first and never shows 0:00 while still locked. */
fun cooldownSecondsLeft(untilMs: Long, nowMs: Long): Int =
    if (untilMs <= nowMs) 0 else ceil((untilMs - nowMs) / 1000.0).toInt()

/** "0:27", "1:05". */
fun formatCountdown(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)

/** Under the dots after a wrong PIN (copy: HANDOFF 13.9; the hyphen is a non-breaking one). */
fun wrongPinMessage(attemptsLeft: Int): String = when {
    attemptsLeft >= 3 -> "Wrong PIN. Try again."
    attemptsLeft == 2 -> "Wrong PIN. 2 more tries, then a 30‑second wait."
    else -> "Wrong PIN. 1 more try, then a 30‑second wait."
}

const val PIN_MISMATCH_MESSAGE = "Those didn’t match. Choose your PIN again."
const val PIN_COOLDOWN_HELP = "Wait for the timer, or reset your PIN with your password."

/** KEYCODE_0..9 and KEYCODE_NUMPAD_0..9 -> the digit, else null. Takes raw Android key codes so it stays testable. */
fun digitForKeyCode(keyCode: Int): Char? = when (keyCode) {
    in 7..16 -> '0' + (keyCode - 7) // KEYCODE_0..KEYCODE_9
    in 144..153 -> '0' + (keyCode - 144) // KEYCODE_NUMPAD_0..NUMPAD_9
    else -> null
}

const val KEYCODE_DEL_CODE = 67

// ---- Step machine ----

/** Why the PIN screen is open. */
enum class PinPurpose {
    /** Picker / rail list: PIN, then Switching. */
    SWITCH,

    /** "Remove from this TV" on an expired profile that has a PIN. */
    REMOVE_PROFILE,

    /** Settings: Set a PIN. */
    SET,

    /** Settings: Change PIN (current PIN first). */
    CHANGE,

    /** Settings: Remove PIN (current PIN first). */
    REMOVE_PIN,

    /** Straight after Add profile: choose a PIN for the new profile, then Switching. */
    ADD_PROFILE,
}

enum class PinStep { UNLOCK, VERIFY, CHOOSE, CONFIRM }

/**
 * One run through the PIN screens. [first] is the PIN typed at CHOOSE while CONFIRM waits for the repeat; [afterForgot]
 * means the account password was just accepted (so CHOOSE offers "Remove PIN instead"); [notice] is the line shown
 * when the step opens (the mismatch message).
 */
data class PinTask(
    val purpose: PinPurpose,
    val profileId: String,
    val step: PinStep = purpose.startStep(),
    val first: String? = null,
    val afterForgot: Boolean = false,
    val notice: String? = null,
)

fun PinPurpose.startStep(): PinStep = when (this) {
    PinPurpose.SWITCH -> PinStep.UNLOCK
    PinPurpose.REMOVE_PROFILE, PinPurpose.CHANGE, PinPurpose.REMOVE_PIN -> PinStep.VERIFY
    PinPurpose.SET, PinPurpose.ADD_PROFILE -> PinStep.CHOOSE
}

/** What gets saved when a run finishes. */
sealed interface PinSave {
    data class Set(val pin: String) : PinSave
    data object Clear : PinSave
}

/** Where a finished run goes. */
enum class PinNav {
    /** Switching screen, then the profile's Home. */
    OPEN_PROFILE,

    /** Remove the profile from this TV. */
    REMOVE_PROFILE,

    /** Pop back to the screen that opened the PIN screen (Settings). */
    BACK,
}

sealed interface PinStepResult {
    /** Stay in the PIN screens, on another step. */
    data class Next(val task: PinTask) : PinStepResult

    /** Finished: save [save] (if any), show [text] for 600 ms (if any), then go [nav]. */
    data class Done(val save: PinSave?, val nav: PinNav, val text: String?) : PinStepResult
}

private fun PinPurpose.finishNav(): PinNav = when (this) {
    PinPurpose.SWITCH, PinPurpose.ADD_PROFILE -> PinNav.OPEN_PROFILE
    PinPurpose.REMOVE_PROFILE -> PinNav.REMOVE_PROFILE
    PinPurpose.SET, PinPurpose.CHANGE, PinPurpose.REMOVE_PIN -> PinNav.BACK
}

/** The current PIN (UNLOCK / VERIFY) was right. */
fun PinTask.onVerified(): PinStepResult = when (purpose) {
    PinPurpose.SWITCH -> PinStepResult.Done(null, PinNav.OPEN_PROFILE, null)
    PinPurpose.REMOVE_PROFILE -> PinStepResult.Done(null, PinNav.REMOVE_PROFILE, null)
    PinPurpose.REMOVE_PIN -> PinStepResult.Done(PinSave.Clear, PinNav.BACK, "PIN removed")
    // Change PIN: on to choosing the new one. (SET / ADD_PROFILE never verify.)
    else -> PinStepResult.Next(copy(step = PinStep.CHOOSE, first = null, notice = null))
}

/** Four digits typed at CHOOSE. */
fun PinTask.onChosen(pin: String): PinStepResult.Next =
    PinStepResult.Next(copy(step = PinStep.CONFIRM, first = pin, notice = null))

/** Four digits typed at CONFIRM: the same ones finish the run, anything else goes back to CHOOSE with a notice. */
fun PinTask.onConfirmed(pin: String): PinStepResult =
    if (pin == first) {
        val text = if (purpose == PinPurpose.CHANGE || afterForgot) "PIN changed" else "PIN set"
        PinStepResult.Done(PinSave.Set(pin), purpose.finishNav(), text)
    } else {
        PinStepResult.Next(copy(step = PinStep.CHOOSE, first = null, notice = PIN_MISMATCH_MESSAGE))
    }

/** "Remove PIN instead" after Forgot PIN. */
fun PinTask.onRemoveInstead(): PinStepResult.Done =
    PinStepResult.Done(PinSave.Clear, purpose.finishNav(), "PIN removed")

/**
 * The account password was accepted. The old PIN stays until a new one is confirmed (Back keeps it). Removing a
 * profile needs no new PIN: the password already proved who it is.
 */
fun PinTask.onPasswordAccepted(): PinStepResult =
    if (purpose == PinPurpose.REMOVE_PROFILE) PinStepResult.Done(null, PinNav.REMOVE_PROFILE, null)
    else PinStepResult.Next(copy(step = PinStep.CHOOSE, first = null, afterForgot = true, notice = null))

/** Back on the PIN screen. Null = leave the PIN screens (pop). */
fun PinTask.onBack(): PinTask? = when {
    step == PinStep.CONFIRM -> copy(step = PinStep.CHOOSE, first = null, notice = null)
    step == PinStep.CHOOSE && afterForgot -> copy(step = purpose.startStep(), first = null, afterForgot = false, notice = null)
    else -> null
}

enum class PinGhost { FORGOT, REMOVE_INSTEAD }

fun PinTask.ghost(): PinGhost? = when {
    step == PinStep.UNLOCK || step == PinStep.VERIFY -> PinGhost.FORGOT
    step == PinStep.CHOOSE && afterForgot -> PinGhost.REMOVE_INSTEAD
    else -> null
}

fun PinTask.instruction(name: String): String = when (step) {
    PinStep.UNLOCK -> "Enter your PIN"
    PinStep.VERIFY -> if (purpose == PinPurpose.REMOVE_PROFILE) "Enter the PIN to remove $name" else "Enter your current PIN"
    PinStep.CHOOSE -> if (afterForgot || purpose == PinPurpose.CHANGE) "Choose a new PIN" else "Choose a 4-digit PIN"
    PinStep.CONFIRM -> "Enter it again to confirm"
}

/** What the PIN screen is told after four digits were checked. */
sealed interface PinResult {
    /** Right (or the step was accepted): show [text] for 600 ms if there is some, then move on. */
    data class Accepted(val text: String? = null) : PinResult

    /** Wrong PIN: red dots + shake, then [message] stays until the next digit. An empty message shows nothing. */
    data class Wrong(val message: String) : PinResult

    /** The 5th wrong PIN (or a profile still cooling down): the pad is disabled until [untilMs]. */
    data class Cooldown(val untilMs: Long) : PinResult

    /** CONFIRM didn't match: shake, then back to CHOOSE (the notice comes with the new step). */
    data object Mismatch : PinResult
}
