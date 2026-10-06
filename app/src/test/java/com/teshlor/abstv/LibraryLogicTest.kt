package com.teshlor.abstv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibraryLogicTest {
    private fun book(title: String, lf: String? = null, author: String? = null) =
        Book("id-$title", Media(Metadata(title = title, authorName = author, authorNameLF = lf)))

    private val asc = SortSpec(LibrarySort.TITLE, false)
    private val on = SortingSettings(ignorePrefix = true, prefixes = listOf("the", "a"))

    // ---- sort persistence ----
    @Test fun sortRoundTrips() {
        for (s in LibrarySort.entries) for (d in listOf(false, true)) {
            val spec = SortSpec(s, d)
            assertEquals(spec, SortSpec.decode(spec.encode()))
        }
        assertEquals("ADDED:desc", SortSpec(LibrarySort.ADDED, true).encode())
    }

    @Test fun badStoredSortFallsBackToTitleAsc() {
        assertEquals(SortSpec(), SortSpec.decode(null))
        assertEquals(SortSpec(), SortSpec.decode(""))
        assertEquals(SortSpec(), SortSpec.decode("PROGRESS:desc"))
        assertEquals(SortSpec(LibrarySort.YEAR, true), SortSpec.decode("YEAR")) // direction missing: sort's default
    }

    @Test fun filterValuesAreBase64ProgressFilters() {
        assertNull(LibraryFilter.ALL.apiFilter)
        assertEquals(progressFilter("in-progress"), LibraryFilter.IN_PROGRESS.apiFilter)
        assertEquals(progressFilter("finished"), LibraryFilter.FINISHED.apiFilter)
        assertEquals(progressFilter("not-started"), LibraryFilter.NOT_STARTED.apiFilter)
    }

    @Test fun overline() {
        assertEquals("AUDIOBOOKS", libraryOverline("Audiobooks", LibraryFilter.ALL, 0, false))
        assertEquals("AUDIOBOOKS · 486 BOOKS", libraryOverline("Audiobooks", LibraryFilter.ALL, 486, true))
        assertEquals("AUDIOBOOKS · FINISHED · 1 BOOK", libraryOverline("Audiobooks", LibraryFilter.FINISHED, 1, true))
    }

    // ---- letters ----
    @Test fun letters() {
        assertEquals('A', letterOf("Alloy"))
        assertEquals('E', letterOf("Émile"))
        assertEquals('#', letterOf("1984"))
        assertEquals('#', letterOf("  "))
        assertEquals('#', letterOf("世界"))
        assertEquals('Q', letterOf("\"Quoted\""))
    }

    @Test fun sortKeyTitleOnlyStripsWhenServerIgnoresPrefixes() {
        assertEquals("The Alloy of Law", sortKeyTitle("The Alloy of Law", SortingSettings.OFF))
        assertEquals("Alloy of Law", sortKeyTitle("The Alloy of Law", on))
        assertEquals("Apple", sortKeyTitle("A Apple", on))
        assertEquals("An Apple", sortKeyTitle("An Apple", on)) // "an" is not in the default prefix list
        assertEquals("Apple", sortKeyTitle("An Apple", SortingSettings(true, listOf("an"))))
        assertEquals("Anathem", sortKeyTitle("Anathem", on))
        assertEquals("The", sortKeyTitle("The", on))
        assertEquals("Theory", sortKeyTitle("Theory", on)) // needs a space after the prefix
    }

    // ---- A-Z jump ----
    private val titles = listOf("1984", "Alloy", "Arcanum", "Bands", "Blade", "Caine", "The Martian", "Mistborn", "Nightblade", "Zero")
    private val books = titles.map { book(it) }

    @Test fun jumpFindsFirstOfLetter() {
        assertEquals(1, firstIndexForLetter(books, 'A', asc, true, on))
        assertEquals(3, firstIndexForLetter(books, 'B', asc, true, on))
        assertEquals(0, firstIndexForLetter(books, '#', asc, true, on))
    }

    @Test fun jumpToMissingLetterLandsOnNextBook() {
        assertEquals(8, firstIndexForLetter(books, 'N', asc, true, on))
        assertEquals(9, firstIndexForLetter(books, 'Y', asc, true, on)) // no Y: first book past it is "Zero"
    }

    @Test fun prefixIgnoredOnlyWhenServerSettingIsOn() {
        // Server ignores "The": "The Alloy of Law" is filed under A, so a scan for B must pass it, and A finds it.
        val ignoring = listOf("The Alloy", "Arcanum", "Blade").map { book(it) }
        assertEquals(2, firstIndexForLetter(ignoring, 'B', asc, true, on))
        assertEquals(0, firstIndexForLetter(ignoring, 'A', asc, true, on))
        // Server not ignoring: the raw order is Arcanum, Blade, The Alloy; T finds "The Alloy", A does not match it.
        val raw = listOf("Arcanum", "Blade", "The Alloy").map { book(it) }
        assertEquals(2, firstIndexForLetter(raw, 'T', asc, true))
        assertEquals(0, firstIndexForLetter(raw, 'A', asc, true))
    }

    @Test fun proofCaseTheAlloyOfLaw() {
        val list = listOf("The Alloy of Law", "Brandon's Notes", "The Final Empire", "Tress of the Emerald Sea", "The Way of Kings").map { book(it) }
        // Server ignores "The" (list order is Alloy, Brandon's, Final, Tress, Way): T is "Tress" at index 3, not the
        // raw-T "The Alloy of Law" at index 0 that matching either key would have produced.
        assertEquals(3, firstIndexForLetter(list, 'T', asc, true, on))
        // Server not ignoring: the raw first letter is what the server sorted by, so the first T is index 0.
        assertEquals(0, firstIndexForLetter(list, 'T', asc, true, SortingSettings.OFF))
    }

    @Test fun needsMorePagesWhenNotReachedAndIncomplete() {
        assertNull(firstIndexForLetter(books.take(5), 'M', asc, false, on))
        assertNull(firstIndexForLetter(emptyList(), 'M', asc, false))
    }

    @Test fun pastTheEndOnceCompleteLandsOnLastBook() {
        assertEquals(4, firstIndexForLetter(books.take(5), 'Z', asc, true, on)) // fully loaded, no Z: last book
        assertNull(firstIndexForLetter(emptyList(), 'A', asc, true))
    }

    @Test fun descendingOrder() {
        val desc = SortSpec(LibrarySort.TITLE, true)
        val list = listOf("Zero", "Nightblade", "Mistborn", "Caine", "Blade", "Alloy").map { book(it) }
        assertEquals(2, firstIndexForLetter(list, 'M', desc, true))
        assertEquals(1, firstIndexForLetter(list, 'O', desc, true)) // no O: first book past it
    }

    @Test fun authorSortUsesLastNameFirstKey() {
        val spec = SortSpec(LibrarySort.AUTHOR, false)
        val list = listOf(
            book("X", lf = "Abercrombie, Joe", author = "Joe Abercrombie"),
            book("Y", lf = "Sanderson, Brandon", author = "Brandon Sanderson"),
            book("Z", lf = null, author = "Weir, Andy"),
        )
        assertEquals(1, firstIndexForLetter(list, 'S', spec, true))
        assertEquals(2, firstIndexForLetter(list, 'W', spec, true))
        assertEquals(1, firstIndexForLetter(list, 'B', spec, true)) // no B: next book
    }
}
