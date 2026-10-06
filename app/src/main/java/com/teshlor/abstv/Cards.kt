@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.teshlor.abstv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Job
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlin.math.roundToInt

// Shared cards for Home and Series (BookCard stays in Screens.kt; its signature is used by other tabs).
// Derived colours follow design/nav/HANDOFF.md section 7, computed from the six theme tokens.

internal val AbsColors.trayColor get() = lerp(bg, surface, 0.55f)
internal val AbsColors.trackColor get() = lerp(bg, surface, 0.55f)
internal val AbsColors.placeholderColor get() = lerp(bg, surface, 0.75f)
internal val AbsColors.currentRowColor get() = lerp(bg, surface, 0.70f)

/** True while the rail is expanded; tabs use it to avoid grabbing focus out of the rail when late data arrives. */
val LocalRailOpen = compositionLocalOf { false }

// ---- Pure helpers (unit tested) ----

/** "11h 19m left", "42m left", "Under a minute left". */
fun timeLeftLabel(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    return when {
        h > 0 -> "${h}h ${m}m left"
        m > 0 -> "${m}m left"
        else -> "Under a minute left"
    }
}

/** "22h 40m", "40m"; used for book and series durations. */
fun durationLabel(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

/** "1 book", "4 books", "4 books · 2 finished" (the finished part is left out when it is 0). */
fun seriesSubtitle(total: Int, finished: Int): String {
    val base = if (total == 1) "1 book" else "$total books"
    return if (finished > 0) "$base · $finished finished" else base
}

// ---- Entry focus ----

/** Plain holder (not state): focus changes must not recompose the tab. */
class FocusFlag { var has = false }

fun Modifier.trackFocus(flag: FocusFlag): Modifier = onFocusChanged { flag.has = it.hasFocus }

/**
 * Requests focus on [target] once per screen entry, the first time [ready] becomes true. It never repeats, so a data
 * refresh (after the Player, the 5 minute auto refresh) can't snap scrolling or steal focus. It also skips the request
 * when the rail is open or something in the content already has focus. [prepare] runs first (scroll the target into
 * composition). A fresh entry, such as after Detail or a tab switch, starts with a fresh flag.
 */
@Composable
fun EntryFocus(ready: Boolean, target: FocusRequester, flag: FocusFlag, prepare: suspend () -> Unit = {}) {
    val railOpen by rememberUpdatedState(LocalRailOpen.current)
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(ready) {
        if (!ready || done) return@LaunchedEffect
        done = true
        if (railOpen || flag.has) return@LaunchedEffect
        prepare()
        target.requestWhenReady()
    }
}

/** The content's most recently focused element, so Right/OK on the rail can return to exactly that card. Plain holder: no recomposition. */
class ReturnFocus { var last: FocusRequester? = null }

val LocalReturnFocus = compositionLocalOf<ReturnFocus?> { null }

/** Marks a focusable as a candidate to return to: gives it its own requester and records it when focused. */
@Composable
fun Modifier.returnTarget(): Modifier {
    val rf = LocalReturnFocus.current ?: return this
    val own = remember { FocusRequester() }
    return this.focusRequester(own).onFocusChanged { if (it.hasFocus) rf.last = own }
}

/**
 * Waits until [this] has stopped scrolling. Focus first triggers the lazy list's own minimal bring-into-view scroll,
 * and a heading reveal started before it finishes is cancelled by it (scrolls are mutually exclusive); so reveal after.
 */
suspend fun ScrollableState.awaitScrollIdle() {
    withFrameNanos { }
    snapshotFlow { isScrollInProgress }.first { !it }
}

/**
 * Runs [action] when focus ENTERS this container (not on every move inside it), once [state] has settled from the
 * focus scroll. Lazy lists only scroll far enough to show the focused card, which strands the heading above it; call a
 * scroll-to-heading here for pivot-like behaviour. No per-frame work: one focus callback, and the "had focus" flag is
 * kept outside snapshot state.
 */
@Composable
fun Modifier.onFocusEntered(state: ScrollableState, action: suspend () -> Unit): Modifier {
    val scope = rememberCoroutineScope()
    val had = remember { BooleanArray(1) }
    val current by rememberUpdatedState(action)
    return onFocusChanged { s ->
        if (s.hasFocus && !had[0]) scope.launch { state.awaitScrollIdle(); if (had[0]) current() } // skip if focus already left the row
        had[0] = s.hasFocus
    }
}

// ---- Cards ----

@Composable
private fun cardColors() = LocalAbsColors.current.let { c ->
    CardDefaults.colors(
        containerColor = c.surface, contentColor = c.onSurface,
        focusedContainerColor = c.accent, focusedContentColor = c.onAccent,
        pressedContainerColor = c.accent, pressedContentColor = c.onAccent,
    )
}

/** Continue Listening card: 144x144 cover with a progress bar, then a 48dp strip with the title and time left. */
@Composable
fun HeroBookCard(
    book: Book,
    coverUrl: String?,
    progress: BookProgress?,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val c = LocalAbsColors.current
    val left = progress?.takeIf { it.state == ProgressState.IN_PROGRESS && it.duration > 0.0 }?.let { timeLeftLabel(it.remainingSeconds) }
    val desc = listOfNotNull(
        book.title, book.author.ifEmpty { null },
        progress?.takeIf { it.state == ProgressState.IN_PROGRESS }?.let { "${(it.fraction * 100).roundToInt()} percent" },
        left,
    ).joinToString(", ")
    Card(
        onClick = onClick,
        modifier = modifier
            .width(144.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .returnTarget().onFocusChanged { if (it.hasFocus) onFocused() }
            .semantics { contentDescription = desc },
        shape = CardDefaults.shape(RoundedCornerShape(8.dp)),
        colors = cardColors(),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, c.focusBorder))),
        scale = CardDefaults.scale(focusedScale = 1.08f),
    ) {
        Column {
            Box(Modifier.size(144.dp)) {
                if (book.hasCover && coverUrl != null) {
                    AsyncImage(
                        model = coverUrl, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(144.dp).background(c.surface),
                    )
                } else {
                    Box(Modifier.size(144.dp).background(c.placeholderColor).padding(start = 12.dp, top = 12.dp, end = 12.dp)) {
                        Text(book.title, fontFamily = FontFamily.Serif, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (progress != null && progress.state == ProgressState.IN_PROGRESS) {
                    Box(
                        Modifier.align(Alignment.BottomStart).padding(8.dp).fillMaxWidth().height(4.dp)
                            .clip(RoundedCornerShape(2.dp)).background(c.bg),
                    ) {
                        Box(Modifier.fillMaxWidth(progress.fraction.coerceIn(0f, 1f)).fillMaxHeight().background(c.accent))
                    }
                }
            }
            Column(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(book.title, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (left != null) {
                    Text(
                        left, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1,
                        color = LocalContentColor.current.copy(alpha = 0.72f),
                    )
                }
            }
        }
    }
}

/**
 * Wide series card (188x176): a tray with up to three stacked covers (first book in front), then a 48dp strip with the
 * name and "4 books · 2 finished". [covers] are already sequence-ordered and requested at the stack's drawn size.
 */
@Composable
fun SeriesCard(
    series: Series,
    covers: List<String?>,
    finished: Int,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val c = LocalAbsColors.current
    val total = series.books.size
    val subtitle = seriesSubtitle(total, finished)
    Card(
        onClick = onClick,
        modifier = modifier
            .width(188.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .returnTarget().onFocusChanged { if (it.hasFocus) onFocused() }
            .semantics { contentDescription = "${series.name}, ${subtitle.replace(" · ", ", ")}" },
        shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
        colors = cardColors(),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, c.focusBorder))),
        scale = CardDefaults.scale(focusedScale = 1.05f),
    ) {
        Column {
            CoverStack(covers, front = 100.dp, step = 34.dp, shrink = 10.dp, modifier = Modifier.size(188.dp, 128.dp))
            Column(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(series.name, fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    subtitle, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = LocalContentColor.current.copy(alpha = 0.72f),
                )
            }
        }
    }
}

/**
 * Stacked covers on a tray: the first is in front and largest; each one behind is [shrink] smaller, [step] further
 * right and darkened with a draw-time colour filter (no offscreen layer). The group is centred in [tray].
 */
@Composable
fun CoverStack(
    covers: List<String?>,
    front: androidx.compose.ui.unit.Dp,
    step: androidx.compose.ui.unit.Dp,
    shrink: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    trayBg: androidx.compose.ui.graphics.Color = LocalAbsColors.current.trayColor,
) {
    val c = LocalAbsColors.current
    val shown = covers.take(3)
    val darken = remember {
        listOf(null, ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(0.68f, 0.68f, 0.68f, 1f) }),
            ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(0.48f, 0.48f, 0.48f, 1f) }))
    }
    val tile = trayBg
    Box(modifier.background(tile)) {
        if (shown.isEmpty()) return@Box
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().fillMaxHeight()) {
            val groupW = front + step * (shown.size - 1)
            val startX = (maxWidth - groupW) / 2
            // Back to front, so the first cover ends up on top.
            for (i in shown.indices.reversed()) {
                val size = front - shrink * i
                val url = shown[i]
                val m = Modifier
                    .offset(x = startX + step * i, y = (maxHeight - size) / 2)
                    .size(size)
                    .clip(RoundedCornerShape(4.dp))
                    .background(c.placeholderColor)
                    .drawWithContent {
                        drawContent()
                        // 2dp tray-coloured edge separates a cover from the one behind it.
                        drawRect(tile, Offset(this.size.width - 2.dp.toPx(), 0f), Size(2.dp.toPx(), this.size.height))
                    }
                if (url != null) {
                    AsyncImage(
                        model = url, contentDescription = null, contentScale = ContentScale.Crop,
                        colorFilter = darken[i], modifier = m,
                    )
                } else {
                    Box(m)
                }
            }
        }
    }
}

