package com.teshlor.abstv

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    var books by mutableStateOf<List<Book>>(emptyList()); private set
    var continueListening by mutableStateOf<List<Book>>(emptyList()); private set
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
        }
    }

    // --- Session storage. Never writes the legacy "token" key; saveTokens also drops it. ---
    private fun storeSession(a: AbsApi, r: LoginResult) {
        store.server = a.baseUrl
        store.saveTokens(r.accessToken, r.refreshToken, r.username)
    }

    private fun clearSession() {
        store.clearTokens()
        prefs.edit().remove("username").apply()
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
        libraries = emptyList(); books = emptyList(); continueListening = emptyList()
        selectedLibrary = null
        lastFocused.clear()
        stateEpoch++
        error = null
        stack.reset(Screen.Login)
    }

    /** Settings > Refresh library. */
    fun refreshLibrary() {
        libraryTab = null
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
            fetchProgress = { a.progress() },
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

    private suspend fun loadLibrary(a: AbsApi, lib: Library) {
        books = a.items(lib.id)
        continueListening = a.continueListening(lib.id)
    }

    /** Switches library, persists the choice and lands on Home. */
    fun selectLibrary(lib: Library) {
        selectedLibrary = lib
        prefs.edit().putString("library_id", lib.id).apply()
        books = emptyList(); continueListening = emptyList()
        lastFocused.clear()
        stateEpoch++
        stack.selectTab(Tab.HOME)
        launchLoading { loadLibrary(api ?: return@launchLoading, lib) }
    }

    /** Rail tab switch. Returning to Home after a while refreshes it (launch, after playback and this are the only auto-refreshes). */
    fun selectTab(tab: Tab) {
        stack.selectTab(tab)
        if (tab == Tab.HOME && System.currentTimeMillis() - lastLoadedAt > 5 * 60_000L) loadHome()
    }

    fun openBook(book: Book) {
        selectedBook = book
        stack.push(Screen.Detail)
        val a = api ?: return
        viewModelScope.launch {
            runCatching { a.item(book.id) }.onSuccess { selectedBook = it }
        }
    }

    fun play() {
        val a = api ?: return
        val book = selectedBook ?: return
        launchLoading {
            player.start(a, book.id)
            stack.push(Screen.Player)
        }
    }

    /** Back for Detail and Player (Browse and Login are handled by the shell). */
    fun back() {
        when (screen) {
            Screen.Player -> {
                player.stop()
                stack.pop() // Player
                stack.pop() // Detail
                loadHome() // refresh Continue Listening
            }
            Screen.Detail -> stack.pop()
            else -> Unit
        }
    }

    override fun onCleared() {
        player.release()
    }
}
