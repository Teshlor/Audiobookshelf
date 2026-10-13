@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.teshlor.abstv

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

// Shared pieces of the profile screens (design/profiles/HANDOFF.md sections 2, 3, 4.7).

/** Profile avatar colours: fixed across themes, picked per person by [Profile.colorIndex]. */
val AvatarColors = listOf(
    Color(0xFF3F5592), // Indigo
    Color(0xFF9A4766), // Rose
    Color(0xFF3D6B50), // Forest
    Color(0xFF8F6420), // Ochre
    Color(0xFF71497F), // Plum
    Color(0xFF2D6E73), // Teal
    Color(0xFF9A4A2A), // Rust
    Color(0xFF56627A), // Slate
)

/** The initial, and the seal ring at 30%. */
val AvatarInk = Color(0xFFFBF4EA)

internal val AbsColors.outlineColor get() = lerp(bg, onSurface, 0.22f)

enum class AvatarBadge { None, Pin, NeedsSignIn }

/** The avatar for [p]. Decorative: the control around it carries the label. */
@Composable
fun ProfileAvatar(p: ProfileSummary, size: Dp, modifier: Modifier = Modifier, badge: AvatarBadge = AvatarBadge.None, bgRing: Boolean = false) {
    AvatarCircle(p.displayName.firstOrNull()?.uppercase().orEmpty(), p.nameKnown, p.colorIndex, size, modifier, badge, bgRing)
}

/** Avatar badge for a tile: a lock means "has a PIN", the accent "!" means "sign in again" and wins when both apply. */
fun ProfileSummary.avatarBadge(): AvatarBadge = when {
    needsSignIn -> AvatarBadge.NeedsSignIn
    hasPin -> AvatarBadge.Pin
    else -> AvatarBadge.None
}

