package com.teshlor.abstv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePinTest {
    private var now = 1_000_000L
    private var n = 0
    private val kv = MapKeyValue()
    private fun store(k: MapKeyValue = kv) = PrefsProfileStore(k, clock = { now }, newId = { "P${++n}" })
    private fun service(s: ProfileStore) = PinService(s, clock = { now }, iterations = 1_000) // fast for tests

    private fun withProfile(s: ProfileStore): Profile =
        s.upsertFromLogin("http://h:1", LoginResult("a", "r", "evan", "u1"))

    @Test fun hashIsDeterministicSaltedAndNotThePin() {
        val salt = PinCrypto.newSalt()
        val h1 = PinCrypto.hash("1234", salt, 1_000)
        assertArrayEquals(h1, PinCrypto.hash("1234", salt, 1_000))
        assertFalse(h1.contentEquals(PinCrypto.hash("1235", salt, 1_000)))
        assertFalse(h1.contentEquals(PinCrypto.hash("1234", PinCrypto.newSalt(), 1_000)))
        assertEquals(32, h1.size)
        assertFalse(PinCrypto.toHex(h1).contains("1234"))
        assertArrayEquals(h1, PinCrypto.fromHex(PinCrypto.toHex(h1)))
        assertTrue(PinCrypto.ITERATIONS >= 10_000)
    }

    @Test fun setPinStoresHashAndSaltNeverThePin() {
        val s = store(); val p = withProfile(s)
        assertTrue(service(s).setPin(p.id, "4821"))
        val saved = s.get(p.id)!!
        assertTrue(saved.hasPin)
        assertFalse(kv.map.values.any { it.contains("4821") })
        assertEquals(32, saved.pinSalt!!.length)
    }

    @Test fun onlyFourDigitsAreAccepted() {
        val s = store(); val p = withProfile(s); val svc = service(s)
        for (bad in listOf("", "123", "12345", "12a4", " 123")) assertFalse(bad, svc.setPin(p.id, bad))
        assertFalse(s.get(p.id)!!.hasPin)
        assertFalse(svc.setPin("nope", "1234"))
    }

    @Test fun correctPinPassesWrongPinDoesNot() {
        val s = store(); val p = withProfile(s); val svc = service(s)
        svc.setPin(p.id, "1234")
        assertEquals(SwitchResult.Switched, svc.verify(p.id, "1234"))
        assertEquals(SwitchResult.WrongPin(4), svc.verify(p.id, "0000"))
        assertEquals(SwitchResult.WrongPin(3), svc.verify(p.id, "0000"))
        assertEquals(SwitchResult.Switched, svc.verify(p.id, "1234"))
        assertEquals(0, s.get(p.id)!!.pinFailures) // success resets the count
    }

    @Test fun fifthWrongStartsA30SecondCooldownThatBlocksEvenTheRightPin() {
        val s = store(); val p = withProfile(s); val svc = service(s)
        svc.setPin(p.id, "1234")
        repeat(4) { svc.verify(p.id, "0000") }
        assertEquals(SwitchResult.CoolingDown(now + 30_000), svc.verify(p.id, "0000"))
        assertEquals(SwitchResult.CoolingDown(now + 30_000), svc.verify(p.id, "1234"))
        assertEquals(0, s.get(p.id)!!.pinFailures)
        now += 29_999
        assertEquals(SwitchResult.CoolingDown(1_000_000 + 30_000), svc.verify(p.id, "1234"))
        now += 1
        assertNull(svc.cooldownUntil(p.id))
        assertEquals(SwitchResult.Switched, svc.verify(p.id, "1234"))
    }

    @Test fun afterACooldownTheNextFiveTriesCountAgain() {
        val s = store(); val p = withProfile(s); val svc = service(s)
        svc.setPin(p.id, "1234")
        repeat(5) { svc.verify(p.id, "0000") }
        now += 30_000
        assertEquals(SwitchResult.WrongPin(4), svc.verify(p.id, "0000"))
    }

    @Test fun attemptsAndCooldownSurviveARestart() {
        val s = store(); val p = withProfile(s)
        service(s).setPin(p.id, "1234")
        repeat(2) { service(s).verify(p.id, "0000") }
        assertEquals(SwitchResult.WrongPin(2), service(store()).verify(p.id, "0000"))
        repeat(2) { service(store()).verify(p.id, "0000") } // 5th wrong overall
        assertEquals(SwitchResult.CoolingDown(now + 30_000), service(store()).gate(p.id, null))
    }

    @Test fun cooldownIsCappedAndRewrittenIfTheClockWasSetBack() {
        val s = store(); val p = withProfile(s); val svc = service(s)
        svc.setPin(p.id, "1234")
        s.setPinAttempts(p.id, 0, now + 10 * 60_000)
        assertEquals(now + 30_000, svc.cooldownUntil(p.id))
        assertEquals(now + 30_000, s.get(p.id)!!.pinCooldownUntil) // persisted, not a rolling window
        now += 30_000
        assertNull(svc.cooldownUntil(p.id))
        assertEquals(SwitchResult.Switched, svc.gate(p.id, "1234"))
    }

    @Test fun gateRules() {
        val s = store(); val p = withProfile(s); val svc = service(s)
        assertEquals(SwitchResult.Switched, svc.gate(p.id, null)) // no PIN
        assertEquals(SwitchResult.NeedsSignIn, svc.gate("missing", null))
        svc.setPin(p.id, "1234")
        assertEquals(SwitchResult.NeedsPin, svc.gate(p.id, null))
        assertEquals(SwitchResult.WrongPin(4), svc.gate(p.id, "9999"))
        assertEquals(SwitchResult.Switched, svc.gate(p.id, "1234"))
        s.clearTokens(p.id)
        assertEquals(SwitchResult.NeedsSignIn, svc.gate(p.id, "1234")) // no session beats the PIN
    }

    @Test fun clearPinRemovesIt() {
        val s = store(); val p = withProfile(s); val svc = service(s)
        svc.setPin(p.id, "1234"); svc.clearPin(p.id)
        assertFalse(s.get(p.id)!!.hasPin)
        assertEquals(SwitchResult.Switched, svc.gate(p.id, null))
    }

    @Test fun passwordLoginForTheSameAccountKeepsThePinButEndsTheLockout() {
        val s = store(); val p = withProfile(s); val svc = service(s)
        svc.setPin(p.id, "1234")
        repeat(5) { svc.verify(p.id, "0000") }
        val again = s.upsertFromLogin("http://h:1", LoginResult("a2", "r2", "evan", "u1"), reauthId = p.id)
        assertEquals(p.id, again.id)
        assertTrue(again.hasPin) // an ordinary sign-in again (expired session, Add profile) never drops the PIN
        assertEquals(0L, again.pinCooldownUntil)
        assertEquals(SwitchResult.NeedsPin, svc.gate(p.id, null))
        assertEquals(SwitchResult.Switched, svc.gate(p.id, "1234"))
    }

    @Test fun forgotPinResetsTriesButKeepsTheOldPinUntilANewOneIsSet() {
        val s = store(); val p = withProfile(s); val svc = service(s)
        svc.setPin(p.id, "1234")
        repeat(5) { svc.verify(p.id, "0000") }
        assertTrue(svc.cooldownUntil(p.id) != null)
        svc.resetAttempts(p.id)
        assertEquals(null, svc.cooldownUntil(p.id))
        assertEquals(0, s.get(p.id)!!.pinFailures)
        assertEquals(SwitchResult.Switched, svc.verify(p.id, "1234")) // old PIN still works
        assertTrue(svc.setPin(p.id, "5555"))
        assertEquals(SwitchResult.Switched, svc.verify(p.id, "5555"))
    }

    @Test fun differentProfilesGetDifferentSalts() {
        val s = store(); val a = withProfile(s)
        val b = s.upsertFromLogin("http://h:1", LoginResult("a", "r", "sam", "u2"))
        val svc = service(s)
        svc.setPin(a.id, "1234"); svc.setPin(b.id, "1234")
        assertNotEquals(s.get(a.id)!!.pinHash, s.get(b.id)!!.pinHash)
    }
}
