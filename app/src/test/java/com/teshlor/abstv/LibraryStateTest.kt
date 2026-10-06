package com.teshlor.abstv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryStateTest {
    private data class Call(val page: Int, val sort: SortSpec, val filter: LibraryFilter)

    private val calls = mutableListOf<Call>()
    private val saved = mutableListOf<SortSpec>()

    /** 130 books "Book 000".. in title order, 60 per page. */
    private fun TestScope.state(
        initial: SortSpec = SortSpec(),
        total: Int = 130,
        progress: suspend () -> ProgressMap = { ProgressMap() },
        gate: CompletableDeferred<Unit>? = null, // pages after the first wait for this
    ) = LibraryTabState(
        scope = this,
        initialSort = initial,
        saveSort = { saved += it },
        loader = { page, limit, sort, filter ->
            calls += Call(page, sort, filter)
            if (page >= 1) gate?.await()
            val from = page * limit
            val results = (from until minOf(from + limit, total)).map { i ->
                Book("id$i", Media(Metadata(title = "Book %03d".format(i))))
            }
            Page(results, total, limit, page)
        },
        fetchProgress = progress,
    )

    @Test fun loadsFirstPageOnCreationWithPersistedSort() = runTest(StandardTestDispatcher()) {
        val s = state(SortSpec(LibrarySort.ADDED, true))
        advanceUntilIdle()
        assertEquals(listOf(Call(0, SortSpec(LibrarySort.ADDED, true), LibraryFilter.ALL)), calls)
        assertEquals(60, s.pager.state.value.items.size)
        assertEquals(130, s.pager.state.value.total)
    }

    @Test fun sortChangeResetsToPageZeroSavesAndUsesDefaultDirection() = runTest(StandardTestDispatcher()) {
        val s = state()
        advanceUntilIdle()
        s.pager.loadMore(); advanceUntilIdle()
        assertEquals(120, s.pager.state.value.items.size)
        calls.clear()

        assertTrue(s.setSort(LibrarySort.DURATION))
        assertEquals(0, s.pager.state.value.items.size) // grid goes to placeholders at once
        advanceUntilIdle()
        assertEquals(listOf(Call(0, SortSpec(LibrarySort.DURATION, true), LibraryFilter.ALL)), calls)
        assertEquals(listOf(SortSpec(LibrarySort.DURATION, true)), saved)
        assertEquals(60, s.pager.state.value.items.size)

        assertFalse(s.setSort(LibrarySort.DURATION)) // same chip again: nothing happens
        s.toggleDirection(); advanceUntilIdle()
        assertEquals(SortSpec(LibrarySort.DURATION, false), calls.last().sort)
        assertEquals(SortSpec(LibrarySort.DURATION, false), saved.last())
    }

    @Test fun filterChangeReloadsWithFilter() = runTest(StandardTestDispatcher()) {
        val s = state()
        advanceUntilIdle()
        calls.clear()
        assertTrue(s.setFilter(LibraryFilter.IN_PROGRESS)); advanceUntilIdle()
        assertEquals(listOf(Call(0, SortSpec(), LibraryFilter.IN_PROGRESS)), calls)
        assertFalse(s.setFilter(LibraryFilter.IN_PROGRESS))
    }

    @Test fun jumpLoadsPagesUntilTheLetterIsReachable() = runTest(StandardTestDispatcher()) {
        val s = state()
        advanceUntilIdle()
        // Every title is "Book ...": B is satisfied by the first book, Z is past the end of all 130.
        val b = async { s.indexForLetter('B') }
        advanceUntilIdle()
        assertEquals(0, b.await())
        assertEquals(listOf(0), calls.map { it.page }) // no extra page needed

        val z = async { s.indexForLetter('Z') }
        advanceUntilIdle()
        assertEquals(129, z.await()) // loaded to the end, then the last book
        assertEquals(listOf(0, 1, 2), calls.map { it.page })
    }

    @Test fun jumpGivesUpWhenTheListChangesUnderIt() = runTest(StandardTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val s = state(gate = gate)
        advanceUntilIdle()
        val z = async { s.indexForLetter('Z') }
        runCurrent() // let the jump start loading before the list changes
        s.setSort(LibrarySort.AUTHOR)
        advanceUntilIdle()
        assertNull(z.await())
    }

    @Test fun refreshProgressCallsTheSharedFetchAndSwallowsFailures() = runTest(StandardTestDispatcher()) {
        var calls = 0
        var fail = false
        val s = state(progress = { calls++; if (fail) error("offline") else ProgressMap() })
        s.refreshProgress(); advanceUntilIdle()
        assertEquals(1, calls)
        fail = true
        s.refreshProgress(); advanceUntilIdle() // must not throw
        assertEquals(2, calls)
    }
}
