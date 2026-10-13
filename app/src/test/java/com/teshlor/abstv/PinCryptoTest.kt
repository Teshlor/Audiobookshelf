package com.teshlor.abstv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class PinCryptoTest {
    private fun pb(p: String, s: String, c: Int, len: Int) =
        PinCrypto.toHex(PinCrypto.pbkdf2Sha256(p.toByteArray(), s.toByteArray(), c, len))

    // RFC 7914 section 11
    @Test fun rfc7914Vector1() = assertEquals(
        "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
        pb("passwd", "salt", 1, 64),
    )

    @Test fun rfc7914Vector2() = assertEquals(
        "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d",
        pb("Password", "NaCl", 80000, 64),
    )

    @Test fun matchesTheJvmImplementation() {
        val salt = PinCrypto.newSalt()
        for (pin in listOf("0000", "1234", "9876")) {
            val jvm = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(pin.toCharArray(), salt, 5_000, 256)).encoded
            assertArrayEquals(jvm, PinCrypto.hash(pin, salt, 5_000))
        }
    }

    @Test fun matchesComparesInConstantTimeHex() {
        val salt = PinCrypto.newSalt()
        val h = PinCrypto.toHex(PinCrypto.hash("1234", salt, 1_000))
        assertTrue(PinCrypto.matches("1234", PinCrypto.toHex(salt), h, 1_000))
        assertFalse(PinCrypto.matches("1235", PinCrypto.toHex(salt), h, 1_000))
    }

    // --- forgot-PIN identity ---
    private fun p(id: String, name: String, uid: String?, server: String = "http://h:1") =
        Profile(id, server, uid, name, "a", "r")

    @Test fun forgotLoginMustBeTheTargetsOwnAccount() {
        val evan = p("E", "evan", "u1"); val sam = p("S", "sam", "u2")
        val all = listOf(evan, sam)
        assertTrue(forgotLoginMatches(evan, all, "h:1", LoginResult("a", "r", "evan", "u1")))
        assertFalse(forgotLoginMatches(evan, all, "h:1", LoginResult("a", "r", "sam", "u2"))) // another saved account
        assertFalse(forgotLoginMatches(evan, all, "h:1", LoginResult("a", "r", "zed", "u9"))) // some third account
    }

    @Test fun identityLessTargetAcceptsOnlyAnAccountNotSavedElsewhere() {
        val nameless = p("M", "", null); val sam = p("S", "sam", "u2")
        val all = listOf(nameless, sam)
        assertTrue(forgotLoginMatches(nameless, all, "h:1", LoginResult("a", "r", "evan", "u1")))
        assertFalse(forgotLoginMatches(nameless, all, "h:1", LoginResult("a", "r", "sam", "u2")))
    }

    // --- tries are counted before hashing ---
    @Test fun aTryIsOnTheDiskWhileTheHashIsStillRunning() {
        var n = 0
        val kv = MapKeyValue()
        val s = PrefsProfileStore(kv, newId = { "P${++n}" })
        val prof = s.upsertFromLogin("http://h:1", LoginResult("a", "r", "evan", "u1"))
        val svc = PinService(s, iterations = 1_000)
        svc.setPin(prof.id, "1234")
        assertEquals(SwitchResult.WrongPin(4), svc.verify(prof.id, "0000"))
        assertEquals(1, PrefsProfileStore(kv).get(prof.id)!!.pinFailures)
        assertEquals(SwitchResult.Switched, svc.verify(prof.id, "1234"))
        assertEquals(0, PrefsProfileStore(kv).get(prof.id)!!.pinFailures) // rolled back on success
    }
}
