@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.teshlor.abstv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

// Library tab (nav milestone M3). Layout per design/nav/HANDOFF.md section 2 and board 04-library.png:
// 6 fixed columns of 120x168 cards in the 800dp content column (x 112-912), 16dp column gap, 20dp row gap.

private val CardWidth = 120.dp
private const val Columns = 6

// Tokens derived from the six theme tokens (HANDOFF section 7).
private val AbsColors.skeleton get() = lerp(bg, surface, 0.45f)
private val AbsColors.placeholder get() = lerp(bg, surface, 0.75f)
private val AbsColors.outline get() = lerp(bg, onSurface, 0.22f)

/** Covers are drawn at 120dp (about 240px on a 1080p TV), so ask the server for that, not the default 400. */

@Composable
fun LibraryTab(vm: AppViewModel) {
    val state = remember(vm.stateEpoch, vm.api, vm.selectedLibrary?.id) { vm.libraryTabState() }
    if (state == null) {
        Box(Modifier.fillMaxSize())
        return
    }
    val c = LocalAbsColors.current
    val scope = rememberCoroutineScope()
    val ps by state.pager.state.collectAsState()
    val sort = state.sort
    val filter = state.filter
    val gridState = rememberLazyGridState()
    val screenKey = vm.screen.key

    // Which card gets focus: one requester, assigned to whichever card has [focusId]; [focusTick] re-fires it.
    val target = remember { FocusRequester() }
    var focusId by remember { mutableStateOf<String?>(null) }
    var focusTick by remember { mutableIntStateOf(0) }
    var focusFirst by remember { mutableStateOf(false) }   // set by a sort/filter change; honoured once page 0 lands
    var initialDone by remember { mutableStateOf(false) }

    fun focusCard(id: String) { focusId = id; focusTick++ }

    LaunchedEffect(state) { state.refreshProgress() }

    // Load the next page when focus gets near the end. Re-collected after each page so a short page still chains.
    LaunchedEffect(state, ps.items.size) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { if (it >= 0) state.pager.onVisible(it) }
    }

    LaunchedEffect(ps.items.isEmpty(), focusFirst) {
        if (ps.items.isEmpty()) {
            if (ps.loadedOnce) focusFirst = false   // nothing to focus (empty or failed): give up quietly
            return@LaunchedEffect
        }
        if (focusFirst) {
            focusFirst = false
            initialDone = true
            gridState.scrollToItem(0)
            focusCard(ps.items.first().id)
        } else if (!initialDone) {
            // Entering the tab: back on the card the user left (after Detail -> Back), else the first book.
            val items = ps.items
            val idx = items.indexOfFirst { it.id == vm.lastFocused[screenKey] }.takeIf { it >= 0 } ?: 0
            withFrameNanos { }   // let a restored scroll position lay out before deciding whether to scroll
            if (gridState.layoutInfo.visibleItemsInfo.none { it.index == idx }) gridState.scrollToItem(idx)
            initialDone = true
            focusCard(items[idx].id)
        }
    }
    LaunchedEffect(focusId, focusTick) { if (focusId != null) target.requestWhenReady() }

    fun onSort(next: LibrarySort) { if (state.setSort(next)) focusFirst = true }
    fun onDirection() { state.toggleDirection(); focusFirst = true }
    fun onFilter(next: LibraryFilter) { if (state.setFilter(next)) focusFirst = true }

    val showStrip = sort.sort.byLetter && ps.items.isNotEmpty()
    val currentLetter by remember(state) {
        derivedStateOf {
            val book = ps.items.getOrNull(gridState.firstVisibleItemIndex)
            book?.let { letterKey(it, state.sort.sort).a }
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            LibraryHeader(
                overline = libraryOverline(vm.selectedLibrary?.name.orEmpty(), filter, ps.total, ps.loadedOnce && ps.error == null),
                sort = sort, filter = filter,
                onSort = ::onSort, onDirection = ::onDirection, onFilter = ::onFilter,
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    ps.items.isEmpty() && !ps.loadedOnce -> SkeletonGrid()
                    ps.items.isEmpty() && ps.error != null -> MessageBlock(
                        icon = Icons.Filled.Warning,
                        title = "Can't reach your server",
                        body = "${vm.api?.baseUrl.orEmpty().substringAfter("://")} didn't answer. Check that the server is on and the TV is on the same network, then try again.",
                        primary = "Try again" to { state.pager.retry() },
                        secondary = "Settings" to { vm.selectTab(Tab.SETTINGS) },
                    )
                    ps.items.isEmpty() && filter != LibraryFilter.ALL -> MessageBlock(
                        icon = Icons.Filled.Info,
                        title = "Nothing here",
                        body = "No books match \"${filter.label}\" right now.",
                        primary = "Show all books" to { state.setFilter(LibraryFilter.ALL) },
                    )
                    ps.items.isEmpty() -> MessageBlock(
                        icon = Icons.Filled.Info,
                        title = "No books in this library yet",
                        body = "Add books to \"${vm.selectedLibrary?.name.orEmpty()}\" in Audiobookshelf on the web, then check again.",
                        primary = "Check again" to { state.reload() },
                    )
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(Columns),
                        state = gridState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 112.dp, end = 48.dp, top = 12.dp, bottom = 40.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        items(ps.items, key = { it.id }, contentType = { "book" }) { b ->
                            BookCard(
                                book = b,
                                coverUrl = vm.api?.bookCoverUrl(b.id),
                                progress = vm.progress[b.id],
                                onClick = { vm.openBook(b) },
                                onFocused = { vm.lastFocused[screenKey] = b.id },
                                modifier = if (b.id == focusId) Modifier.focusRequester(target) else Modifier,
                            )
                        }
                        if (ps.error != null) {
                            item(key = "load-error", span = { GridItemSpan(maxLineSpan) }, contentType = "footer") {
                                Row(
                                    Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    Text("Couldn't load more books.", fontSize = 15.sp, color = c.muted)
                                    AbsButton(onClick = { state.pager.retry() }) { Text("Try again") }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (showStrip) {
            LetterStrip(
                current = currentLetter,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
            ) { letter ->
                scope.launch {
                    val idx = state.indexForLetter(letter) ?: return@launch
                    val book = state.pager.state.value.items.getOrNull(idx) ?: return@launch
                    gridState.scrollToItem(idx)
                    focusCard(book.id)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- header

@Composable
private fun LibraryHeader(
    overline: String,
    sort: SortSpec,
    filter: LibraryFilter,
    onSort: (LibrarySort) -> Unit,
    onDirection: () -> Unit,
    onFilter: (LibraryFilter) -> Unit,
) {
    val c = LocalAbsColors.current
    Column(
        Modifier.padding(start = 112.dp, end = 48.dp, top = 28.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(overline, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.6.sp, fontWeight = FontWeight.Medium, color = c.muted, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Library", fontFamily = FontFamily.Serif, fontSize = 30.sp, lineHeight = 36.sp)
            Box(Modifier.weight(1f).padding(start = 18.dp)) {
                Box(
                    Modifier.width(96.dp).height(1.dp).background(
                        Brush.horizontalGradient(listOf(c.accent.copy(alpha = 0.6f), c.accent.copy(alpha = 0f))),
                    ),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = c.muted, modifier = Modifier.size(20.dp))
                LibrarySort.entries.forEach { s ->
                    Chip(s.label, selected = s == sort.sort, onClick = { onSort(s) })
                }
                Chip(
                    if (sort.desc) "↓" else "↑", selected = false, onClick = onDirection,
                    description = if (sort.desc) "Descending. Switch to ascending." else "Ascending. Switch to descending.",
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LibraryFilter.entries.forEach { f -> Chip(f.label, selected = f == filter, onClick = { onFilter(f) }) }
        }
    }
}

/** HANDOFF 5.5: 36 tall, round, 1dp outline at rest; selected = `surface` fill + accent text + check; focused = accent. */
@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit, description: String = label) {
    val c = LocalAbsColors.current
    Surface(
        onClick = onClick,
        modifier = Modifier.height(36.dp).semantics { contentDescription = if (selected) "$description, selected" else description },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(18.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) c.surface else Color.Transparent,
            contentColor = if (selected) c.accent else c.muted,
            focusedContainerColor = c.accent, focusedContentColor = c.onAccent,
            pressedContainerColor = c.accent, pressedContentColor = c.onAccent,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (selected) Color.Transparent else c.outline)),
            focusedBorder = Border(BorderStroke(1.dp, Color.Transparent)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Row(
            Modifier.height(36.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (selected) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

// -------------------------------------------------------------------------------------------- A-Z strip

/**
 * Thin vertical letter strip in the right margin. OK on a letter loads up to it and moves focus to that book;
 * [current] (the letter of the first visible book) is drawn in the accent colour.
 */
@Composable
private fun LetterStrip(current: Char?, modifier: Modifier, onJump: (Char) -> Unit) {
    val c = LocalAbsColors.current
    Column(modifier.focusGroup(), horizontalAlignment = Alignment.CenterHorizontally) {
        JUMP_LETTERS.forEach { letter ->
            Surface(
                onClick = { onJump(letter) },
                modifier = Modifier.size(width = 24.dp, height = 17.dp).semantics {
                    contentDescription = if (letter == '#') "Jump to numbers and symbols" else "Jump to $letter"
                },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    contentColor = if (letter == current) c.accent else c.muted,
                    focusedContainerColor = c.accent, focusedContentColor = c.onAccent,
                    pressedContainerColor = c.accent, pressedContentColor = c.onAccent,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        letter.toString(), fontSize = 11.sp, lineHeight = 13.sp, textAlign = TextAlign.Center,
                        fontWeight = if (letter == current) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ cards

// ------------------------------------------------------------------------------------- loading / messages

/** Static placeholders (no shimmer): three rows of skeleton cards. */
@Composable
private fun SkeletonGrid() {
    val c = LocalAbsColors.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(Columns),
        modifier = Modifier.fillMaxSize(),
        userScrollEnabled = false,
        contentPadding = PaddingValues(start = 112.dp, end = 48.dp, top = 12.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(Columns * 3, key = { "skeleton-$it" }, contentType = { "skeleton" }) {
            Column(Modifier.width(CardWidth).clip(RoundedCornerShape(8.dp)).background(c.skeleton)) {
                Box(Modifier.size(CardWidth))
                Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 10.dp, vertical = 16.dp)) {
                    Box(Modifier.fillMaxWidth(0.7f).height(10.dp).background(c.surface, RoundedCornerShape(5.dp)))
                }
            }
        }
    }
}

/** HANDOFF "Loading, empty and error": 64dp circle + 30dp icon, serif title, muted body, first button focused. */
@Composable
private fun MessageBlock(
    icon: ImageVector,
    title: String,
    body: String,
    primary: Pair<String, () -> Unit>,
    secondary: Pair<String, () -> Unit>? = null,
) {
    val c = LocalAbsColors.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestWhenReady() }
    Box(Modifier.fillMaxSize().padding(start = 112.dp, end = 48.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.width(460.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(64.dp).background(c.surface, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = c.accent, modifier = Modifier.size(30.dp))
            }
            Text(title, fontFamily = FontFamily.Serif, fontSize = 26.sp, lineHeight = 32.sp, textAlign = TextAlign.Center)
            Text(body, fontSize = 15.sp, lineHeight = 22.sp, color = c.muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AbsButton(onClick = primary.second, modifier = Modifier.focusRequester(first)) { Text(primary.first) }
                secondary?.let { (label, action) -> AbsButton(onClick = action) { Text(label) } }
            }
        }
    }
}
