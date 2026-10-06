@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalLayoutApi::class)

package com.teshlor.abstv

import android.app.Application
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okio.ByteString.Companion.encodeUtf8
import androidx.compose.material3.Text as M3Text

// --- State ---------------------------------------------------------------------------------------------------------

sealed interface SearchPhase {
    data object Idle : SearchPhase
    data class Results(val query: String, val result: SearchResult) : SearchPhase
    data object Failed : SearchPhase
}

/**
 * A series or narrator opened from a result. Shown inside the Search tab (Back returns to the results with the
 * query kept) as a plain list of that series'/narrator's books.
 *
 * Narrator choice (documented decision): the server's search only matches book titles/subtitles, so filling the
 * query with the narrator's name would find nothing. Instead a narrator result runs `items?filter=narrators.<b64>`
 * for the current library and lists those books.
 */
data class Drill(val overline: String, val name: String, val books: List<Book>, val loading: Boolean, val failed: Boolean = false)

/**
 * Activity-scoped (not saved with the screen) so the query, the results and the focused item survive
 * Search -> Detail -> Back. Reset when the signed-in server, library or session changes.
 */
class SearchViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("abs", Context.MODE_PRIVATE)
    private var api: AbsApi? = null
    private var libraryId: String? = null
    /** The (api, library, epoch) the state was last reset for. Observable so the screen can wait for [bind] after a switch. */
    var bound: List<Any?>? by mutableStateOf(null)
        private set

    private val counter = RequestCounter()
    private val debouncer = Debouncer(viewModelScope, SEARCH_DEBOUNCE_MS)

    var input by mutableStateOf(TextFieldValue(""))
        private set
    var phase by mutableStateOf<SearchPhase>(SearchPhase.Idle)
        private set
    var searching by mutableStateOf(false)
        private set
    var drill by mutableStateOf<Drill?>(null)
        private set
    var recents by mutableStateOf(RecentSearches.decode(prefs.getString("recent_searches", null)))
        private set

    /** Result key to focus when the tab is shown again (after Detail or a drill-in). Consumed once. */
    var restoreKey: String? = null

    /** Set by the IME Search key and recent chips: focus the results as soon as they are there. */
    var focusResultsPending by mutableStateOf(false)
    var drillNeedsFocus = false
    private var drillOriginKey: String? = null

    val query: String get() = input.text

    fun bind(api: AbsApi?, libraryId: String?, epoch: Int) {
        val key = listOf(api, libraryId, epoch)
        if (key == bound) return
        val firstBind = bound == null
        bound = key
        this.api = api
        this.libraryId = libraryId
        if (firstBind) return
        counter.invalidate(); debouncer.cancel()
        input = TextFieldValue(""); phase = SearchPhase.Idle; searching = false; drill = null
        restoreKey = null; focusResultsPending = false
        recents = RecentSearches.decode(prefs.getString("recent_searches", null)) // empty after logout cleared it
    }

    fun onFieldChange(v: TextFieldValue) {
        val changed = v.text != input.text
        input = v
        if (!changed) return
        drill = null
        if (!isSearchable(v.text)) {
            counter.invalidate(); debouncer.cancel()
            searching = false; phase = SearchPhase.Idle
        } else {
            debouncer.submit { run(v.text) }
        }
    }

    /** IME Search key: remember the query, search now (no debounce) and move focus to the results. */
    fun submit() {
        if (!isSearchable(query)) return
        debouncer.cancel()
        rememberQuery(query)
        focusResultsPending = true
        viewModelScope.launch { run(query) }
    }

    fun useRecent(q: String) {
        input = TextFieldValue(q)
        drill = null
        submit()
    }

    fun retry() {
        focusResultsPending = true
        viewModelScope.launch { run(query) }
    }

    fun selectAllText() {
        input = input.copy(selection = TextRange(0, input.text.length))
    }

    private suspend fun run(q: String) {
        val a = api ?: return
        val lib = libraryId ?: return
        val id = counter.next()
        searching = true
        val norm = normalizeQuery(q)
        try {
            val r = a.search(lib, norm)
            if (counter.isCurrent(id)) { phase = SearchPhase.Results(norm, r); searching = false }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (counter.isCurrent(id)) { phase = SearchPhase.Failed; searching = false }
        }
    }

    fun rememberQuery(q: String) {
        recents = RecentSearches.add(recents, q)
        prefs.edit().putString("recent_searches", RecentSearches.encode(recents)).apply()
    }

    fun clearRecents() {
        recents = emptyList()
        prefs.edit().remove("recent_searches").apply()
    }

    /** A series result opens the Series detail screen (same as the Series tab); Back returns here with focus restored. */
    fun openSeries(m: SeriesMatch, originKey: String, push: (Series) -> Unit) {
        rememberQuery(query)
        restoreKey = originKey
        push(m.series)
    }

    fun openNarrator(m: NarratorMatch, originKey: String) {
        rememberQuery(query)
        drillOriginKey = originKey; restoreKey = originKey; drillNeedsFocus = true
        val a = api; val lib = libraryId
        drill = Drill("NARRATOR", m.name, emptyList(), loading = a != null && lib != null)
        if (a == null || lib == null) return
        viewModelScope.launch {
            val books = try {
                a.narratorBooks(lib, m.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (drill?.name == m.name) drill = drill?.copy(loading = false, failed = true)
                return@launch
            }
            if (drill?.name == m.name) drill = drill?.copy(books = books, loading = false)
        }
    }

    fun closeDrill() {
        drill = null
        restoreKey = drillOriginKey
    }
}

