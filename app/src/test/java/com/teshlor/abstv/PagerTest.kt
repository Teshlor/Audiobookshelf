package com.teshlor.abstv

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PagerTest {
    private val calls = mutableListOf<Int>()

    private fun TestScope.pager(total: Int, size: Int = 10, fail: () -> Boolean = { false }) =
        Pager<Int>(this, size) { page, limit ->
            calls += page
            if (fail()) error("boom")
            val from = page * limit
            Page((from until minOf(from + limit, total)).toList(), total, limit, page)
        }

    @Test fun loadsPagesUntilTotalThenStops() = runTest(StandardTestDispatcher()) {
        val p = pager(25)
        repeat(6) { p.loadMore(); advanceUntilIdle() }
        val s = p.state.value
        assertEquals(25, s.items.size)
        assertEquals((0 until 25).toList(), s.items)
        assertTrue(s.endReached)
        assertEquals(listOf(0, 1, 2), calls) // no 4th request
    }

    @Test fun exactMultipleOfPageSizeStopsWithoutExtraCall() = runTest(StandardTestDispatcher()) {
        val p = pager(20)
        repeat(4) { p.loadMore(); advanceUntilIdle() }
        assertEquals(listOf(0, 1), calls)
        assertTrue(p.state.value.endReached)
    }

    @Test fun loadMoreWhileLoadingIsIgnored() = runTest(StandardTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val p = Pager<Int>(this, 10) { page, limit -> calls += page; gate.await(); Page(listOf(page), 100, limit, page) }
        p.loadMore(); p.loadMore(); p.loadMore()
        advanceUntilIdle()
        assertTrue(p.state.value.loading)
        assertEquals(listOf(0), calls)
        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(p.state.value.loading)
        assertEquals(listOf(0), calls)
    }

    @Test fun onVisiblePrefetchesWithin15OfEnd() = runTest(StandardTestDispatcher()) {
        val p = pager(100, size = 30)
        p.onVisible(0); advanceUntilIdle()   // empty list: loads page 0
        assertEquals(listOf(0), calls)
        p.onVisible(10); advanceUntilIdle()  // 10 < 30 - 15: nothing
        assertEquals(listOf(0), calls)
        p.onVisible(15); advanceUntilIdle()  // 15 >= 15: loads page 1
        assertEquals(listOf(0, 1), calls)
    }

    @Test fun errorBlocksLoadMoreUntilRetry() = runTest(StandardTestDispatcher()) {
        var failing = true
        val p = pager(25, fail = { failing })
        p.loadMore(); advanceUntilIdle()
        assertNotNull(p.state.value.error)
        assertTrue(p.state.value.loadedOnce)
        p.loadMore(); advanceUntilIdle()
        assertEquals(listOf(0), calls)
        failing = false
        p.retry(); advanceUntilIdle()
        assertNull(p.state.value.error)
        assertEquals(10, p.state.value.items.size)
        assertEquals(listOf(0, 0), calls)
    }

    @Test fun resetDropsOldItemsAndIgnoresStaleResponse() = runTest(StandardTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        var sort = "a"
        val p = Pager<String>(this, 10) { page, limit ->
            val mine = sort
            if (mine == "a") gate.await()
            Page(listOf("$mine$page"), 50, limit, page)
        }
        p.loadMore(); advanceUntilIdle()   // in flight for sort "a"
        sort = "b"
        p.reset(); advanceUntilIdle()      // cancels "a", loads "b" page 0
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("b0"), p.state.value.items)
        p.loadMore(); advanceUntilIdle()
        assertEquals(listOf("b0", "b1"), p.state.value.items)
    }

    @Test fun emptyServerResultEndsPaging() = runTest(StandardTestDispatcher()) {
        val p = pager(0)
        p.loadMore(); advanceUntilIdle()
        assertTrue(p.state.value.endReached)
        assertTrue(p.state.value.loadedOnce)
        assertEquals(0, p.state.value.total)
    }
}
