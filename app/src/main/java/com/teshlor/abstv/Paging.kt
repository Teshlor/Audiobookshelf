package com.teshlor.abstv

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable data class PagerState<T>(
    val items: List<T> = emptyList(),
    val total: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
    /** True once every item up to the server's `total` is loaded (or the server returned an empty page). */
    val endReached: Boolean = false,
    /** False until the first page has completed (success or failure). */
    val loadedOnce: Boolean = false,
)

/**
 * Loads a server-paged list (one page at a time for scrolling; several in parallel for [loadUntil]) (0-based pages, as the ABS API uses).
 * Call [onVisible] with the last visible grid index; it prefetches when within [prefetchDistance] of the end.
 * [loadMore] is a no-op while a page is in flight, after the end, and while an error is pending
 * (call [retry] to try again), so fast D-pad scrolling can't fire duplicate requests.
 *
 * Main-thread only: [scope] must be a Main-dispatched scope (e.g. viewModelScope) and every method must be called
 * from Main. The counters are not synchronised.
 */
class Pager<T>(
    private val scope: CoroutineScope,
    val pageSize: Int,
    private val prefetchDistance: Int = 15,
    private val loader: suspend (page: Int, limit: Int) -> Page<T>,
) {
    private val _state = MutableStateFlow(PagerState<T>())
    val state: StateFlow<PagerState<T>> = _state.asStateFlow()

    /** Next page to append to [PagerState.items]; pages are always appended in order, so the list stays contiguous. */
    private var nextPage = 0
    private var generation = 0
    private val inFlight = HashMap<Int, Job>()
    /** Pages that arrived ahead of [nextPage] (parallel loading); appended as soon as the gap closes. */
    private val buffer = HashMap<Int, Page<T>>()
    /** Pages whose request failed (page -> message). The surfaced error is the lowest one; it clears only when [retry] refetches them. */
    private val failed = java.util.TreeMap<Int, String>()

    fun onVisible(lastVisibleIndex: Int) {
        val s = _state.value
        if (lastVisibleIndex >= s.items.size - prefetchDistance) loadMore()
    }

    fun loadMore() {
        val s = _state.value
        if (s.endReached || s.error != null) return
        fetch(nextPage) // deduped: a no-op while that page is in flight
    }

    /** Starts [page] unless it is already in flight or buffered, so no page is ever requested twice. */
    private fun fetch(page: Int) {
        if (page in inFlight || page in buffer) return
        val gen = generation
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = loader(page, pageSize)
                if (gen != generation) return@launch
                inFlight.remove(page)
                buffer[page] = result
                drain()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                inFlight.remove(page)
                failed[page] = e.message ?: e.toString()
                _state.update {
                    it.copy(loading = inFlight.isNotEmpty(), error = failed.firstEntry().value, loadedOnce = true)
                }
            }
        }
        inFlight[page] = job
        _state.update { it.copy(loading = true) }
        job.start()
    }

    /** Appends every buffered page that is now next in line. */
    private fun drain() {
        var appended = false
        while (!_state.value.endReached) {
            val result = buffer.remove(nextPage) ?: break
            nextPage++
            appended = true
            _state.update { cur ->
                val items = cur.items + result.results
                cur.copy(
                    items = items,
                    total = result.total,
                    loading = inFlight.isNotEmpty(),
                    loadedOnce = true,
                    endReached = result.results.isEmpty() || items.size >= result.total,
                )
            }
        }
        if (_state.value.endReached) { buffer.clear(); failed.tailMap(nextPage).clear() }
        if (_state.value.endReached && failed.isEmpty()) _state.update { it.copy(error = null) }
        if (!appended) _state.update { it.copy(loading = inFlight.isNotEmpty()) }
    }

    /**
     * Loads ahead with up to [maxParallel] pages in flight (appended in order) until [done] holds for the list, the
     * end is reached, a page fails, or the list is reset. Returns true if [done] held. Pages already in flight (e.g.
     * the grid's own prefetch) are reused, never requested again. Pages still in flight when [done] becomes true keep
     * loading and simply extend the list.
     */
    suspend fun loadUntil(maxParallel: Int = 4, done: (PagerState<T>) -> Boolean): Boolean {
        val gen = generation
        while (true) {
            if (gen != generation) return false
            val s = _state.value
            if (done(s)) return true
            // Only a failed page that blocks the append point ends the jump; one further on may never matter.
            if (s.endReached || nextPage in failed) return false
            // Before the first page we don't know the total, so fetch only page 0.
            val lastPage = if (s.loadedOnce && s.total > 0) (s.total - 1) / pageSize else nextPage
            val windowEnd = minOf(lastPage, nextPage + maxParallel - 1)
            for (p in nextPage..windowEnd) if (p !in failed) fetch(p)
            // Short pages (server caps the limit) can leave the window empty: always keep nextPage moving.
            if (inFlight.isEmpty() && nextPage !in buffer) fetch(nextPage)
            // Wait for the list (or error) to move on; a reset also changes it (back to empty, then loading).
            _state.first { it != s || gen != generation }
        }
    }

    /** Clears the error and retries the failed page. */
    fun retry() {
        val pages = failed.keys.toList()
        failed.clear()
        _state.update { it.copy(error = null) }
        if (pages.isEmpty()) loadMore() else pages.forEach { fetch(it) }
    }

    /** Drops everything (sort/filter/library change) and loads page 0 again. */
    fun reset() {
        generation++
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
        buffer.clear()
        failed.clear()
        nextPage = 0
        _state.value = PagerState()
        loadMore()
    }
}
