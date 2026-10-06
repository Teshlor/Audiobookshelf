@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.teshlor.abstv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

// ---------------------------------------------------------------------------------------------------------
// Pure logic (unit-tested in CollectionsLogicTest)
// ---------------------------------------------------------------------------------------------------------

/** One of the two small tiles in the card mosaic's right column. */
sealed interface MosaicTile {
    data class Cover(val book: Book) : MosaicTile
    /** The "+N" tile: how many books are not shown. */
    data class More(val count: Int) : MosaicTile
    /** Outlined empty tile (a two-book collection). */
    data object Empty : MosaicTile
}

/**
 * The cover mosaic on a collection card (HANDOFF 5.4): a lead cover plus a right column of two small tiles.
 * 0 books: nothing. 1 book: the lead only (drawn centred). 2: lead, second cover, empty outline.
 * 3: lead and two covers. 4 or more: lead, second cover, "+N" with N = books - 2.
 */
data class CollectionMosaic(val lead: Book?, val top: MosaicTile?, val bottom: MosaicTile?) {
    val hasColumn: Boolean get() = top != null
}

fun collectionMosaic(books: List<Book>): CollectionMosaic = when (books.size) {
    0 -> CollectionMosaic(null, null, null)
    1 -> CollectionMosaic(books[0], null, null)
    2 -> CollectionMosaic(books[0], MosaicTile.Cover(books[1]), MosaicTile.Empty)
    3 -> CollectionMosaic(books[0], MosaicTile.Cover(books[1]), MosaicTile.Cover(books[2]))
    else -> CollectionMosaic(books[0], MosaicTile.Cover(books[1]), MosaicTile.More(books.size - 2))
}

/** The 2x2 mosaic on the detail panel: the first four books in collection order; missing slots are null. */
fun detailMosaic(books: List<Book>): List<Book?> = List(4) { books.getOrNull(it) }

fun bookCountLabel(n: Int) = if (n == 1) "1 book" else "$n books"

