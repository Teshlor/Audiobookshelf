package com.teshlor.abstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavStackTest {
    @Test fun startsOnInitialScreen() {
        val n = NavStack(Screen.Login)
        assertEquals(Screen.Login, n.current)
        assertNull(n.tab)
        assertFalse(n.pop())
    }

    @Test fun selectTabResetsStack() {
        val n = NavStack(Screen.Browse(Tab.HOME))
        n.push(Screen.Detail)
        n.selectTab(Tab.SETTINGS)
        assertEquals(Screen.Browse(Tab.SETTINGS), n.current)
        assertEquals(1, n.size)
        assertEquals(Tab.SETTINGS, n.tab)
        assertFalse(n.pop())
    }

    @Test fun detailAndPlayerPopBackToBrowse() {
        val n = NavStack(Screen.Browse(Tab.HOME))
        n.push(Screen.Detail)
        n.push(Screen.Player)
        assertEquals(Tab.HOME, n.tab) // the rail still shows Home while Detail/Player are on top
        assertTrue(n.pop())
        assertEquals(Screen.Detail, n.current)
        assertTrue(n.pop())
        assertEquals(Screen.Browse(Tab.HOME), n.current)
        assertFalse(n.pop())
    }

    @Test fun resetToLoginClearsEverything() {
        val n = NavStack(Screen.Browse(Tab.HOME))
        n.push(Screen.Detail)
        n.reset(Screen.Login)
        assertEquals(Screen.Login, n.current)
        assertEquals(1, n.size)
        assertNull(n.tab)
    }

    @Test fun keysAreStablePerScreen() {
        assertEquals("tab:HOME", Screen.Browse(Tab.HOME).key)
        assertEquals("tab:SETTINGS", Screen.Browse(Tab.SETTINGS).key)
        assertEquals("detail", Screen.Detail.key)
        assertEquals("player", Screen.Player.key)
        assertEquals("login", Screen.Login.key)
    }

    @Test fun everyTabIsEnabled() {
        assertEquals(Tab.entries.toSet(), Tab.entries.filter { it.enabled }.toSet())
    }

    @Test fun seriesBooksKeyAndRemovalCallbacks() {
        assertEquals("series:s1", Screen.SeriesBooks("s1", "Saga").key)
        val n = NavStack(Screen.Browse(Tab.SERIES))
        val removed = mutableListOf<Screen>()
        n.onRemoved = { removed += it }
        val sb = Screen.SeriesBooks("s1", "Saga")
        n.push(sb)
        n.push(Screen.Detail)
        assertEquals(Tab.SERIES, n.tab)
        n.pop()
        assertTrue(removed == listOf<Screen>(Screen.Detail))
        n.selectTab(Tab.HOME) // resets, which removes the series detail too
        assertTrue(sb in removed)
    }
}
