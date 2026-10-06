@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.teshlor.abstv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

// ---- Pure logic (unit tested) ----

private fun Book.sequenceNumber(): Double? = sequence?.toDoubleOrNull()

/** Series order: numeric sequence ascending (1, 1.5, 2, 10); books without a usable sequence go last, in their given order. */
fun sortBySequence(books: List<Book>): List<Book> =
    books.sortedWith(compareBy<Book> { it.sequenceNumber() == null }.thenBy { it.sequenceNumber() ?: 0.0 })

/** "Book 3" from the series sequence when the book has one, else from its 1-based position. */
fun bookLabel(book: Book, index: Int): String = "Book ${book.sequence ?: (index + 1).toString()}"

fun finishedCount(books: List<Book>, progress: ProgressMap): Int = books.count { progress.stateOf(it.id) == ProgressState.FINISHED }

/** What the Continue button plays. [index] is the book's position in the sequence-sorted list. */
data class ContinueTarget(val book: Book, val index: Int, val label: String)

/**
 * First in-progress book; else the first unstarted one ("Start" if nothing was finished yet, otherwise "Continue");
 * everything finished: "Listen again" from book 1. Null for an empty series.
 */
fun continueTarget(books: List<Book>, progress: ProgressMap): ContinueTarget? {
    if (books.isEmpty()) return null
    fun target(i: Int, verb: String) = ContinueTarget(books[i], i, "$verb · ${bookLabel(books[i], i)}")
    val inProgress = books.indexOfFirst { progress.stateOf(it.id) == ProgressState.IN_PROGRESS }
    if (inProgress >= 0) return target(inProgress, "Continue")
    val unstarted = books.indexOfFirst { progress.stateOf(it.id) == ProgressState.NOT_STARTED }
    if (unstarted >= 0) {
        val anyFinished = books.any { progress.stateOf(it.id) == ProgressState.FINISHED }
        return target(unstarted, if (anyFinished) "Continue" else "Start")
    }
    return target(0, "Listen again")
}

/** "2 of 4 finished · Book 3 in progress". */
fun seriesCaption(books: List<Book>, progress: ProgressMap): String {
    val finished = finishedCount(books, progress)
    val idx = books.indexOfFirst { progress.stateOf(it.id) == ProgressState.IN_PROGRESS }
    val base = "$finished of ${books.size} finished"
    return if (idx >= 0) "$base · ${bookLabel(books[idx], idx)} in progress" else base
}

/** Cover URLs for the three front books of the stack, drawn 100dp (200px). */
fun seriesCoverUrls(series: Series, api: AbsApi?, width: Int = 200): List<String?> =
    sortBySequence(series.books).take(3).map { api?.coverUrl(it.id, width) }

// ---- Series grid ----

