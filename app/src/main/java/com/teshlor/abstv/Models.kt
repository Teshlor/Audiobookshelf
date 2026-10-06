package com.teshlor.abstv

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** [hasAudio] is not part of the server's library JSON; [AbsApi.audioLibraries] fills it in. */
@Immutable @Serializable data class Library(
    val id: String,
    val name: String,
    val mediaType: String = "",
    val hasAudio: Boolean = true,
)

@Immutable @Serializable data class Metadata(
    val title: String = "",
    val subtitle: String? = null,
    val authorName: String? = null,
    /** "Last, First": the key the server sorts by when sorting by author (A-Z jump on the Library tab). */
    val authorNameLF: String? = null,
    val narratorName: String? = null,
    val description: String? = null,
    /**
     * Upstream v2.37.1 shapes: a JSON OBJECT {id,name,sequence} on series-filtered lists and series shelves
     * (set from the item's `series`), an ARRAY of {id,name,sequence} on expanded items and search hits,
     * absent on plain lists. Read it through [Book.sequence] / [Book.seriesRefs].
     */
    val series: JsonElement? = null,
)

@Immutable @Serializable data class Media(
    val metadata: Metadata = Metadata(),
    val duration: Double = 0.0,
    val coverPath: String? = null,
    /** Playable audio tracks. Present on minified and expanded items; null on search hits (use duration). */
    val numTracks: Int? = null,
    /** "epub"/"pdf" etc. when the item has an ebook file. */
    val ebookFormat: String? = null,
)

@Immutable data class SeriesRef(val id: String, val name: String, val sequence: String?)

@Immutable @Serializable data class Book(val id: String, val media: Media = Media()) {
    val title get() = media.metadata.title
    val author get() = media.metadata.authorName.orEmpty()

    /** Series entries from either the object or array shape of `metadata.series`. */
    val seriesRefs: List<SeriesRef>
        get() = when (val s = media.metadata.series) {
            is JsonObject -> listOfNotNull(s.toSeriesRef())
            is JsonArray -> s.mapNotNull { (it as? JsonObject)?.toSeriesRef() }
            else -> emptyList()
        }

    /** Position in the series, e.g. "1", "1.5", "10"; null when unknown. */
    val sequence: String? get() = seriesRefs.firstOrNull()?.sequence

    /** False for ebook-only items (no audio tracks). Falls back to duration when numTracks is absent. */
    val hasAudio: Boolean get() = media.numTracks?.let { it > 0 } ?: (media.duration > 0.0)

    /** Null when the item has no cover; show a placeholder instead of requesting one. */
    val hasCover: Boolean get() = media.coverPath != null
}

private fun JsonObject.toSeriesRef(): SeriesRef? {
    val id = (this["id"] as? JsonPrimitive)?.contentOrNull ?: return null
    return SeriesRef(
        id = id,
        name = (this["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        sequence = (this["sequence"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() },
    )
}

@Immutable @Serializable data class Series(
    val id: String,
    val name: String = "",
    val books: List<Book> = emptyList(),
)

@Immutable @Serializable data class BookCollection(
    val id: String,
    val name: String = "",
    val description: String? = null,
    val books: List<Book> = emptyList(),
)

@Immutable @Serializable data class Page<T>(
    val results: List<T> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val page: Int = 0,
)

@Serializable data class RawShelf(
    val id: String,
    val label: String = "",
    val type: String = "",
    val entities: JsonArray = JsonArray(emptyList()),
)

sealed interface Shelf {
    val id: String
    val label: String
}
@Immutable data class BookShelf(override val id: String, override val label: String, val books: List<Book>) : Shelf
@Immutable data class SeriesShelf(override val id: String, override val label: String, val series: List<Series>) : Shelf

@Immutable @Serializable data class BookMatch(val libraryItem: Book)
@Immutable @Serializable data class SeriesMatch(val series: Series, val books: List<Book> = emptyList())
@Immutable @Serializable data class NarratorMatch(val name: String, val numBooks: Int = 0)
@Immutable @Serializable data class SearchResult(
    val book: List<BookMatch> = emptyList(),
    val series: List<SeriesMatch> = emptyList(),
    val narrators: List<NarratorMatch> = emptyList(),
)

@Immutable @Serializable data class AudioTrack(
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

// ---- Listening progress (GET /api/me) ----

@Serializable data class MediaProgress(
    val libraryItemId: String? = null,
    /** Set for podcast episodes only; those entries are ignored. */
    val episodeId: String? = null,
    val progress: Double = 0.0,
    val currentTime: Double = 0.0,
    val duration: Double = 0.0,
    val isFinished: Boolean = false,
)

@Serializable data class Me(val username: String = "", val mediaProgress: List<MediaProgress> = emptyList())

enum class ProgressState { NOT_STARTED, IN_PROGRESS, FINISHED }

@Immutable data class BookProgress(
    val fraction: Float,
    val currentTime: Double,
    val duration: Double,
    val isFinished: Boolean,
) {
    val state: ProgressState
        get() = when {
            isFinished -> ProgressState.FINISHED
            currentTime > 0.0 || fraction > 0f -> ProgressState.IN_PROGRESS
            else -> ProgressState.NOT_STARTED
        }

    /** Seconds left to listen; 0 when finished. */
    val remainingSeconds: Double get() = if (isFinished) 0.0 else (duration - currentTime).coerceAtLeast(0.0)
}

/** Per-book progress for the signed-in user. A missing entry means not started. */
@Immutable class ProgressMap(private val byItemId: Map<String, BookProgress> = emptyMap()) {
    operator fun get(libraryItemId: String): BookProgress? = byItemId[libraryItemId]
    fun stateOf(libraryItemId: String): ProgressState = byItemId[libraryItemId]?.state ?: ProgressState.NOT_STARTED
    val size: Int get() = byItemId.size

    companion object {
        fun from(me: Me): ProgressMap = ProgressMap(
            me.mediaProgress
                .filter { it.libraryItemId != null && it.episodeId == null }
                .associate { mp ->
                    val frac = when {
                        mp.isFinished -> 1f
                        mp.progress > 0.0 -> mp.progress.toFloat()
                        mp.duration > 0.0 -> (mp.currentTime / mp.duration).toFloat()
                        else -> 0f
                    }.coerceIn(0f, 1f)
                    mp.libraryItemId!! to BookProgress(frac, mp.currentTime, mp.duration, mp.isFinished)
                },
        )
    }
}
