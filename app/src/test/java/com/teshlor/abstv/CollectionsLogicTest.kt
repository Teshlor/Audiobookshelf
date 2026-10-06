package com.teshlor.abstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionsLogicTest {
    private fun book(n: Int, duration: Double = 0.0) =
        Book("b$n", Media(Metadata(title = "Book $n"), duration = duration, coverPath = "/c/$n"))

    private fun books(n: Int) = List(n) { book(it + 1) }

    @Test fun mosaicEmpty() {
        val m = collectionMosaic(emptyList())
        assertNull(m.lead); assertFalse(m.hasColumn)
    }

    @Test fun mosaicOneBookIsLeadOnly() {
        val m = collectionMosaic(books(1))
        assertEquals("b1", m.lead?.id); assertFalse(m.hasColumn); assertNull(m.bottom)
    }

    @Test fun mosaicTwoBooksHasEmptyOutlineTile() {
        val m = collectionMosaic(books(2))
        assertEquals(MosaicTile.Cover(book(2)), m.top)
        assertEquals(MosaicTile.Empty, m.bottom)
    }

    @Test fun mosaicThreeBooksShowsThirdCover() {
        val m = collectionMosaic(books(3))
        assertEquals(MosaicTile.Cover(book(2)), m.top)
        assertEquals(MosaicTile.Cover(book(3)), m.bottom)
    }

    @Test fun mosaicFourOrMoreShowsPlusN() {
        assertEquals(MosaicTile.More(2), collectionMosaic(books(4)).bottom)
        val eight = collectionMosaic(books(8))
        assertEquals(MosaicTile.More(6), eight.bottom)
        assertEquals("b1", eight.lead?.id)
        assertEquals(MosaicTile.Cover(book(2)), eight.top)
    }

    @Test fun detailMosaicPadsToFourSlots() {
        assertEquals(listOf("b1", "b2", null, null), detailMosaic(books(2)).map { it?.id })
        assertEquals(listOf("b1", "b2", "b3", "b4"), detailMosaic(books(9)).map { it?.id })
    }

    @Test fun countAndDurationLabels() {
        assertEquals("1 book", bookCountLabel(1))
        assertEquals("0 books", bookCountLabel(0))
        assertEquals("102h 46m", formatHoursMinutes(102 * 3600.0 + 46 * 60 + 30))
        assertEquals("46m", formatHoursMinutes(46 * 60.0))
        assertEquals("0m", formatHoursMinutes(-5.0))
        assertEquals("2 books · 3h 0m", collectionSummary(listOf(book(1, 3600.0), book(2, 7200.0))))
        assertEquals("2 books", collectionSummary(books(2)))
    }

    @Test fun progressCaptionCountsFinishedAndInProgress() {
        val me = Me(
            "u",
            listOf(
                MediaProgress(libraryItemId = "b1", isFinished = true, progress = 1.0),
                MediaProgress(libraryItemId = "b2", progress = 0.4, currentTime = 40.0, duration = 100.0),
                MediaProgress(libraryItemId = "b3", episodeId = "ep", progress = 0.9), // podcast entry: ignored
            ),
        )
        val p = ProgressMap.from(me)
        assertEquals("1 of 3 finished · 1 in progress", progressCaption(books(3), p))
        assertNull(progressCaption(books(3), ProgressMap()))
        assertEquals("0 of 3 finished · 1 in progress", progressCaption(books(3), ProgressMap.from(Me("u", listOf(me.mediaProgress[1])))))
    }

    @Test fun descriptionStripsHtml() {
        assertEquals("Hello world", plainDescription("<p>Hello <b>world</b></p>"))
        assertEquals("", plainDescription(null))
    }

    @Test fun overlineSingularAndPlural() {
        assertEquals("AUDIOBOOKS · 6 COLLECTIONS", collectionsOverline("Audiobooks", 6))
        assertEquals("AUDIOBOOKS · 1 COLLECTION", collectionsOverline("Audiobooks", 1))
        assertEquals("MY LIB · COLLECTIONS", collectionsOverline("My Lib", 0))
    }

    @Test fun collectionScreenKeyIsPerCollectionAndStackExposesIt() {
        val a = Screen.CollectionBooks(BookCollection("a", "A"))
        val b = Screen.CollectionBooks(BookCollection("b", "B"))
        assertEquals("collection:a", a.key)
        val n = NavStack(Screen.Browse(Tab.COLLECTIONS))
        n.push(a)
        assertEquals(Tab.COLLECTIONS, n.tab)
        assertEquals(listOf(Screen.Browse(Tab.COLLECTIONS), a), n.screens)
        n.pop()
        assertEquals(listOf<Screen>(Screen.Browse(Tab.COLLECTIONS)), n.screens)
        n.push(b); n.selectTab(Tab.HOME)
        assertTrue(n.screens.none { it is Screen.CollectionBooks })
    }
}