/** Books narrated by [name] in [libraryId]: `filter=narrators.<base64 name>`. Public client, so AbsApi stays untouched. */
suspend fun AbsApi.narratorBooks(libraryId: String, name: String): List<Book> = withContext(Dispatchers.IO) {
    val url = baseUrl.toHttpUrl().newBuilder().addPathSegments("api/libraries/$libraryId/items")
        .addQueryParameter("filter", "narrators." + name.encodeUtf8().base64())
        .addQueryParameter("limit", "100").addQueryParameter("sort", "media.metadata.title").build()
    val body = client.newCall(Request.Builder().url(url).build()).execute().use { r ->
        if (!r.isSuccessful) throw AbsHttpException(r.code)
        r.body?.string().orEmpty()
    }
    val arr = AbsParse.json.parseToJsonElement(body).jsonObject["results"]?.jsonArray ?: return@withContext emptyList()
    sortBySequence(AbsParse.json.decodeFromJsonElement(ListSerializer(Book.serializer()), arr))
}

// --- Screen --------------------------------------------------------------------------------------------------------

private val SideStart = 112.dp
private val SideEnd = 48.dp

@Composable
private fun searchFieldColors(): androidx.compose.material3.TextFieldColors {
    val c = LocalAbsColors.current
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = c.onSurface, unfocusedTextColor = c.onSurface,
        focusedBorderColor = c.accent, unfocusedBorderColor = c.muted,
        focusedLabelColor = c.accent, unfocusedLabelColor = c.muted,
        cursorColor = c.accent,
        focusedContainerColor = c.surface, unfocusedContainerColor = c.surface,
        focusedPlaceholderColor = c.muted, unfocusedPlaceholderColor = c.muted,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SearchTab(vm: AppViewModel) {
    val c = LocalAbsColors.current
    val activity = LocalContext.current as ComponentActivity
    val sv = remember(activity) { ViewModelProvider(activity)[SearchViewModel::class.java] }
    // State writes belong in an effect, not in composition (bind is idempotent per api/library/epoch).
    LaunchedEffect(vm.api, vm.selectedLibrary?.id, vm.stateEpoch) { sv.bind(vm.api, vm.selectedLibrary?.id, vm.stateEpoch) }
    // After a library switch or re-login show nothing until bind() has reset the query, so the old one never flashes.
    if (sv.bound != listOf(vm.api, vm.selectedLibrary?.id, vm.stateEpoch)) {
        Box(Modifier.fillMaxSize())
        return
    }
    val progress = vm.progress

    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val fieldRequester = remember { FocusRequester() }
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun req(key: String) = requesters.getOrPut(key) { FocusRequester() }
    val listState = rememberLazyListState()
    val rowStates = remember { listOf(LazyListState(), LazyListState(), LazyListState()) }
    val imeVisible = WindowInsets.isImeVisible

    val phase = sv.phase
    val drill = sv.drill
    val results = (phase as? SearchPhase.Results)?.result
    val shownQuery = (phase as? SearchPhase.Results)?.query.orEmpty()
    val searchable = isSearchable(sv.query)
    val total = results?.let { it.book.size + it.series.size + it.narrators.size } ?: 0

    // Back from a drill-in returns to the results (the shell's Back, which opens the rail, only sees it otherwise).
    BackHandler(enabled = drill != null) { sv.closeDrill() }

    fun openKeyboard() {
        scope.launch {
            fieldRequester.requestWhenReady()
            delay(120)
            keyboard?.show()
        }
    }

    // Focusable keys in view order. The effect below turns restore/pending requests into focus once the target exists.
    val keys: List<String> = when {
        drill != null -> drill.books.map { "book:${it.id}" }
        !searchable -> sv.recents.map { "recent:$it" } + if (sv.recents.isNotEmpty()) listOf("clear") else emptyList()
        phase is SearchPhase.Failed -> listOf("retry")
        results != null && total == 0 -> listOf("edit") + sv.recents.map { "recent:$it" }
        results != null -> results.book.map { "book:${it.libraryItem.id}" } +
            results.series.map { "series:${it.series.id}" } + results.narrators.map { "narr:${it.name}" }
        else -> emptyList()
    }

    // Enter the tab: the keyboard opens by itself (user decision), unless we are coming back from a result.
    LaunchedEffect(Unit) {
        if (sv.restoreKey == null && sv.drill == null) openKeyboard()
    }

    LaunchedEffect(keys, sv.searching, drill?.loading) {
        val restore = sv.restoreKey
        val target = when {
            restore != null && restore in keys -> restore.also { sv.restoreKey = null }
            drill != null && sv.drillNeedsFocus && keys.isNotEmpty() -> keys.first().also { sv.drillNeedsFocus = false }
            drill == null && sv.focusResultsPending && !sv.searching && phase !is SearchPhase.Idle ->
                (if (results != null && total > 0) keys.firstOrNull() else keys.firstOrNull())
                    .also { sv.focusResultsPending = false }
            else -> null
        }
        if (target != null) {
            keyboard?.hide()
            // Bring the target into composition first: rows are lazy.
            if (drill != null) {
                listState.scrollToItem((keys.indexOf(target) / BOOKS_PER_ROW).coerceAtLeast(0))
            } else if (results != null) {
                val groups = listOf(results.book.size, results.series.size, results.narrators.size)
                val g = when {
                    target.startsWith("book:") -> 0
                    target.startsWith("series:") -> 1
                    target.startsWith("narr:") -> 2
                    else -> -1
                }
                if (g >= 0) {
                    val before = groups.take(g).count { it > 0 }
                    listState.scrollToItem(before)
                    val idx = keys.filter { it.startsWith(target.substringBefore(':') + ":") }.indexOf(target)
                    if (idx > 0) rowStates[g].scrollToItem(idx)
                }
            }
            req(target).requestWhenReady()
        }
    }

    Column(Modifier.fillMaxSize().padding(top = 32.dp)) {
        Text(
            "SEARCH", fontSize = 12.sp, letterSpacing = 1.6.sp, color = c.muted,
            modifier = Modifier.padding(start = SideStart).semantics { contentDescription = "Search" },
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = sv.input,
            onValueChange = sv::onFieldChange,
            modifier = Modifier.padding(start = SideStart, end = SideEnd).fillMaxWidth().focusRequester(fieldRequester),
            singleLine = true,
            shape = CircleShape,
            colors = searchFieldColors(),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, lineHeight = 24.sp),
            placeholder = { M3Text("Books, series or narrators", color = c.muted) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = c.muted, modifier = Modifier.size(26.dp)) },
            trailingIcon = if (results != null && searchable && drill == null) {
                { M3Text(if (total == 1) "1 result" else "$total results", fontSize = 13.sp, color = c.muted, modifier = Modifier.padding(end = 8.dp)) }
            } else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { sv.submit(); keyboard?.hide() }),
        )
        Spacer(Modifier.height(16.dp))

        val a = vm.api
        when {
            drill != null -> DrillView(
                drill, a, progress, listState, ::req,
                onOpen = { b -> sv.restoreKey = "book:${b.id}"; vm.openBook(b) },
            )
            !searchable -> StartView(
                sv.recents, ::req,
                onRecent = { sv.useRecent(it) }, onClear = { sv.clearRecents(); scope.launch { fieldRequester.requestWhenReady() } },
            )
            phase is SearchPhase.Failed -> MessageView(
                title = "Search didn't work", body = "${a?.baseUrl.orEmpty().removePrefix("http://").removePrefix("https://")} didn't answer. Try again in a moment.",
                button = "Try again", requester = req("retry"),
            ) { sv.retry() }
            results == null -> {
                if (sv.searching) Text("Searching…", color = c.muted, modifier = Modifier.padding(start = SideStart))
            }
            total == 0 -> NoResultsView(
                shownQuery, sv.recents, ::req,
                onEdit = { sv.selectAllText(); openKeyboard() }, onRecent = { sv.useRecent(it) },
            )
            imeVisible -> TypingPreview(results, a)
            else -> ResultsView(
                results, shownQuery, a, progress, listState, rowStates, ::req,
                onBook = { b -> sv.rememberQuery(sv.query); sv.restoreKey = "book:${b.id}"; vm.openBook(b) },
                onSeries = { m -> sv.openSeries(m, "series:${m.series.id}") { vm.openSeries(it) } },
                onNarrator = { m -> sv.openNarrator(m, "narr:${m.name}") },
            )
        }
    }
}