@Composable
fun AvatarCircle(
    initial: String,
    nameKnown: Boolean,
    colorIndex: Int,
    size: Dp,
    modifier: Modifier = Modifier,
    badge: AvatarBadge = AvatarBadge.None,
    bgRing: Boolean = false,
) {
    val c = LocalAbsColors.current
    val color = AvatarColors[colorIndex.coerceIn(0, AvatarColors.size - 1)]
    val s = size.value.toInt()
    val initialSp = when { s >= 100 -> 48; s >= 60 -> 28; s >= 52 -> 24; s >= 44 -> 21; else -> 15 }
    val inset = when { s >= 100 -> 6; s >= 60 -> 4; s >= 44 -> 3; else -> 0 }
    // requiredSize: tv-material wraps a rail item's leadingContent in a 24dp box, and the 32dp avatar needs its own size.
    Box(modifier.requiredSize(size).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize()
                .background(color, CircleShape)
                .then(if (bgRing) Modifier.border(2.dp, c.bg, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (inset > 0) {
                Box(Modifier.fillMaxSize().padding(inset.dp).border(1.dp, AvatarInk.copy(alpha = 0.3f), CircleShape))
            }
            if (nameKnown && initial.isNotEmpty()) {
                Text(initial, fontFamily = FontFamily.Serif, fontSize = initialSp.sp, color = AvatarInk, maxLines = 1)
            } else {
                Icon(Icons.Filled.Person, contentDescription = null, tint = AvatarInk, modifier = Modifier.size((initialSp + 4).dp))
            }
        }
        if (badge != AvatarBadge.None) {
            val (badgeSize, iconSize, out) = when {
                s >= 100 -> Triple(30, 16, 2)
                else -> Triple(22, 13, 6)
            }
            Box(
                Modifier.align(Alignment.BottomEnd).offset(x = out.dp, y = out.dp).size(badgeSize.dp)
                    .background(if (badge == AvatarBadge.NeedsSignIn) c.accent else c.sheet, CircleShape)
                    .border(2.dp, c.bg, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (badge == AvatarBadge.NeedsSignIn) {
                    Icon(ProfileIcons.priorityHigh, null, tint = c.onAccent, modifier = Modifier.size(iconSize.dp))
                } else {
                    Icon(Icons.Filled.Lock, null, tint = c.onSurface, modifier = Modifier.size(iconSize.dp))
                }
            }
        }
    }
}

/** Dashed circle of [size] with a [icon] centred; the empty preview avatar and the Add profile tile at rest. */
@Composable
fun DashedCircle(size: Dp, icon: ImageVector, iconSize: Dp, tint: Color, modifier: Modifier = Modifier, strokeColor: Color = LocalAbsColors.current.outlineColor) {
    Box(
        modifier.size(size).drawBehind {
            drawCircle(
                strokeColor,
                radius = this.size.minDimension / 2 - 1.dp.toPx(),
                style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
            )
        },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(iconSize)) }
}

/** True unless the TV has animations turned off (Developer options): the PIN shake is skipped then. */
@Composable
fun rememberAnimationsOn(): Boolean {
    val ctx: Context = LocalContext.current
    return remember {
        try {
            Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
        } catch (e: Exception) {
            true
        }
    }
}

/** A quiet button: icon + 14sp Medium text in `muted`, an accent pill at 1.1x when focused. */
@Composable
fun GhostButton(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalAbsColors.current
    Surface(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(20.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent, contentColor = c.muted,
            focusedContainerColor = c.accent, focusedContentColor = c.onAccent,
            pressedContainerColor = c.accent, pressedContentColor = c.onAccent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
    ) {
        Row(Modifier.height(40.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

/**
 * The Log out dialog's skin (design 4.7): avatar + serif title, muted body, [confirm] and then Cancel on the right, with
 * Cancel focused. Back = Cancel.
 */
@Composable
fun ConfirmDialog(
    who: ProfileSummary?,
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val c = LocalAbsColors.current
    val cancel = remember { FocusRequester() }
    LaunchedEffect(Unit) { cancel.requestWhenReady() }
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(c.bg.copy(alpha = 0.78f)), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(440.dp)
                    .background(c.sheet, RoundedCornerShape(20.dp))
                    .border(1.dp, c.hairline, RoundedCornerShape(20.dp))
                    .padding(start = 28.dp, top = 28.dp, end = 28.dp, bottom = 24.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (who != null) {
                        ProfileAvatar(who, 48.dp)
                        Spacer(Modifier.width(16.dp))
                    }
                    Text(title, fontFamily = FontFamily.Serif, fontSize = 24.sp, lineHeight = 30.sp, color = c.onSurface)
                }
                Text(body, fontSize = 15.sp, lineHeight = 22.sp, color = c.muted, modifier = Modifier.padding(top = 14.dp))
                Row(Modifier.padding(top = 24.dp).align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AbsButton(onClick = onConfirm) { Text(confirmLabel) }
                    AbsButton(onClick = onCancel, modifier = Modifier.focusRequester(cancel)) { Text("Cancel") }
                }
            }
        }
    }
}

/** Icons the profile screens need that material-icons-core doesn't have, drawn as simple 24dp paths. */
object ProfileIcons {
    private fun build(name: String, vararg parts: Triple<String, Boolean, PathFillType>): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            for ((d, filled, fillType) in parts) {
                addPath(
                    PathParser().parsePathString(d).toNodes(),
                    pathFillType = fillType,
                    fill = if (filled) SolidColor(Color.Black) else null,
                    stroke = if (filled) null else SolidColor(Color.Black),
                    strokeLineWidth = 1.9f,
                    strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
                    strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
                )
            }
        }.build()

    private fun filled(d: String, t: PathFillType = PathFillType.NonZero) = Triple(d, true, t)
    private fun line(d: String) = Triple(d, false, PathFillType.NonZero)

    private const val CIRCLE_R10 = "M12,2 A10,10 0 1 1 11.99,2 Z"

    /** "!" glyph: bar + dot. */
    val priorityHigh: ImageVector by lazy {
        build("priorityHigh", filled("M10.6,3 H13.4 V13.5 H10.6 Z"), filled("M12,15.6 A2,2 0 1 1 11.99,15.6 Z"))
    }

    /** Filled circle with a "!" cut out (rail list: needs sign-in). */
    val errorBadge: ImageVector by lazy {
        build(
            "errorBadge",
            filled("$CIRCLE_R10 M10.8,6 H13.2 V13 H10.8 Z M12,14.6 A1.5,1.5 0 1 1 11.99,14.6 Z", PathFillType.EvenOdd),
        )
    }

    val lockOpen: ImageVector by lazy {
        build("lockOpen", line("M5.5,11 H17.5 V20.5 H5.5 Z"), line("M8.5,11 V7.5 A3.5,3.5 0 0 1 15.2,6.1"))
    }

    val timer: ImageVector by lazy {
        build("timer", line("M12,6.5 A7,7 0 1 1 11.99,6.5 Z"), line("M12,10 V14 L14.5,15.5"), line("M9.5,2.8 H14.5"))
    }

    val backspace: ImageVector by lazy {
        build("backspace", line("M9,5.5 H20 A1.5,1.5 0 0 1 21.5,7 V17 A1.5,1.5 0 0 1 20,18.5 H9 L2.5,12 Z"), line("M12.8,9.5 L17.8,14.5 M17.8,9.5 L12.8,14.5"))
    }

    val personAdd: ImageVector by lazy {
        build(
            "personAdd",
            filled("M10,4.2 A3.3,3.3 0 1 1 9.99,4.2 Z"), filled("M3.5,19.5 C3.5,15.8 6.5,13.8 10,13.8 C13.5,13.8 16.5,15.8 16.5,19.5 Z"),
            line("M19.5,8 V14 M16.5,11 H22.5"),
        )
    }

    val personRemove: ImageVector by lazy {
        build(
            "personRemove",
            filled("M10,4.2 A3.3,3.3 0 1 1 9.99,4.2 Z"), filled("M3.5,19.5 C3.5,15.8 6.5,13.8 10,13.8 C13.5,13.8 16.5,15.8 16.5,19.5 Z"),
            line("M16.5,11 H22.5"),
        )
    }

    val add: ImageVector get() = Icons.Filled.Add
    val person: ImageVector get() = Icons.Filled.Person
    val lock: ImageVector get() = Icons.Filled.Lock
    val checkCircle: ImageVector get() = Icons.Filled.CheckCircle
    /** Stand-in for Material's lock_reset (not in icons-core). */
    val lockReset: ImageVector get() = Icons.Filled.Refresh
}
