package com.teshlor.abstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileMigrationTest {
    private fun kv(vararg pairs: Pair<String, String>) = MapKeyValue(linkedMapOf(*pairs))
    private fun migrate(kv: MapKeyValue) = migrateLegacy(kv, clock = { 5L }, newId = { "P1" })
    private fun profiles(kv: MapKeyValue) = decodeProfiles(kv.getString("profiles"))

    @Test fun accessAndRefreshInstallBecomesProfileWithScopedSettings() {
        val kv = kv(
            "server" to "192.168.1.5:13378/", "access" to "A", "refresh" to "R", "username" to "evan",
            "library_id" to "lib1", "library_sort" to "ADDED:desc", "recent_searches" to "dune", "theme_override" to "dark",
        )
        migrate(kv)
        val p = profiles(kv).single()
        assertEquals("P1", p.id)
        assertEquals("http://192.168.1.5:13378", p.server)
        assertEquals("evan", p.username)
        assertEquals("A", p.accessToken)
        assertEquals("R", p.refreshToken)
        assertNull(p.userId)
        assertEquals("P1", kv.getString("active_profile"))
        assertEquals("http://192.168.1.5:13378", kv.getString("last_server"))
        assertEquals("lib1", kv.getString("library_id@P1"))
        assertEquals("ADDED:desc", kv.getString("library_sort@P1"))
        assertEquals("dune", kv.getString("recent_searches@P1"))
        for (k in listOf("server", "access", "refresh", "token", "username", "library_id", "library_sort", "recent_searches")) {
            assertNull(k, kv.getString(k))
        }
        assertEquals("dark", kv.getString("theme_override")) // device-wide, untouched
    }

    @Test fun legacyTokenOnlyInstallHasNoRefreshToken() {
        val kv = kv("server" to "http://h:1", "token" to "LEGACY")
        migrate(kv)
        val p = profiles(kv).single()
        assertEquals("LEGACY", p.accessToken)
        assertNull(p.refreshToken)
        assertEquals("", p.username)
        assertNull(kv.getString("token"))
    }

    @Test fun serverWithoutTokenOnlyKeepsLastServer() {
        val kv = kv("server" to "http://h:1", "username" to "evan")
        migrate(kv)
        assertTrue(profiles(kv).isEmpty())
        assertNull(kv.getString("active_profile"))
        assertEquals("http://h:1", kv.getString("last_server"))
        assertNull(kv.getString("server"))
    }

    @Test fun secondRunIsNoOp() {
        val kv = kv("server" to "http://h:1", "access" to "A", "refresh" to "R")
        migrate(kv)
        val snapshot = kv.map.toMap()
        migrateLegacy(kv, newId = { "P2" })
        assertEquals(snapshot, kv.map.toMap())
    }

    @Test fun freshInstallWritesEmptyListSoItNeverRunsAgain() {
        val kv = kv()
        migrate(kv)
        assertEquals("[]", kv.getString("profiles"))
    }

    @Test fun crashAfterCommitBeforeCleanupIsNotMigratedTwice() {
        val kv = kv("server" to "http://h:1", "access" to "A", "refresh" to "R")
        migrate(kv)
        // Simulate the old keys surviving the crash.
        kv.map["server"] = "http://h:1"; kv.map["access"] = "A"; kv.map["refresh"] = "R"
        migrateLegacy(kv, newId = { "P2" })
        val list = profiles(kv)
        assertEquals(1, list.size)
        assertEquals("P1", list.single().id)
        assertFalse(kv.map.containsKey("access"))
    }
}
