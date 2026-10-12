package com.teshlor.abstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileSessionTest {
    private fun p(
        id: String, name: String = "evan", access: String? = "a", pin: Boolean = false, color: Int = 0,
        used: Long = 0, userId: String? = "u$id",
    ) = Profile(id, "http://h:1", userId, name, access, "r", used, color, if (pin) "h" else null, if (pin) "s" else null)

    // --- startDestination ---
    @Test fun startDestinationRules() {
        assertEquals(Screen.Login, startDestination(emptyList()))
        assertEquals(Screen.Browse(Tab.HOME), startDestination(listOf(p("1")))) // one profile behaves exactly as before
        assertEquals(Screen.Login, startDestination(listOf(p("1", access = null))))
        assertEquals(Screen.Profiles, startDestination(listOf(p("1"), p("2"))))
        assertEquals(Screen.Profiles, startDestination(listOf(p("1", access = null), p("2"))))
        assertEquals(Screen.Profiles, startDestination(listOf(p("1", pin = true))))
    }

    @Test fun resolveActivePrefersStoredThenMostRecent() {
        val l = listOf(p("1", used = 5), p("2", used = 9))
        assertEquals("2", resolveActive(l, null)!!.id)
        assertEquals("1", resolveActive(l, "1")!!.id)
        assertEquals("2", resolveActive(l, "gone")!!.id)
        assertNull(resolveActive(emptyList(), null))
    }

    // --- picker on foreground ---
    private val two = listOf(p("1"), p("2"))
    private val home = Screen.Browse(Tab.HOME)
    private val hour = PICKER_AFTER_BACKGROUND_MS

    @Test fun pickerAfterAnHourAway() {
        assertTrue(shouldShowPickerOnForeground(two, 0, hour, false, home))
        assertFalse(shouldShowPickerOnForeground(two, 0, hour - 1, false, home))
        assertTrue(shouldShowPickerOnForeground(two, 10, 10 + 5 * hour, false, Screen.Player))
    }

    @Test fun noPickerWhenColdStartPlayingSingleOrAlreadyThere() {
        assertFalse(shouldShowPickerOnForeground(two, null, 10 * hour, false, home)) // never backgrounded
        assertFalse(shouldShowPickerOnForeground(two, 0, 10 * hour, true, home)) // playing
        assertFalse(shouldShowPickerOnForeground(listOf(p("1")), 0, 10 * hour, false, home))
        assertFalse(shouldShowPickerOnForeground(emptyList(), 0, 10 * hour, false, Screen.Login))
        assertFalse(shouldShowPickerOnForeground(two, 0, 10 * hour, false, Screen.Profiles))
        assertFalse(shouldShowPickerOnForeground(two, 0, 10 * hour, false, Screen.Login))
        assertTrue(shouldShowPickerOnForeground(listOf(p("1", pin = true)), 0, hour, false, home)) // a lone PIN profile still gates
    }

    @Test fun loginPushedOverAnOpenProfileStillGetsThePicker() {
        // Login at the stack root has nothing behind it; pushed over a profile, Back would pop into it without a PIN.
        assertFalse(shouldShowPickerOnForeground(two, 0, 10 * hour, false, Screen.Login, stackSize = 1))
        assertTrue(shouldShowPickerOnForeground(two, 0, 10 * hour, false, Screen.Login, stackSize = 2))
        assertTrue(shouldShowPickerOnForeground(listOf(p("1", pin = true)), 0, hour, false, Screen.Login, stackSize = 2))
    }

    @Test fun searchBindKeyIncludesTheProfile() {
        val a = Any()
        assertEquals(searchBindKey(a, "lib", 1, "p1"), searchBindKey(a, "lib", 1, "p1"))
        assertFalse(searchBindKey(a, "lib", 1, "p1") == searchBindKey(a, "lib", 1, "p2"))
    }

    @Test fun reauthOfNamelessProfileMergesIntoAnExistingMatchingAccount() {
        val evan = p("E", name = "evan", userId = "u1")
        val nameless = p("M", name = "", access = null, userId = null)
        val s = PrefsProfileStore(MapKeyValue(linkedMapOf("profiles" to encodeProfiles(listOf(evan, nameless)))))
        val r = s.upsertFromLogin("http://h:1", LoginResult("a2", "r2", "evan", "u1"), reauthId = "M")
        assertEquals("E", r.id) // merged into the existing account, not the nameless one
        assertTrue(s.get("M")!!.needsSignIn)
        assertEquals("", s.get("M")!!.username)
    }

    // --- login prefill ---
    @Test fun loginPrefill() {
        val all = listOf(p("1", name = ""), p("2", name = "sam"))
        assertEquals(LoginPrefill(LoginMode.FIRST, server = "http://last:1"), loginPrefillFor(LoginMode.FIRST, emptyList(), null, "http://last:1"))
        assertEquals("http://h:1", loginPrefillFor(LoginMode.ADD, all, "2", "http://last:1").server)
        assertEquals("http://last:1", loginPrefillFor(LoginMode.ADD, all, null, "http://last:1").server)
        val re = loginPrefillFor(LoginMode.REAUTH, all, "1", "x", "2")
        assertEquals(LoginMode.REAUTH, re.mode); assertEquals("sam", re.username); assertEquals("2", re.profileId); assertEquals("Sam", re.displayName)
        assertEquals("Profile 1", loginPrefillFor(LoginMode.REAUTH, all, null, "x", "1").displayName)
        assertEquals(LoginMode.FIRST, loginPrefillFor(LoginMode.REAUTH, all, null, "x", "gone").mode)
    }

    // --- summaries / names / colours ---
    @Test fun displayNames() {
        assertEquals("Evan", profileDisplayName("evan", 0))
        assertEquals("McKay", profileDisplayName("McKay", 0))
        assertEquals("Profile 3", profileDisplayName("", 2))
        assertEquals("192.168.1.10:13378", hostLabel("http://192.168.1.10:13378/"))
    }

    @Test fun summariesCarryFlagsButNoSecrets() {
        val s = profileSummaries(listOf(p("1", name = ""), p("2", access = null, pin = true, color = 3)), "1", "2")
        assertEquals("Profile 1", s[0].displayName); assertFalse(s[0].nameKnown); assertTrue(s[0].isCurrent); assertFalse(s[0].isLastUsed)
        assertTrue(s[1].needsSignIn); assertTrue(s[1].hasPin); assertEquals(3, s[1].colorIndex); assertTrue(s[1].isLastUsed)
        assertEquals("h:1", s[0].host)
    }

    @Test fun colourIsFirstUnusedElseCountMod8() {
        assertEquals(0, pickColorIndex(emptyList(), 0))
        assertEquals(1, pickColorIndex(listOf(0, 2), 2))
        assertEquals(0, pickColorIndex(listOf(1, 2), 2)) // a freed colour is reused
        assertEquals(10 % 8, pickColorIndex((0..7).toList(), 10))
    }

    @Test fun loginsGetDistinctColoursAndAFreedOneIsReused() {
        var n = 0
        val s = PrefsProfileStore(MapKeyValue(), newId = { "P${++n}" })
        val a = s.upsertFromLogin("http://h:1", LoginResult("a", "r", "a", "1"))
        val b = s.upsertFromLogin("http://h:1", LoginResult("a", "r", "b", "2"))
        assertEquals(0, a.colorIndex); assertEquals(1, b.colorIndex)
        s.remove(a.id)
        assertEquals(0, s.upsertFromLogin("http://h:1", LoginResult("a", "r", "c", "3")).colorIndex)
        assertEquals(1, s.upsertFromLogin("http://h:1", LoginResult("a2", "r", "b", "2")).colorIndex) // relogin keeps colour
    }

    @Test fun unassignedColoursAreFilledOnLoadWithoutDuplicates() {
        val kv = MapKeyValue(linkedMapOf("profiles" to encodeProfiles(listOf(p("1", color = -1), p("2", color = 0), p("3", color = -1)))))
        val s = PrefsProfileStore(kv)
        assertEquals(listOf(1, 0, 2), s.all().map { it.colorIndex })
    }

    @Test fun migratedProfileGetsColourZero() {
        val kv = MapKeyValue(linkedMapOf("server" to "h:1", "access" to "a", "refresh" to "r"))
        migrateLegacy(kv)
        val s = PrefsProfileStore(kv)
        assertEquals(0, s.all().single().colorIndex)
        assertEquals("Profile 1", profileSummaries(s.all(), null, null).single().displayName)
    }

    // --- identity rules ---
    @Test fun migratedProfileWithoutNameIsReauthedByIdNotDuplicated() {
        val kv = MapKeyValue(linkedMapOf("profiles" to encodeProfiles(listOf(p("M", name = "", access = null, userId = null)))))
        val s = PrefsProfileStore(kv)
        val r = s.upsertFromLogin("http://h:1", LoginResult("a2", "r2", "evan", "u9"), reauthId = "M")
        assertEquals("M", r.id); assertEquals("u9", r.userId); assertEquals("evan", r.username)
        assertEquals(1, s.all().size)
    }

    @Test fun emptyUserIdIsTreatedAsAbsent() {
        val s = PrefsProfileStore(MapKeyValue(linkedMapOf("profiles" to encodeProfiles(listOf(p("1", name = "evan", userId = ""))))))
        val r = s.upsertFromLogin("http://h:1", LoginResult("a", "r", "Evan", "u1"))
        assertEquals("1", r.id) // matched by name, not treated as a different user id
        assertEquals("u1", r.userId)
        val fresh = PrefsProfileStore(MapKeyValue()).upsertFromLogin("http://h:1", LoginResult("a", "r", "x", ""))
        assertNull(fresh.userId)
    }

    @Test fun clearTokensIfOnlyClearsTheSamePair() {
        val s = PrefsProfileStore(MapKeyValue(linkedMapOf("profiles" to encodeProfiles(listOf(p("1"))))))
        assertFalse(s.clearTokensIf("1", "a-old", "r"))
        assertFalse(s.clearTokensIf("1", "a", "r-new"))
        assertEquals("a", s.get("1")!!.accessToken)
        assertTrue(s.clearTokensIf("1", "a", "r"))
        assertTrue(s.get("1")!!.needsSignIn)
        assertFalse(s.clearTokensIf("nope", "a", "r"))
    }

    @Test fun profileTokenStoreExpiryCannotWipeAFreshLogin() {
        val s = PrefsProfileStore(MapKeyValue(linkedMapOf("profiles" to encodeProfiles(listOf(p("1"))))))
        val t = ProfileTokenStore(s, "1")
        s.upsertFromLogin("http://h:1", LoginResult("fresh", "r2", "evan", "u1"))
        assertFalse(t.clearTokensIf("a", "r"))
        assertEquals("fresh", t.accessToken)
    }

    @Test fun corruptProfilesAreKeptNotOverwritten() {
        val kv = MapKeyValue(linkedMapOf("profiles" to "{not json"))
        val s = PrefsProfileStore(kv)
        assertTrue(s.all().isEmpty())
        assertEquals("{not json", kv.getString("profiles_corrupt"))
    }
}
