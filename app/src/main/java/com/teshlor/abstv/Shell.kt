@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package com.teshlor.abstv

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.border
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.ModalNavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.NavigationDrawerItemDefaults
import androidx.tv.material3.NavigationDrawerScope
import androidx.tv.material3.Text
import androidx.tv.material3.rememberDrawerState
import kotlinx.coroutines.launch

// Colours derived from the six theme tokens (HANDOFF section 7), so every theme works unchanged.
private val AbsColors.sheet get() = lerp(bg, surface, 0.30f)
private val AbsColors.hairline get() = lerp(bg, onSurface, 0.12f)
private val AbsColors.selectedPill get() = lerp(sheet, accent, 0.16f)

private val RailCollapsed = 88.dp
private val ContentStart = 112.dp

/**
 * The navigation rail (tv-material ModalNavigationDrawer: it overlays the content, so the content never re-lays out
 * while the rail opens) around a tab's [content]. Moving focus through the rail changes nothing; OK switches tabs.
 */
@Composable
fun Shell(vm: AppViewModel, content: @Composable () -> Unit) {
    val c = LocalAbsColors.current
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as? Activity
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val tab = vm.stack.tab ?: Tab.HOME
    val requesters = remember { Tab.entries.associateWith { FocusRequester() } }
    val contentRequester = remember { FocusRequester() }
    var confirmLogout by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf(false) }
    val open = drawerState.currentValue == DrawerValue.Open

    fun closeRailToContent() {
        drawerState.setValue(DrawerValue.Closed)
        scope.launch { contentRequester.requestWhenReady() }
    }

    fun openRail() {
        drawerState.setValue(DrawerValue.Open)
        scope.launch { requesters.getValue(tab).requestWhenReady() }
    }

    // Back (PLAN section 2): content opens the rail on the current tab; on the rail, non-Home goes Home, Home exits.
    BackHandler(enabled = !confirmLogout) {
        when {
            switching -> { switching = false; scope.launch { requesters.getValue(tab).requestWhenReady() } }
            !open -> openRail()
            tab != Tab.HOME -> { vm.selectTab(Tab.HOME); closeRailToContent() }
            else -> activity?.finish()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        scrimBrush = Brush.horizontalGradient(
            0f to c.bg.copy(alpha = 0.90f), 0.3f to c.bg.copy(alpha = 0.90f), 1f to c.bg.copy(alpha = 0.66f),
        ),
        drawerContent = { value ->
            val expanded = value == DrawerValue.Open
            Column(
                Modifier
                    .fillMaxHeight()
                    .background(if (expanded) c.sheet else Color.Transparent)
                    .padding(horizontal = 16.dp, vertical = 24.dp)
                    // Entering the rail from the content lands on the current tab, not the nearest item.
                    .focusProperties { enter = { requesters.getValue(tab) } }
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (switching) {
                    LibraryList(vm) { lib ->
                        switching = false
                        vm.selectLibrary(lib)
                        closeRailToContent()
                    }
                } else {
                    if (vm.libraries.size > 1) {
                        RailItemRow(
                            label = vm.selectedLibrary?.name ?: "Library",
                            description = "Library: ${vm.selectedLibrary?.name}. Switch library.",
                            icon = null, initial = vm.selectedLibrary?.name?.firstOrNull()?.uppercase() ?: "L",
                            selected = false, expanded = expanded, requester = null,
                        ) { switching = true }
                        Divider(expanded)
                    }
                    Tab.entries.filter { it.enabled && it != Tab.SETTINGS }.forEach { t ->
                        RailItemRow(
                            label = t.label, description = t.label, icon = NavIcons.forTab(t, t == tab), selected = t == tab,
                            expanded = expanded, requester = requesters.getValue(t),
                        ) {
                            if (t != tab) vm.selectTab(t)
                            closeRailToContent()
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Divider(expanded)
                    RailItemRow(
                        label = Tab.SETTINGS.label, description = "Settings", icon = NavIcons.forTab(Tab.SETTINGS, tab == Tab.SETTINGS),
                        selected = tab == Tab.SETTINGS, expanded = expanded, requester = requesters.getValue(Tab.SETTINGS),
                    ) {
                        if (tab != Tab.SETTINGS) vm.selectTab(Tab.SETTINGS)
                        closeRailToContent()
                    }
                    RailItemRow(
                        label = "Log out", description = "Log out", icon = NavIcons.logout, selected = false,
                        expanded = expanded, requester = null,
                    ) { confirmLogout = true }
                }
            }
        },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(contentRequester)
                .focusRestorer()
                .drawRailFade(c.bg),
        ) { content() }
    }

    if (confirmLogout) LogoutDialog(
        onConfirm = { confirmLogout = false; vm.logout() },
        onCancel = { confirmLogout = false },
    )
}

/**
 * Requests focus once the target is attached. Lazy items and freshly shown rail rows are only placed after layout,
 * so an immediate requestFocus() throws; retry for a few frames instead of failing silently.
 */
suspend fun FocusRequester.requestWhenReady(maxFrames: Int = 30): Boolean {
    repeat(maxFrames) {
        if (runCatching { requestFocus() }.isSuccess) return true
        withFrameNanos { }
    }
    return false
}

/** Solid bg under the collapsed rail plus a 24dp fade to the content, drawn last (cached, so free per frame). */
private fun Modifier.drawRailFade(bg: Color): Modifier = drawWithCache {
    val rail = RailCollapsed.toPx()
    val fade = Brush.horizontalGradient(listOf(bg, bg.copy(alpha = 0f)), startX = rail, endX = ContentStart.toPx())
    val railSize = Size(rail, size.height)
    val fadeSize = Size(ContentStart.toPx() - rail, size.height)
    onDrawWithContent {
        drawContent()
        drawRect(bg, Offset.Zero, railSize)
        drawRect(fade, Offset(rail, 0f), fadeSize)
    }
}

@Composable
private fun Divider(expanded: Boolean) {
    val c = LocalAbsColors.current
    Box(
        Modifier.padding(vertical = 4.dp).padding(start = if (expanded) 0.dp else 16.dp)
            .width(if (expanded) 224.dp else 24.dp).height(1.dp).background(c.hairline),
    )
}

@Composable
private fun NavigationDrawerScope.RailItemRow(
    label: String,
    description: String,
    icon: ImageVector?,
    selected: Boolean,
    expanded: Boolean,
    requester: FocusRequester?,
    initial: String = "",
    onClick: () -> Unit,
) {
    val c = LocalAbsColors.current
    Box {
        NavigationDrawerItem(
            selected = selected,
            onClick = onClick,
            modifier = Modifier
                .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
                .semantics { contentDescription = description; this.selected = selected },
            leadingContent = {
                if (icon != null) Icon(icon, contentDescription = null)
                else Text(initial, fontFamily = FontFamily.Serif, fontSize = 18.sp, color = c.accent)
            },
            colors = NavigationDrawerItemDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = c.onSurface,
                inactiveContentColor = c.muted,
                selectedContainerColor = if (expanded) c.selectedPill else Color.Transparent,
                selectedContentColor = c.accent,
                focusedContainerColor = c.accent,
                focusedContentColor = c.onAccent,
                focusedSelectedContainerColor = c.accent,
                focusedSelectedContentColor = c.onAccent,
                pressedContainerColor = c.accent,
                pressedContentColor = c.onAccent,
                pressedSelectedContainerColor = c.accent,
                pressedSelectedContentColor = c.onAccent,
            ),
            scale = NavigationDrawerItemDefaults.scale(focusedScale = 1.05f),
        ) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (selected) {
            Box(
                Modifier.align(Alignment.CenterStart).offset(x = (-10).dp).size(3.dp, 20.dp)
                    .background(c.accent, RoundedCornerShape(1.5.dp)),
            )
        }
    }
}