/** "102h 46m", "46m", "0m". Hours are not wrapped into days. */
fun formatHoursMinutes(seconds: Double): String {
    val totalMin = (seconds.coerceAtLeast(0.0) / 60.0).toLong()
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

/** "8 books · 102h 46m"; the duration is left out when no book reports one. */
fun collectionSummary(books: List<Book>): String {
    val total = books.sumOf { it.media.duration }
    return if (total > 0.0) "${bookCountLabel(books.size)} · ${formatHoursMinutes(total)}" else bookCountLabel(books.size)
}

/** "2 of 8 finished · 1 in progress"; null when the user has not started any of them. */
fun progressCaption(books: List<Book>, progress: ProgressMap): String? {
    var finished = 0
    var inProgress = 0
    for (b in books) when (progress.stateOf(b.id)) {
        ProgressState.FINISHED -> finished++
        ProgressState.IN_PROGRESS -> inProgress++
        ProgressState.NOT_STARTED -> Unit
    }
    if (finished == 0 && inProgress == 0) return null
    return buildString {
        append("$finished of ${books.size} finished")
        if (inProgress > 0) append(" · $inProgress in progress")
    }
}

private val htmlTag = Regex("<[^>]*>")
fun plainDescription(raw: String?): String = raw.orEmpty().replace(htmlTag, "").trim()

fun collectionsOverline(libraryName: String?, total: Int): String {
    val lib = (libraryName ?: "Audiobooks").uppercase()
    return if (total > 0) "$lib · $total ${if (total == 1) "COLLECTION" else "COLLECTIONS"}" else "$lib · COLLECTIONS"
}

// ---------------------------------------------------------------------------------------------------------
// Derived colours (HANDOFF section 7)
// ---------------------------------------------------------------------------------------------------------

private val AbsColors.tile get() = lerp(bg, surface, 0.55f)
private val AbsColors.skeleton get() = lerp(bg, surface, 0.45f)
private val AbsColors.placeholder get() = lerp(bg, surface, 0.75f)
private val AbsColors.outline get() = lerp(bg, onSurface, 0.22f)

private val ContentStart = 112.dp
private const val CardColumns = 4
private const val DetailColumns = 3

// ---------------------------------------------------------------------------------------------------------
// Collections grid
// ---------------------------------------------------------------------------------------------------------

@Composable
fun CollectionsTab(vm: AppViewModel) {
    val c = LocalAbsColors.current
    // Created lazily and cached by the view model per library; drops on Refresh, library switch and logout.
    val pager = remember(vm.selectedLibrary?.id, vm.stateEpoch) { vm.collectionsPager() }
    if (pager == null) {
        Column(Modifier.fillMaxSize().padding(start = ContentStart, top = 32.dp, end = 48.dp)) {
            PageHeader(collectionsOverline(vm.selectedLibrary?.name, 0))
            SkeletonRows()
        }
        return
    }
    val st by pager.state.collectAsState()
    val items = remember(st.items) { st.items.distinctBy { it.id } } // paging across a server-side change can repeat an id

    when {
        st.items.isEmpty() && st.error != null -> MessageBlock(
            icon = Icons.Filled.Warning,
            title = "Can't reach your server",
            body = "${vm.api?.baseUrl?.substringAfter("://").orEmpty()} didn't answer. Check that the server is on and the TV is on the same network, then try again.",
            primaryLabel = "Try again", onPrimary = { pager.retry() },
            secondaryLabel = "Settings", onSecondary = { vm.selectTab(Tab.SETTINGS) },
        )
        !st.loadedOnce -> Column(Modifier.fillMaxSize().padding(start = ContentStart, top = 32.dp, end = 48.dp)) {
            PageHeader(collectionsOverline(vm.selectedLibrary?.name, 0))
            SkeletonRows()
        }
        items.isEmpty() -> MessageBlock(
            icon = NavIcons.forTab(Tab.COLLECTIONS, true),
            title = "No collections yet",
            body = "Make one in Audiobookshelf on the web: open a book and choose Add to collection. It shows up here next time you open this tab.",
            primaryLabel = "Check again", onPrimary = { pager.reset() },
        )
        else -> CollectionsGrid(vm, pager, items, st.total, st.error != null, c)
    }
}

@Composable
private fun CollectionsGrid(
    vm: AppViewModel,
    pager: Pager<BookCollection>,
    items: List<BookCollection>,
    total: Int,
    stale: Boolean,
    c: AbsColors,
) {
    val gridState = rememberLazyGridState()
    val remembered = vm.lastFocused[vm.screen.key]
    // Chosen once, when the first page arrives: the card the user last had (after Back) or the first one.
    val targetId = remember(items.isNotEmpty()) { items.firstOrNull { it.id == remembered }?.id ?: items.firstOrNull()?.id }
    val target = remember { FocusRequester() }

    LaunchedEffect(gridState, pager) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { pager.onVisible(it - 1) } // minus the header item
    }
    LaunchedEffect(targetId) {
        val id = targetId ?: return@LaunchedEffect
        // Back from a collection: only scroll when the card is off-screen, so the restored position stays put.
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
        if (gridState.layoutInfo.visibleItemsInfo.none { it.key == id }) {
            val idx = items.indexOfFirst { it.id == id }
            if (idx >= 0) gridState.scrollToItem(idx + 1)
        }
        target.requestWhenReady()
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(CardColumns),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = ContentStart, top = 32.dp, end = 48.dp, bottom = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
            Column {
                PageHeader(collectionsOverline(vm.selectedLibrary?.name, total))
                Spacer(Modifier.height(16.dp))
            }
        }
        items(items, key = { it.id }, contentType = { "collection" }) { col ->
            CollectionCard(
                col, vm.api,
                onClick = { vm.openCollection(col) },
                onFocused = { vm.lastFocused[vm.screen.key] = col.id },
                focusRequester = if (col.id == targetId) target else null,
            )
        }
        if (stale) {
            item(key = "stale", span = { GridItemSpan(maxLineSpan) }, contentType = "stale") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Couldn't load more. Showing what was loaded earlier.", fontSize = 13.sp, color = c.muted)
                    AbsButton(onClick = { pager.retry() }) { Text("Try again") }
                }
            }
        }
    }
}

/** Overline, serif title and the 160dp accent rule (HANDOFF 5.1). The grid starts 112dp from the top. */
@Composable
private fun PageHeader(overline: String) {
    val c = LocalAbsColors.current
    Column {
        Text(overline, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Medium, color = c.muted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Collections", fontFamily = FontFamily.Serif, fontSize = 30.sp, lineHeight = 36.sp)
            Spacer(Modifier.width(18.dp))
            Box(
                Modifier.width(160.dp).height(1.dp).background(
                    Brush.horizontalGradient(listOf(c.accent.copy(alpha = 0.6f), c.accent.copy(alpha = 0f))),
                ),
            )
        }
    }
}

