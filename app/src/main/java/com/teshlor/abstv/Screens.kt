@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.teshlor.abstv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import androidx.compose.material3.Text as M3Text

/** Themed button that fills with the accent (and scales up) when focused so D-pad focus is obvious. */
@Composable
fun AbsButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val c = LocalAbsColors.current
    androidx.tv.material3.Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = c.surface,
            contentColor = c.onSurface,
            focusedContainerColor = c.accent,
            focusedContentColor = c.onAccent,
            pressedContainerColor = c.accent,
            pressedContentColor = c.onAccent,
        ),
        scale = ButtonDefaults.scale(focusedScale = 1.1f),
        content = content,
    )
}

@Composable
private fun fieldColors(): androidx.compose.material3.TextFieldColors {
    val c = LocalAbsColors.current
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = c.onSurface,
        unfocusedTextColor = c.onSurface,
        focusedBorderColor = c.accent,
        unfocusedBorderColor = c.muted,
        focusedLabelColor = c.accent,
        unfocusedLabelColor = c.muted,
        cursorColor = c.accent,
    )
}

@Composable
fun LoginScreen(vm: AppViewModel) {
    val c = LocalAbsColors.current
    var server by remember { mutableStateOf(vm.savedServer) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 240.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text("Audiobookshelf", fontSize = 32.sp, color = c.accent)
        OutlinedTextField(
            server, { server = it }, Modifier.fillMaxWidth(), singleLine = true, colors = fieldColors(),
            label = { M3Text("Server (e.g. 192.168.1.10:13378)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), singleLine = true, colors = fieldColors(), label = { M3Text("Username") })
        OutlinedTextField(
            pass, { pass = it }, Modifier.fillMaxWidth(), singleLine = true, colors = fieldColors(), label = { M3Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
        )
        vm.error?.let { Text(it, color = c.error) }
        AbsButton(onClick = { vm.login(server, user, pass) }) {
            Text(if (vm.loading) "Signing in…" else "Sign in")
        }
    }
}

@Composable
fun BookCard(vm: AppViewModel, book: Book) {
    val c = LocalAbsColors.current
    Card(
        onClick = { vm.openBook(book) },
        modifier = Modifier.width(150.dp),
        colors = CardDefaults.colors(
            containerColor = c.surface,
            contentColor = c.onSurface,
            focusedContainerColor = c.accent,
            focusedContentColor = c.onAccent,
            pressedContainerColor = c.accent,
            pressedContentColor = c.onAccent,
        ),
        border = CardDefaults.border(focusedBorder = Border(BorderStroke(3.dp, c.focusBorder))),
        scale = CardDefaults.scale(focusedScale = 1.08f),
    ) {
        Column {
            AsyncImage(
                model = vm.api?.coverUrl(book.id),
                contentDescription = book.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(150.dp).background(c.surface),
            )
            Text(
                book.title, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(6.dp),
            )
        }
    }
}

@Composable
fun HomeScreen(vm: AppViewModel) {
    val c = LocalAbsColors.current
    val side = Modifier.padding(horizontal = 48.dp)
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(side, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                vm.libraries.forEach { lib ->
                    AbsButton(onClick = { vm.selectLibrary(lib) }) {
                        Text(if (lib.id == vm.selectedLibrary?.id) "● ${lib.name}" else lib.name)
                    }
                }
                AbsButton(onClick = { vm.loadHome() }) { Text("Refresh") }
                AbsButton(onClick = { vm.logout() }) { Text("Log out") }
                if (vm.loading) Text("Loading…", color = c.muted)
            }
        }
        vm.error?.let { item { Text(it, modifier = side, color = c.error) } }
        if (vm.continueListening.isNotEmpty()) {
            item { Text("Continue Listening", modifier = side, fontSize = 20.sp, color = c.accent) }
            item {
                // Bleeds to the screen edges so the scaled first card's focus ring isn't clipped.
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(vm.continueListening, key = { it.id }) { BookCard(vm, it) }
                }
            }
        }
        item { Text("Books", modifier = side, fontSize = 20.sp, color = c.accent) }
        items(vm.books.chunked(5)) { row ->
            Row(side, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { BookCard(vm, it) }
            }
        }
    }
}

@Composable
fun DetailScreen(vm: AppViewModel) {
    val c = LocalAbsColors.current
    val book = vm.selectedBook ?: return
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.fillMaxSize().padding(48.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        AsyncImage(
            model = vm.api?.coverUrl(book.id), contentDescription = book.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(280.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(book.title, fontSize = 28.sp)
            if (book.author.isNotEmpty()) Text(book.author, fontSize = 18.sp, color = c.accent)
            if (book.media.duration > 0) Text(formatTime(book.media.duration))
            book.media.metadata.description?.let {
                Text(it.replace(Regex("<[^>]*>"), ""), fontSize = 14.sp, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
            vm.error?.let { Text(it, color = c.error) }
            AbsButton(onClick = { vm.play() }, modifier = Modifier.focusRequester(focus)) {
                Text(if (vm.loading) "Starting…" else "Play / Resume")
            }
        }
    }
}

@Composable
fun PlayerScreen(vm: AppViewModel) {
    val c = LocalAbsColors.current
    val p = vm.player
    val book = vm.selectedBook
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        if (book != null) {
            AsyncImage(
                model = vm.api?.coverUrl(book.id), contentDescription = p.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(220.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
            )
        }
        Text(p.title, fontSize = 24.sp)
        Text(p.author, color = c.accent)
        Box(Modifier.fillMaxWidth(0.6f).height(6.dp).background(c.surface)) {
            val frac = if (p.duration > 0) (p.position / p.duration).toFloat().coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth(frac).height(6.dp).background(c.accent))
        }
        Text("${formatTime(p.position)} / ${formatTime(p.duration)}")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AbsButton(onClick = { p.seekBy(-30.0) }) { Text("⏪ 30s") }
            AbsButton(onClick = { p.togglePlay() }, modifier = Modifier.focusRequester(focus)) {
                Text(if (p.isPlaying) "Pause" else "Play")
            }
            AbsButton(onClick = { p.seekBy(30.0) }) { Text("30s ⏩") }
        }
        Text("Back to stop and return", fontSize = 12.sp, color = c.muted)
    }
}

fun formatTime(sec: Double): String {
    val s = sec.toLong().coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val r = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
}