@Composable
private fun NavigationDrawerScope.LibraryList(vm: AppViewModel, onPick: (Library) -> Unit) {
    val c = LocalAbsColors.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestWhenReady() }
    Text("LIBRARIES", fontSize = 12.sp, letterSpacing = 1.6.sp, color = c.muted, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
    vm.libraries.forEachIndexed { i, lib ->
        val current = lib.id == vm.selectedLibrary?.id
        NavigationDrawerItem(
            selected = current,
            onClick = { onPick(lib) },
            modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
            leadingContent = { if (current) Icon(NavIcons.check, contentDescription = null) },
            colors = NavigationDrawerItemDefaults.colors(
                containerColor = Color.Transparent, contentColor = c.onSurface,
                selectedContainerColor = c.selectedPill, selectedContentColor = c.accent,
                focusedContainerColor = c.accent, focusedContentColor = c.onAccent,
                focusedSelectedContainerColor = c.accent, focusedSelectedContentColor = c.onAccent,
            ),
        ) { Text(lib.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
    Text(
        "Back returns to the menu without switching.", fontSize = 12.sp, color = c.muted,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
    )
}

@Composable
private fun LogoutDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    val c = LocalAbsColors.current
    val cancel = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancel.requestWhenReady() }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) { // Back = Cancel
        Box(Modifier.fillMaxSize().background(c.bg.copy(alpha = 0.78f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(440.dp)
                    .background(c.sheet, RoundedCornerShape(20.dp))
                    .border(1.dp, c.hairline, RoundedCornerShape(20.dp))
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Log out?", fontFamily = FontFamily.Serif, fontSize = 26.sp, lineHeight = 32.sp)
                Text("You'll need to sign in again to listen.", fontSize = 15.sp, color = c.muted)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AbsButton(onClick = onConfirm) { Text("Log out") }
                    AbsButton(onClick = onCancel, modifier = Modifier.focusRequester(cancel)) { Text("Cancel") }
                }
            }
        }
    }
}
