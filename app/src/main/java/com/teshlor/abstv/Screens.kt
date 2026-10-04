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
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage

private val Accent = Color(0xFFF59E0B)

@Composable
fun LoginScreen(vm: AppViewModel) {
    var server by remember { mutableStateOf(vm.savedServer) }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 240.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text("Audiobookshelf", fontSize = 32.sp, color = Accent)
        OutlinedTextField(
            server, { server = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Server (e.g. 192.168.1.10:13378)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Username") })
        OutlinedTextField(
            pass, { pass = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
        )
        vm.error?.let { Text(it, color = Color(0xFFF87171)) }
        Button(onClick = { vm.login(server, user, pass) }) {
            Text(if (vm.loading) "Signing in…" else "Sign in")
        }
    }
}

@Composable
fun BookCard(vm: AppViewModel, book: Book) {
    Card(onClick = { vm.openBook(book) }, modifier = Modifier.width(150.dp)) {
        Column {
            AsyncImage(
                model = vm.api?.coverUrl(book.id),
                contentDescription = book.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(150.dp).background(Color(0xFF374151)),
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
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                vm.libraries.forEach { lib ->
                    Button(onClick = { vm.selectLibrary(lib) }) {
                        Text(if (lib.id == vm.selectedLibrary?.id) "● ${lib.name}" else lib.name)
                    }
                }
                Button(onClick = { vm.loadHome() }) { Text("Refresh") }
                Button(onClick = { vm.logout() }) { Text("Log out") }
                if (vm.loading) Text("Loading…")
            }
        }
        vm.error?.let { item { Text(it, color = Color(0xFFF87171)) } }
        if (vm.continueListening.isNotEmpty()) {
            item { Text("Continue Listening", fontSize = 20.sp, color = Accent) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(vm.continueListening, key = { it.id }) { BookCard(vm, it) }
                }
            }
        }
        item { Text("Books", fontSize = 20.sp, color = Accent) }
        items(vm.books.chunked(5)) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { BookCard(vm, it) }
            }
        }
    }
}

@Composable
fun DetailScreen(vm: AppViewModel) {
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
            if (book.author.isNotEmpty()) Text(book.author, fontSize = 18.sp, color = Accent)
            if (book.media.duration > 0) Text(formatTime(book.media.duration))
            book.media.metadata.description?.let {
                Text(it.replace(Regex("<[^>]*>"), ""), fontSize = 14.sp, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
            vm.error?.let { Text(it, color = Color(0xFFF87171)) }
            Button(onClick = { vm.play() }, modifier = Modifier.focusRequester(focus)) {
                Text(if (vm.loading) "Starting…" else "Play / Resume")
            }
        }
    }
}

@Composable
fun PlayerScreen(vm: AppViewModel) {
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
        Text(p.author, color = Accent)
        Box(Modifier.fillMaxWidth(0.6f).height(6.dp).background(Color(0xFF374151))) {
            val frac = if (p.duration > 0) (p.position / p.duration).toFloat().coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth(frac).height(6.dp).background(Accent))
        }
        Text("${formatTime(p.position)} / ${formatTime(p.duration)}")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { p.seekBy(-30.0) }) { Text("⏪ 30s") }
            Button(onClick = { p.togglePlay() }, modifier = Modifier.focusRequester(focus)) {
                Text(if (p.isPlaying) "Pause" else "Play")
            }
            Button(onClick = { p.seekBy(30.0) }) { Text("30s ⏩") }
        }
        Text("Back to stop and return", fontSize = 12.sp, color = Color.Gray)
    }
}

fun formatTime(sec: Double): String {
    val s = sec.toLong().coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val r = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
}
