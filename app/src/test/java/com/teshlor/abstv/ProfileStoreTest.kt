package com.teshlor.abstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileStoreTest {
    private var n = 0
    private val kv = MapKeyValue()
    private fun store(kv: MapKeyValue = this.kv) = PrefsProfileStore(kv, clock = { 100L }, newId = { "P${++n}" })
    private fun login(name: String = "evan", id: String? = "u1", access: String = "a", refresh: String? = "r") =
        LoginResult(access, refresh, name, id)

    @Test fun sameServerAndUserIdUpdatesInPlace() {
        val s = store()
        val a = s.upsertFromLogin("http://h:1", login())
        kv.edit { it.putString("library_id@${a.id}", "lib") }
        val b = s.upsertFromLogin("http://h:1", login(name = "Evan2", access = "a2", refresh = "r2"))
        assertEquals(a.id, b.id)
        assertEquals(1, s.all().size)
        assertEquals("a2", s.get(a.id)!!.accessToken)
        assertEquals("Evan2", s.get(a.id)!!.username)
        assertEquals("lib", kv.getString("library_id@${a.id}"))
    }

    @Test fun sameUsernameOnDifferentServerIsANewProfile() {
        val s = store()
        val a = s.upsertFromLogin("http://h:1", login(id = null))
        val b = s.upsertFromLogin("http://other:1", login(id = null))
        assertTrue(a.id != b.id)
        assertEquals(2, s.all().size)
    }

    @Test fun differentUserIdsOnSameServerAreDifferentEvenWithSameName() {
        val s = store()
        s.upsertFromLogin("http://h:1", login(id = "u1"))
        s.upsertFromLogin("http://h:1", login(id = "u2"))
        assertEquals(2, s.all().size)
    }

    @Test fun serverNormalizationMatches() {
        val s = store()
        val a = s.upsertFromLogin("h:1/", login())
        val b = s.upsertFromLogin("http://h:1", login())
        assertEquals(a.id, b.id)
        assertEquals("http://h:1", b.server)
    }

    @Test fun userIdNullProfileMatchesByUsernameAndGetsUserId() {
        val seeded = store(MapKeyValue(linkedMapOf("profiles" to encodeProfiles(listOf(Profile("M", "http://h:1", null, "Evan", "a", "r"))))))
        val b = seeded.upsertFromLogin("http://h:1", login(name = "evan", id = "u9"))
        assertEquals("M", b.id)
        assertEquals("u9", seeded.get("M")!!.userId)
        assertEquals(1, seeded.all().size)
    }

    @Test fun clearTokensKeepsProfileNeedingSignIn() {
        val s = store()
        val a = s.upsertFromLogin("http://h:1", login())
        assertFalse(a.needsSignIn)
        s.clearTokens(a.id)
        val after = s.get(a.id)!!
        assertTrue(after.needsSignIn)
        assertNull(after.refreshToken)
        assertEquals("evan", after.username)
        // and a re-login clears it
        assertFalse(s.upsertFromLogin("http://h:1", login()).needsSignIn)
    }

    @Test fun removeDeletesScopedKeysAndActive() {
        val s = store()
        val a = s.upsertFromLogin("http://h:1", login())
        val b = s.upsertFromLogin("http://h:1", login(name = "kid", id = "u2"))
        s.setActive(a.id)
        kv.edit { e ->
            for (k in ProfileKeys.SCOPED) { e.putString("$k@${a.id}", "x"); e.putString("$k@${b.id}", "y") }
        }
        s.remove(a.id)
        assertNull(s.get(a.id))
        assertNull(s.activeId)
        for (k in ProfileKeys.SCOPED) {
            assertNull(kv.getString("$k@${a.id}"))
            assertEquals("y", kv.getString("$k@${b.id}"))
        }
    }

    @Test fun persistsAndReloads() {
        val s = store()
        val a = s.upsertFromLogin("http://h:1", login())
        s.setActive(a.id)
        val reloaded = PrefsProfileStore(kv)
        assertEquals(listOf(s.get(a.id)), reloaded.all())
        assertEquals(a.id, reloaded.activeId)
    }
}
