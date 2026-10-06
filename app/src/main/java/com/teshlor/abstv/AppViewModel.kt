package com.teshlor.abstv

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class AppViewModel(app: Application) : AndroidViewModel(app) {
    // One "abs" file: `prefs` for non-token keys (username, library_id), `store` for server + token pair.
    private val prefs = app.getSharedPreferences("abs", Context.MODE_PRIVATE)
    private val store: TokenStore = SharedPrefsTokenStore(prefs)

    /** True after the server rejected the refresh token; the UI is already back on Login showing [error]. */
    var authExpired by mutableStateOf(false); private set

    // Fires on an OkHttp thread; hop to Main before touching state.
    private val authSession = AuthSession(store) { viewModelScope.launch { handleAuthExpired() } }

    val stack = NavStack(Screen.Login)
    val screen: Screen get() = stack.current

    /** Last focused item id per screen key. Plain map on purpose: focus changes must not recompose anything. */
    val lastFocused = HashMap<String, String>()

    var username by mutableStateOf(prefs.getString("username", "").orEmpty()); private set
    private var lastLoadedAt = 0L

    /** Bumped on logout and library switch; Root keys saved screen state by it so scroll positions reset. */
    var stateEpoch by mutableStateOf(0); private set
    var api by mutableStateOf<AbsApi?>(null); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    var libraries by mutableStateOf<List<Library>>(emptyList()); private set
    var selectedLibrary by mutableStateOf<Library?>(null); private set
    /** Home shelves in the server's order, and the signed-in user's per-book progress (empty if /api/me failed). */
    var shelves by mutableStateOf<List<Shelf>>(emptyList()); private set
    var progress by mutableStateOf(ProgressMap()); private set

    /** Server's ignore-prefix sorting settings (for the A-Z jump). Off until read from /api/authorize. */
    var sortingSettings by mutableStateOf(SortingSettings.OFF); private set
    var selectedBook by mutableStateOf<Book?>(null); private set

    val player = PlayerController(app)

    /** Set only by the debug-build `--es theme` QA hook; null means follow the date. */
    var forcedTheme: AbsTheme? = null
    var theme by mutableStateOf(themeFor()); private set

    /** Re-checks the date (launch and every onStart, so a TV left on overnight picks up the change). */
    fun refreshTheme() {
        theme = forcedTheme ?: themeFor()
    }

    val savedServer: String get() = store.server

    init {
        // A pre-Auth-v2 install has only the legacy token, which the store serves as the access token.
        val server = store.server
        if (server.isNotEmpty() && !store.accessToken.isNullOrEmpty()) {
            api = AbsApi(server, authSession)
            stack.reset(Screen.Browse(Tab.HOME))
            loadHome()
            loadSortingSettings(api!!)
            // Installs from before the nav work never stored a username; backfill it once for Settings.
            if (username.isEmpty()) backfillUsername(api!!)
        }
    }

    private fun backfillUsername(a: AbsApi) {
        viewModelScope.launch {
            val name = try {
                a.me().username
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ""
            }
            if (name.isNotEmpty() && api === a) {
                username = name
                prefs.edit().putString("username", name).apply()
            }
        }
    }

    // --- Session storage. Never writes the legacy "token" key; saveTokens also drops it. ---
    private fun storeSession(a: AbsApi, r: LoginResult) {
        store.server = a.baseUrl
        store.saveTokens(r.accessToken, r.refreshToken, r.username)
    }

    private fun clearSession() {
        store.clearTokens()
        prefs.edit().remove("username").remove("recent_searches").apply()
    }

    private fun launchLoading(block: suspend () -> Unit) {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                block()
            } catch (e: Exception) {
                if (shouldShowError(authExpired, e)) error = e.message ?: e.toString()
            } finally {
                loading = false
            }
        }
    }

    fun login(server: String, username: String, password: String) = launchLoading {
        authExpired = false
        val r = AbsApi(server).login(username, password)
        val a = AbsApi(server, authSession)
        storeSession(a, r)
        api = a
        // The login reply already carries the username (falls back to what was typed), so no /api/me round trip.
        this.username = r.username?.takeIf { it.isNotEmpty() } ?: username
        stack.reset(Screen.Browse(Tab.HOME))
        loadSortingSettings(a)
        loadHomeInternal(a)
    }

    private fun handleAuthExpired() {
        if (api == null) return // already signed out
        logout()
        authExpired = true
        error = "Please sign in again"
    }

    fun logout() {
        player.stop()
        clearSession()
        api = null
        username = ""
        libraries = emptyList(); clearLibraryData()
        selectedLibrary = null
        sortingSettings = SortingSettings.OFF
        collectionsCache = null
        lastFocused.clear()
        error = null
        stack.reset(Screen.Login) // fires onRemoved with the current epoch...
        stateEpoch++ // ...so bump only afterwards
    }

    /** Series tab data, created lazily per library and dropped on Refresh, library switch and logout. */
    private var seriesPagerHolder: Pager<Series>? = null

    /** Series detail books by series id (sequence-sorted); same lifetime as [seriesPager]. Main-thread only. */
    val seriesBooksCache = HashMap<String, List<Book>>()

    fun seriesPager(): Pager<Series>? {
        seriesPagerHolder?.let { return it }
        val a = api ?: return null
        val lib = selectedLibrary ?: return null
        return Pager<Series>(viewModelScope, pageSize = 30) { page, limit -> a.series(lib.id, page, limit) }
            .also { seriesPagerHolder = it; it.loadMore() }
    }

    private fun clearLibraryData() {
        shelves = emptyList(); progress = ProgressMap()
        seriesPagerHolder = null
        seriesBooksCache.clear()
    }

    /** Settings > Refresh library. */
    fun refreshLibrary() {
        seriesPagerHolder = null
        seriesBooksCache.clear()
        collectionsCache = null
        libraryTab = null
        api?.let { loadSortingSettings(it) }
        loadHome()
    }

    private var libraryTab: LibraryTabState? = null
    private var libraryTabKey: Triple<Int, AbsApi, String>? = null

    /** Library tab state: built on first use, kept per (epoch, api, library), dropped by [refreshLibrary]. */
    fun libraryTabState(): LibraryTabState? {
        val a = api ?: return null
        val lib = selectedLibrary ?: return null
        val key = Triple(stateEpoch, a, lib.id)
        libraryTab?.let { if (libraryTabKey == key) return it }
        return LibraryTabState(
            scope = viewModelScope,
            initialSort = SortSpec.decode(prefs.getString("library_sort", null)),
            saveSort = { prefs.edit().putString("library_sort", it.encode()).apply() },
            loader = { page, limit, sort, filter ->
                a.libraryItems(lib.id, page, limit, sort.sort.apiKey, sort.desc, filter.apiFilter)
            },
            fetchProgress = { refreshProgress() },
        ).also { libraryTab = it; libraryTabKey = key }
    }

    fun loadHome() = launchLoading { loadHomeInternal(api ?: return@launchLoading) }

    private suspend fun loadHomeInternal(a: AbsApi) {
        // Audio libraries only: ebook-only libraries are hidden from the switcher.
        libraries = a.audioLibraries().filter { it.hasAudio }
        val wanted = selectedLibrary?.id ?: prefs.getString("library_id", null)
        val lib = libraries.firstOrNull { it.id == wanted } ?: libraries.firstOrNull()
        selectedLibrary = lib
        if (lib != null) {
            prefs.edit().putString("library_id", lib.id).apply()
            loadLibrary(a, lib)
        }
        lastLoadedAt = System.currentTimeMillis()
    }

    /**
     * The sort setting is server-wide, so it is read once per session (login, restored session) and on Refresh library,
     * in the background: it never holds [loading] and a failure keeps the previous value (default off).
     */
    private fun loadSortingSettings(a: AbsApi) {
        viewModelScope.launch {
            try {
                sortingSettings = a.sortingSettings()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // keep the previous value
            }
        }
    }

    /** Re-reads /api/me into the shared [progress] map (every tab's cards read it) and returns it. Throws on failure. */
    suspend fun refreshProgress(): ProgressMap {
        val a = api ?: return progress
        return a.progress().also { progress = it }
    }

    /** [refreshProgress] for tabs that treat progress as decoration: a failure keeps the previous map. */
    suspend fun refreshProgressQuietly() {
        try {
            refreshProgress()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // keep what we have
        }
    }

    private suspend fun loadLibrary(a: AbsApi, lib: Library) {
        // One personalized() request feeds every Home shelf. Progress is best-effort: without it cards just show no bar.
        coroutineScope {
            val shelvesJob = async { a.personalized(lib.id) }
            val progressJob = async {
                try {
                    a.progress()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
            shelves = shelvesJob.await()
            progressJob.await()?.let { progress = it }
        }
    }

    /** Switches library, persists the choice and lands on Home. */
    fun selectLibrary(lib: Library) {
        selectedLibrary = lib
        prefs.edit().putString("library_id", lib.id).apply()
        clearLibraryData()
        collectionsCache = null
        lastFocused.clear()
        stack.selectTab(Tab.HOME) // fires onRemoved with the current epoch...
        stateEpoch++ // ...so bump only afterwards
        launchLoading { loadLibrary(api ?: return@launchLoading, lib) }
    }

    /** Rail tab switch. Returning to Home after a while refreshes it (launch, after playback and this are the only auto-refreshes). */
    fun selectTab(tab: Tab) {
        stack.selectTab(tab)
        if (tab == Tab.HOME && System.currentTimeMillis() - lastLoadedAt > 5 * 60_000L) loadHome()
    }

    private var collectionsCache: Pair<String, Pager<BookCollection>>? = null

    /** Collections tab pager for the selected library: created lazily, cached per library, cleared on Refresh, library switch and logout. */
    fun collectionsPager(): Pager<BookCollection>? {
        val a = api ?: return null
        val lib = selectedLibrary ?: return null
        collectionsCache?.takeIf { it.first == lib.id }?.let { return it.second }
        val pager = Pager<BookCollection>(viewModelScope, 30) { page, limit -> a.collections(lib.id, page, limit) }
        collectionsCache = lib.id to pager
        pager.loadMore()
        return pager
    }

    fun openCollection(collection: BookCollection) = stack.push(Screen.CollectionBooks(collection))

    fun openBook(book: Book) {
        selectedBook = book
        stack.push(Screen.Detail)
        val a = api ?: return
        viewModelScope.launch {
            runCatching { a.item(book.id) }.onSuccess { selectedBook = it }
        }
    }

    fun openSeries(series: Series) {
        stack.push(Screen.SeriesBooks(series.id, series.name))
    }

    /** Series detail's Continue button: plays [book] without going through Book Detail. */
    fun playBook(book: Book) {
        selectedBook = book
        play()
    }

    fun play() {
        val a = api ?: return
        val book = selectedBook ?: return
        launchLoading {
            player.start(a, book.id)
            stack.push(Screen.Player)
        }
    }

    /** Back for Detail, Player and SeriesBooks (Browse and Login are handled by the shell). */
    fun back() {
        when (screen) {
            Screen.Player -> {
                player.stop()
                stack.pop() // Player
                if (screen == Screen.Detail) stack.pop() // Series detail plays without a Detail page
                loadHome() // refresh Continue Listening
            }
            Screen.Detail, is Screen.SeriesBooks, is Screen.CollectionBooks -> stack.pop()
            else -> Unit
        }
    }

    override fun onCleared() {
        player.release()
    }
}
