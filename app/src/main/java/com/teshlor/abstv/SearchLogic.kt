package com.teshlor.abstv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Pure helpers for the Search tab (no Android, no Compose), so they can be unit-tested.

const val MIN_QUERY_LENGTH = 2
const val SEARCH_DEBOUNCE_MS = 350L
const val MAX_RECENT_SEARCHES = 8

/** Trims and collapses inner whitespace, so "  the   way " searches as "the way". */
fun normalizeQuery(q: String): String = q.trim().replace(Regex("\\s+"), " ")

/** Live search starts from 2 characters. */
fun isSearchable(q: String): Boolean = normalizeQuery(q).length >= MIN_QUERY_LENGTH

/** Recent searches, newest first, de-duplicated without regard to case, capped at [MAX_RECENT_SEARCHES]. */
object RecentSearches {
    fun add(list: List<String>, query: String, max: Int = MAX_RECENT_SEARCHES): List<String> {
        val q = normalizeQuery(query)
        if (!isSearchable(q)) return list
        return (listOf(q) + list.filterNot { it.equals(q, ignoreCase = true) }).take(max)
    }

    /** One query per line; [add] already strips line breaks via [normalizeQuery]. */
    fun encode(list: List<String>): String = list.joinToString("\n")

    fun decode(raw: String?, max: Int = MAX_RECENT_SEARCHES): List<String> =
        raw.orEmpty().split('\n').map { normalizeQuery(it) }.filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }.take(max)
}

/**
 * Ranges (inclusive indices into [text]) to highlight for [query]. Every case-insensitive occurrence of the whole
 * query wins; when the whole query isn't there (the server also matches word by word) each word is highlighted
 * instead. Overlapping and adjacent ranges are merged.
 */
fun highlightRanges(text: String, query: String): List<IntRange> {
    val q = normalizeQuery(query)
    if (q.isEmpty() || text.isEmpty()) return emptyList()
    var ranges = occurrences(text, q)
    if (ranges.isEmpty()) ranges = q.split(' ').filter { it.isNotEmpty() }.flatMap { occurrences(text, it) }
    return merge(ranges)
}

private fun occurrences(text: String, needle: String): List<IntRange> {
    val out = mutableListOf<IntRange>()
    var from = 0
    while (true) {
        val i = text.indexOf(needle, from, ignoreCase = true)
        if (i < 0) break
        out += i until i + needle.length
        from = i + needle.length
    }
    return out
}

private fun merge(ranges: List<IntRange>): List<IntRange> {
    val sorted = ranges.sortedBy { it.first }
    val out = mutableListOf<IntRange>()
    for (r in sorted) {
        val last = out.lastOrNull()
        if (last != null && r.first <= last.last + 1) out[out.lastIndex] = last.first..maxOf(last.last, r.last)
        else out += r
    }
    return out
}

/** Request counter: only the response whose id is still current may be shown; older ones are stale and dropped. */
class RequestCounter {
    private var latest = 0
    fun next(): Int = ++latest
    fun isCurrent(id: Int): Boolean = id == latest

    /** Makes every in-flight request stale (query cleared, library switched). */
    fun invalidate() { latest++ }
}

/** Runs only the last submitted block, after [delayMs] of quiet. A new submit cancels the pending or running one. */
class Debouncer(private val scope: CoroutineScope, private val delayMs: Long = SEARCH_DEBOUNCE_MS) {
    private var job: Job? = null

    fun submit(block: suspend () -> Unit) {
        job?.cancel()
        job = scope.launch { delay(delayMs); block() }
    }

    fun cancel() { job?.cancel(); job = null }
}

/** Up to two letters for the narrator avatar: "Rob Inglis" -> "RI". */
fun initialsOf(name: String): String =
    name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }
