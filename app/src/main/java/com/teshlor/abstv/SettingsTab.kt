@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.teshlor.abstv

import androidx.compose.foundation.layout.Arrangement
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
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
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
        InfoRow("Theme", "Automatic · ${vm.theme.name.lowercase().replace('_', ' ')}", null)
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
