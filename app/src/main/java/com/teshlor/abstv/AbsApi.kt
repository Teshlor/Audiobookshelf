package com.teshlor.abstv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

@Serializable data class Library(val id: String, val name: String, val mediaType: String = "")
@Serializable data class Metadata(
    val title: String = "",
    val authorName: String? = null,
    val description: String? = null,
)
@Serializable data class Media(val metadata: Metadata = Metadata(), val duration: Double = 0.0)
@Serializable data class Book(val id: String, val media: Media = Media()) {
    val title get() = media.metadata.title
    val author get() = media.metadata.authorName.orEmpty()
}
@Serializable data class AudioTrack(
    val startOffset: Double = 0.0,
    val duration: Double = 0.0,
    val contentUrl: String,
)
@Serializable data class PlaySession(
    val id: String,
    val startTime: Double = 0.0,
    val duration: Double = 0.0,
    val displayTitle: String = "",
    val displayAuthor: String = "",
    val audioTracks: List<AudioTrack> = emptyList(),
)

class AbsApi(serverUrl: String, val token: String = "") {
    val baseUrl: String = normalize(serverUrl)

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val b = chain.request().newBuilder()
            if (token.isNotEmpty()) b.header("Authorization", "Bearer $token")
            chain.proceed(b.build())
        }
        .build()

    private suspend fun exec(req: Request): String = withContext(Dispatchers.IO) {
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Server returned HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
    }

    private fun get(path: String) = Request.Builder().url(baseUrl + path).build()
    private fun post(path: String, body: JsonObject) = Request.Builder()
        .url(baseUrl + path)
        .post(body.toString().toRequestBody("application/json".toMediaType()))
        .build()

    /** Returns the user's API token. */
    suspend fun login(username: String, password: String): String {
        val body = exec(post("/login", buildJsonObject {
            put("username", username)
            put("password", password)
        }))
        val user = json.parseToJsonElement(body).jsonObject["user"]?.jsonObject
        return user?.get("token")?.jsonPrimitive?.content
            ?: throw IOException("Login response had no token")
    }

    /** Parses off the main thread: callers run on Main (viewModelScope) and the library JSON is large. */
    private suspend fun <T> parse(body: String, block: (String) -> T): T =
        withContext(Dispatchers.Default) { block(body) }

    suspend fun libraries(): List<Library> = parse(exec(get("/api/libraries"))) { body ->
        val arr = json.parseToJsonElement(body).jsonObject["libraries"]!!.jsonArray
        json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(Library.serializer()), arr)
    }

    suspend fun items(libraryId: String): List<Book> =
        parse(exec(get("/api/libraries/$libraryId/items?limit=500&sort=media.metadata.title"))) { body ->
            val arr = json.parseToJsonElement(body).jsonObject["results"]!!.jsonArray
            json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(Book.serializer()), arr)
        }

    suspend fun continueListening(libraryId: String): List<Book> =
        parse(exec(get("/api/libraries/$libraryId/personalized"))) { body ->
            val shelves = json.parseToJsonElement(body).jsonArray
            val shelf = shelves.firstOrNull { it.jsonObject["id"]?.jsonPrimitive?.content == "continue-listening" }
            val entities = shelf?.jsonObject?.get("entities")?.jsonArray
            if (entities == null) emptyList()
            else json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(Book.serializer()), entities)
        }

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

    fun coverUrl(itemId: String) = "$baseUrl/api/items/$itemId/cover?width=400&token=$token"

    fun trackUrl(contentUrl: String): String {
        val sep = if (contentUrl.contains('?')) '&' else '?'
        return "$baseUrl$contentUrl${sep}token=$token"
    }

    companion object {
        fun normalize(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
            return s
        }
    }
}
