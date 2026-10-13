@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.teshlor.abstv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import androidx.compose.material3.Text as M3Text

/** Themed button that fills with the accent (and scales up) when focused so D-pad focus is obvious. */
@Composable
fun AbsButton(onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val c = LocalAbsColors.current
    androidx.tv.material3.Button(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.colors(
            containerColor = c.surface,
            contentColor = c.onSurface,
            focusedContainerColor = c.accent,
            focusedContentColor = c.onAccent,
            pressedContainerColor = c.accent,
            pressedContentColor = c.onAccent,
        ),
        scale = ButtonDefaults.scale(focusedScale = 1.1f),
        content = content,
    )
}

@Composable
private fun fieldColors(): androidx.compose.material3.TextFieldColors {
    val c = LocalAbsColors.current
    return OutlinedTextFieldDefaults.colors(
        focusedTextColor = c.onSurface,
        unfocusedTextColor = c.onSurface,
        focusedBorderColor = c.accent,
        unfocusedBorderColor = c.muted,
        focusedLabelColor = c.accent,
        unfocusedLabelColor = c.muted,
        cursorColor = c.accent,
    )
}

/**
 * One sign-in screen for four situations (design/profiles/HANDOFF.md 4.5, 4.6, 13.4): the very first sign-in (as it always
 * was), Add profile, Sign in again (password only, plus "Remove from this TV"), and Forgot PIN (password only).
 */
