package com.teshlor.abstv

import java.text.Normalizer

/**
 * Pure logic for the Library tab (sort, filter, A-Z jump), kept free of Compose so it is unit-testable.
 *
 * A-Z jump, how it works: the server pages the list, and ABS has no "starts with" filter, so a letter's position
 * is only known once the pages up to it are loaded. [firstIndexForLetter] scans the loaded books in list order and
 * returns the first book of that letter (or, if the letter has no books, the first book past it). If the scan runs
 * off the end of what is loaded it answers null and the caller loads the next page and asks again. At most
 * ceil(total / 60) requests, all of which the grid would load anyway when scrolled.
 */
enum class LibrarySort(
    val label: String,
    /** Value of the server's `sort` query parameter. */
    val apiKey: String,
    /** Direction used when the user switches to this sort. */
    val defaultDesc: Boolean,
    /** True when the A-Z strip makes sense (the list is ordered by a name). */
    val byLetter: Boolean,
) {
    TITLE("Title", "media.metadata.title", false, true),
    AUTHOR("Author", "media.metadata.authorNameLF", false, true),
    ADDED("Recently added", "addedAt", true, false),
    YEAR("Year", "media.metadata.publishedYear", true, false),
    DURATION("Duration", "media.duration", true, false),
}

data class SortSpec(val sort: LibrarySort = LibrarySort.TITLE, val desc: Boolean = false) {
    /** Stored under `library_sort` in the "abs" prefs, e.g. "ADDED:desc". */
    fun encode(): String = "${sort.name}:${if (desc) "desc" else "asc"}"

    fun flipped() = copy(desc = !desc)

    companion object {
        /** Unknown, missing or malformed values fall back to Title ascending. */
        fun decode(raw: String?): SortSpec {
            val parts = raw?.split(':') ?: return SortSpec()
            val sort = LibrarySort.entries.firstOrNull { it.name == parts.getOrNull(0) } ?: return SortSpec()
            return when (parts.getOrNull(1)) {
                "asc" -> SortSpec(sort, false)
                "desc" -> SortSpec(sort, true)
                else -> SortSpec(sort, sort.defaultDesc)
            }
        }
    }
}

enum class LibraryFilter(val label: String, private val value: String?) {
    ALL("All", null),
    IN_PROGRESS("In progress", "in-progress"),
    FINISHED("Finished", "finished"),
    NOT_STARTED("Not started", "not-started");

    /** The server's `filter` query value, or null for no filter. */
    val apiFilter: String? get() = value?.let { progressFilter(it) }
}

/** "AUDIOBOOKS · IN PROGRESS · 12 BOOKS". The count is omitted until the first page has arrived. */
fun libraryOverline(libraryName: String, filter: LibraryFilter, total: Int, loaded: Boolean): String {
    val parts = mutableListOf(libraryName.ifBlank { "Library" })
    if (filter != LibraryFilter.ALL) parts += filter.label
    if (loaded) parts += "$total ${if (total == 1) "book" else "books"}"
    return parts.joinToString(" · ").uppercase()
}

const val JUMP_LETTERS = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ"

/**
 * The server's "ignore prefixes when sorting" setting (`serverSettings.sortingIgnorePrefix` / `sortingPrefixes`).
 * Off by default, which is also what we assume when the settings can't be read.
 */
data class SortingSettings(val ignorePrefix: Boolean = false, val prefixes: List<String> = listOf("the", "a")) {
    companion object { val OFF = SortingSettings() }
}

/** The title as the server files it: with ignore-prefix on, a leading "<prefix> " (server's list) is dropped. */
fun sortKeyTitle(title: String, settings: SortingSettings): String {
    val t = title.trim()
    if (!settings.ignorePrefix) return t
    for (p in settings.prefixes) {
        val prefix = p.trim()
        if (prefix.isEmpty()) continue
        if (t.length > prefix.length && t.startsWith(prefix, ignoreCase = true) && t[prefix.length].isWhitespace()) {
            val rest = t.substring(prefix.length).trim()
            if (rest.isNotEmpty()) return rest
        }
    }
    return t
}

/** A-Z for letters (accents folded: "Émile" is E), '#' for digits, symbols, other scripts and blanks. */
fun letterOf(text: String): Char {
    val first = text.trim().firstOrNull { it.isLetterOrDigit() } ?: return '#'
    val base = Normalizer.normalize(first.toString(), Normalizer.Form.NFD).first().uppercaseChar()
    return if (base in 'A'..'Z') base else '#'
}

/**
 * The letter a book is filed under in the current ordering. Title sort: the first letter of the title, after dropping
 * a leading prefix only when the server is set to ignore them ([settings]); otherwise the raw first letter, exactly as
 * the server sorts. Author sort: the first letter of "Last, First".
 */
fun letterKey(book: Book, sort: LibrarySort, settings: SortingSettings = SortingSettings.OFF): Char = when (sort) {
    LibrarySort.AUTHOR -> letterOf(book.media.metadata.authorNameLF?.takeIf { it.isNotBlank() } ?: book.author)
    else -> letterOf(sortKeyTitle(book.title, settings))
}

/**
 * Index in [books] (already in list order) of the first book of [letter], or of the first book past it when the
 * letter has none. Returns null when more pages must be loaded to decide ([complete] = false), and the last index
 * (letters beyond the final book) once everything is loaded. Null for an empty list.
 */
fun firstIndexForLetter(
    books: List<Book>, letter: Char, spec: SortSpec, complete: Boolean, settings: SortingSettings = SortingSettings.OFF,
): Int? {
    books.forEachIndexed { i, b ->
        val k = letterKey(b, spec.sort, settings)
        if (k == letter) return i
        if (if (spec.desc) k < letter else k > letter) return i
    }
    return if (complete) books.lastIndex.takeIf { it >= 0 } else null
}
