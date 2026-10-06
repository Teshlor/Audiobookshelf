package com.teshlor.abstv

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * Loads a server-paged list one page at a time (0-based pages, as the ABS API uses).
 * Call [onVisible] with the last visible grid index; it prefetches when within [prefetchDistance] of the end.
 * [loadMore] is a no-op while a page is in flight, after the end, and while an error is pending
 * (call [retry] to try again), so fast D-pad scrolling can't fire duplicate requests.
 */
class Pager<T>(
    private val scope: CoroutineScope,
    val pageSize: Int,
    private val prefetchDistance: Int = 15,
    private val loader: suspend (page: Int, limit: Int) -> Page<T>,
) {
    private val _state = MutableStateFlow(PagerState<T>())
    val state: StateFlow<PagerState<T>> = _state.asStateFlow()

    private var nextPage = 0
    private var job: Job? = null
    private var generation = 0

    fun onVisible(lastVisibleIndex: Int) {
        val s = _state.value
        if (lastVisibleIndex >= s.items.size - prefetchDistance) loadMore()
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.endReached || s.error != null) return
        _state.update { it.copy(loading = true) }
        val gen = generation
        val page = nextPage
        job = scope.launch {
            try {
                val result = loader(page, pageSize)
                if (gen != generation) return@launch
                nextPage = page + 1
                _state.update { cur ->
                    val items = cur.items + result.results
                    cur.copy(
                        items = items,
                        total = result.total,
                        loading = false,
                        error = null,
                        loadedOnce = true,
                        endReached = result.results.isEmpty() || items.size >= result.total,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen != generation) return@launch
                _state.update { it.copy(loading = false, error = e.message ?: e.toString(), loadedOnce = true) }
            }
        }
    }

    /** Clears the error and retries the failed page. */
    fun retry() {
        _state.update { it.copy(error = null) }
        loadMore()
    }

    /** Drops everything (sort/filter/library change) and loads page 0 again. */
    fun reset() {
        generation++
        job?.cancel()
        nextPage = 0
        _state.value = PagerState()
        loadMore()
    }
}
