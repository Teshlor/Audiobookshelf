package com.teshlor.abstv

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private fun book(id: String, seq: String?, duration: Double = 3600.0) = Book(
    id,
    Media(
        metadata = Metadata(
            title = "Book $id",
            series = seq?.let { s -> buildJsonObject { put("id", "s"); put("name", "Saga"); put("sequence", s) } },
        ),
        duration = duration,
    ),
)

private fun prog(vararg e: Triple<String, Double, Boolean>): ProgressMap = ProgressMap(
    e.associate { (id, cur, fin) -> id to BookProgress(if (fin) 1f else (cur / 3600.0).toFloat(), cur, 3600.0, fin) },
)

class SeriesLogicTest {
    @Test fun sortsNumericallyNotAlphabetically() {
        val sorted = sortBySequence(listOf(book("a", "10"), book("b", "2"), book("c", "1.5"), book("d", "1")))
        assertEquals(listOf("d", "c", "b", "a"), sorted.map { it.id })
    }

    @Test fun booksWithoutSequenceGoLastInOriginalOrder() {
        val sorted = sortBySequence(listOf(book("x", null), book("a", "2"), book("y", "garbage"), book("b", "1")))
        assertEquals(listOf("b", "a", "x", "y"), sorted.map { it.id })
    }

    @Test fun bookLabelUsesSequenceThenPosition() {
        assertEquals("Book 1.5", bookLabel(book("a", "1.5"), 3))
        assertEquals("Book 4", bookLabel(book("a", null), 3))
    }

    private val four = listOf(book("1", "1"), book("2", "2"), book("3", "3"), book("4", "4"))

    @Test fun continueTargetPrefersInProgress() {
        val p = prog(Triple("1", 0.0, true), Triple("2", 0.0, true), Triple("3", 1800.0, false))
        val t = continueTarget(four, p)!!
        assertEquals("3", t.book.id)
        assertEquals("Continue · Book 3", t.label)
        assertEquals("2 of 4 finished · Book 3 in progress", seriesCaption(four, p))
    }

    @Test fun continueTargetStartsAtBookOneWhenNothingStarted() {
        assertEquals("Start · Book 1", continueTarget(four, ProgressMap())!!.label)
    }

    @Test fun continueTargetFindsNextUnstartedAfterFinished() {
        val t = continueTarget(four, prog(Triple("1", 0.0, true), Triple("2", 0.0, true)))!!
        assertEquals("3", t.book.id)
        assertEquals("Continue · Book 3", t.label)
    }

    @Test fun continueTargetListenAgainWhenAllFinished() {
        val all = prog(*four.map { Triple(it.id, 0.0, true) }.toTypedArray())
        val t = continueTarget(four, all)!!
        assertEquals("1", t.book.id)
        assertEquals("Listen again · Book 1", t.label)
        assertEquals(4, finishedCount(four, all))
        assertNull(continueTarget(emptyList(), all))
    }

    @Test fun labels() {
        assertEquals("11h 19m left", timeLeftLabel(11 * 3600 + 19 * 60 + 30.0))
        assertEquals("42m left", timeLeftLabel(42 * 60.0))
        assertEquals("Under a minute left", timeLeftLabel(20.0))
        assertEquals("22h 40m", durationLabel(22 * 3600 + 40 * 60.0))
        assertEquals("40m", durationLabel(2400.0))
        assertEquals("1 book", seriesSubtitle(1, 0))
        assertEquals("4 books", seriesSubtitle(4, 0))
        assertEquals("4 books · 2 finished", seriesSubtitle(4, 2))
    }

    @Test fun homeFocusTargetRestoresOrFallsBack() {
        val shelves = listOf(
            BookShelf("continue-listening", "Continue Listening", listOf(book("a", null), book("b", null))),
            BookShelf("discover", "Discover", listOf(book("b", null))),
            SeriesShelf("recent-series", "Recent Series", listOf(Series("s1", "Saga"))),
        )
        assertEquals("continue-listening/a", homeFocusTarget(shelves, null))
        assertEquals("discover/b", homeFocusTarget(shelves, "discover/b")) // same book on two shelves stays distinct
        assertEquals("recent-series/s1", homeFocusTarget(shelves, "recent-series/s1"))
        assertEquals("continue-listening/a", homeFocusTarget(shelves, "gone/zzz"))
        assertNull(homeFocusTarget(emptyList(), "x"))
    }
}