@Composable
fun LoginScreen(vm: AppViewModel) {
    val c = LocalAbsColors.current
    val prefill = vm.loginPrefill
    val mode = prefill.mode
    var server by remember(prefill) { mutableStateOf(prefill.server) }
    var user by remember(prefill) { mutableStateOf(prefill.username) }
    var pass by remember { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val who = vm.profiles.firstOrNull { it.id == prefill.profileId }
    val passwordOnly = (mode == LoginMode.REAUTH || mode == LoginMode.FORGOT_PIN) && prefill.username.isNotEmpty()
    val canCancel = when (mode) {
        LoginMode.FIRST -> false
        LoginMode.REAUTH -> vm.stack.size > 1 || vm.profiles.size >= 2 // the only profile: nowhere to cancel to
        else -> true
    }
    LaunchedEffect(mode) { if (mode != LoginMode.FIRST) first.requestWhenReady() }

    fun submit() {
        if (!vm.signingIn) vm.login(server, if (passwordOnly) prefill.username else user, pass)
    }
    val imeDone = KeyboardActions(onDone = { submit() })

    @Composable
    fun PasswordField(modifier: Modifier = Modifier) {
        OutlinedTextField(
            pass, { pass = it }, modifier.fillMaxWidth(), singleLine = true, colors = fieldColors(), label = { M3Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = imeDone,
        )
    }

    @Composable
    fun ErrorLine() {
        vm.error?.let { Text(it, fontSize = 14.sp, lineHeight = 20.sp, color = c.error, modifier = Modifier.padding(top = 8.dp)) }
    }

    when (mode) {
        LoginMode.FIRST -> Column(
            Modifier.fillMaxSize().padding(horizontal = 240.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Text("Audiobookshelf", fontFamily = FontFamily.Serif, fontSize = 32.sp, color = c.accent)
            OutlinedTextField(
                server, { server = it }, Modifier.fillMaxWidth(), singleLine = true, colors = fieldColors(),
                label = { M3Text("Server (e.g. 192.168.1.10:13378)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                user, { user = it }, Modifier.fillMaxWidth(), singleLine = true, colors = fieldColors(), label = { M3Text("Username") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            PasswordField()
            vm.error?.let { Text(it, color = c.error) }
            AbsButton(onClick = { submit() }) { Text(if (vm.signingIn) "Signing in…" else "Sign in") }
        }

        LoginMode.ADD -> Column(Modifier.fillMaxSize().padding(start = 240.dp, end = 240.dp, top = 84.dp)) {
            LoginHeader("AUDIOBOOKSHELF", "Add a profile") {
                // Previews the colour the new profile will get; the initial follows the username as it is typed.
                val initial = user.trim().firstOrNull()?.uppercase()
                if (initial == null) DashedCircle(64.dp, ProfileIcons.person, 24.dp, c.muted)
                else AvatarCircle(initial, true, prefill.colorIndex, 64.dp)
            }
            Text(
                "Sign in with your own Audiobookshelf account. It stays on this TV, so next time you just pick your name.",
                fontSize = 15.sp, lineHeight = 22.sp, color = c.muted, modifier = Modifier.padding(top = 18.dp).width(480.dp),
            )
            Column(Modifier.padding(top = 20.dp).width(480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    server, { server = it }, Modifier.fillMaxWidth(), singleLine = true, colors = fieldColors(),
                    label = { M3Text("Server (e.g. 192.168.1.10:13378)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                )
                OutlinedTextField(
                    user, { user = it }, Modifier.fillMaxWidth().focusRequester(first), singleLine = true, colors = fieldColors(),
                    label = { M3Text("Username") }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                )
                PasswordField()
            }
            ErrorLine()
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AbsButton(onClick = { submit() }) { Text(if (vm.signingIn) "Signing in…" else "Sign in") }
                AbsButton(onClick = { vm.cancelLogin() }) { Text("Cancel") }
            }
        }

        LoginMode.REAUTH, LoginMode.FORGOT_PIN -> Column(Modifier.fillMaxSize().padding(start = 240.dp, end = 240.dp, top = 140.dp)) {
            val forgot = mode == LoginMode.FORGOT_PIN
            val name = prefill.displayName
            LoginHeader(if (forgot) "FORGOT PIN" else "SIGN IN AGAIN", name) {
                val badge = if (forgot) AvatarBadge.Pin else AvatarBadge.NeedsSignIn
                if (who != null) ProfileAvatar(who, 64.dp, badge = badge)
                else AvatarCircle(name.firstOrNull()?.uppercase().orEmpty(), true, prefill.colorIndex, 64.dp, badge = badge)
            }
            val host = hostLabel(prefill.server)
            val body = buildAnnotatedString {
                if (!passwordOnly) {
                    append(
                        if (forgot) "Enter your Audiobookshelf username and password, then choose a new PIN."
                        else "Your session on $host ended. Enter your username and password to keep listening.",
                    )
                } else {
                    append(if (forgot) "Enter the Audiobookshelf password for " else "Your session on $host ended. Enter the password for ")
                    withStyle(SpanStyle(color = c.onSurface, fontWeight = FontWeight.Medium)) { append(prefill.username) }
                    append(if (forgot) ", then choose a new PIN." else " to keep listening.")
                }
            }
            Text(body, fontSize = 15.sp, lineHeight = 22.sp, color = c.muted, modifier = Modifier.padding(top = 20.dp).width(480.dp))
            Column(Modifier.padding(top = 16.dp).width(480.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!passwordOnly) {
                    OutlinedTextField(
                        user, { user = it }, Modifier.fillMaxWidth().focusRequester(first), singleLine = true, colors = fieldColors(),
                        label = { M3Text("Username") }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    )
                    PasswordField()
                } else {
                    PasswordField(Modifier.focusRequester(first))
                }
            }
            ErrorLine()
            Row(Modifier.padding(top = if (vm.error != null) 12.dp else 22.dp).width(480.dp), verticalAlignment = Alignment.CenterVertically) {
                AbsButton(onClick = { submit() }) {
                    Text(if (vm.signingIn) (if (forgot) "Checking…" else "Signing in…") else if (forgot) "Continue" else "Sign in")
                }
                if (canCancel) {
                    Spacer(Modifier.width(12.dp))
                    AbsButton(onClick = { vm.cancelLogin() }) { Text("Cancel") }
                }
                if (!forgot && prefill.profileId != null) {
                    Spacer(Modifier.weight(1f))
                    GhostButton("Remove from this TV", ProfileIcons.personRemove, onClick = { confirmRemove = true })
                }
            }
        }
    }

    val pid = prefill.profileId
    if (confirmRemove && pid != null) ConfirmDialog(
        who = who,
        title = "Remove ${prefill.displayName} from this TV?",
        body = "${prefill.displayName}’s sign-in will be removed from this TV. Listening progress stays saved on the server.",
        confirmLabel = "Remove",
        onConfirm = { confirmRemove = false; vm.requestRemoveProfile(pid) },
        onCancel = { confirmRemove = false },
    )
}

/** Serif section title followed by a short fading accent rule (decorative). */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    val c = LocalAbsColors.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontFamily = FontFamily.Serif, fontSize = 21.sp, lineHeight = 24.sp, letterSpacing = 0.2.sp, color = c.accent)
        Spacer(Modifier.width(14.dp))
        Box(
            Modifier.width(220.dp).height(1.dp).background(
                Brush.horizontalGradient(listOf(c.accent.copy(alpha = 0.6f), c.accent.copy(alpha = 0f))),
            ),
        )
    }
}

@Composable
fun DetailScreen(vm: AppViewModel) {
    val c = LocalAbsColors.current
    val book = vm.selectedBook ?: return
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(Modifier.fillMaxSize().padding(48.dp), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        AsyncImage(
            model = vm.api?.coverUrl(book.id), contentDescription = book.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(280.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(book.title, fontSize = 28.sp)
            if (book.author.isNotEmpty()) Text(book.author, fontSize = 18.sp, color = c.accent)
            if (book.media.duration > 0) Text(formatTime(book.media.duration))
            book.media.metadata.description?.let {
                Text(it.replace(Regex("<[^>]*>"), ""), fontSize = 14.sp, maxLines = 8, overflow = TextOverflow.Ellipsis)
            }
            vm.error?.let { Text(it, color = c.error) }
            AbsButton(onClick = { vm.play() }, modifier = Modifier.focusRequester(focus)) {
                Text(if (vm.loading) "Starting…" else "Play / Resume")
            }
        }
    }
}

@Composable
fun PlayerScreen(vm: AppViewModel) {
    val c = LocalAbsColors.current
    val p = vm.player
    val book = vm.selectedBook
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        if (book != null) {
            AsyncImage(
                model = vm.api?.coverUrl(book.id), contentDescription = p.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(220.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
            )
        }
        Text(p.title, fontSize = 24.sp)
        Text(p.author, color = c.accent)
        Box(Modifier.fillMaxWidth(0.6f).height(6.dp).background(c.surface)) {
            val frac = if (p.duration > 0) (p.position / p.duration).toFloat().coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth(frac).height(6.dp).background(c.accent))
        }
        Text("${formatTime(p.position)} / ${formatTime(p.duration)}")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AbsButton(onClick = { p.seekBy(-30.0) }) { Text("⏪ 30s") }
            AbsButton(onClick = { p.togglePlay() }, modifier = Modifier.focusRequester(focus)) {
                Text(if (p.isPlaying) "Pause" else "Play")
            }
            AbsButton(onClick = { p.seekBy(30.0) }) { Text("30s ⏩") }
        }
        Text("Back to stop and return", fontSize = 12.sp, color = c.muted)
    }
}

fun formatTime(sec: Double): String {
    val s = sec.toLong().coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val r = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
}
