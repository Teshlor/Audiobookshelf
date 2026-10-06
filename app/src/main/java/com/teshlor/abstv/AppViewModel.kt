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
    private val prefs = app.getSharedPreferences("abs", Context.MODE_PRIVATE)

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

    val savedServer: String get() = prefs.getString("server", "").orEmpty()

    init {
        val server = prefs.getString("server", null)
        val token = prefs.getString("token", null)
        if (!server.isNullOrEmpty() && !token.isNullOrEmpty()) {
            api = AbsApi(server, token)
            stack.reset(Screen.Browse(Tab.HOME))
            loadHome()
        }
    }

    // --- Session storage: kept in these two small functions so the Auth v2 (JWT) change merges cleanly. ---
    private fun storeSession(a: AbsApi, token: String) {
        prefs.edit().putString("server", a.baseUrl).putString("token", token).apply()
    }

    private fun clearSession() {
        prefs.edit().remove("token").remove("username").apply()
    }

    private fun launchLoading(block: suspend () -> Unit) {
        viewModelScope.launch {
            loading = true
            error = null
            try {
                block()
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                loading = false
            }
        }
    }

    fun login(server: String, username: String, password: String) = launchLoading {
        val token = AbsApi(server).login(username, password)
        val a = AbsApi(server, token)
        storeSession(a, token)
        api = a
        val name = runCatching { a.me().username }.getOrNull()?.takeIf { it.isNotEmpty() } ?: username
        this.username = name
        prefs.edit().putString("username", name).apply()
        stack.reset(Screen.Browse(Tab.HOME))
        loadHomeInternal(a)
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
    fun refreshLibrary() = loadHome()

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