@Composable
fun SeriesTab(vm: AppViewModel) {
    val c = LocalAbsColors.current
    val pager = remember { vm.seriesPager() }
    if (pager == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No library selected", color = c.muted) }
        return
    }
    val st by pager.state.collectAsState()
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val flag = remember { FocusFlag() }
    val target = remember { FocusRequester() }
    val key = vm.screen.key
    val remembered = remember { vm.lastFocused[key] }
    val targetId = remember(st.items.isNotEmpty()) {
        remembered?.takeIf { r -> st.items.any { it.id == r } } ?: st.items.firstOrNull()?.id
    }

    LaunchedEffect(gridState, pager) {
        // The header is item 0, so grid index n is series n - 1.
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { pager.onVisible(it - 1) }
    }
    EntryFocus(ready = targetId != null, target = target, flag = flag) {
        val idx = st.items.indexOfFirst { it.id == targetId }
        if (idx >= 0 && gridState.layoutInfo.visibleItemsInfo.none { it.index == idx + 1 }) gridState.scrollToItem(idx + 1)
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        state = gridState,
        modifier = Modifier.fillMaxSize().trackFocus(flag).gridRowFocus(
            gridState, 4, firstCard = 1, lastCard = { st.items.size },   // item 0 is the header
            focusedIndex = { if (flag.has) st.items.indexOfFirst { it.id == vm.lastFocused[key] }.takeIf { it >= 0 }?.plus(1) else null },
        ),
        contentPadding = PaddingValues(start = 112.dp, end = 48.dp, top = 32.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
            Column(Modifier.padding(bottom = 4.dp)) {
                Text(
                    if (st.total > 0) "SERIES · ${st.total}" else "SERIES",
                    fontSize = 12.sp, letterSpacing = 1.6.sp, color = c.muted,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Series", fontFamily = FontFamily.Serif, fontSize = 30.sp, lineHeight = 36.sp)
                    Spacer(Modifier.width(18.dp))
                    AccentRule(160)
                }
            }
        }
        itemsIndexed(st.items, key = { _, it -> it.id }, contentType = { _, _ -> "series" }) { i, s ->
            SeriesCard(
                series = s,
                covers = remember(s.id, vm.api) { seriesCoverUrls(s, vm.api) },
                finished = finishedCount(s.books, vm.progress),
                onClick = { vm.openSeries(s) },
                onFocused = {
                    vm.lastFocused[key] = s.id
                    // First row: bring the header back (the grid only scrolls far enough to show the card).
                    if (i < 4 && gridState.firstVisibleItemIndex > 0) scope.launch { gridState.awaitScrollIdle(); if (vm.lastFocused[key] == s.id) gridState.animateScrollToItem(0) }
                },
                focusRequester = if (s.id == targetId) target else null,
            )
        }
        if (!st.loadedOnce && st.error == null) {
            item(key = "loading", span = { GridItemSpan(maxLineSpan) }, contentType = "status") {
                Text("Loading…", color = c.muted)
            }
        }
        if (st.error != null) {
            item(key = "error", span = { GridItemSpan(maxLineSpan) }, contentType = "status") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (st.items.isEmpty()) "Can't reach your server" else "Couldn't load more series", fontFamily = FontFamily.Serif, fontSize = 22.sp)
                    Text(st.error.orEmpty(), color = c.error, fontSize = 13.sp)
                    AbsButton(onClick = { pager.retry() }) { Text("Try again") }
                }
            }
        }
        if (st.loadedOnce && st.error == null && st.items.isEmpty()) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }, contentType = "status") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No series yet", fontFamily = FontFamily.Serif, fontSize = 22.sp)
                    Text("Books with a series set in Audiobookshelf show up here.", color = c.muted, fontSize = 15.sp)
                    AbsButton(onClick = { pager.reset() }) { Text("Check again") }
                }
            }
        }
    }
}

/** 1dp rule that fades from accent@60% to nothing (headings). */
@Composable
fun AccentRule(widthDp: Int) {
    val c = LocalAbsColors.current
    Box(
        Modifier.width(widthDp.dp).height(1.dp).background(
            Brush.horizontalGradient(listOf(c.accent.copy(alpha = 0.6f), c.accent.copy(alpha = 0f))),
        ),
    )
}

// ---- Series detail ----

private sealed interface BooksState {
    data object Loading : BooksState
    data class Loaded(val books: List<Book>) : BooksState
    data class Failed(val message: String) : BooksState
}