/** Static placeholders (no shimmer) while the first page loads. */
@Composable
private fun SkeletonRows() {
    val c = LocalAbsColors.current
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                repeat(CardColumns) {
                    Column(Modifier.size(188.dp, 176.dp).clip(RoundedCornerShape(10.dp)).background(c.tile)) {
                        Spacer(Modifier.weight(1f))
                        Box(Modifier.fillMaxWidth().height(48.dp).background(c.skeleton))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------
// Collection card
// ---------------------------------------------------------------------------------------------------------

@Composable
private fun CollectionCard(
    collection: BookCollection,
    api: AbsApi?,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    focusRequester: FocusRequester?,
) {
    val c = LocalAbsColors.current
    val count = bookCountLabel(collection.books.size)
    Card(
        onClick = onClick,
        modifier = Modifier
            .width(188.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { if (it.hasFocus) onFocused() }
            .semantics { contentDescription = "${collection.name}, $count" },
        shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
        colors = CardDefaults.colors(
            containerColor = c.surface,
            contentColor = c.onSurface,
            focusedContainerColor = c.accent,
            focusedContentColor = c.onAccent,
            pressedContainerColor = c.accent,
            pressedContentColor = c.onAccent,
        ),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, c.focusBorder), shape = RoundedCornerShape(10.dp))),
        scale = CardDefaults.scale(focusedScale = 1.05f),
    ) {
        Column {
            Box(Modifier.size(188.dp, 128.dp).background(c.tile), contentAlignment = Alignment.Center) {
                CardMosaic(collectionMosaic(collection.books), api)
            }
            Column(
                Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(collection.name, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(count, fontSize = 12.sp, lineHeight = 16.sp, color = LocalContentColor.current.copy(alpha = 0.78f), maxLines = 1)
            }
        }
    }
}

@Composable
private fun CardMosaic(m: CollectionMosaic, api: AbsApi?) {
    val c = LocalAbsColors.current
    if (m.lead == null) {
        Box(Modifier.size(104.dp).clip(RoundedCornerShape(5.dp)).background(c.placeholder))
        return
    }
    if (!m.hasColumn) {
        CoverTile(m.lead, api, Modifier.size(104.dp), 5.dp)
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        CoverTile(m.lead, api, Modifier.size(104.dp), 5.dp)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            m.top?.let { SmallTile(it, api) }
            m.bottom?.let { SmallTile(it, api) }
        }
    }
}

@Composable
private fun SmallTile(tile: MosaicTile, api: AbsApi?) {
    val c = LocalAbsColors.current
    val size = Modifier.size(50.dp)
    when (tile) {
        is MosaicTile.Cover -> CoverTile(tile.book, api, size, 4.dp)
        is MosaicTile.More -> Box(size.clip(RoundedCornerShape(4.dp)).background(c.surface), contentAlignment = Alignment.Center) {
            Text("+${tile.count}", fontFamily = FontFamily.Serif, fontSize = 18.sp, color = c.accent, maxLines = 1)
        }
        MosaicTile.Empty -> Box(size.clip(RoundedCornerShape(4.dp)).border(1.dp, c.outline, RoundedCornerShape(4.dp)))
    }
}

/** A square cover, or a plain tile when the server has no cover for the book (nothing to request). */
@Composable
private fun CoverTile(book: Book, api: AbsApi?, modifier: Modifier, radius: Dp) {
    val c = LocalAbsColors.current
    val shape = RoundedCornerShape(radius)
    val url = if (book.hasCover) api?.coverUrl(book.id) else null
    if (url != null) {
        AsyncImage(
            model = url, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape).background(c.placeholder),
        )
    } else {
        Box(modifier.clip(shape).background(c.placeholder))
    }
}

// ---------------------------------------------------------------------------------------------------------
// Collection detail (Screen.CollectionBooks): rendered from the list entry, no extra request
// ---------------------------------------------------------------------------------------------------------

