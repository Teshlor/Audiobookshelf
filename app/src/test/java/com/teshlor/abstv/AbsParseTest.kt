package com.teshlor.abstv

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private fun fixture(name: String): String =
    AbsParseTest::class.java.getResourceAsStream("/abs/$name")!!.bufferedReader().use { it.readText() }

class AbsParseTest {
    private var server: MockWebServer? = null

    @After fun tearDown() { server?.shutdown() }

    @Test fun seriesFilterIsBase64() {
        assertEquals("series.YWJj", seriesFilter("abc"))
        assertEquals("progress.aW4tcHJvZ3Jlc3M=", progressFilter("in-progress"))
    }

    @Test fun librariesParse() {
        val libs = AbsParse.libraries(fixture("libraries.json"))
        assertEquals(listOf("lib-1", "lib-2", "lib-3"), libs.map { it.id })
        assertEquals("podcast", libs[2].mediaType)
    }

    @Test fun personalizedKeepsBookAndSeriesShelvesInOrder() {
        val shelves = AbsParse.shelves(fixture("personalized.json"))
        // dropped: continue-reading, read-again (ebook shelves), newest-authors, empty
        assertEquals(
            listOf("continue-listening", "continue-series", "recently-added", "recent-series", "discover", "listen-again"),
            shelves.map { it.id },
        )
        val cl = shelves[0] as BookShelf
        assertEquals("Continue Listening", cl.label)
        assertEquals(listOf("Dune", "Emma"), cl.books.map { it.title })
        assertEquals("2", (shelves[1] as BookShelf).books[0].sequence) // series object shape
        assertFalse((shelves[2] as BookShelf).books[0].hasCover)
        val rs = shelves[3] as SeriesShelf
        assertEquals("Saga", rs.series[0].name)
        assertEquals(2, rs.series[0].books.size)
    }

    @Test fun itemsPageParsesAndDetectsEbookOnlyItems() {
        val page = AbsParse.bookPage(fixture("items_page.json"))
        assertEquals(486, page.total)
        assertEquals(2, page.results.size)
        assertEquals("Dune", page.results[0].title)
        assertEquals("A. Author", page.results[0].author)
        assertEquals("N. Narrator", page.results[0].media.metadata.narratorName)
        assertNull(page.results[0].sequence)
        assertTrue(page.results[0].hasAudio)
        assertFalse(page.results[1].hasAudio)
        assertEquals("epub", page.results[1].media.ebookFormat)
    }

    @Test fun seriesFilteredItemsReadSequenceFromObjectShape() {
        val page = AbsParse.bookPage(fixture("items_series_filtered.json"))
        assertEquals(listOf("1", "1.5", "2", "10", null), page.results.map { it.sequence })
        assertEquals("Saga", page.results[0].seriesRefs.single().name)
    }

    @Test fun expandedAndSearchItemsReadSequenceFromArrayShape() {
        val book = AbsParse.json.decodeFromString(Book.serializer(), fixture("item_expanded.json"))
        assertEquals("1", book.sequence)
        assertEquals("s-1", book.seriesRefs.single().id)
    }

    @Test fun seriesPageParses() {
        val page = AbsParse.seriesPage(fixture("series_page.json"))
        assertEquals(31, page.total)
        assertEquals("Saga", page.results[0].name)
        assertEquals(listOf("Saga 1", "Saga 1.5"), page.results[0].books.map { it.title })
    }

    @Test fun collectionsParseWithBooksAndArraySeries() {
        val page = AbsParse.collectionPage(fixture("collections.json"))
        assertEquals(1, page.total)
        val c = page.results[0]
        assertEquals("Favourites", c.name)
        assertEquals("Best ones", c.description)
        assertEquals(listOf("Saga 1", "Saga 1.5"), c.books.map { it.title })
        assertEquals("1", c.books[0].sequence)
    }

    @Test fun searchParsesBooksSeriesAndNarrators() {
        val r = AbsParse.search(fixture("search.json"))
        assertEquals("Saga 1", r.book.single().libraryItem.title)
        assertEquals("1", r.book.single().libraryItem.sequence)
        assertEquals("Saga", r.series.single().series.name)
        assertEquals(2, r.series.single().books.size)
        assertEquals(NarratorMatch("N. Narrator", 4), r.narrators.single())
    }

    @Test fun searchWithNothingParses() {
        val r = AbsParse.search("""{"book":[],"narrators":[],"tags":[],"genres":[],"series":[],"authors":[]}""")
        assertTrue(r.book.isEmpty() && r.series.isEmpty() && r.narrators.isEmpty())
    }

    @Test fun meParsesUsernameAndProgress() {
        val me = AbsParse.me(fixture("me.json"))
        assertEquals("evan", me.username)
        assertEquals(5, me.mediaProgress.size)
        val map = ProgressMap.from(me)
        assertEquals(3, map.size) // podcast episode and id-less entries ignored
        val p = map["li-1"]!!
        assertEquals(ProgressState.IN_PROGRESS, p.state)
        assertEquals(0.25f, p.fraction, 0.0001f)
        assertEquals(7500.0, p.remainingSeconds, 0.001)
        assertEquals(ProgressState.FINISHED, map.stateOf("li-2"))
        assertEquals(0.0, map["li-2"]!!.remainingSeconds, 0.0)
        assertEquals(1f, map["li-2"]!!.fraction, 0f)
        assertEquals(ProgressState.NOT_STARTED, map.stateOf("li-3"))
        assertEquals(ProgressState.NOT_STARTED, map.stateOf("missing"))
        assertNull(map["missing"])
    }