private const val BOOKS_PER_ROW = 5

// --- Pieces --------------------------------------------------------------------------------------------------------

@Composable
private fun StartView(recents: List<String>, req: (String) -> FocusRequester, onRecent: (String) -> Unit, onClear: () -> Unit) {
    val c = LocalAbsColors.current
    Column(Modifier.padding(start = SideStart, end = SideEnd), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Type to search. The keyboard's mic key searches by voice.", fontSize = 15.sp, color = c.muted)
        if (recents.isNotEmpty()) {
            Text("RECENT SEARCHES", fontSize = 12.sp, letterSpacing = 1.6.sp, color = c.muted)
            RecentChips(recents, req, onRecent, onClear)
        }
    }
}

@Composable
private fun RecentChips(recents: List<String>, req: (String) -> FocusRequester, onRecent: (String) -> Unit, onClear: (() -> Unit)?) {
    LazyRow(
        contentPadding = PaddingValues(end = SideEnd),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(recents, key = { it }) { q -> Chip(q, req("recent:$q")) { onRecent(q) } }
        if (onClear != null) item(key = "\u0000clear") {
            Chip("Clear", req("clear"), icon = true, borderless = true, onClick = onClear)
        }
    }
}

@Composable
private fun Chip(text: String, requester: FocusRequester, icon: Boolean = false, borderless: Boolean = false, onClick: () -> Unit) {
    val c = LocalAbsColors.current
    Surface(
        onClick = onClick,
        modifier = Modifier.height(40.dp).focusRequester(requester),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (borderless) androidx.compose.ui.graphics.Color.Transparent else c.surface,
            contentColor = if (borderless) c.muted else c.onSurface,
            focusedContainerColor = c.accent, focusedContentColor = c.onAccent,
            pressedContainerColor = c.accent, pressedContentColor = c.onAccent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Row(Modifier.padding(horizontal = 16.dp).height(40.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MessageView(title: String, body: String, button: String, requester: FocusRequester, onClick: () -> Unit) {
    val c = LocalAbsColors.current
    Column(Modifier.padding(start = SideStart, end = SideEnd), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, fontFamily = FontFamily.Serif, fontSize = 26.sp, lineHeight = 32.sp)
        Text(body, fontSize = 15.sp, color = c.muted)
        AbsButton(onClick = onClick, modifier = Modifier.focusRequester(requester)) { Text(button) }
    }
}

@Composable
private fun NoResultsView(
    q: String, recents: List<String>, req: (String) -> FocusRequester,
    onEdit: () -> Unit, onRecent: (String) -> Unit,
) {
    val c = LocalAbsColors.current
    Column(Modifier.fillMaxSize()) {
        MessageView(
            title = "Nothing matches “$q”",
            body = "Check the spelling, or try part of a title, a series or a narrator's name.",
            button = "Edit search", requester = req("edit"), onClick = onEdit,
        )
        if (recents.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("RECENT SEARCHES", fontSize = 12.sp, letterSpacing = 1.6.sp, color = c.muted, modifier = Modifier.padding(start = SideStart))
            Spacer(Modifier.height(12.dp))
            Box(Modifier.padding(start = SideStart)) { RecentChips(recents, req, onRecent, null) }
        }
    }
}

@Composable
private fun GroupHeading(title: String, count: Int) {
    val c = LocalAbsColors.current
    Row(Modifier.padding(start = SideStart), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, fontFamily = FontFamily.Serif, fontSize = 18.sp, lineHeight = 22.sp, color = c.accent)
        Text(count.toString(), fontSize = 13.sp, color = c.muted)
    }
}

@Composable
private fun ResultsView(
    r: SearchResult, q: String, api: AbsApi?, progress: ProgressMap, listState: LazyListState, rowStates: List<LazyListState>,
    req: (String) -> FocusRequester,
    onBook: (Book) -> Unit, onSeries: (SeriesMatch) -> Unit, onNarrator: (NarratorMatch) -> Unit,
) {
    val rowPad = PaddingValues(start = SideStart, end = SideEnd)
    // Each group's heading shares a list item with its row; scroll that item to the top when focus enters the row.
    val seriesIdx = if (r.book.isNotEmpty()) 1 else 0
    val narratorIdx = seriesIdx + if (r.series.isNotEmpty()) 1 else 0
    LazyColumn(
        Modifier.fillMaxSize(), state = listState,
        contentPadding = PaddingValues(bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (r.book.isNotEmpty()) item(key = "g-books") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GroupHeading("Books", r.book.size)
                LazyRow(state = rowStates[0], modifier = Modifier.onFocusEntered { listState.animateScrollToItem(0) }, contentPadding = rowPad, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(r.book, key = { it.libraryItem.id }) { m ->
                        val b = m.libraryItem
                        BookCard(b, api?.bookCoverUrl(b.id), { onBook(b) }, {}, focusRequester = req("book:${b.id}"), progress = progress[b.id], highlight = q)
                    }
                }
            }
        }
        if (r.series.isNotEmpty()) item(key = "g-series") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GroupHeading("Series", r.series.size)
                LazyRow(state = rowStates[1], modifier = Modifier.onFocusEntered { listState.animateScrollToItem(seriesIdx) }, contentPadding = rowPad, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(r.series, key = { it.series.id }) { m ->
                        SeriesResultCard(m, api, q, req("series:${m.series.id}")) { onSeries(m) }
                    }
                }
            }
        }
        if (r.narrators.isNotEmpty()) item(key = "g-narrators") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GroupHeading("Narrators", r.narrators.size)
                LazyRow(state = rowStates[2], modifier = Modifier.onFocusEntered { listState.animateScrollToItem(narratorIdx) }, contentPadding = rowPad, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    items(r.narrators, key = { it.name }) { m ->
                        NarratorPill(m, q, req("narr:${m.name}")) { onNarrator(m) }
                    }
                }
            }
        }
    }
}

