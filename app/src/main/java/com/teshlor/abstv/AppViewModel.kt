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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The Switching screen stays up at least this long so it never flickers. */
const val SWITCH_MIN_MS = 600L

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
    /** Home / library loads (and Settings > Refresh). Sign-in has its own flag so the two can't clear each other's spinner. */
    var loading by mutableStateOf(false); private set
    /** A sign-in request is running (the Login button label). */
    var signingIn by mutableStateOf(false); private set
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
        switchJob?.cancel()
        player.stop()
        sessionScope.cancel()
        sessionScope = newSessionScope()
        loading = false
        signingIn = false
        pinTask = null; pinPending = null
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

    /** The profile whose library is open (Settings header, rail row), or null on the picker. */
    val currentProfile: ProfileSummary? get() = profiles.firstOrNull { it.isCurrent }

    /**
     * Someone was picked (picker tile, rail list). Expired -> Sign in again (no PIN first, the PIN stays set); PIN ->
     * the PIN screen; otherwise straight to the Switching screen. Picking the open profile does nothing.
     */
    fun chooseProfile(id: String) {
        val p = profileStore.get(id) ?: return
        when {
            p.needsSignIn -> startReauth(id)
            id == currentProfileId && api != null -> Unit
            p.hasPin -> startPinTask(PinTask(PinPurpose.SWITCH, id))
            else -> openProfile(id)
        }
    }

    private var switchJob: Job? = null

    /**
     * Shows the Switching screen for at least [SWITCH_MIN_MS] (so it never flickers), then opens [id]'s Home. Call only
     * once the PIN (if any) is proven. A session that died in the meantime goes to Sign in again.
     */
    fun openProfile(id: String) {
        val p = profileStore.get(id) ?: return
        if (p.needsSignIn) { startReauth(id); return }
        showScreenAfterReset(Screen.Switching(id))
        refreshProfiles()
        switchJob = viewModelScope.launch {
            delay(SWITCH_MIN_MS)
            val now = profileStore.get(id)
            when {
                now == null -> routeFromStorage()
                now.needsSignIn -> {
                    loginPrefill = loginPrefillFor(LoginMode.REAUTH, profileStore.all(), null, lastServer, id)
                    error = null
                    stack.reset(Screen.Login); stateEpoch++
                }
                else -> enterProfile(now)
            }
        }
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

    /**
     * Cancel / Back on a sign-in. Pushed over another screen (Add profile, Sign in again from the picker or rail, Forgot
     * PIN): pops back to it. A sign-in at the root (expired session) goes to the picker when others are saved. False when
     * there is nowhere to go (the only profile).
     */
    fun cancelLogin(): Boolean {
        if (screen != Screen.Login) return false
        if (stack.size <= 1 && profileStore.all().size < 2) return false
        loginJob?.cancel() // a cancelled Add profile must not sign in afterwards
        signingIn = false
        error = null
        if (stack.size > 1) {
            stack.pop()
            loginPrefill = LoginPrefill()
        } else {
            authExpired = false
            routeFromStorage()
        }
        return true
    }

    /** Sign out of the open profile: removes it (and its per-profile settings) from this TV. Server-side progress is untouched. */
    fun signOutCurrent() {
        currentProfileId?.let { removeProfile(it) }
    }

    /** Removes [id] from this TV (also what "Remove from this TV" on the sign-in-again screen calls). */
    fun removeProfile(id: String) {
        val wasCurrent = id == currentProfileId
        val onLogin = screen == Screen.Login || screen == Screen.Pin
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

    // --- PIN screens ---

    /** The PIN run in progress (what the PIN screen shows); null when no PIN screen is up. */
    var pinTask by mutableStateOf<PinTask?>(null); private set
    private var pinPending: PinStepResult? = null

    fun startPinTask(task: PinTask) {
        pinTask = task
        pinPending = null
        stack.push(Screen.Pin)
    }

    /** Settings rows. They act on the open profile. */
    fun startSetPin() { currentProfileId?.let { startPinTask(PinTask(PinPurpose.SET, it)) } }
    fun startChangePin() { currentProfileId?.let { startPinTask(PinTask(PinPurpose.CHANGE, it)) } }
    fun startRemovePin() { currentProfileId?.let { startPinTask(PinTask(PinPurpose.REMOVE_PIN, it)) } }

    /** "Remove from this TV" on a sign-in-again screen: asks for the PIN first when the profile has one. */
    fun requestRemoveProfile(id: String) {
        if (profileStore.get(id)?.hasPin == true) startPinTask(PinTask(PinPurpose.REMOVE_PROFILE, id)) else removeProfile(id)
    }

    /** Wall-clock ms until which PIN entry for [id] is locked, or null. For the PIN screen's countdown. */
    fun pinCooldownUntil(id: String): Long? = pins.cooldownUntil(id)

    /**
     * Four digits came in on the PIN screen. Checks them (PBKDF2 off the main thread), saves a new PIN when the run
     * finishes, and remembers what [pinStepDone] should do next. The screen shows the result first (shake, message or
     * "PIN set"), then calls [pinStepDone].
     */
    suspend fun submitPin(pin: String): PinResult {
        val task = pinTask ?: return PinResult.Wrong("")
        val res: PinStepResult = when (task.step) {
            PinStep.UNLOCK, PinStep.VERIFY -> when (val r = withContext(Dispatchers.Default) { pins.verify(task.profileId, pin) }) {
                SwitchResult.Switched -> task.onVerified()
                is SwitchResult.WrongPin -> { refreshProfiles(); return PinResult.Wrong(wrongPinMessage(r.attemptsLeft)) }
                is SwitchResult.CoolingDown -> { refreshProfiles(); return PinResult.Cooldown(r.untilMs) }
                else -> return PinResult.Wrong("")
            }
            PinStep.CHOOSE -> task.onChosen(pin)
            PinStep.CONFIRM -> task.onConfirmed(pin)
        }
        if (res is PinStepResult.Done) {
            when (val s = res.save) {
                is PinSave.Set -> withContext(Dispatchers.Default) { pins.setPin(task.profileId, s.pin) }
                PinSave.Clear -> pins.clearPin(task.profileId)
                null -> Unit
            }
            refreshProfiles()
        }
        pinPending = res
        return when {
            res is PinStepResult.Next && res.task.notice != null -> PinResult.Mismatch
            res is PinStepResult.Done -> PinResult.Accepted(res.text)
            else -> PinResult.Accepted(null)
        }
    }

    /** The PIN screen finished showing the result of [submitPin]: move to the next step or leave. */
    fun pinStepDone() {
        val task = pinTask ?: return
        when (val res = pinPending ?: return) {
            is PinStepResult.Next -> { pinTask = res.task; pinPending = null }
            is PinStepResult.Done -> {
                pinPending = null
                when (res.nav) {
                    PinNav.OPEN_PROFILE -> { pinTask = null; openProfile(task.profileId) }
                    PinNav.REMOVE_PROFILE -> removeProfile(task.profileId)
                    PinNav.BACK -> {
                        pinTask = null
                        stack.pop()
                    }
                }
            }
        }
    }

    /** Back on the PIN screen: CONFIRM -> CHOOSE, CHOOSE after Forgot PIN -> the old PIN prompt, otherwise leave. */
    fun pinBack() {
        val task = pinTask ?: return
        pinPending = null
        val prev = task.onBack()
        if (prev != null) pinTask = prev
        else { pinTask = null; stack.pop() }
    }

    /** "Remove PIN instead" after Forgot PIN. */
    fun pinRemoveInstead(): PinResult {
        val task = pinTask ?: return PinResult.Wrong("")
        val res = task.onRemoveInstead()
        pins.clearPin(task.profileId)
        refreshProfiles()
        pinPending = res
        return PinResult.Accepted(res.text)
    }

    /** "Forgot PIN?": the account password for this profile, then a new PIN. */
    fun startForgotPin() {
        val task = pinTask ?: return
        loginPrefill = loginPrefillFor(LoginMode.FORGOT_PIN, profileStore.all(), currentProfileId, lastServer, task.profileId)
        error = null
        stack.push(Screen.Login)
    }

    /** After Add profile created someone new: "Add a PIN?" ... */
    fun addPinOfferAccept(id: String) = startPinTask(PinTask(PinPurpose.ADD_PROFILE, id))

    /** ... or Skip / Back: on to the new profile. */
    fun addPinOfferSkip(id: String) = openProfile(id)

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

    private var loginJob: Job? = null

    /**
     * Signs in. A login for an account already on this TV refreshes it in place and keeps its PIN. By mode: FIRST /
     * REAUTH open that profile; ADD opens it too, after offering a PIN when the profile is new; FORGOT_PIN only proves
     * the password and goes on to choosing a PIN.
     */
    fun login(server: String, username: String, password: String) {
        loginJob?.cancel()
        val prefill = loginPrefill
        val targetId = prefill.profileId.takeIf { prefill.mode == LoginMode.REAUTH || prefill.mode == LoginMode.FORGOT_PIN }
        val before = profileStore.all().map { it.id }.toSet()
        // Not in sessionScope: entering the profile resets that scope. Cancelled by cancelLogin() and resetSessionState().
        val job = viewModelScope.launch {
            signingIn = true
            error = null
            authExpired = false
            try {
                val r = AbsApi(server).login(username, password)
                val p = profileStore.upsertFromLogin(server, r, targetId)
                prefs.edit().putString(ProfileKeys.LAST_SERVER, p.server).apply()
                when {
                    prefill.mode == LoginMode.FORGOT_PIN -> {
                        if (p.id != targetId) throw IllegalStateException("That password belongs to a different account.")
                        pins.resetAttempts(p.id)
                        finishForgotPin()
                    }
                    prefill.mode == LoginMode.ADD && p.id !in before -> {
                        // Someone new: offer a PIN before opening them (not for an account that was already here).
                        signingIn = false
                        loginPrefill = LoginPrefill()
                        stack.reset(Screen.AddPinOffer(p.id)); stateEpoch++
                        refreshProfiles()
                    }
                    else -> enterProfile(p)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (shouldShowError(authExpired, e)) error = loginErrorMessage(prefill.mode, e)
            } finally {
                if (loginJob === coroutineContext[Job]) signingIn = false
            }
        }
        loginJob = job
    }

    /** Forgot PIN's password was accepted: pop the form and carry on with the PIN run. */
    private fun finishForgotPin() {
        val task = pinTask
        loginPrefill = LoginPrefill()
        signingIn = false
        stack.pop() // the sign-in form
        refreshProfiles()
        if (task == null) return
        when (val res = task.onPasswordAccepted()) {
            is PinStepResult.Next -> pinTask = res.task
            is PinStepResult.Done -> { pinPending = res; pinStepDone() }
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