/**
 * Up/Down in a lazy grid. When the row above/below the focused card is entirely off-screen, the grid's own focus search
 * runs "beyond bounds" and lands on the LAST card of that row (the rightmost one) instead of the card in the same
 * column. So in that case we scroll one row first (the target row is then on screen), and let the normal geometric
 * focus search pick the card in the same column. When the target is already (partly) visible the default search is
 * right and is left alone.
 *
 * [focusedIndex] is the focused card's LazyGrid item index (null: focus is not on a card, do nothing); cards are
 * the items [firstCard] .. [lastCard] (a header item before them shifts [firstCard] to 1).
 */
@Composable
fun Modifier.gridRowFocus(
    state: LazyGridState, columns: Int, firstCard: Int, lastCard: () -> Int, focusedIndex: () -> Int?, rowGap: Dp = 20.dp,
): Modifier {
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val gapPx = with(LocalDensity.current) { rowGap.toPx() }
    val job = remember { arrayOfNulls<Job>(1) }
    val last by rememberUpdatedState(lastCard)
    val focused by rememberUpdatedState(focusedIndex)
    return onPreviewKeyEvent { e ->
        val up = e.key == Key.DirectionUp
        val down = e.key == Key.DirectionDown
        if (e.type != KeyEventType.KeyDown || (!up && !down)) return@onPreviewKeyEvent false
        if (job[0]?.isActive == true) return@onPreviewKeyEvent true   // a row scroll is under way: swallow auto-repeat
        val idx = focused() ?: return@onPreviewKeyEvent false
        val lastIdx = last()
        val target = if (up) idx - columns else minOf(idx + columns, lastIdx)
        if (idx < firstCard || idx > lastIdx) return@onPreviewKeyEvent false
        if (up && target < firstCard) return@onPreviewKeyEvent false          // first row: default (goes to the controls above)
        if (down && (idx - firstCard) / columns == (lastIdx - firstCard) / columns) return@onPreviewKeyEvent false // last row
        val visible = state.layoutInfo.visibleItemsInfo
        if (visible.any { it.index == target }) return@onPreviewKeyEvent false // target row on screen: default is right
        val rows = visible.filter { it.index >= firstCard }.map { it.offset.y }.distinct().sorted()
        val stride = if (rows.size >= 2) rows[1] - rows[0] else (visible.firstOrNull()?.size?.height ?: 0) + gapPx.toInt()
        if (stride <= 0) return@onPreviewKeyEvent false
        job[0] = scope.launch {
            state.animateScrollBy(if (up) -stride.toFloat() else stride.toFloat())
            withFrameNanos { }
            focusManager.moveFocus(if (up) FocusDirection.Up else FocusDirection.Down)
        }
        true
    }
}

