package com.teshlor.abstv

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.encodeUtf8
import java.io.IOException

/** Non-2xx response. [code] lets callers tell "no audio" (404 on /play) from a dead server. */
class AbsHttpException(val code: Int) : IOException("Server returned HTTP $code")

/** Pure JSON parsing for the ABS v2.37.1 response shapes, kept free of I/O so unit tests can feed it fixtures. */
internal object AbsParse {
    val json = Json { ignoreUnknownKeys = true }

    private fun <T> list(ser: KSerializer<T>, el: kotlinx.serialization.json.JsonElement): List<T> =
        json.decodeFromJsonElement(ListSerializer(ser), el)

    fun libraries(body: String): List<Library> =
        list(Library.serializer(), json.parseToJsonElement(body).jsonObject["libraries"]!!.jsonArray)

    fun bookPage(body: String): Page<Book> = json.decodeFromString(Page.serializer(Book.serializer()), body)
    fun seriesPage(body: String): Page<Series> = json.decodeFromString(Page.serializer(Series.serializer()), body)
    fun collectionPage(body: String): Page<BookCollection> =
        json.decodeFromString(Page.serializer(BookCollection.serializer()), body)

    /** Total only; the cheap way to count without decoding items. */
    fun total(body: String): Int = json.parseToJsonElement(body).jsonObject["total"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0

    private val droppedShelves = setOf("continue-reading", "read-again")

    /** Keeps `book` and `series` shelves in the server's order; drops ebook-reading and empty shelves. */
    fun shelves(body: String): List<Shelf> =
        list(RawShelf.serializer(), json.parseToJsonElement(body)).mapNotNull { raw ->
            if (raw.id in droppedShelves || raw.entities.isEmpty()) return@mapNotNull null
            when (raw.type) {
                "book" -> BookShelf(raw.id, raw.label, list(Book.serializer(), raw.entities))
                "series" -> SeriesShelf(raw.id, raw.label, list(Series.serializer(), raw.entities))
                else -> null // authors, podcast episodes, ...
            }
        }

    fun search(body: String): SearchResult = json.decodeFromString(SearchResult.serializer(), body)
    fun me(body: String): Me = json.decodeFromString(Me.serializer(), body)
}

fun seriesFilter(seriesId: String) = "series." + seriesId.encodeUtf8().base64()

/** progress filter values accepted by upstream: in-progress, finished, not-started, not-finished. */
fun progressFilter(value: String) = "progress." + value.encodeUtf8().base64()

class AbsApi(serverUrl: String, val auth: AuthSession? = null) {
    val baseUrl: String = normalize(serverUrl)

    private val json = AbsParse.json

    /** Bearer interceptor + refresh authenticator when signed in; also what ExoPlayer streams through. */
    val client: OkHttpClient = auth?.newClient(baseUrl) ?: OkHttpClient()

    private suspend fun exec(req: Request): String = withContext(Dispatchers.IO) {
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw AbsHttpException(r.code)
            r.body?.string().orEmpty()
        }
    }

    private fun get(path: String) = Request.Builder().url(baseUrl + path).build()
    private fun get(url: HttpUrl) = Request.Builder().url(url).build()

    /** `<base>/api/<segments>` with proper query encoding (filter values are base64 and contain `=`, `+`). */
    private fun apiUrl(path: String, vararg query: Pair<String, String?>): HttpUrl {
        val b = baseUrl.toHttpUrl().newBuilder().addPathSegments(path.trim('/'))
        query.forEach { (k, v) -> if (v != null) b.addQueryParameter(k, v) }
        return b.build()
    }
    private fun post(path: String, body: JsonObject) = Request.Builder()
        .url(baseUrl + path)
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()

    /** Asks for the access + refresh pair (`x-return-tokens`); the legacy `user.token` is ignored. */
    suspend fun login(username: String, password: String): LoginResult {
        val req = post("/login", buildJsonObject {
            put("username", username)
            put("password", password)
        }).newBuilder().header("x-return-tokens", "true").build()
        val r = AuthSession.parseTokens(exec(req))
        return r.copy(username = r.username ?: username)
    }

    /** Parses off the main thread: callers run on Main (viewModelScope) and the library JSON is large. */
    private suspend fun <T> parse(body: String, block: (String) -> T): T =
        withContext(Dispatchers.Default) { block(body) }

    suspend fun libraries(): List<Library> = parse(exec(get("/api/libraries")), AbsParse::libraries)

    /**
     * Book libraries only, each with [Library.hasAudio] filled in. Ebook-only libraries (mediaType "book" but no
     * audio files) come back with hasAudio = false so the UI can hide them. An empty library counts as audio.
     */
    suspend fun audioLibraries(): List<Library> = coroutineScope {
        libraries().filter { it.mediaType == "book" }
            .map { lib -> async { lib.copy(hasAudio = hasAudio(lib.id)) } }
            .awaitAll()
    }

