package com.teshlor.abstv

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(app: Application) : AndroidViewModel(app) {
    // One "abs" file: device-wide keys (theme_override, last_server), the profile list, and per-profile `key@<profileId>` settings.
    private val prefs = app.getSharedPreferences("abs", Context.MODE_PRIVATE)
    private val kv = SharedPrefsKeyValue(prefs)

    // Moves a single-session install to profile #1 BEFORE the store reads the file, so the user stays signed in.
    private val profileStore: ProfileStore = run { migrateLegacy(kv); PrefsProfileStore(kv) }
    private val pins = PinService(profileStore)

    /** True after the server rejected the refresh token; the UI is already on the sign-in-again screen. */
    var authExpired by mutableStateOf(false); private set

    /** Cancelled and replaced on every profile switch / sign-out, so no load or pager of the old profile can land in the new one. */
    private var sessionScope = newSessionScope()
    private fun newSessionScope() = CoroutineScope(
        viewModelScope.coroutineContext.minusKey(Job) + SupervisorJob(viewModelScope.coroutineContext[Job]),
    )

    /** One AuthSession + AbsApi per profile, so a late close/refresh/expiry of profile A only ever touches A. Main thread only. */
    private val apis = HashMap<String, AbsApi>()
    private fun apiFor(p: Profile): AbsApi = apis.getOrPut(p.id) {
        // onExpired fires on an OkHttp thread; hop to Main before touching state.
        AbsApi(p.server, AuthSession(ProfileTokenStore(profileStore, p.id)) { viewModelScope.launch { onProfileExpired(p.id) } })
    }

    val stack = NavStack(Screen.Login)

    /** Set by the shell while it is composed: Right on the open rail returns to the content. Returns true if it handled the key. */
    var railRightHandler: (() -> Boolean)? = null
    val screen: Screen get() = stack.current

    /** Last focused item id per screen key. Plain map on purpose: focus changes must not recompose anything. */
    val lastFocused = HashMap<String, String>()

    /** The open profile's username (may be empty for a migrated profile until /api/me backfills it). */
    var username by mutableStateOf(""); private set
    private var lastLoadedAt = 0L

    /** Bumped on sign-out, profile switch and library switch; Root keys saved screen state by it so scroll positions reset. */
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
    /** The user's pinned theme from Settings (pref `theme_override`); null = Auto by date. Device-wide: not per profile, kept across sign-out. */
    var themeChoice by mutableStateOf(AbsTheme.fromKey(prefs.getString("theme_override", null))); private set
    var theme by mutableStateOf(resolveTheme(null, themeChoice)); private set

    fun chooseTheme(choice: AbsTheme?) {
        themeChoice = choice
        prefs.edit().apply { if (choice == null) remove("theme_override") else putString("theme_override", choice.name.lowercase()) }.apply()
        refreshTheme()
    }

    /** Re-checks the date (launch and every onStart, so a TV left on overnight picks up the change). */
    fun refreshTheme() {
        theme = resolveTheme(forcedTheme, themeChoice)
    }

    // --- Profiles ---

    /** Everyone saved on this TV, in the order they were added. Fed to the picker and the rail. */
    var profiles by mutableStateOf<List<ProfileSummary>>(emptyList()); private set

    /** The profile whose library is open, or null on the picker / login. Keys the per-profile settings. */
    var currentProfileId by mutableStateOf<String?>(null); private set

    /** What the Login screen starts with (first run, add profile, or sign in again). */
    var loginPrefill by mutableStateOf(LoginPrefill()); private set

    /** SystemClock.elapsedRealtime() of the last onStop, null before the first one. */
    private var backgroundedAt: Long? = null

    private val lastServer: String get() = prefs.getString(ProfileKeys.LAST_SERVER, null).orEmpty()

    private fun getScoped(key: String, pid: String? = currentProfileId): String? =
        pid?.let { prefs.getString(ProfileKeys.scoped(key, it), null) }

    private fun putScoped(key: String, value: String, pid: String? = currentProfileId) {
        if (pid != null) prefs.edit().putString(ProfileKeys.scoped(key, pid), value).apply()
    }

    private fun refreshProfiles() {
        profiles = profileSummaries(profileStore.all(), currentProfileId, profileStore.activeId)
    }

    /** Lands on the right screen for what is stored: Login, the picker, or straight into the one signed-in profile. */
    private fun routeFromStorage() {
        val all = profileStore.all()
        when (startDestination(all)) {
            Screen.Profiles -> showScreenAfterReset(Screen.Profiles)
            is Screen.Browse -> enterProfile(resolveActive(all, profileStore.activeId)!!)
            else -> {
                val p = all.firstOrNull()
                loginPrefill = if (p != null) loginPrefillFor(LoginMode.REAUTH, all, null, lastServer, p.id)
                else loginPrefillFor(LoginMode.FIRST, all, null, lastServer)
                showScreenAfterReset(Screen.Login)
            }
        }
        refreshProfiles()
    }

    private fun showScreenAfterReset(screen: Screen) {
        resetSessionState()
        stack.reset(screen) // fires onRemoved with the current epoch...
        stateEpoch++ // ...so bump only afterwards
    }

    /** Drops everything that belongs to the open profile. Does not touch the stack or the stored profiles. */
    private fun resetSessionState() {
        loginJob?.cancel()
        player.stop()
        sessionScope.cancel()
        sessionScope = newSessionScope()
        loading = false
        api = null
        currentProfileId = null
        username = ""
        libraries = emptyList(); clearLibraryData()
        selectedLibrary = null
        selectedBook = null
        sortingSettings = SortingSettings.OFF
        collectionsCache = null
        libraryTab = null; libraryTabKey = null
        lastFocused.clear()
        error = null
    }

    /** Opens [p]'s library (Home) as the active profile. [p] must have a session. */
    private fun enterProfile(p: Profile) {
        resetSessionState()
        authExpired = false
        profileStore.setActive(p.id)
        currentProfileId = p.id
        val a = apiFor(p)
        api = a
        username = p.username
        loginPrefill = LoginPrefill()
        stack.reset(Screen.Browse(Tab.HOME)) // fires onRemoved with the current epoch...
        stateEpoch++ // ...so bump only afterwards
        refreshProfiles()
        loadSortingSettings(a)
        loadHome()
        backfillIdentity(p.id, a)
    }

    /**
     * Fills in what a migrated profile never stored (user id, and a username an old install may lack) from /api/me.
     * An empty id counts as absent.
     */
    private fun backfillIdentity(pid: String, a: AbsApi) {
        val p = profileStore.get(pid) ?: return
        if (!p.userId.isNullOrEmpty() && p.username.isNotEmpty()) return
        sessionScope.launch {
            val me = try {
                a.me()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }
            if (me.id.isNotEmpty() && profileStore.get(pid)?.userId.isNullOrEmpty()) profileStore.setUserId(pid, me.id)
            if (me.username.isNotEmpty() && profileStore.get(pid)?.username.isNullOrEmpty()) {
                profileStore.setUsername(pid, me.username)
                if (currentProfileId == pid) username = me.username
            }
            refreshProfiles()
        }
    }

    /**
     * Switches to profile [id]. A PIN-protected profile needs the right [pin] (see [SwitchResult]). On NeedsSignIn nothing
     * changes: call [startReauth]. Switching to the profile that is already open is a no-op Switched.
     */
    suspend fun switchProfile(id: String, pin: String? = null): SwitchResult {
        if (id == currentProfileId && api != null) return SwitchResult.Switched
        val r = withContext(Dispatchers.Default) { pins.gate(id, pin) } // PBKDF2 stays off the main thread
        if (r == SwitchResult.Switched) {
            val p = profileStore.get(id)
            if (p == null || p.needsSignIn) return SwitchResult.NeedsSignIn
            enterProfile(p)
        } else {
            refreshProfiles() // attempts / cooldown changed
        }
        return r
    }

    /** Add profile: opens the sign-in form on top of the current screen (Cancel = [cancelLogin]). The open session is kept. */
    fun startAddProfile() {
        loginPrefill = loginPrefillFor(LoginMode.ADD, profileStore.all(), currentProfileId, lastServer)
        error = null
        if (screen != Screen.Login) stack.push(Screen.Login)
    }

    /** Sign in again for [profileId] (expired session, or forgot PIN: a successful sign-in also clears its PIN). */
    fun startReauth(profileId: String) {
        loginPrefill = loginPrefillFor(LoginMode.REAUTH, profileStore.all(), currentProfileId, lastServer, profileId)
        error = null
        if (screen != Screen.Login) stack.push(Screen.Login)
    }

    /** Cancel / Back on an ADD or REAUTH sign-in that was pushed over another screen. False when there is nothing to go back to. */
    fun cancelLogin(): Boolean {
        if (screen != Screen.Login) return false
        loginJob?.cancel() // a cancelled Add profile must not sign in afterwards
        loading = false
        error = null
        if (!stack.pop()) return false
        loginPrefill = LoginPrefill()
        return true
    }

    /** Sign out of the open profile: removes it (and its per-profile settings) from this TV. Server-side progress is untouched. */
    fun signOutCurrent() {
        currentProfileId?.let { removeProfile(it) }
    }

    /** Removes [id] from this TV (also what "Remove from this TV" on the sign-in-again screen calls). */
    fun removeProfile(id: String) {
        val wasCurrent = id == currentProfileId
        val onLogin = screen == Screen.Login
        if (wasCurrent) resetSessionState()
        apis.remove(id)
        profileStore.remove(id)
        if (wasCurrent || onLogin) {
            authExpired = false
            routeFromStorage()
        } else {
            refreshProfiles()
        }
    }

    /** The server rejected [pid]'s refresh token (tokens are already cleared; the profile stays and now needs sign-in). */
    fun onProfileExpired(pid: String) {
        if (profileStore.get(pid) == null) return
        if (pid != currentProfileId) {
            refreshProfiles()
            return
        }
        resetSessionState()
        authExpired = true
        loginPrefill = loginPrefillFor(LoginMode.REAUTH, profileStore.all(), null, lastServer, pid)
        stack.reset(Screen.Login)
        stateEpoch++
        refreshProfiles()
    }

    /** Optional 4-digit PIN for [id]. False if it isn't 4 digits. Not wired to any screen yet. */
    fun setPin(id: String, pin: String): Boolean = pins.setPin(id, pin).also { refreshProfiles() }
    fun clearPin(id: String) { pins.clearPin(id); refreshProfiles() }

    /** Wall-clock ms until which PIN entry for [id] is locked, or null. For the picker's countdown. */
    fun pinCooldownUntil(id: String): Long? = pins.cooldownUntil(id)

    /** MainActivity.onStop (not a config change). [nowMs] = SystemClock.elapsedRealtime(). */
    fun onAppBackground(nowMs: Long) { backgroundedAt = nowMs }

    /**
     * MainActivity.onStart. After an hour or more away the picker comes back (see [shouldShowPickerOnForeground]); the
     * open profile is closed first, so the next person starts clean. Returns true if it did.
     */
    fun onAppForeground(nowMs: Long): Boolean {
        val since = backgroundedAt
        backgroundedAt = null
        if (!shouldShowPickerOnForeground(profileStore.all(), since, nowMs, player.isPlaying, screen, stack.size)) return false
        showScreenAfterReset(Screen.Profiles)
        refreshProfiles()
        return true
    }

    // --- Sign-in and library data ---
    private fun launchLoading(block: suspend () -> Unit) {
        val scope = sessionScope
        scope.launch {
            loading = true
            error = null
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (shouldShowError(authExpired, e)) error = e.message ?: e.toString()
            } finally {
                if (scope === sessionScope) loading = false
            }
        }
    }

    /** Signs in and opens that profile. A login for an account already on this TV refreshes it in place (and clears its PIN). */
    private var loginJob: Job? = null

    fun login(server: String, username: String, password: String) {
        loginJob?.cancel()
        val reauthId = loginPrefill.takeIf { it.mode == LoginMode.REAUTH }?.profileId
        // Not in sessionScope: entering the profile resets that scope. Cancelled by cancelLogin() and resetSessionState().
        loginJob = viewModelScope.launch {
            loading = true
            error = null
            authExpired = false
            try {
                val r = AbsApi(server).login(username, password)
                val p = profileStore.upsertFromLogin(server, r, reauthId)
                prefs.edit().putString(ProfileKeys.LAST_SERVER, p.server).apply()
                enterProfile(p)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loading = false
                error = e.message ?: e.toString()
            }
        }
    }


    /** Series tab data, created lazily per library and dropped on Refresh, library switch and logout. */
    private var seriesPagerHolder: Pager<Series>? = null

    /** Series detail books by series id (sequence-sorted); same lifetime as [seriesPager]. Main-thread only. */
    val seriesBooksCache = HashMap<String, List<Book>>()

    fun seriesPager(): Pager<Series>? {
        seriesPagerHolder?.let { return it }
        val a = api ?: return null
        val lib = selectedLibrary ?: return null
        return Pager<Series>(sessionScope, pageSize = 30) { page, limit -> a.series(lib.id, page, limit) }
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
        val pid = currentProfileId
        val key = Triple(stateEpoch, a, lib.id)
        libraryTab?.let { if (libraryTabKey == key) return it }
        return LibraryTabState(
            scope = sessionScope,
            initialSort = SortSpec.decode(getScoped("library_sort", pid)),
            saveSort = { putScoped("library_sort", it.encode(), pid) },
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
        val pid = currentProfileId
        val wanted = selectedLibrary?.id ?: getScoped("library_id", pid)
        val lib = libraries.firstOrNull { it.id == wanted } ?: libraries.firstOrNull()
        selectedLibrary = lib
        if (lib != null) {
            putScoped("library_id", lib.id, pid)
            loadLibrary(a, lib)
        }
        lastLoadedAt = System.currentTimeMillis()
    }

    /**
     * The sort setting is server-wide, so it is read once per session (login, restored session) and on Refresh library,
     * in the background: it never holds [loading] and a failure keeps the previous value (default off).
     */
    private fun loadSortingSettings(a: AbsApi) {
        sessionScope.launch {
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
        val p = a.progress()
        if (api === a) progress = p // a switch while this was in flight must not land A's progress in B
        return p
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
        putScoped("library_id", lib.id)
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
        val pager = Pager<BookCollection>(sessionScope, 30) { page, limit -> a.collections(lib.id, page, limit) }
        collectionsCache = lib.id to pager
        pager.loadMore()
        return pager
    }

    fun openCollection(collection: BookCollection) = stack.push(Screen.CollectionBooks(collection))

    fun openBook(book: Book) {
        selectedBook = book
        stack.push(Screen.Detail)
        val a = api ?: return
        sessionScope.launch {
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

    init {
        // Last on purpose: every property above must exist before the first screen is chosen.
        routeFromStorage()
    }

    override fun onCleared() {
        player.release()
    }
}