    // ---- request building, against a local MockWebServer ----

    private fun api(dispatcher: (RecordedRequest) -> MockResponse): Pair<AbsApi, MockWebServer> {
        val s = MockWebServer()
        s.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = dispatcher(request) }
        s.start()
        server = s
        return AbsApi(s.url("/").toString(), "tok123") to s
    }

    private fun ok(name: String) = MockResponse().setBody(fixture(name))

    @Test fun coverUrlHasNoToken() {
        val a = AbsApi("http://host:13378/", "secret")
        assertEquals("http://host:13378/api/items/li-1/cover?width=400", a.coverUrl("li-1"))
        assertFalse(a.coverUrl("x").contains("secret"))
    }

    @Test fun libraryItemsBuildsFilterSortAndAuth() = runBlocking {
        val (a, s) = api { ok("items_page.json") }
        val page = a.libraryItems("lib-1", page = 2, limit = 60, sort = "addedAt", desc = true, filter = progressFilter("in-progress"))
        assertEquals(486, page.total)
        val req = s.takeRequest()
        assertEquals("Bearer tok123", req.getHeader("Authorization"))
        val u = req.requestUrl!!
        assertEquals("/api/libraries/lib-1/items", u.encodedPath)
        assertEquals("2", u.queryParameter("page"))
        assertEquals("60", u.queryParameter("limit"))
        assertEquals("addedAt", u.queryParameter("sort"))
        assertEquals("1", u.queryParameter("desc"))
        assertEquals("1", u.queryParameter("minified"))
        assertEquals("progress.aW4tcHJvZ3Jlc3M=", u.queryParameter("filter"))
    }

    @Test fun seriesBooksUsesSequenceSortAndSeriesFilter() = runBlocking {
        val (a, s) = api { ok("items_series_filtered.json") }
        val books = a.seriesBooks("lib-1", "abc")
        assertEquals(5, books.size)
        val u = s.takeRequest().requestUrl!!
        assertEquals("series.YWJj", u.queryParameter("filter"))
        assertEquals("sequence", u.queryParameter("sort"))
        assertEquals("100", u.queryParameter("limit"))
        assertEquals("0", u.queryParameter("page"))
    }

    @Test fun searchCollectionsAndSeriesParams() = runBlocking {
        val (a, s) = api { r ->
            when {
                r.path!!.contains("/search") -> ok("search.json")
                r.path!!.contains("/collections") -> ok("collections.json")
                else -> ok("series_page.json")
            }
        }
        a.search("lib-1", "tolkien & co")
        assertEquals("tolkien & co", s.takeRequest().requestUrl!!.queryParameter("q"))
        a.collections("lib-1", 1)
        val c = s.takeRequest().requestUrl!!
        assertEquals("30", c.queryParameter("limit"))
        assertEquals("1", c.queryParameter("page"))
        a.series("lib-1", 0)
        assertEquals("name", s.takeRequest().requestUrl!!.queryParameter("sort"))
    }

    @Test fun meAndProgress() = runBlocking {
        val (a, s) = api { ok("me.json") }
        assertEquals("evan", a.me().username)
        assertEquals(ProgressState.FINISHED, a.progress().stateOf("li-2"))
        assertEquals("/api/me", s.takeRequest().requestUrl!!.encodedPath)
    }

    @Test fun audioLibrariesFlagsEbookOnlyLibrary() = runBlocking {
        val (a, _) = api { r ->
            val u = r.requestUrl!!
            val filtered = u.queryParameter("filter") != null
            when {
                u.encodedPath == "/api/libraries" -> ok("libraries.json")
                u.encodedPath.contains("lib-1") -> MockResponse().setBody(if (filtered) """{"total":2}""" else """{"total":10}""")
                else -> MockResponse().setBody("""{"total":7}""") // lib-2: every item has no audio tracks
            }
        }
        val libs = a.audioLibraries()
        assertEquals(listOf("lib-1", "lib-2"), libs.map { it.id }) // podcast library dropped
        assertTrue(libs[0].hasAudio)
        assertFalse(libs[1].hasAudio)
    }

    @Test fun hasAudioUsesTracksNoneFilter() = runBlocking {
        val (a, s) = api { MockResponse().setBody("""{"total":3}""") }
        a.hasAudio("lib-9")
        val first = s.takeRequest().requestUrl!!
        val second = s.takeRequest().requestUrl!!
        assertEquals("1", first.queryParameter("limit"))
        assertEquals("tracks.bm9uZQ==", second.queryParameter("filter"))
    }

    @Test fun playOnItemWithoutAudioSurfacesHttp404() = runBlocking {
        val (a, _) = api { MockResponse().setResponseCode(404) }
        try {
            a.play("li-eb")
            fail("expected AbsHttpException")
        } catch (e: AbsHttpException) {
            assertEquals(404, e.code)
        }
    }
}