    /**
     * Cheapest reliable signal in stock v2.37.1: two `limit=1` item queries. `filter=tracks.none` matches items whose
     * audioFiles array is empty (libraryItemsBookFilters.js), so the library has audio iff total > total(tracks.none).
     * Fails open (true) if a call fails for any reason other than cancellation, so a flaky server never hides a real library.
     */
    suspend fun hasAudio(libraryId: String): Boolean = try {
        val all = parse(exec(get(apiUrl("api/libraries/$libraryId/items", "limit" to "1", "minified" to "1"))), AbsParse::total)
        val noTracks = parse(
            exec(get(apiUrl("api/libraries/$libraryId/items", "limit" to "1", "minified" to "1", "filter" to "tracks." + "none".encodeUtf8().base64()))),
            AbsParse::total,
        )
        all == 0 || all > noTracks
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        true
    }

    /** Home shelves in the server's order (book + series shelves only). */
    suspend fun personalized(libraryId: String, limit: Int = 12): List<Shelf> =
        parse(exec(get(apiUrl("api/libraries/$libraryId/personalized", "limit" to limit.toString()))), AbsParse::shelves)

    /** [page] is 0-based. [filter] is a full `group.<b64>` string, e.g. [progressFilter] or [seriesFilter]. */
    suspend fun libraryItems(
        libraryId: String,
        page: Int,
        limit: Int = 60,
        sort: String = "media.metadata.title",
        desc: Boolean = false,
        filter: String? = null,
    ): Page<Book> = parse(
        exec(
            get(
                apiUrl(
                    "api/libraries/$libraryId/items",
                    "limit" to limit.toString(), "page" to page.toString(), "sort" to sort,
                    "desc" to if (desc) "1" else "0", "minified" to "1", "filter" to filter,
                ),
            ),
        ),
        AbsParse::bookPage,
    )

    suspend fun series(libraryId: String, page: Int, limit: Int = 30, sort: String = "name"): Page<Series> = parse(
        exec(
            get(
                apiUrl(
                    "api/libraries/$libraryId/series",
                    "limit" to limit.toString(), "page" to page.toString(), "sort" to sort, "minified" to "1",
                ),
            ),
        ),
        AbsParse::seriesPage,
    )

    /** Books of one series in sequence order (the series list endpoint carries no usable sequence). */
    suspend fun seriesBooks(libraryId: String, seriesId: String): List<Book> =
        libraryItems(libraryId, page = 0, limit = 100, sort = "sequence", filter = seriesFilter(seriesId)).results

    /** Collections already carry their ordered books, so no per-collection call is needed. */
    suspend fun collections(libraryId: String, page: Int, limit: Int = 30): Page<BookCollection> = parse(
        exec(get(apiUrl("api/libraries/$libraryId/collections", "limit" to limit.toString(), "page" to page.toString()))),
        AbsParse::collectionPage,
    )

    suspend fun search(libraryId: String, q: String, limit: Int = 24): SearchResult =
        parse(exec(get(apiUrl("api/libraries/$libraryId/search", "q" to q, "limit" to limit.toString()))), AbsParse::search)

    /** The signed-in user: username plus per-item listening progress. */
    suspend fun me(): Me = parse(exec(get("/api/me")), AbsParse::me)

    /** Progress lookup for cards: time left, progress bar, finished state. */
    suspend fun progress(): ProgressMap = ProgressMap.from(me())

    suspend fun item(id: String): Book =
        parse(exec(get("/api/items/$id?expanded=1"))) { json.decodeFromString(Book.serializer(), it) }

    suspend fun play(itemId: String): PlaySession = parse(
        exec(post("/api/items/$itemId/play", buildJsonObject {
            put("deviceInfo", buildJsonObject {
                put("clientName", "Audiobookshelf TV")
                put("deviceId", "abstv-android")
            })
            put("forceDirectPlay", true)
            put("forceTranscode", false)
            put("mediaPlayer", "exoplayer")
            put("supportedMimeTypes", buildJsonArray {
                listOf("audio/mpeg", "audio/mp4", "audio/aac", "audio/flac", "audio/ogg", "audio/x-m4b")
                    .forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
            })
        }))
    ) { json.decodeFromString(PlaySession.serializer(), it) }

    suspend fun sync(sessionId: String, currentTime: Double, timeListened: Double, duration: Double) {
        exec(post("/api/session/$sessionId/sync", buildJsonObject {
            put("currentTime", currentTime)
            put("timeListened", timeListened)
            put("duration", duration)
        }))
    }

    suspend fun close(sessionId: String, currentTime: Double, timeListened: Double, duration: Double) {
        exec(post("/api/session/$sessionId/close", buildJsonObject {
            put("currentTime", currentTime)
            put("timeListened", timeListened)
            put("duration", duration)
        }))
    }

    /** Covers are public upstream, so no token (keeps it out of Coil's cache keys and logs). [width] = drawn px. */
    fun coverUrl(itemId: String, width: Int = 400) = "$baseUrl/api/items/$itemId/cover?width=$width"

    /** No `?token=`: the player's data source sends the Bearer header (and refreshes it) through [client]. */
    fun trackUrl(contentUrl: String) = "$baseUrl$contentUrl"

    companion object {
        fun normalize(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
            return s
        }
    }
}
