@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.teshlor.abstv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Icon
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

@Composable
fun SettingsTab(vm: AppViewModel) {
    val c = LocalAbsColors.current
    val ctx = LocalContext.current
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { focus.requestWhenReady() }
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty() }
    Column(Modifier.padding(start = 112.dp, top = 32.dp, end = 48.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("SETTINGS", fontSize = 12.sp, letterSpacing = 1.6.sp, color = c.muted)
        Text("Settings", fontFamily = FontFamily.Serif, fontSize = 30.sp, lineHeight = 36.sp)
        Box(Modifier.padding(top = 8.dp))
        InfoRow("Server", vm.api?.baseUrl.orEmpty(), if (vm.username.isNotEmpty()) "Signed in as ${vm.username}" else null)
        // The one actionable row, so it takes the initial focus.
        Surface(
            onClick = { vm.refreshLibrary() },
            modifier = Modifier.width(560.dp).focusRequester(focus),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = c.surface, contentColor = c.onSurface,
                focusedContainerColor = c.accent, focusedContentColor = c.onAccent,
                pressedContainerColor = c.accent, pressedContentColor = c.onAccent,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                Text("Refresh library", fontSize = 15.sp)
                Text(if (vm.loading) "Refreshing…" else "Reload shelves and books from the server", fontSize = 12.sp)
            }
        }
        val themeFocus = remember { FocusRequester() }
        var picking by remember { mutableStateOf(false) }
        Surface(
            onClick = { picking = true },
            modifier = Modifier.width(560.dp).focusRequester(themeFocus),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = c.surface, contentColor = c.onSurface,
                focusedContainerColor = c.accent, focusedContentColor = c.onAccent,
                pressedContainerColor = c.accent, pressedContentColor = c.onAccent,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                val chosen = vm.themeChoice
                Text(if (chosen == null) "Theme: Auto (${themeLabel(vm.theme)})" else "Theme: ${themeLabel(chosen)}", fontSize = 15.sp)
                Text("Choose a theme, or follow the calendar", fontSize = 12.sp)
            }
        }
        if (picking) ThemePicker(
            current = vm.themeChoice,
            onPick = { vm.chooseTheme(it); picking = false; scope.launch { themeFocus.requestWhenReady() } },
            onCancel = { picking = false; scope.launch { themeFocus.requestWhenReady() } },
        )
        InfoRow("About", "Audiobookshelf TV $version", null)
    }
}

@Composable
private fun InfoRow(title: String, value: String, extra: String?) {
    val c = LocalAbsColors.current
    Column(
        Modifier.width(560.dp).padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Text(title, fontSize = 12.sp, color = c.muted)
        Text(value, fontSize = 15.sp)
        if (extra != null) Text(extra, fontSize = 12.sp, color = c.muted)
    }
}

fun themeLabel(t: AbsTheme): String = when (t) {
    AbsTheme.NEW_YEAR -> "New Year's Eve"
    AbsTheme.AURORA -> "Northern Lights"
    AbsTheme.VALENTINE -> "Valentine's"
    AbsTheme.FIREFLIES -> "Firefly Nights"
    AbsTheme.JULY_4 -> "Fourth of July"
    else -> t.name.lowercase().replaceFirstChar { it.uppercase() }
}

/** Auto (by date) first, then the 12 themes in calendar order. Focus starts on the current choice; Back cancels. */
@Composable
private fun ThemePicker(current: AbsTheme?, onPick: (AbsTheme?) -> Unit, onCancel: () -> Unit) {
    val c = LocalAbsColors.current
    val options: List<AbsTheme?> = listOf<AbsTheme?>(null) + ThemePickerOrder
    val startIdx = options.indexOf(current).coerceAtLeast(0)
    val listState = rememberLazyListState()
    val requesters = remember { List(options.size) { FocusRequester() } }
    LaunchedEffect(Unit) {
        listState.scrollToItem(startIdx)
        requesters[startIdx].requestWhenReady()
    }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(c.bg.copy(alpha = 0.78f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(440.dp).background(c.surface, RoundedCornerShape(20.dp)).padding(vertical = 24.dp, horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Theme", fontFamily = FontFamily.Serif, fontSize = 26.sp, lineHeight = 32.sp, modifier = Modifier.padding(start = 8.dp))
                LazyColumn(state = listState, modifier = Modifier.height(360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(options) { i, t ->
                        val colors = AbsPalettes.getValue(t ?: AbsTheme.AUTUMN)
                        Surface(
                            onClick = { onPick(t) },
                            modifier = Modifier.fillMaxWidth().focusRequester(requesters[i]),
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = Color.Transparent, contentColor = c.onSurface,
                                focusedContainerColor = c.accent, focusedContentColor = c.onAccent,
                                pressedContainerColor = c.accent, pressedContentColor = c.onAccent,
                            ),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                        ) {
                            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (t == null) {
                                    Box(Modifier.size(28.dp).clip(CircleShape).background(c.muted))
                                } else {
                                    // Swatch: the theme's accent dot on its bg.
                                    Box(Modifier.size(28.dp).clip(CircleShape).background(colors.bg), contentAlignment = Alignment.Center) {
                                        Box(Modifier.size(14.dp).clip(CircleShape).background(colors.accent))
                                    }
                                }
                                Text(
                                    if (t == null) "Auto (by date)" else themeLabel(t), fontSize = 15.sp,
                                    modifier = Modifier.padding(start = 14.dp).weight(1f),
                                )
                                if (t == current) Icon(NavIcons.check, contentDescription = "Current", modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
