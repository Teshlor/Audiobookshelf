@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package com.teshlor.abstv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// The PIN pad (design/profiles/HANDOFF.md 13.2, 13.3). The step machine and pad state are in PinFlow.kt (unit tested).

/** Hosts [PinScreen] for the view model's current [PinTask]. A new step or notice starts a fresh pad. */
@Composable
fun PinTaskScreen(vm: AppViewModel) {
    val task = vm.pinTask ?: return
    val who = vm.profiles.firstOrNull { it.id == task.profileId } ?: return
    key(task) {
        PinScreen(
            who = who,
            instruction = task.instruction(who.displayName),
            ghost = task.ghost(),
            notice = task.notice,
            initialCooldownUntil = if (task.step == PinStep.UNLOCK || task.step == PinStep.VERIFY) vm.pinCooldownUntil(who.id) else null,
            onSubmit = { vm.submitPin(it) },
            onGhost = {
                when (task.ghost()) {
                    PinGhost.FORGOT -> { vm.startForgotPin(); null }
                    PinGhost.REMOVE_INSTEAD -> vm.pinRemoveInstead()
                    null -> null
                }
            },
            onDone = { vm.pinStepDone() },
            onBack = { vm.pinBack() },
        )
    }
}

private val DigitKeys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9")

@Composable
fun PinScreen(
    who: ProfileSummary,
    instruction: String,
    ghost: PinGhost?,
    notice: String?,
    initialCooldownUntil: Long?,
    onSubmit: suspend (String) -> PinResult,
    onGhost: () -> PinResult?,
    onDone: () -> Unit,
    onBack: () -> Unit,
) {
    val c = LocalAbsColors.current
    val scope = rememberCoroutineScope()
    val animationsOn = rememberAnimationsOn()

    var pad by remember { mutableStateOf(PinPadState(cooldownUntilMs = initialCooldownUntil ?: 0L)) }
    var message by remember { mutableStateOf(notice) }
    var messageIsError by remember { mutableStateOf(notice != null) }
    var wrong by remember { mutableStateOf(false) }
    var successText by remember { mutableStateOf<String?>(null) }
    var secsLeft by remember { mutableIntStateOf(cooldownSecondsLeft(initialCooldownUntil ?: 0L, System.currentTimeMillis())) }
    val shake = remember { Animatable(0f) }
    val cooling = pad.cooldownUntilMs > 0L
    val busy = pad.checking || successText != null

    // Focus: digit keys (index = digit), backspace, the ghost button. Focus starts on 5.
    val keyReq = remember { List(10) { FocusRequester() } }
    val backReq = remember { FocusRequester() }
    val ghostReq = remember { FocusRequester() }
    val lastKey = remember { arrayOf(keyReq[5]) } // Right from the ghost button goes back to the key you left
    LaunchedEffect(cooling) {
        // Cooldown: focus moves to Forgot PIN. When it ends: back to 5.
        (if (cooling && ghost != null) ghostReq else keyReq[5]).requestWhenReady()
    }

    // Countdown. Wall clock, so a restart or a moved clock can't shorten it (the store clamps the end).
    LaunchedEffect(pad.cooldownUntilMs) {
        val until = pad.cooldownUntilMs
        if (until <= 0L) return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis()
            val left = cooldownSecondsLeft(until, now)
            if (left != secsLeft) secsLeft = left
            if (left <= 0) break
            delay(200)
        }
        pad = pad.cooldownOver()
        message = null
    }

    fun press(d: Char) {
        val next = pad.press(d, System.currentTimeMillis())
        if (next != pad) {
            message = null; messageIsError = false; wrong = false
            pad = next
        }
    }
    fun backspace() {
        val next = pad.delete(System.currentTimeMillis())
        if (next != pad) { message = null; messageIsError = false; wrong = false; pad = next }
    }

    // Four digits in: check them (off the main thread, in the view model), then show the result.
    LaunchedEffect(pad.complete) {
        val pin = pad.complete ?: return@LaunchedEffect
        suspend fun shakeDots() {
            wrong = true
            if (animationsOn) {
                shake.animateTo(0f, keyframes {
                    durationMillis = 360
                    0f at 0; -10f at 51; 10f at 103; -8f at 154; 8f at 206; -4f at 257; 4f at 309; 0f at 360
                })
            } else delay(360)
        }
        when (val r = onSubmit(pin)) {
            is PinResult.Accepted -> {
                if (r.text != null) { successText = r.text; delay(600) }
                onDone()
            }
            is PinResult.Wrong -> {
                if (r.message.isNotEmpty()) { message = r.message; messageIsError = true }
                shakeDots()
                wrong = false
                pad = pad.cleared()
            }
            is PinResult.Cooldown -> {
                shakeDots()
                wrong = false
                secsLeft = cooldownSecondsLeft(r.untilMs, System.currentTimeMillis())
                message = null
                pad = pad.cooling(r.untilMs)
            }
            PinResult.Mismatch -> {
                shakeDots()
                onDone()
            }
        }
    }

    BackHandler(true) { if (!busy) onBack() }

    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            val code = e.nativeKeyEvent.keyCode
            val digit = digitForKeyCode(code)
            if (digit == null && code != KEYCODE_DEL_CODE) return@onPreviewKeyEvent false
            // Number keys and DEL work wherever focus is and never move it. A held key must not fill the dots.
            if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) {
                if (digit != null) press(digit) else backspace()
            }
            true
        },
    ) {
        // Left column (x 240-480, centred at 360).
        Column(Modifier.offset(x = 240.dp, y = 124.dp).width(240.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            ProfileAvatar(who, 112.dp)
            Text(
                who.displayName, fontFamily = FontFamily.Serif, fontSize = 28.sp, lineHeight = 34.sp, color = c.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 14.dp),
            )
            Text(
                if (cooling) "Too many tries" else instruction, fontSize = 15.sp, lineHeight = 20.sp,
                color = if (cooling) c.onSurface else c.muted, fontWeight = if (cooling) FontWeight.Medium else FontWeight.Normal,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
            )
            Box(Modifier.padding(top = 12.dp).height(28.dp), contentAlignment = Alignment.Center) {
                if (cooling) {
                    // Announced once at the start, not every second.
                    val announce = remember(pad.cooldownUntilMs) { "Too many tries. Try again in $secsLeft seconds." }
                    Row(
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.clearAndSetSemantics { contentDescription = announce },
                    ) {
                        Icon(ProfileIcons.timer, null, tint = c.onSurface, modifier = Modifier.size(22.dp))
                        Text(
                            formatCountdown(secsLeft), fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium,
                            color = c.onSurface, style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"),
                        )
                    }
                } else {
                    Dots(pad.digits.length, wrong, shake.asState())
                }
            }
            Box(Modifier.padding(top = 6.dp).width(240.dp).heightIn(min = 40.dp).semantics { liveRegion = LiveRegionMode.Polite }, contentAlignment = Alignment.TopCenter) {
                val text = if (cooling) PIN_COOLDOWN_HELP else message
                when {
                    successText != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(ProfileIcons.checkCircle, null, tint = c.accent, modifier = Modifier.size(20.dp))
                        Text(successText!!, fontSize = 14.sp, lineHeight = 20.sp, color = c.onSurface)
                    }
                    text != null -> Text(
                        text, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 2, textAlign = TextAlign.Center,
                        color = if (messageIsError && !cooling) c.error else c.muted,
                    )
                }
            }
            if (ghost != null) {
                Spacer(Modifier.height(10.dp))
                GhostButton(
                    label = if (ghost == PinGhost.FORGOT) "Forgot PIN?" else "Remove PIN instead",
                    icon = if (ghost == PinGhost.FORGOT) ProfileIcons.lockReset else ProfileIcons.lockOpen,
                    onClick = {
                        if (!busy) scope.launch {
                            val r = onGhost()
                            if (r is PinResult.Accepted) {
                                if (r.text != null) { successText = r.text; delay(600) }
                                onDone()
                            }
                        }
                    },
                    modifier = Modifier.focusRequester(ghostReq).focusProperties { right = lastKey[0] },
                )
            }
        }

        // The 3x4 pad (x 520, y 148): 56dp circles, 16 apart. The bottom-left cell is empty.
        Column(Modifier.offset(x = 520.dp, y = 148.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            for (row in 0 until 3) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    for (col in 0 until 3) {
                        val label = DigitKeys[row * 3 + col]
                        val d = label[0] - '0'
                        PadKey(
                            label = label, cooling = cooling, requester = keyReq[d],
                            onFocused = { lastKey[0] = keyReq[d] },
                            modifier = Modifier.focusProperties {
                                if (col == 0) left = if (ghost != null) ghostReq else FocusRequester.Cancel
                                if (row == 2 && col == 0) down = keyReq[0] // the cell under 7 is empty
                            },
                        ) { press(label[0]) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Spacer(Modifier.size(56.dp))
                PadKey(
                    label = "0", cooling = cooling, requester = keyReq[0], onFocused = { lastKey[0] = keyReq[0] },
                    modifier = Modifier.focusProperties { left = if (ghost != null) ghostReq else FocusRequester.Cancel },
                ) { press('0') }
                PadKey(
                    label = null, icon = ProfileIcons.backspace, description = "Delete", cooling = cooling, requester = backReq,
                    onFocused = { lastKey[0] = backReq },
                ) { backspace() }
            }
        }
    }
}

