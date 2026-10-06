@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.teshlor.abstv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlin.math.roundToInt

private val CardSize = 120.dp

/** Covers are drawn at 120dp, so ask the server for 2x (240px) rather than the old 400px. */
fun AbsApi.bookCoverUrl(itemId: String) = coverUrl(itemId, 240)

/**
 * The one standard book card (HANDOFF 5.2): 120dp cover plus a fixed 48dp title strip, so rows always line up.
 * Optional solid overlays on the cover: a progress bar (in progress), a finished check, and a sequence badge
 * ([sequenceBadge], e.g. "#3"). [highlight] marks the matched part of the title (Search). Books the server has no
 * cover for get a text tile, which costs nothing to load.
 */
@Composable
fun BookCard(
    book: Book,
    coverUrl: String?,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    progress: BookProgress? = null,
    sequenceBadge: String? = null,
    highlight: String? = null,
) {
    val c = LocalAbsColors.current
    var focused by remember { mutableStateOf(false) }
    val state = progress?.state
    val desc = remember(book.id, progress, sequenceBadge) { cardDescription(book, progress, sequenceBadge) }
    Card(
        onClick = onClick,
        modifier = modifier
            .width(CardSize)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                focused = it.hasFocus
                if (it.hasFocus) onFocused()
            }
            .semantics { contentDescription = desc },
        shape = CardDefaults.shape(RoundedCornerShape(8.dp)),
        colors = CardDefaults.colors(
            containerColor = c.surface, contentColor = c.onSurface,
            focusedContainerColor = c.accent, focusedContentColor = c.onAccent,
            pressedContainerColor = c.accent, pressedContentColor = c.onAccent,
        ),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, c.focusBorder))),
        scale = CardDefaults.scale(focusedScale = 1.08f),
    ) {
        Column {
            Box(Modifier.size(CardSize)) {
                if (book.hasCover && coverUrl != null) {
                    AsyncImage(
                        model = coverUrl, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().background(c.placeholderColor),
                    )
                } else {
                    NoCoverTile(book)
                }
                if (progress != null && state == ProgressState.IN_PROGRESS) {
                    Box(
                        Modifier.align(Alignment.BottomStart).padding(8.dp).fillMaxWidth().height(4.dp)
                            .background(c.bg, RoundedCornerShape(2.dp)),
                    ) {
                        Box(
                            Modifier.fillMaxWidth(progress.fraction.coerceIn(0.02f, 1f)).height(4.dp)
                                .background(c.accent, RoundedCornerShape(2.dp)),
                        )
                    }
                } else if (state == ProgressState.FINISHED) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).background(c.accent, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(16.dp))
                    }
                }
                if (sequenceBadge != null) {
                    Box(
                        Modifier.align(Alignment.TopStart).padding(6.dp).height(20.dp)
                            .background(c.bg, RoundedCornerShape(10.dp)).padding(horizontal = 7.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(sequenceBadge, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = c.accent)
                    }
                }
            }
            // Fixed 48dp strip; the weight stays Medium in every state so focus never re-lays out the text.
            Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    if (highlight.isNullOrEmpty()) AnnotatedString(book.title) else highlighted(book.title, highlight, focused),
                    fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Books the server has no cover for: title, an accent rule and the author, drawn as text. */
@Composable
private fun NoCoverTile(book: Book) {
    val c = LocalAbsColors.current
    Column(
        Modifier.fillMaxSize().background(c.placeholderColor).padding(start = 10.dp, top = 30.dp, end = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Text(book.title, fontFamily = FontFamily.Serif, fontSize = 13.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Box(Modifier.padding(vertical = 6.dp).width(20.dp).height(1.dp).background(c.accent))
        if (book.author.isNotEmpty()) {
            Text(book.author, fontSize = 10.sp, lineHeight = 13.sp, color = c.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Title with the matched ranges in accent + Bold; when [focused], onAccent + underline (HANDOFF 5.7). */
@Composable
internal fun highlighted(text: String, query: String, focused: Boolean): AnnotatedString {
    val c = LocalAbsColors.current
    return remember(text, query, focused, c) {
        buildAnnotatedString {
            append(text)
            val style = if (focused) SpanStyle(color = c.onAccent, fontWeight = FontWeight.Bold, textDecoration = TextDecoration.Underline)
            else SpanStyle(color = c.accent, fontWeight = FontWeight.Bold)
            highlightRanges(text, query).forEach { addStyle(style, it.first, it.last + 1) }
        }
    }
}

/** "Caine Black Knife, Matthew Stover, 38 percent, 11h 19m left" / "..., finished" / "..., book 3" (HANDOFF section 10). */
private fun cardDescription(book: Book, progress: BookProgress?, sequenceBadge: String?): String = listOfNotNull(
    book.title,
    book.author.ifEmpty { null },
    progress?.takeIf { it.state == ProgressState.IN_PROGRESS }?.let { p ->
        buildString {
            append("${(p.fraction * 100).roundToInt()} percent")
            if (p.duration > 0.0) append(", ${timeLeftLabel(p.remainingSeconds)}")
        }
    },
    if (progress?.state == ProgressState.FINISHED) "finished" else null,
    sequenceBadge?.let { "book ${it.removePrefix("#")}" },
).joinToString(", ")