/** Shown while the keyboard covers the lower half: a compact Books preview plus a count of the other groups. */
@Composable
private fun TypingPreview(r: SearchResult, api: AbsApi?) {
    val c = LocalAbsColors.current
    val other = buildList {
        if (r.series.isNotEmpty()) add("${r.series.size} series")
        if (r.narrators.isNotEmpty()) add(if (r.narrators.size == 1) "1 narrator" else "${r.narrators.size} narrators")
    }.joinToString(" · ")
    Row(Modifier.padding(start = SideStart, end = SideEnd), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            r.book.take(4).forEach { m ->
                val b = m.libraryItem
                Column(Modifier.width(96.dp)) {
                    AsyncImage(
                        model = api?.coverUrl(b.id), contentDescription = b.title, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(96.dp).clip(RoundedCornerShape(6.dp)).background(c.surface),
                    )
                    Text(b.title, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (other.isNotEmpty()) {
                Text("ALSO MATCHING", fontSize = 12.sp, letterSpacing = 1.6.sp, color = c.muted)
                Text(other, fontSize = 15.sp)
            }
            Text("Press Back or the keyboard's search key to close it and see everything.", fontSize = 13.sp, color = c.muted)
        }
    }
}

@Composable
private fun DrillView(
    d: Drill, api: AbsApi?, progress: ProgressMap, listState: LazyListState, req: (String) -> FocusRequester, onOpen: (Book) -> Unit,
) {
    val c = LocalAbsColors.current
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = SideStart, end = SideEnd), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(d.overline, fontSize = 12.sp, letterSpacing = 1.6.sp, color = c.muted)
            Text(d.name, fontFamily = FontFamily.Serif, fontSize = 26.sp, lineHeight = 32.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    d.failed -> "Couldn't load these books. Press Back and try again."
                    d.loading -> "Loading…"
                    else -> bookCountLabel(d.books.size)
                },
                fontSize = 13.sp, color = if (d.failed) c.error else c.muted,
            )
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(
            Modifier.fillMaxSize(), state = listState,
            contentPadding = PaddingValues(start = SideStart, end = SideEnd, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(d.books.chunked(BOOKS_PER_ROW), key = { row -> row.first().id }) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { b -> BookCard(b, api?.bookCoverUrl(b.id), { onOpen(b) }, {}, focusRequester = req("book:${b.id}"), progress = progress[b.id]) }
                }
            }
        }
    }
}

