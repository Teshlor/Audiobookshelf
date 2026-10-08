package com.teshlor.abstv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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

    /** Books with the given titles, 60 per page; records every page requested. */
    private fun TestScope.titled(
        titles: List<String>,
        initial: SortSpec = SortSpec(),
        gate: Map<Int, CompletableDeferred<Unit>> = emptyMap(),
    ) = LibraryTabState(
        scope = this, initialSort = initial, saveSort = {},
        loader = { page, limit, sort, filter ->
            calls += Call(page, sort, filter)
            gate[page]?.await()
            val from = minOf(page * limit, titles.size)
            Page(titles.subList(from, minOf(from + limit, titles.size)).mapIndexed { i, t ->
                Book("id${from + i}", Media(Metadata(title = t)))
            }, titles.size, limit, page)
        },
        fetchProgress = { ProgressMap() },
    )

    /** 1000 titles, 40 each of A..Y in order (W = 880..919). */
    private val thousand = ('A'..'Y').flatMap { c -> List(40) { "$c book $it" } }

    @Test fun jumpToWInThousandBooksUsesParallelPagesWithoutDuplicates() = runTest(StandardTestDispatcher()) {
        val s = titled(thousand)
        advanceUntilIdle()
        calls.clear()
        val w = async { s.indexForLetter('W') }
        advanceUntilIdle()
        assertEquals(880, w.await())
        val pages = calls.map { it.page }
        assertEquals(pages.distinct(), pages)
        assertTrue(pages.containsAll((1..14).toList())) // 880 / 60 = page 14
        assertTrue(pages.size <= 17) // at most the 4-wide window past the target
        val items = s.pager.state.value.items
        assertEquals(thousand.take(items.size), items.map { it.title }) // contiguous, in order
    }

    @Test fun jumpHonoursIgnorePrefix() = runTest(StandardTestDispatcher()) {
        val titles = listOf("Apple", "The Banana", "A Cherry") + List(150) { "Zed $it" }
        val s = titled(titles) // server order with prefixes ignored: Apple, Banana, Cherry, Zed...
        advanceUntilIdle()
        val on = SortingSettings(ignorePrefix = true)
        val c = async { s.indexForLetter('C', on) }
        advanceUntilIdle()
        assertEquals(2, c.await())
        val t = async { s.indexForLetter('T', on) } // "The Banana" is filed under B; T has none -> first past it
        advanceUntilIdle()
        assertEquals(3, t.await())
        val tOff = async { s.indexForLetter('T', SortingSettings.OFF) } // prefixes not ignored: it is a T
        advanceUntilIdle()
        assertEquals(1, tOff.await())
    }

    @Test fun jumpWithDescendingSort() = runTest(StandardTestDispatcher()) {
        val titles = ('Z' downTo 'A').flatMap { c -> List(10) { "$c $it" } } // 260 books, Z first
        val s = titled(titles, SortSpec(LibrarySort.TITLE, true))
        advanceUntilIdle()
        val d = async { s.indexForLetter('D') }
        advanceUntilIdle()
        assertEquals(('Z' - 'D') * 10, d.await())
    }

    @Test fun jumpToMissingLetterLandsOnNextLetter() = runTest(StandardTestDispatcher()) {
        val titles = List(100) { "A $it" } + List(100) { "F $it" } + List(100) { "Q $it" }
        val s = titled(titles)
        advanceUntilIdle()
        val m = async { s.indexForLetter('M') } // no M: first Q
        advanceUntilIdle()
        assertEquals(200, m.await())
        val z = async { s.indexForLetter('Z') } // past everything: last book
        advanceUntilIdle()
        assertEquals(299, z.await())
    }

    @Test fun jumpWithinLoadedPagesMakesNoRequests() = runTest(StandardTestDispatcher()) {
        val s = titled(thousand)
        advanceUntilIdle()
        calls.clear()
        val b = async { s.indexForLetter('B') }
        advanceUntilIdle()
        assertEquals(40, b.await())
        assertTrue(calls.isEmpty())
    }

    @Test fun gridPrefetchDuringJumpDoesNotDuplicate() = runTest(StandardTestDispatcher()) {
        val gate = (1..20).associateWith { CompletableDeferred<Unit>() }
        val s = titled(thousand, gate = gate)
        advanceUntilIdle()
        calls.clear()
        val w = async { s.indexForLetter('W') }
        advanceUntilIdle()
        s.pager.onVisible(59); s.pager.onVisible(100); s.pager.loadMore()
        advanceUntilIdle()
        gate.values.forEach { it.complete(Unit) }
        advanceUntilIdle()
        assertEquals(880, w.await())
        val pages = calls.map { it.page }
        assertEquals(pages.distinct(), pages)
    }

    @Test fun newJumpAfterCancelledOneStillResolves() = runTest(StandardTestDispatcher()) {
        val gate = (1..20).associateWith { CompletableDeferred<Unit>() }
        val s = titled(thousand, gate = gate)
        advanceUntilIdle()
        val w = launch { s.indexForLetter('W') }
        advanceUntilIdle()
        w.cancel() // the screen cancels the old jump when another letter is pressed
        val b = async { s.indexForLetter('B') }
        advanceUntilIdle()
        assertEquals(40, b.await())
        gate.values.forEach { it.complete(Unit) }
        advanceUntilIdle()
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
