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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
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
internal val AbsColors.sheet get() = lerp(bg, surface, 0.30f)
internal val AbsColors.hairline get() = lerp(bg, onSurface, 0.12f)
internal val AbsColors.selectedPill get() = lerp(sheet, accent, 0.16f)

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
    // One holder for the Shell's lifetime: the Right handler registered once below captures it, so it must not be re-keyed.
    val returnFocus = remember { ReturnFocus() }
    LaunchedEffect(vm.screen.key) { returnFocus.last = null } // a new screen starts with nothing to return to
    val railExpanded = remember { BooleanArray(1) } // what the drawer is actually showing (its currentValue can lag the focus-driven expand)
    var confirmLogout by remember { mutableStateOf(false) }
    var railMode by remember { mutableStateOf(RailMode.MENU) }
    val profileReq = remember { FocusRequester() }
    val open = drawerState.currentValue == DrawerValue.Open

    /**
     * The one way back from the rail (OK on a rail item, Right, Back-to-Home). Focus is requested only after the drawer
     * has actually reached Closed, because while it is still open/animating the content is not a focus target and the
     * request lands nowhere, leaving the next D-pad press to just "wake" focus.
     */
    fun closeRailToContent() {
        railMode = RailMode.MENU // Right from the library / profile list must not leave the collapsed rail showing its rows
        drawerState.setValue(DrawerValue.Closed)
        scope.launch {
            snapshotFlow { drawerState.currentValue }.first { it == DrawerValue.Closed }
            withFrameNanos { }
            // Back to the exact card the user left (its own requester); a card that is gone or a tab with none remembered
            // falls back to the first focusable element of the content.
            val last = returnFocus.last
            if (last == null || runCatching { last.requestFocus() }.isFailure) contentRequester.requestWhenReady()
        }
    }

    // Right on the open rail (any item, or the library switcher) closes it and returns to the content's last focused card.
    DisposableEffect(vm) {
        vm.railRightHandler = { if (railExpanded[0]) { closeRailToContent(); true } else false }
        onDispose { vm.railRightHandler = null }
    }

    fun openRail() {
        drawerState.setValue(DrawerValue.Open)
        scope.launch { requesters.getValue(tab).requestWhenReady() }
    }

    // Back (PLAN section 2): content opens the rail on the current tab; on the rail, non-Home goes Home, Home exits.
    // Series detail (not a tab root) handles Back itself, unless the rail is open.
    BackHandler(enabled = !confirmLogout && (vm.screen is Screen.Browse || open || railMode != RailMode.MENU)) {
        when {
            railMode == RailMode.LIBRARIES -> { railMode = RailMode.MENU; scope.launch { requesters.getValue(tab).requestWhenReady() } }
            railMode == RailMode.PROFILES -> { railMode = RailMode.MENU; scope.launch { profileReq.requestWhenReady() } }
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
            SideEffect { railExpanded[0] = expanded }
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
                if (railMode == RailMode.LIBRARIES) {
                    LibraryList(vm) { lib ->
                        railMode = RailMode.MENU
                        vm.selectLibrary(lib)
                        closeRailToContent()
                    }
                } else if (railMode == RailMode.PROFILES) {
                    ProfileList(vm, Modifier.weight(1f), onSignOut = { confirmLogout = true })
                } else {
                    if (vm.libraries.size > 1) {
                        RailItemRow(
                            label = vm.selectedLibrary?.name ?: "Library",
                            description = "Library: ${vm.selectedLibrary?.name}. Switch library.",
                            icon = null, initial = vm.selectedLibrary?.name?.firstOrNull()?.uppercase() ?: "L",
                            selected = false, expanded = expanded, requester = null,
                        ) { railMode = RailMode.LIBRARIES }
                        Divider(expanded)
                    }
                    Tab.entries.filter { it.enabled && it != Tab.SETTINGS }.forEach { t ->
                        RailItemRow(
                            label = t.label, description = t.label, icon = NavIcons.forTab(t, t == tab), selected = t == tab,
                            expanded = expanded, requester = requesters.getValue(t),
                        ) {
                            if (t != tab || vm.screen !is Screen.Browse) vm.selectTab(t)
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
                    ProfileRailRow(vm.currentProfile, others = vm.profiles.size > 1, requester = profileReq) { railMode = RailMode.PROFILES }
                }
            }
        },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .focusRequester(contentRequester)
                .focusGroup()
                .drawRailFade(c.bg),
        ) { CompositionLocalProvider(LocalRailOpen provides open, LocalReturnFocus provides returnFocus) { content() } }
    }

    if (confirmLogout) {
        val who = vm.currentProfile
        val name = who?.displayName ?: "this profile"
        ConfirmDialog(
            who = who,
            title = "Sign out $name?",
            body = "$name\u2019s profile will be removed from this TV. Listening progress stays saved on your server.",
            confirmLabel = "Sign out",
            onConfirm = { confirmLogout = false; vm.signOutCurrent() },
            onCancel = { confirmLogout = false },
        )
    }
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

/** What the open rail sheet shows: the nav items, the library list, or the profile list (design 4.3). */
enum class RailMode { MENU, LIBRARIES, PROFILES }