@Composable
fun CollectionBooksScreen(vm: AppViewModel, collection: BookCollection) {
    val c = LocalAbsColors.current
    val api = vm.api
    val books = remember(collection) { collection.books.distinctBy { it.id } }
    // Per-book progress is read once for the caption; a failure just hides it.
    val progress by produceState(ProgressMap(), api) {
        value = try {
            api?.progress() ?: ProgressMap()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProgressMap()
        }
    }
    val remembered = vm.lastFocused[vm.screen.key]
    val targetId = remember(books) { books.firstOrNull { it.id == remembered }?.id ?: books.firstOrNull()?.id }
    val target = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    LaunchedEffect(targetId) {
        val id = targetId ?: return@LaunchedEffect
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.isNotEmpty() }.first { it }
        if (gridState.layoutInfo.visibleItemsInfo.none { it.key == id }) {
            val idx = books.indexOfFirst { it.id == id }
            if (idx >= 0) gridState.scrollToItem(idx)
        }
        target.requestWhenReady()
    }

    Row(Modifier.fillMaxSize().padding(start = ContentStart, top = 40.dp, end = 48.dp)) {
        // Left panel: 240 wide, stays put while the books scroll.
        Column(Modifier.width(240.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DetailMosaic(detailMosaic(books), api)
            Spacer(Modifier.height(8.dp))
            Text("COLLECTION", fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Medium, color = c.muted)
            Text(collection.name, fontFamily = FontFamily.Serif, fontSize = 26.sp, lineHeight = 32.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            val description = plainDescription(collection.description)
            if (description.isNotEmpty()) {
                Text(description, fontSize = 14.sp, lineHeight = 20.sp, color = c.muted, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            Text(collectionSummary(books), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            progressCaption(books, progress)?.let { Text(it, fontSize = 13.sp, color = c.muted) }
        }
        Spacer(Modifier.width(32.dp))
        Column(Modifier.weight(1f)) {
            SectionTitle("In this collection")
            if (books.isEmpty()) {
                Text("This collection has no books yet.", color = c.muted, modifier = Modifier.padding(top = 16.dp))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(DetailColumns),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 20.dp, bottom = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    items(books, key = { it.id }, contentType = { "book" }) { b ->
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                            BookCard(
                                b, api?.coverUrl(b.id), { vm.openBook(b) }, { vm.lastFocused[vm.screen.key] = b.id },
                                focusRequester = if (b.id == targetId) target else null,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 2x2 of 116dp tiles with 8dp gaps (240 wide). Missing slots stay as empty tiles. */
@Composable
private fun DetailMosaic(slots: List<Book?>, api: AbsApi?) {
    val c = LocalAbsColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        slots.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { b ->
                    if (b != null) CoverTile(b, api, Modifier.size(116.dp), 6.dp)
                    else Box(Modifier.size(116.dp).clip(RoundedCornerShape(6.dp)).background(c.tile))
                }
            }
        }
    }
}

/** Frees the saved state of collection screens that are no longer on the back stack (pop, tab switch, logout). */
@Composable
fun PruneCollectionState(vm: AppViewModel, holder: SaveableStateHolder) {
    val seen = remember { HashSet<String>() }
    val epoch = vm.stateEpoch
    vm.screen // subscribe: re-run on every navigation
    val live = vm.stack.screens.filterIsInstance<Screen.CollectionBooks>().map { "${it.key}#$epoch" }.toSet()
    SideEffect {
        val gone = seen.filter { it !in live }
        gone.forEach { holder.removeState(it) }
        seen.removeAll(gone.toSet())
        seen.addAll(live)
    }
}

// ---------------------------------------------------------------------------------------------------------
// Message block (empty and error states, HANDOFF section 11)
// ---------------------------------------------------------------------------------------------------------

@Composable
private fun MessageBlock(
    icon: ImageVector,
    title: String,
    body: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: () -> Unit = {},
) {
    val c = LocalAbsColors.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestWhenReady() }
    Box(Modifier.fillMaxSize().padding(start = ContentStart, end = 48.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.width(460.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(c.surface), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = c.accent, modifier = Modifier.size(30.dp))
            }
            Text(title, fontFamily = FontFamily.Serif, fontSize = 26.sp, lineHeight = 32.sp)
            Text(body, fontSize = 15.sp, lineHeight = 22.sp, color = c.muted, textAlign = TextAlign.Center)
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AbsButton(onClick = onPrimary, modifier = Modifier.focusRequester(first)) { Text(primaryLabel) }
                if (secondaryLabel != null) AbsButton(onClick = onSecondary) { Text(secondaryLabel) }
            }
        }
    }
}
