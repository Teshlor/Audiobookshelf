package com.teshlor.abstv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Library tab state: sort, filter, the paged book list and the progress map. Lives in AppViewModel (one per
 * library; dropped on refresh, library switch and logout) so scroll position and loaded pages survive tab switches.
 *
 * Main-thread only, like [Pager]. [loader] and [fetchProgress] are injected so tests need no server.
 */
class LibraryTabState(
    private val scope: CoroutineScope,
    initialSort: SortSpec,
    private val saveSort: (SortSpec) -> Unit,
    private val loader: suspend (page: Int, limit: Int, sort: SortSpec, filter: LibraryFilter) -> Page<Book>,
    private val fetchProgress: suspend () -> ProgressMap,
) {
    var sort by mutableStateOf(initialSort); private set
    var filter by mutableStateOf(LibraryFilter.ALL); private set

    /** Empty until /api/me answers; a failure keeps the previous map (cards just show no progress). */
    var progress by mutableStateOf(ProgressMap()); private set

    /** Bumped on every reset so an in-flight [indexForLetter] gives up when the list changes under it. */
    private var version = 0

    val pager = Pager(scope, pageSize = 60) { page, limit -> loader(page, limit, sort, filter) }

    init {
        pager.reset()
    }

    /** Switches sort (using that sort's natural direction) and reloads from page 0. False if unchanged. */
    fun setSort(next: LibrarySort): Boolean {
        if (next == sort.sort) return false
        applySort(SortSpec(next, next.defaultDesc))
        return true
    }

    fun toggleDirection() = applySort(sort.flipped())

    private fun applySort(spec: SortSpec) {
        sort = spec
        saveSort(spec)
        reload()
    }

    /** False if unchanged. */
    fun setFilter(next: LibraryFilter): Boolean {
        if (next == filter) return false
        filter = next
        reload()
        return true
    }

    /** Page 0 again with the current sort and filter. */
    fun reload() {
        version++
        pager.reset()
    }

    fun refreshProgress() {
        scope.launch {
            try {
                progress = fetchProgress()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep what we have: progress is decoration, never an error screen.
            }
        }
    }

    /**
     * Loads pages as needed and returns the index of the first book of [letter] (see [firstIndexForLetter]),
     * or null if the list changed meanwhile, a page failed, or the list is empty.
     */
    suspend fun indexForLetter(letter: Char, settings: SortingSettings = SortingSettings.OFF): Int? {
        val v = version
        while (true) {
            val s = pager.state.value
            firstIndexForLetter(s.items, letter, sort, s.endReached, settings)?.let { return it }
            if (v != version || s.error != null || s.endReached) return null
            pager.loadMore()
            // A page is in flight (ours or the grid's): wait for the list to move on.
            pager.state.first { it.items.size != s.items.size || it.endReached || it.error != null || v != version }
        }
    }
}
