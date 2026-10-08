package com.teshlor.abstv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
                fetchProgress() // the shared map (AppViewModel.progress) is updated by the callback itself
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep what we have: progress is decoration, never an error screen.
            }
        }
    }

    /**
     * Loads pages as needed (up to [JUMP_PARALLEL] at once, appended in order) and returns the index of the first book
     * of [letter] (see [firstIndexForLetter]), or null if the list changed meanwhile, a page failed, or the list is
     * empty.
     *
     * Why not binary-search the server order with limit=1 probes: the grid needs every item up to the target loaded
     * (the list is contiguous), so those pages must be fetched anyway; probing only adds serial round trips.
     * Parallel paging costs ceil(target / 60) requests in about a quarter of the round trips.
     */
    suspend fun indexForLetter(letter: Char, settings: SortingSettings = SortingSettings.OFF): Int? {
        val v = version
        val spec = sort
        pager.loadUntil(JUMP_PARALLEL) { s -> firstIndexForLetter(s.items, letter, spec, s.endReached, settings) != null }
        if (v != version) return null
        val s = pager.state.value
        return firstIndexForLetter(s.items, letter, spec, s.endReached, settings)
    }

    private companion object { const val JUMP_PARALLEL = 4 }
}
