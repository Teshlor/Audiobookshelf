package com.teshlor.abstv

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

sealed interface Screen {
    data object Login : Screen
    data object Home : Screen
    data object Detail : Screen
    data object Player : Screen
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("abs", Context.MODE_PRIVATE)

    var screen by mutableStateOf<Screen>(Screen.Login); private set
    var api by mutableStateOf<AbsApi?>(null); private set
    var loading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    var libraries by mutableStateOf<List<Library>>(emptyList()); private set
    var selectedLibrary by mutableStateOf<Library?>(null); private set
    var books by mutableStateOf<List<Book>>(emptyList()); private set
    var continueListening by mutableStateOf<List<Book>>(emptyList()); private set
    var selectedBook by mutableStateOf<Book?>(null); private set

    val player = PlayerController(app)

    val savedServer: String get() = prefs.getString("server", "").orEmpty()

    init {
        val server = prefs.getString("server", null)
        val token = prefs.getString("token", null)
        if (!server.isNullOrEmpty() && !token.isNullOrEmpty()) {
            api = AbsApi(server, token)
            screen = Screen.Home
            loadHome()
        }
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
        prefs.edit().putString("server", a.baseUrl).putString("token", token).apply()
        api = a
        screen = Screen.Home
        loadHomeInternal(a)
    }

    fun logout() {
        player.stop()
        prefs.edit().remove("token").apply()
        api = null
        libraries = emptyList(); books = emptyList(); continueListening = emptyList()
        error = null
        screen = Screen.Login
    }

    fun loadHome() = launchLoading { loadHomeInternal(api ?: return@launchLoading) }

    private suspend fun loadHomeInternal(a: AbsApi) {
        libraries = a.libraries().filter { it.mediaType == "book" }
        val lib = selectedLibrary?.takeIf { l -> libraries.any { it.id == l.id } } ?: libraries.firstOrNull()
        selectedLibrary = lib
        if (lib != null) loadLibrary(a, lib)
    }

    private suspend fun loadLibrary(a: AbsApi, lib: Library) {
        books = a.items(lib.id)
        continueListening = a.continueListening(lib.id)
    }

    fun selectLibrary(lib: Library) = launchLoading {
        selectedLibrary = lib
        loadLibrary(api ?: return@launchLoading, lib)
    }

    fun openBook(book: Book) {
        selectedBook = book
        screen = Screen.Detail
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
            screen = Screen.Player
        }
    }

    fun back() {
        when (screen) {
            Screen.Player -> {
                player.stop()
                screen = Screen.Home
                loadHome() // refresh Continue Listening
            }
            Screen.Detail -> screen = Screen.Home
            else -> Unit
        }
    }

    override fun onCleared() {
        player.release()
    }
}