@Composable
private fun Dots(count: Int, wrong: Boolean, shake: androidx.compose.runtime.State<Float>) {
    val c = LocalAbsColors.current
    Row(
        Modifier.graphicsLayer { translationX = shake.value.dp.toPx() }
            .semantics(mergeDescendants = true) {
                contentDescription = "$count of $PIN_LENGTH digits entered"
                liveRegion = LiveRegionMode.Polite
            },
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        for (i in 0 until PIN_LENGTH) {
            val filled = i < count
            val pop = remember { Animatable(1f) }
            LaunchedEffect(filled) {
                if (filled) { pop.snapTo(0.6f); pop.animateTo(1f, tween(120)) } else pop.snapTo(1f)
            }
            Box(
                Modifier.size(16.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }
                    .then(
                        if (filled) Modifier.background(if (wrong) c.error else c.accent, CircleShape)
                        else Modifier.border(2.dp, if (wrong) c.error else c.muted, CircleShape),
                    ),
            )
        }
    }
}

@Composable
private fun PadKey(
    label: String?,
    cooling: Boolean,
    requester: FocusRequester,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    description: String? = null,
    onClick: () -> Unit,
) {
    val c = LocalAbsColors.current
    Surface(
        onClick = onClick,
        modifier = modifier.size(56.dp).focusRequester(requester)
            .focusProperties { canFocus = !cooling }
            .onFocusChanged { if (it.hasFocus) onFocused() }
            .graphicsLayer { alpha = if (cooling) 0.45f else 1f }
            .semantics { contentDescription = description ?: label.orEmpty(); role = Role.Button },
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (cooling) c.sheet else c.trayColor,
            contentColor = if (cooling) c.muted else c.onSurface,
            focusedContainerColor = c.accent, focusedContentColor = c.onAccent,
            pressedContainerColor = c.accent, pressedContentColor = c.onAccent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
    ) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            if (icon != null) Icon(icon, null, modifier = Modifier.size(24.dp)) // takes the key's content colour (onAccent when focused)
            else Text(label.orEmpty(), fontSize = 24.sp, lineHeight = 28.sp)
        }
    }
}
