@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.teshlor.abstv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

// "Who's listening?", the Switching screen and "Add a PIN?" (design/profiles/HANDOFF.md 4.1, 4.4, 13.5).

/** What a screen reader says for a picker tile / rail row. */
fun ProfileSummary.a11yLabel(): String = when {
    needsSignIn -> "$displayName, needs to sign in again"
    hasPin -> "$displayName, has a PIN"
    else -> displayName
}

private const val ADD_TILE = "add"

@Composable
fun ProfilesScreen(vm: AppViewModel) {
    val c = LocalAbsColors.current
    val profiles = vm.profiles
    // Keyed by the ids, not the list: a refresh that only flips a badge must not rebuild the requesters and re-grab focus.
    val ids = profiles.map { it.id }
    val tileIds = remember(ids) { ids + ADD_TILE }
    val requesters = remember(tileIds) { tileIds.map { FocusRequester() } }
    val startIdx = remember(tileIds) {
        vm.lastFocused["profiles"]?.let { tileIds.indexOf(it) }?.takeIf { it >= 0 }
            ?: profiles.indexOfFirst { it.isLastUsed }.takeIf { it >= 0 }
            ?: 0
    }
    val sameServer = profiles.map { it.server }.distinct().size <= 1
    val listState = rememberLazyListState()
    val scrolling = tileIds.size > 5
    LaunchedEffect(tileIds) {
        if (scrolling) listState.scrollToItem(startIdx)
        requesters[startIdx].requestWhenReady()
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(top = 146.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Who’s listening?", fontFamily = FontFamily.Serif, fontSize = 34.sp, lineHeight = 42.sp, color = c.onSurface,
                textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() },
            )
            Box(
                Modifier.padding(top = 9.dp).width(160.dp).height(1.dp).background(
                    Brush.horizontalGradient(listOf(c.accent.copy(alpha = 0f), c.accent.copy(alpha = 0.6f), c.accent.copy(alpha = 0f))),
                ),
            )
            Text(
                if (sameServer && profiles.isNotEmpty()) "Profiles on this TV · ${profiles.first().host}" else "Profiles on this TV",
                fontSize = 15.sp, lineHeight = 20.sp, color = c.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp),
            )
            Spacer(Modifier.height(16.dp))
            if (!scrolling) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    tileIds.forEachIndexed { i, id ->
                        PickerTile(vm, profiles.getOrNull(i), showHost = !sameServer, requester = requesters[i], key = id)
                    }
                }
            } else {
                LazyRow(state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 88.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    itemsIndexed(tileIds, key = { _, id -> id }) { i, id ->
                        PickerTile(vm, profiles.getOrNull(i), showHost = !sameServer, requester = requesters[i], key = id)
                    }
                }
            }
        }
        Text(
            "To switch later, open the menu and choose your name at the bottom.", fontSize = 13.sp, lineHeight = 18.sp, color = c.muted,
            textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.TopCenter).offset(y = 482.dp),
        )
    }
}

