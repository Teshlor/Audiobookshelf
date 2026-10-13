package com.teshlor.abstv

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme as tvDarkColorScheme
import androidx.tv.material3.Surface
import androidx.compose.material3.MaterialTheme as M3Theme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // QA hook, debug builds only: adb shell am start -S -n com.teshlor.abstv/.MainActivity --es theme halloween
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            vm.forcedTheme = AbsTheme.fromKey(intent?.getStringExtra("theme"))
            vm.refreshTheme()
        }
        setContent {
            val c = AbsPalettes.getValue(vm.theme)
            CompositionLocalProvider(LocalAbsColors provides c) {
                M3Theme(
                    colorScheme = darkColorScheme(
                        primary = c.accent, onPrimary = c.onAccent, background = c.bg, onBackground = c.onSurface,
                        surface = c.bg, onSurface = c.onSurface, surfaceVariant = c.surface,
                        onSurfaceVariant = c.onSurface, outline = c.muted, error = c.error,
                    ),
                ) {
                    MaterialTheme(
                        colorScheme = tvDarkColorScheme(
                            primary = c.accent, onPrimary = c.onAccent, background = c.bg, onBackground = c.onSurface,
                            surface = c.bg, onSurface = c.onSurface, surfaceVariant = c.surface,
                            onSurfaceVariant = c.onSurface, border = c.focusBorder, error = c.error,
                        ),
                    ) {
                        Surface(shape = RectangleShape) { Root() }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.refreshTheme()
        vm.player.inForeground = true
        vm.onAppForeground(SystemClock.elapsedRealtime())
    }

    override fun onStop() {
        // A rotation/config change restarts the activity but is not leaving the app.
        if (!isChangingConfigurations) {
            vm.player.inForeground = false
            vm.player.pauseAndSync()
            vm.onAppBackground(SystemClock.elapsedRealtime())
        }
        super.onStop()
    }

    @Composable
    private fun Root() {
        val saveable = rememberSaveableStateHolder()
        // Detail, Player and the series / collection pages pop themselves; tab roots, the open rail and the library and
        // profile lists are handled by Shell; the picker (Back exits), the PIN pad, Switching and "Add a PIN?" by their own screens.
        BackHandler(
            enabled = vm.screen == Screen.Detail || vm.screen == Screen.Player ||
                vm.screen is Screen.SeriesBooks || vm.screen is Screen.CollectionBooks,
        ) { vm.back() }
        // A sign-in opened over another screen (Add profile, Sign in again, Forgot PIN): Back returns to it. At the root
        // (an expired session) Back goes to the picker when there are other profiles; with just one it exits as before.
        BackHandler(enabled = vm.screen == Screen.Login && (vm.stack.size > 1 || vm.profiles.size >= 2)) { vm.cancelLogin() }
        // A popped series or collection detail drops its saved scroll position and remembered focus.
        // (logout and selectLibrary reset the stack BEFORE bumping stateEpoch, so the keys below still match.)
        DisposableEffect(saveable) {
            vm.stack.onRemoved = { gone ->
                if (gone is Screen.SeriesBooks || gone is Screen.CollectionBooks) {
                    saveable.removeState("${gone.key}#${vm.stateEpoch}")
                    vm.lastFocused.remove(gone.key)
                }
            }
            onDispose { vm.stack.onRemoved = null }
        }
        // Header band on Login and Home only; everything else gets the plain bg.
        // (The Login family: sign-in, picker, PIN pad, Switching and "Add a PIN?".)
        val s0 = vm.screen
        ThemeDecor(
            vm.theme,
            showBand = s0 == Screen.Login || s0 == Screen.Profiles || s0 == Screen.Pin || s0 is Screen.Switching ||
                s0 is Screen.AddPinOffer || s0 == Screen.Browse(Tab.HOME),
        )
        // Per-screen saved state keeps scroll positions across Detail -> Back.
        // The epoch changes on logout and library switch, so Home starts fresh (scroll position) after either.
        val s = vm.screen
        val stateKey = "${s.key}#${vm.stateEpoch}"
        when (s) {
            Screen.Login -> saveable.SaveableStateProvider(stateKey) { LoginScreen(vm) }
            Screen.Profiles -> saveable.SaveableStateProvider(stateKey) { ProfilesScreen(vm) }
            Screen.Pin -> saveable.SaveableStateProvider(stateKey) { PinTaskScreen(vm) }
            is Screen.Switching -> saveable.SaveableStateProvider(stateKey) { SwitchingScreen(vm, s) }
            is Screen.AddPinOffer -> saveable.SaveableStateProvider(stateKey) { AddPinOfferScreen(vm, s) }
            // ONE Shell for every screen that has the rail, so switching tabs keeps the same drawer, focus requesters and
            // coroutines. (A Shell per screen key was disposed on every tab switch, which left the new rail unable to take
            // focus: D-pad Left did nothing after choosing a tab with OK.)
            is Screen.Browse, is Screen.SeriesBooks, is Screen.CollectionBooks -> Shell(vm) {
                saveable.SaveableStateProvider(stateKey) {
                    when (s) {
                        is Screen.SeriesBooks -> SeriesBooksScreen(vm, s)
                        is Screen.CollectionBooks -> CollectionBooksScreen(vm, s.collection)
                        is Screen.Browse -> when (s.tab) {
                            Tab.HOME -> HomeScreen(vm)
                            Tab.SETTINGS -> SettingsTab(vm)
                            Tab.SEARCH -> SearchTab(vm)
                            Tab.LIBRARY -> LibraryTab(vm)
                            Tab.SERIES -> SeriesTab(vm)
                            Tab.COLLECTIONS -> CollectionsTab(vm)
                        }
                        else -> Unit
                    }
                }
            }
            Screen.Detail -> saveable.SaveableStateProvider(stateKey) { DetailScreen(vm) }
            Screen.Player -> saveable.SaveableStateProvider(stateKey) { PlayerScreen(vm) }
        }
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Handled here, before the view tree: the drawer swallows Right while it is open, which left focus nowhere.
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && event.action == KeyEvent.ACTION_DOWN && vm.railRightHandler?.invoke() == true) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (vm.player.active) {
            when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { vm.player.togglePlay(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { vm.player.seekBy(30.0); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { vm.player.seekBy(-30.0); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
