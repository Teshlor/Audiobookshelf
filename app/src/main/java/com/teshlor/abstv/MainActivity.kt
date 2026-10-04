package com.teshlor.abstv

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.shape.RectangleShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.compose.material3.MaterialTheme as M3Theme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            M3Theme(colorScheme = darkColorScheme()) {
                MaterialTheme {
                    Surface(shape = RectangleShape) { Root() }
                }
            }
        }
    }

    @Composable
    private fun Root() {
        BackHandler(enabled = vm.screen == Screen.Detail || vm.screen == Screen.Player) { vm.back() }
        when (vm.screen) {
            Screen.Login -> LoginScreen(vm)
            Screen.Home -> HomeScreen(vm)
            Screen.Detail -> DetailScreen(vm)
            Screen.Player -> PlayerScreen(vm)
        }
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
