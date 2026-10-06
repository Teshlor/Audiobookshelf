package com.teshlor.abstv

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchLogicTest {
    @Test fun searchableFromTwoCharacters() {
        assertFalse(isSearchable("a"))
        assertFalse(isSearchable("  a  "))
        assertTrue(isSearchable("ab"))
        assertEquals("the way", normalizeQuery("  the   way "))
    }

    @Test fun recentsAreNewestFirstDedupedCaseInsensitiveAndCapped() {
        var l = emptyList<String>()
        l = RecentSearches.add(l, "dune")
        l = RecentSearches.add(l, "Hobbit")
        l = RecentSearches.add(l, "DUNE")
        assertEquals(listOf("DUNE", "Hobbit"), l)
        (1..10).forEach { l = RecentSearches.add(l, "query $it") }
        assertEquals(8, l.size)
        assertEquals("query 10", l.first())
        assertEquals("query 3", l.last())
    }

    @Test fun recentsIgnoreTooShortQueries() {
        assertEquals(listOf("abc"), RecentSearches.add(listOf("abc"), "x"))
    }

    @Test fun recentsRoundTripThroughPrefsString() {
        val l = listOf("one two", "three")
        assertEquals(l, RecentSearches.decode(RecentSearches.encode(l)))
        assertEquals(emptyList<String>(), RecentSearches.decode(null))
        assertEquals(listOf("a b"), RecentSearches.decode("a b\n\nA B"))
    }

    @Test fun highlightFindsAllOccurrencesIgnoringCase() {
        assertEquals(listOf(0..2, 11..13), highlightRanges("The Way of the King", "the"))
    }

    @Test fun highlightFallsBackToWordsWhenWholeQueryIsAbsent() {
        // "king way" is not a substring: Way = 4..6, King = 15..18.
        assertEquals(listOf(4..6, 15..18), highlightRanges("The Way of the King", "king way"))
    }

    @Test fun highlightMergesAdjacentMatches() {
        assertEquals(listOf(0..3), highlightRanges("aaaa", "aa"))
    }

    @Test fun highlightEdgeCases() {
        assertEquals(emptyList<IntRange>(), highlightRanges("Dune", ""))
        assertEquals(emptyList<IntRange>(), highlightRanges("", "dune"))
        assertEquals(emptyList<IntRange>(), highlightRanges("Dune", "zzz"))
    }

    @Test fun staleResponsesAreDropped() {
        val c = RequestCounter()
        val first = c.next()
        val second = c.next()
        assertFalse(c.isCurrent(first))
        assertTrue(c.isCurrent(second))
        c.invalidate()
        assertFalse(c.isCurrent(second))
    }

    @Test fun debouncerRunsOnlyTheLastSubmitAfterQuiet() = runTest {
        val ran = mutableListOf<String>()
        val d = Debouncer(this, 350)
        d.submit { ran += "a" }
        advanceTimeBy(200)
        d.submit { ran += "ab" }
        advanceTimeBy(200) // 400 ms after "a", but only 200 after "ab"
        assertTrue(ran.isEmpty())
        advanceTimeBy(160)
        assertEquals(listOf("ab"), ran)
        advanceUntilIdle()
    }

    @Test fun debouncerCancelStopsPendingRun() = runTest {
        var ran = false
        val d = Debouncer(this, 350)
        d.submit { ran = true }
        d.cancel()
        advanceUntilIdle()
        assertFalse(ran)
    }

    @Test fun sortBySequenceNumericThenUnnumbered() {
        fun b(id: String, seq: String?) = Book(
            id,
            Media(
                Metadata(
                    title = id,
                    series = seq?.let {
                        buildJsonObject {
                            put("id", JsonPrimitive("s"))
                            put("name", JsonPrimitive("S"))
                            put("sequence", JsonPrimitive(it))
                        }
                    },
                ),
            ),
        )
        val sorted = sortBySequence(listOf(b("x", "10"), b("n", null), b("y", "1.5"), b("z", "1"), b("w", "2")))
        assertEquals(listOf("z", "y", "w", "x", "n"), sorted.map { it.id })
    }

    @Test fun initialsAndCounts() {
        assertEquals("RI", initialsOf("Rob Inglis"))
        assertEquals("S", initialsOf("Stephen"))
        assertEquals("1 book", bookCountLabel(1))
        assertEquals("3 books", bookCountLabel(3))
    }
}