/** Your avatar row where Log out used to be: two lines, never selected. OK swaps the sheet to the profile list. */
@Composable
private fun NavigationDrawerScope.ProfileRailRow(p: ProfileSummary?, others: Boolean, requester: FocusRequester, onClick: () -> Unit) {
    val c = LocalAbsColors.current
    var focused by remember { mutableStateOf(false) }
    NavigationDrawerItem(
        selected = false,
        onClick = onClick,
        modifier = Modifier.focusRequester(requester).onFocusChanged { focused = it.hasFocus }
            .semantics { contentDescription = "Profile: ${p?.displayName.orEmpty()}. ${if (others) "Switch profile." else "Add profile or sign out."}" },
        leadingContent = { if (p != null) ProfileAvatar(p, 32.dp, bgRing = true) },
        supportingContent = {
            Text(if (others) "Switch profile" else "Add profile or sign out", fontSize = 12.sp, color = if (focused) c.onAccent else c.muted, maxLines = 1)
        },
        colors = railItemColors(c, expanded = true),
        scale = NavigationDrawerItemDefaults.scale(focusedScale = 1.05f),
    ) {
        Text(p?.displayName.orEmpty(), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun railItemColors(c: AbsColors, expanded: Boolean) = NavigationDrawerItemDefaults.colors(
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
)

/**
 * The profile list that replaces the nav items in the open sheet (design 4.3): who you are, the others to switch to,
 * Add profile, and Sign out at the bottom. Focus starts on the first other profile (Add profile when alone).
 */
@Composable
private fun NavigationDrawerScope.ProfileList(vm: AppViewModel, listModifier: Modifier, onSignOut: () -> Unit) {
    val c = LocalAbsColors.current
    val drawer = this
    val me = vm.currentProfile
    val others = vm.profiles.filter { !it.isCurrent }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestWhenReady() }
    if (me != null) {
        Row(Modifier.padding(start = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ProfileAvatar(me, 56.dp)
            Column(Modifier.padding(start = 16.dp)) {
                Text(me.displayName, fontFamily = FontFamily.Serif, fontSize = 22.sp, lineHeight = 28.sp, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${me.username.ifEmpty { me.displayName }} \u00b7 ${me.host}", fontSize = 12.sp, lineHeight = 16.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    Divider(true)
    if (others.isNotEmpty()) {
        Text("SWITCH TO", fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.6.sp, color = c.muted, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
    }
    LazyColumn(listModifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(others, key = { _, p -> p.id }) { i, p ->
            with(drawer) { OtherProfileRow(p, if (i == 0) Modifier.focusRequester(first) else Modifier) { vm.chooseProfile(p.id) } }
        }
        item(key = "add") {
            with(drawer) {
                SimpleListRow(
                    label = "Add profile", supporting = null, icon = ProfileIcons.personAdd, description = "Add profile",
                    modifier = if (others.isEmpty()) Modifier.focusRequester(first) else Modifier,
                ) { vm.startAddProfile() }
            }
        }
        item(key = "hint") {
            Text(
                "Back returns to the menu without switching.", fontSize = 12.sp, lineHeight = 17.sp, color = c.muted,
                modifier = Modifier.padding(start = 16.dp, top = 6.dp).width(224.dp),
            )
        }
    }
    Divider(true)
    with(drawer) {
        SimpleListRow(
            label = "Sign out", supporting = "Removes ${me?.displayName.orEmpty()} from this TV", icon = NavIcons.logout,
            description = "Sign out ${me?.displayName.orEmpty()}. Removes ${me?.displayName.orEmpty()} from this TV.",
            onClick = onSignOut,
        )
    }
}

@Composable
private fun NavigationDrawerScope.OtherProfileRow(p: ProfileSummary, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalAbsColors.current
    var focused by remember { mutableStateOf(false) }
    val tint = if (focused) c.onAccent else if (p.needsSignIn) c.accent else c.muted
    NavigationDrawerItem(
        selected = false,
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.hasFocus }
            .semantics { contentDescription = if (p.needsSignIn || p.hasPin) p.a11yLabel().let { if (p.needsSignIn) it else "Switch to $it" } else "Switch to ${p.displayName}" },
        leadingContent = { ProfileAvatar(p, 32.dp, bgRing = true) },
        supportingContent = if (p.needsSignIn) {
            { Text("Sign in again", fontSize = 12.sp, color = if (focused) c.onAccent else c.muted, maxLines = 1) }
        } else null,
        trailingContent = when {
            p.needsSignIn -> ({ Icon(ProfileIcons.errorBadge, null, tint = tint, modifier = Modifier.size(18.dp)) })
            p.hasPin -> ({ Icon(ProfileIcons.lock, null, tint = tint, modifier = Modifier.size(18.dp)) })
            else -> null
        },
        colors = railItemColors(c, expanded = true),
        scale = NavigationDrawerItemDefaults.scale(focusedScale = 1.05f),
    ) { Text(p.displayName, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun NavigationDrawerScope.SimpleListRow(
    label: String,
    supporting: String?,
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val c = LocalAbsColors.current
    var focused by remember { mutableStateOf(false) }
    NavigationDrawerItem(
        selected = false,
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.hasFocus }.semantics { contentDescription = description },
        leadingContent = { Icon(icon, contentDescription = null) },
        supportingContent = supporting?.let { s -> { Text(s, fontSize = 12.sp, color = if (focused) c.onAccent else c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        colors = railItemColors(c, expanded = true),
        scale = NavigationDrawerItemDefaults.scale(focusedScale = 1.05f),
    ) { Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}