/** One person (or the Add tile when [p] is null). Focus state is local, so moving between tiles recomposes only two of them. */
@Composable
private fun PickerTile(vm: AppViewModel, p: ProfileSummary?, showHost: Boolean, requester: FocusRequester, key: String) {
    val c = LocalAbsColors.current
    var focused by remember { mutableStateOf(false) }
    val avatarColor = if (p != null) AvatarColors[p.colorIndex.coerceIn(0, AvatarColors.size - 1)] else c.surface
    val label = p?.a11yLabel() ?: "Add profile"
    Column(
        Modifier.width(152.dp).padding(vertical = 12.dp).onFocusChanged { focused = it.hasFocus },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Only the avatar (with its badge and ring) scales, not the name under it. Read in the layer block: no recomposition.
        Box(
            Modifier.size(112.dp).graphicsLayer { val s = if (focused) 1.1f else 1f; scaleX = s; scaleY = s },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                onClick = { if (p != null) vm.chooseProfile(p.id) else vm.startAddProfile() },
                modifier = Modifier.size(112.dp).focusRequester(requester).semantics(mergeDescendants = true) {
                    contentDescription = label; role = Role.Button
                }.onFocusChanged { if (it.hasFocus) vm.lastFocused["profiles"] = key },
                shape = ClickableSurfaceDefaults.shape(CircleShape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (p != null) avatarColor else Color.Transparent, contentColor = c.onSurface,
                    focusedContainerColor = if (p != null) avatarColor else c.surface, focusedContentColor = c.onSurface,
                    pressedContainerColor = if (p != null) avatarColor else c.surface, pressedContentColor = c.onSurface,
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(BorderStroke(3.dp, c.accent), inset = 4.dp, shape = CircleShape),
                    pressedBorder = Border(BorderStroke(3.dp, c.accent), inset = 4.dp, shape = CircleShape),
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f), // the whole tile scales (badge included), see above
            ) {
                if (p != null) {
                    AvatarCircle(p.displayName.firstOrNull()?.uppercase().orEmpty(), p.nameKnown, p.colorIndex, 112.dp)
                } else if (focused) {
                    Box(Modifier.fillMaxSize().border(2.dp, c.accent, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(ProfileIcons.add, null, tint = c.accent, modifier = Modifier.size(40.dp))
                    }
                } else {
                    DashedCircle(112.dp, ProfileIcons.add, 40.dp, c.muted)
                }
            }
            if (p != null && p.avatarBadge() != AvatarBadge.None) {
                // Outside the Surface: it clips its content to the circle, and the badge sits on the rim.
                BadgeOnly(p, Modifier.align(Alignment.BottomEnd).offset(x = 2.dp, y = 2.dp))
            }
        }
        Text(
            p?.displayName ?: "Add profile", fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium,
            color = if (focused) c.onSurface else c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp).fillMaxWidth(),
        )
        val status: Pair<String, Color>? = when {
            p != null && p.needsSignIn -> "Sign in again" to c.accent
            p != null && showHost -> p.host to c.muted
            else -> null
        }
        Box(Modifier.height(20.dp)) {
            status?.let { (text, color) ->
                Text(text, fontSize = 13.sp, lineHeight = 18.sp, color = color, fontWeight = if (p?.needsSignIn == true) FontWeight.Medium else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.padding(top = 2.dp).size(24.dp, 2.dp).background(if (focused) c.accent else Color.Transparent, RoundedCornerShape(1.dp)))
    }
}

@Composable
private fun BadgeOnly(p: ProfileSummary, modifier: Modifier) {
    val c = LocalAbsColors.current
    val needs = p.avatarBadge() == AvatarBadge.NeedsSignIn
    Box(
        modifier.size(30.dp).background(if (needs) c.accent else c.sheet, CircleShape).border(2.dp, c.bg, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (needs) Icon(ProfileIcons.priorityHigh, null, tint = c.onAccent, modifier = Modifier.size(16.dp))
        else Icon(ProfileIcons.lock, null, tint = c.onSurface, modifier = Modifier.size(16.dp))
    }
}

/** "Opening {name}'s library..." (design 4.4). Not focusable; Back is ignored; the view model moves on after 600 ms. */
@Composable
fun SwitchingScreen(vm: AppViewModel, s: Screen.Switching) {
    val c = LocalAbsColors.current
    BackHandler(true) { }
    val p = vm.profiles.firstOrNull { it.id == s.profileId }
    val transition = rememberInfiniteTransition(label = "switching")
    val angle: State<Float> = transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "arc")
    val track = c.trackColor
    val accent = c.accent
    Column(Modifier.fillMaxSize().padding(top = 200.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
            if (p != null) ProfileAvatar(p, 112.dp)
            // r 61.5 around the avatar: where the focus ring was. Rotated in its own layer so only that layer redraws.
            Canvas(Modifier.requiredSize(132.dp).graphicsLayer { rotationZ = angle.value }) {
                val stroke = 3.dp.toPx()
                val r = 61.5.dp.toPx()
                val tl = Offset(size.width / 2 - r, size.height / 2 - r)
                drawCircle(track, radius = r, style = Stroke(stroke))
                drawArc(accent, -90f, 360f * 0.24f, false, tl, Size(r * 2, r * 2), style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Text(
            p?.displayName.orEmpty(), fontFamily = FontFamily.Serif, fontSize = 30.sp, lineHeight = 36.sp, color = c.onSurface,
            modifier = Modifier.padding(top = 22.dp),
        )
        Text(
            "Opening ${p?.displayName.orEmpty()}’s library…", fontSize = 15.sp, lineHeight = 20.sp, color = c.muted,
            modifier = Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** Header shared by the Login-family screens: a 64dp avatar, an overline, and a serif title in the accent colour. */
@Composable
fun LoginHeader(overline: String, title: String, avatar: @Composable () -> Unit) {
    val c = LocalAbsColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        avatar()
        Column {
            Text(overline, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.6.sp, color = c.muted, maxLines = 1)
            Text(
                title, fontFamily = FontFamily.Serif, fontSize = 32.sp, lineHeight = 40.sp, color = c.accent,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() },
            )
        }
    }
}

/** "Add a PIN?" straight after Add profile (design 13.5). Skip and Back both go on to the new profile. */
@Composable
fun AddPinOfferScreen(vm: AppViewModel, s: Screen.AddPinOffer) {
    val c = LocalAbsColors.current
    val p = vm.profiles.firstOrNull { it.id == s.profileId }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { first.requestWhenReady() }
    BackHandler(true) { vm.addPinOfferSkip(s.profileId) }
    if (p == null) return
    Column(Modifier.fillMaxSize().padding(start = 240.dp, end = 240.dp, top = 140.dp)) {
        LoginHeader("${p.displayName.uppercase()} IS ON THIS TV", "Add a PIN?") { ProfileAvatar(p, 64.dp) }
        Text(
            "A 4-digit PIN stops others here from opening ${p.displayName}’s profile. You can add or change it any time in Settings.",
            fontSize = 15.sp, lineHeight = 22.sp, color = c.muted, modifier = Modifier.padding(top = 20.dp).width(480.dp),
        )
        Row(Modifier.padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AbsButton(onClick = { vm.addPinOfferAccept(s.profileId) }, modifier = Modifier.focusRequester(first)) { Text("Add a PIN") }
            AbsButton(onClick = { vm.addPinOfferSkip(s.profileId) }) { Text("Skip") }
        }
    }
}
