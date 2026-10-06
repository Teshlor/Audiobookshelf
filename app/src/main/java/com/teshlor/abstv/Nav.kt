package com.teshlor.abstv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Rail destinations. [enabled] hides a tab (and its rail item) until its screen exists; flip it on when the
 * tab is built. SETTINGS lives in the rail's bottom group, the rest in the nav group.
 */
enum class Tab(val label: String, val enabled: Boolean) {
    SEARCH("Search", true),
    HOME("Home", true),
    LIBRARY("Library", false),
    SERIES("Series", false),
    COLLECTIONS("Collections", false),
    SETTINGS("Settings", true),
}

sealed interface Screen {
    /** Key for saved state and the last-focused-item map. */
    val key: String

    data object Login : Screen { override val key = "login" }
    data class Browse(val tab: Tab) : Screen { override val key get() = "tab:${tab.name}" }
    data object Detail : Screen { override val key = "detail" }
    data object Player : Screen { override val key = "player" }
}

/** Back stack. Switching tabs resets it to that tab's root, so a tab never has stale screens above it. */
class NavStack(initial: Screen = Screen.Login) {
    private val items = mutableListOf(initial)

    var current: Screen by mutableStateOf(initial)
        private set

    val size: Int get() = items.size

    /** The tab of the nearest Browse screen in the stack (what the rail highlights), or null on Login. */
    val tab: Tab? get() = items.lastOrNull { it is Screen.Browse }?.let { (it as Screen.Browse).tab }

    fun push(screen: Screen) {
        items.add(screen)
        current = screen
    }

    /** Pops the top screen. Returns false (and does nothing) when it is the last one. */
    fun pop(): Boolean {
        if (items.size <= 1) return false
        items.removeAt(items.lastIndex)
        current = items.last()
        return true
    }

    fun selectTab(tab: Tab) = reset(Screen.Browse(tab))

    fun reset(screen: Screen) {
        items.clear()
        items.add(screen)
        current = screen
    }
}