// --- Cards ---------------------------------------------------------------------------------------------------------

@Composable
private fun SeriesResultCard(m: SeriesMatch, api: AbsApi?, query: String, requester: FocusRequester, onClick: () -> Unit) {
    val c = LocalAbsColors.current
    var focused by remember { mutableStateOf(false) }
    val first = m.books.firstOrNull()
    Surface(
        onClick = onClick,
        modifier = Modifier.width(300.dp).height(64.dp).focusRequester(requester).onFocusChanged { focused = it.hasFocus },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = c.surface, contentColor = c.onSurface,
            focusedContainerColor = c.accent, focusedContentColor = c.onAccent,
            pressedContainerColor = c.accent, pressedContentColor = c.onAccent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Row(Modifier.fillMaxSize().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(c.bg)) {
                if (first != null && first.media.coverPath != null) {
                    AsyncImage(
                        model = api?.coverUrl(first.id), contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    highlighted(m.series.name, query, focused), fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(bookCountLabel(m.books.size), fontSize = 12.sp, color = if (focused) c.onAccent else c.muted)
            }
        }
    }
}

@Composable
private fun NarratorPill(m: NarratorMatch, query: String, requester: FocusRequester, onClick: () -> Unit) {
    val c = LocalAbsColors.current
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.height(48.dp).focusRequester(requester).onFocusChanged { focused = it.hasFocus },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(24.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = c.surface, contentColor = c.onSurface,
            focusedContainerColor = c.accent, focusedContentColor = c.onAccent,
            pressedContainerColor = c.accent, pressedContentColor = c.onAccent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Row(Modifier.padding(start = 8.dp, end = 18.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(c.bg), contentAlignment = Alignment.Center) {
                Text(initialsOf(m.name), fontFamily = FontFamily.Serif, fontSize = 13.sp, color = c.accent)
            }
            Text(highlighted(m.name, query, focused), fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(bookCountLabel(m.numBooks), fontSize = 13.sp, color = if (focused) c.onAccent else c.muted)
        }
    }
}