@Composable
fun SeriesBooksScreen(vm: AppViewModel, screen: Screen.SeriesBooks) {
    val c = LocalAbsColors.current
    var attempt by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf<BooksState>(vm.seriesBooksCache[screen.seriesId]?.let { BooksState.Loaded(it) } ?: BooksState.Loading) }
    LaunchedEffect(screen.seriesId, attempt) {
        if (state is BooksState.Loaded) return@LaunchedEffect
        state = BooksState.Loading
        val api = vm.api
        val lib = vm.selectedLibrary
        if (api == null || lib == null) { state = BooksState.Failed("Not signed in"); return@LaunchedEffect }
        state = try {
            val books = sortBySequence(api.seriesBooks(lib.id, screen.seriesId))
            vm.seriesBooksCache[screen.seriesId] = books
            BooksState.Loaded(books)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            BooksState.Failed(e.message ?: e.toString())
        }
    }

    val s = state
    when (s) {
        is BooksState.Loaded -> SeriesDetail(vm, screen, s.books)
        else -> Column(Modifier.fillMaxSize().padding(start = 112.dp, top = 40.dp, end = 48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("SERIES", fontSize = 12.sp, letterSpacing = 1.6.sp, color = c.muted)
            Text(screen.name, fontFamily = FontFamily.Serif, fontSize = 26.sp, lineHeight = 32.sp)
            if (s is BooksState.Failed) {
                val retry = remember { FocusRequester() }
                LaunchedEffect(Unit) { retry.requestWhenReady() }
                Text("Couldn't load this series", fontSize = 15.sp)
                Text(s.message, color = c.error, fontSize = 13.sp)
                AbsButton(onClick = { attempt++ }, modifier = Modifier.focusRequester(retry)) { Text("Try again") }
            } else {
                Text("Loading…", color = c.muted)
            }
        }
    }
}

@Composable
private fun SeriesDetail(vm: AppViewModel, screen: Screen.SeriesBooks, books: List<Book>) {
    val c = LocalAbsColors.current
    val progress = vm.progress
    val key = screen.key
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val flag = remember { FocusFlag() }
    val button = remember { FocusRequester() }
    val rowTarget = remember { FocusRequester() }
    val restoreId = remember { vm.lastFocused[key]?.takeIf { r -> books.any { it.id == r } } }
    val ct = continueTarget(books, progress)
    val author = books.firstNotNullOfOrNull { it.author.ifEmpty { null } }
    val totalDuration = books.sumOf { it.media.duration }
    val current = ct?.book?.id?.takeIf { progress.stateOf(it) != ProgressState.FINISHED }

    // Initial focus: the Continue button; after Detail -> Back, the row that was focused.
    EntryFocus(ready = books.isNotEmpty(), target = if (restoreId != null) rowTarget else button, flag = flag) {
        val idx = books.indexOfFirst { it.id == restoreId }
        if (idx >= 0 && listState.layoutInfo.visibleItemsInfo.none { it.index == idx + 1 }) listState.scrollToItem(idx + 1)
    }

    Row(
        Modifier.fillMaxSize().padding(start = 112.dp, top = 40.dp, end = 48.dp).trackFocus(flag),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        Column(Modifier.width(240.dp)) {
            CoverStack(
                covers = remember(books, vm.api) { books.take(3).map { vm.api?.coverUrl(it.id, 320) } },
                front = 160.dp, step = 28.dp, shrink = 16.dp,
                modifier = Modifier.size(240.dp, 160.dp), trayBg = Color.Transparent,
            )
            Spacer(Modifier.height(16.dp))
            Text("SERIES", fontSize = 12.sp, letterSpacing = 1.6.sp, color = c.muted)
            Text(screen.name, fontFamily = FontFamily.Serif, fontSize = 26.sp, lineHeight = 32.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (author != null) Text(author, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${seriesSubtitle(books.size, 0)} · ${durationLabel(totalDuration)}",
                fontSize = 13.sp, color = c.muted,
            )
            Spacer(Modifier.height(10.dp))
            Row(Modifier.width(240.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                books.forEach { b ->
                    val p = progress[b.id]
                    val frac = when (progress.stateOf(b.id)) {
                        ProgressState.FINISHED -> 1f
                        ProgressState.IN_PROGRESS -> p?.fraction ?: 0f
                        ProgressState.NOT_STARTED -> 0f
                    }
                    Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.trackColor)) {
                        if (frac > 0f) Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).fillMaxHeight().background(c.accent))
                    }
                }
            }
            Text(seriesCaption(books, progress), fontSize = 13.sp, color = c.muted, modifier = Modifier.padding(top = 8.dp))
            if (ct != null) {
                Spacer(Modifier.height(14.dp))
                AbsButton(onClick = { vm.playBook(ct.book) }, modifier = Modifier.focusRequester(button)) {
                    Text(if (vm.loading) "Starting…" else "▶ ${ct.label}", maxLines = 1)
                }
            }
        }
        LazyColumn(
            Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "title", contentType = "title") { SectionTitle("Reading order", Modifier.padding(bottom = 4.dp)) }
            itemsIndexed(books, key = { _, b -> b.id }, contentType = { _, _ -> "row" }) { i, b ->
                BookRow(
                    book = b, index = i, progress = progress[b.id], coverUrl = vm.api?.coverUrl(b.id, 112),
                    isCurrent = b.id == current,
                    onClick = { vm.openBook(b) },
                    onFocused = {
                        vm.lastFocused[key] = b.id
                        if (i == 0 && listState.firstVisibleItemIndex > 0) scope.launch { listState.awaitScrollIdle(); if (vm.lastFocused[key] == b.id) listState.animateScrollToItem(0) }
                    },
                    focusRequester = if (b.id == restoreId) rowTarget else null,
                )
            }
        }
    }
}

