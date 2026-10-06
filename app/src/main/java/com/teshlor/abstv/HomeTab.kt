@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.teshlor.abstv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

private val HomeStart = 112.dp

/** Server shelf id for Continue Listening; shown as hero cards with time left. */
private const val CONTINUE_LISTENING = "continue-listening"
private const val CONTINUE_SERIES = "continue-series"

/** Focus identity of a card: the same book can sit on several shelves, so the shelf id is part of it. */
fun focusKey(shelfId: String, itemId: String) = "$shelfId/$itemId"

/** The card to focus: the remembered one if it still exists, else the first card of the first shelf. */
fun homeFocusTarget(shelves: List<Shelf>, remembered: String?): String? {
    fun keys(s: Shelf): List<String> = when (s) {
        is BookShelf -> s.books.map { focusKey(s.id, it.id) }
        is SeriesShelf -> s.series.map { focusKey(s.id, it.id) }
    }
    val all = shelves.flatMap { keys(it) }
    return remembered?.takeIf { it in all } ?: all.firstOrNull()
}

@Composable
fun HomeScreen(vm: AppViewModel) {
    val c = LocalAbsColors.current
    val side = Modifier.padding(start = HomeStart, end = 48.dp)
    val shelves = vm.shelves
    val key = vm.screen.key
    val listState = rememberLazyListState()
    val flag = remember { FocusFlag() }
    val target = remember { FocusRequester() }
    var restoreKey by remember { mutableStateOf<String?>(null) }

    // Decided once, when the first shelves arrive for this screen entry; refreshes never move focus (see EntryFocus).
    val remembered = remember { vm.lastFocused[key] }
    val targetKey = remember(shelves.isNotEmpty()) { homeFocusTarget(shelves, remembered) }
    val showError = vm.error != null
    val showStatus = showError || shelves.isEmpty()
    val rowBase = 1 + if (showStatus) 1 else 0 // header (+ status) come before the shelf title/row pairs

    EntryFocus(ready = targetKey != null, target = target, flag = flag) {
        val shelfIdx = shelves.indexOfFirst { s -> targetKey != null && targetKey.startsWith(s.id + "/") }
        if (shelfIdx > 0 && targetKey == remembered) {
            restoreKey = targetKey
            listState.scrollToItem(rowBase + shelfIdx * 2)
        }
    }

    LazyColumn(
        Modifier.fillMaxSize().trackFocus(flag),
        state = listState,
        contentPadding = PaddingValues(top = 32.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "header", contentType = "header") {
            Column(side) {
                Text(
                    (vm.selectedLibrary?.name ?: "Audiobooks").uppercase(),
                    fontSize = 12.sp, letterSpacing = 1.6.sp, color = c.muted,
                )
                Text("Home", fontFamily = FontFamily.Serif, fontSize = 30.sp, lineHeight = 36.sp)
            }
        }
        if (showStatus) {
            item(key = "status", contentType = "status") {
                Column(side, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        vm.error != null -> {
                            Text(vm.error.orEmpty(), color = c.error)
                            AbsButton(onClick = { vm.loadHome() }) { Text("Try again") }
                        }
                        vm.loading -> Text("Loading…", color = c.muted)
                        else -> Text("No books to show yet. Add some in Audiobookshelf, then refresh from Settings.", color = c.muted)
                    }
                }
            }
        }
        for ((shelfIndex, shelf) in shelves.withIndex()) {
            item(key = "title:${shelf.id}", contentType = "title") { SectionTitle(shelf.label, side) }
            item(key = "row:${shelf.id}", contentType = "row") {
                ShelfRow(vm, shelf, targetKey, target, restoreKey, listState) {
                    // Heading (and, for the first shelf, the Home header) back into view when focus enters the row.
                    listState.animateScrollToItem(if (shelfIndex == 0) 0 else rowBase + shelfIndex * 2)
                }
            }
        }
    }
}

@Composable
private fun ShelfRow(
    vm: AppViewModel, shelf: Shelf, targetKey: String?, target: FocusRequester, restoreKey: String?,
    outerState: LazyListState,
    onEnter: suspend () -> Unit,
) {
    val rowState: LazyListState = rememberLazyListState()
    val screenKey = vm.screen.key
    val api = vm.api
    // After Detail -> Back the remembered card may be off-screen in its row; bring it into composition first.
    LaunchedEffect(restoreKey) {
        val k = restoreKey ?: return@LaunchedEffect
        val idx = when (shelf) {
            is BookShelf -> shelf.books.indexOfFirst { focusKey(shelf.id, it.id) == k }
            is SeriesShelf -> shelf.series.indexOfFirst { focusKey(shelf.id, it.id) == k }
        }
        if (idx >= 0 && rowState.layoutInfo.visibleItemsInfo.none { it.index == idx }) rowState.scrollToItem(idx)
    }
    // Rows bleed under the rail (start padding) so scaled cards aren't clipped at the edges.
    LazyRow(
        modifier = Modifier.onFocusEntered(outerState, onEnter).snapBackToStart(rowState),
        state = rowState,
        contentPadding = PaddingValues(start = HomeStart, end = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(if (shelf.id == CONTINUE_LISTENING) 20.dp else 16.dp),
    ) {
        when (shelf) {
            is BookShelf -> items(shelf.books, key = { it.id }, contentType = { shelf.id == CONTINUE_LISTENING }) { b ->
                val fk = focusKey(shelf.id, b.id)
                val req = if (fk == targetKey) target else null
                if (shelf.id == CONTINUE_LISTENING) {
                    HeroBookCard(
                        b, api?.coverUrl(b.id, 288), vm.progress[b.id],
                        { vm.openBook(b) }, { vm.lastFocused[screenKey] = fk }, focusRequester = req,
                    )
                } else {
                    BookCard(
                        b, api?.bookCoverUrl(b.id), { vm.openBook(b) }, { vm.lastFocused[screenKey] = fk }, focusRequester = req,
                        progress = vm.progress[b.id],
                        // Continue Series shows each book's place in its series ("#3").
                        sequenceBadge = if (shelf.id == CONTINUE_SERIES) b.sequence?.let { "#$it" } else null,
                    )
                }
            }
            is SeriesShelf -> items(shelf.series, key = { it.id }, contentType = { "series" }) { s ->
                val fk = focusKey(shelf.id, s.id)
                SeriesCard(
                    series = s,
                    covers = remember(s.id, api) { seriesCoverUrls(s, api) },
                    finished = finishedCount(s.books, vm.progress),
                    onClick = { vm.openSeries(s) },
                    onFocused = { vm.lastFocused[screenKey] = fk },
                    focusRequester = if (fk == targetKey) target else null,
                )
            }
        }
    }
}
