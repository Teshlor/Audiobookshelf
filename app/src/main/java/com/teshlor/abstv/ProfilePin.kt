package com.teshlor.abstv

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Salted PBKDF2WithHmacSHA256. The PIN itself is never stored or logged. */
object PinCrypto {
    const val ITERATIONS = 20_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256

    fun newSalt(random: SecureRandom = SecureRandom()): ByteArray = ByteArray(SALT_BYTES).also { random.nextBytes(it) }

    fun hash(pin: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    fun fromHex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Constant-time compare. */
    fun matches(pin: String, saltHex: String, hashHex: String, iterations: Int = ITERATIONS): Boolean =
        MessageDigest.isEqual(hash(pin, fromHex(saltHex), iterations), fromHex(hashHex))
}

const val PIN_LENGTH = 4
const val PIN_MAX_FAILURES = 5
const val PIN_COOLDOWN_MS = 30_000L

fun isValidPin(pin: String?): Boolean = pin != null && pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }

/** Outcome of asking to switch to a profile. */
sealed interface SwitchResult {
    /** The profile is now the active one. */
    data object Switched : SwitchResult

    /** The profile has a PIN and none was given. */
    data object NeedsPin : SwitchResult

    /** Wrong PIN; [attemptsLeft] more tries before the cooldown. */
    data class WrongPin(val attemptsLeft: Int) : SwitchResult

    /** PIN entry is locked until [untilMs] (wall clock). Includes the 5th wrong try. */
    data class CoolingDown(val untilMs: Long) : SwitchResult

    /** The profile has no session (or doesn't exist): send the user to the sign-in-again screen. */
    data object NeedsSignIn : SwitchResult
}

/**
 * PIN storage and the switch gate, on top of a [ProfileStore]. Pure logic (no Android): the wrong-try count and the
 * cooldown are written back to the store so they survive a restart. Thread-safe; verification takes ~tens of ms, so
 * callers may run it off the main thread.
 */
class PinService(
    private val store: ProfileStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    private val iterations: Int = PinCrypto.ITERATIONS,
) {
    private val lock = Any()

    /** Sets (or replaces) the PIN. False if it isn't exactly 4 digits or the profile doesn't exist. */
    fun setPin(id: String, pin: String): Boolean = synchronized(lock) {
        if (!isValidPin(pin) || store.get(id) == null) return false
        val salt = PinCrypto.newSalt(random)
        store.setPin(id, PinCrypto.toHex(PinCrypto.hash(pin, salt, iterations)), PinCrypto.toHex(salt))
        true
    }

    fun clearPin(id: String) = synchronized(lock) { store.clearPin(id) }

    fun hasPin(id: String): Boolean = store.get(id)?.hasPin == true

    /**
     * Cooldown end for [id], or null if entry is open. If the stored end is further out than one cooldown (the clock was
     * set back), it is clamped AND written back, so the lock ends 30 s from now instead of rolling forward.
     */
    fun cooldownUntil(id: String): Long? = synchronized(lock) {
        val p = store.get(id) ?: return null
        val now = clock()
        if (p.pinCooldownUntil <= now) return null
        val max = now + PIN_COOLDOWN_MS
        if (p.pinCooldownUntil > max) {
            store.setPinAttempts(id, p.pinFailures, max)
            return max
        }
        p.pinCooldownUntil
    }

    /**
     * Checks [pin] against [id]'s PIN. A profile without a PIN always passes. During a cooldown nothing is checked and
     * nothing is counted. The 5th wrong try starts a 30 s cooldown and resets the count.
     */
    fun verify(id: String, pin: String): SwitchResult = synchronized(lock) {
        val p = store.get(id) ?: return SwitchResult.NeedsSignIn
        if (!p.hasPin) return SwitchResult.Switched
        cooldownUntil(id)?.let { return SwitchResult.CoolingDown(it) }
        val ok = isValidPin(pin) && PinCrypto.matches(pin, p.pinSalt!!, p.pinHash!!, iterations)
        if (ok) {
            if (p.pinFailures != 0 || p.pinCooldownUntil != 0L) store.setPinAttempts(id, 0, 0)
            return SwitchResult.Switched
        }
        val failures = p.pinFailures + 1
        if (failures >= PIN_MAX_FAILURES) {
            val until = clock() + PIN_COOLDOWN_MS
            store.setPinAttempts(id, 0, until)
            SwitchResult.CoolingDown(until)
        } else {
            store.setPinAttempts(id, failures, 0)
            SwitchResult.WrongPin(PIN_MAX_FAILURES - failures)
        }
    }

    /**
     * The gate in front of switching to [id]: no session -> NeedsSignIn; PIN-protected -> NeedsPin / CoolingDown until
     * a correct [pin] is given; otherwise Switched. Does not change the active profile itself.
     */
    fun gate(id: String, pin: String?): SwitchResult {
        val p = store.get(id) ?: return SwitchResult.NeedsSignIn
        if (p.needsSignIn) return SwitchResult.NeedsSignIn
        if (!p.hasPin) return SwitchResult.Switched
        if (pin == null) return cooldownUntil(id)?.let { SwitchResult.CoolingDown(it) } ?: SwitchResult.NeedsPin
        return verify(id, pin)
    }
}