/** Reading-order row (528x72): number, thumbnail, title and "Book 2 · 33h 10m", then finished / time left / not started. */
@Composable
private fun BookRow(
    book: Book,
    index: Int,
    progress: BookProgress?,
    coverUrl: String?,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    focusRequester: FocusRequester?,
) {
    val c = LocalAbsColors.current
    var focused by remember { mutableStateOf(false) }
    val state = progress?.state ?: ProgressState.NOT_STARTED
    val statusText = when (state) {
        ProgressState.FINISHED -> "Finished"
        ProgressState.IN_PROGRESS -> progress?.let { timeLeftLabel(it.remainingSeconds) }.orEmpty()
        ProgressState.NOT_STARTED -> "Not started"
    }
    val desc = "${bookLabel(book, index)}, ${book.title}" +
        (if (book.media.duration > 0) ", ${durationLabel(book.media.duration)}" else "") + ", " + statusText.lowercase()
    val number = book.sequence ?: (index + 1).toString()
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth().height(72.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .returnTarget().onFocusChanged { focused = it.hasFocus; if (it.hasFocus) onFocused() }
            .semantics { contentDescription = desc },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isCurrent) c.currentRowColor else Color.Transparent,
            contentColor = c.onSurface,
            focusedContainerColor = c.accent, focusedContentColor = c.onAccent,
            pressedContainerColor = c.accent, pressedContentColor = c.onAccent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(Modifier.fillMaxSize().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "#$number", fontFamily = FontFamily.Serif, fontSize = 20.sp, maxLines = 1, softWrap = false,
                color = if (focused) c.onAccent else if (isCurrent) c.accent else c.muted,
                modifier = Modifier.width(52.dp).padding(start = 8.dp),
            )
            if (coverUrl != null) {
                AsyncImage(
                    model = coverUrl, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).background(c.placeholderColor),
                )
            } else {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(6.dp)).background(c.placeholderColor))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(book.title, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(bookLabel(book, index), book.media.duration.takeIf { it > 0 }?.let { durationLabel(it) }).joinToString(" · ")
                Text(meta, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, color = LocalContentColor.current.copy(alpha = 0.72f))
            }
            Column(horizontalAlignment = Alignment.End) {
                when (state) {
                    ProgressState.FINISHED -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(NavIcons.check, contentDescription = null, tint = if (focused) c.onAccent else c.accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Finished", fontSize = 13.sp)
                    }
                    ProgressState.IN_PROGRESS -> {
                        Box(
                            Modifier.width(96.dp).height(4.dp).clip(RoundedCornerShape(2.dp))
                                .background(if (focused) c.onAccent.copy(alpha = 0.30f) else c.trackColor),
                        ) {
                            Box(Modifier.fillMaxWidth((progress?.fraction ?: 0f).coerceIn(0f, 1f)).fillMaxHeight().background(if (focused) c.onAccent else c.accent))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(statusText, fontSize = 13.sp, maxLines = 1)
                    }
                    ProgressState.NOT_STARTED -> Text(statusText, fontSize = 13.sp, color = LocalContentColor.current.copy(alpha = 0.72f))
                }
            }
        }
    }
}
