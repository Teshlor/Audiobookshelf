package com.teshlor.abstv

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Plays one audiobook (possibly many files) as a single timeline and reports
 * progress to the Audiobookshelf server through a playback session.
 */
class PlayerController(context: Context) {
    // Lazy: building ExoPlayer is slow on weak TV SoCs and is not needed until a book is played, so keep it
    // off the cold-start path. Always first touched on Main (start()).
    private val appContext = context.applicationContext
    private val playerLazy = lazy {
        ExoPlayer.Builder(appContext)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true,
            )
            .build()
    }
    private val player by playerLazy
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var active by mutableStateOf(false); private set
    var isPlaying by mutableStateOf(false); private set
    var position by mutableDoubleStateOf(0.0); private set
    var duration by mutableDoubleStateOf(0.0); private set
    var title by mutableStateOf(""); private set
    var author by mutableStateOf(""); private set

    private var api: AbsApi? = null
    private var sessionId: String? = null
    private var offsets: List<Double> = emptyList()
    private var listened = 0.0
    private var tickJob: Job? = null
    // Listened seconds not yet confirmed by the server, and the part of them currently on the wire.
    private var pendingSent = 0.0
    // Bumped per session so a slow request from an old session can't touch the new counters.
    private var generation = 0

    /** True while the activity is started; playback never begins or continues outside it. */
    var inForeground = false

    suspend fun start(api: AbsApi, itemId: String) {
        stop()
        val s = try {
            api.play(itemId)
        } catch (e: AbsHttpException) {
            // Upstream answers 404 when the item has no audio tracks (e.g. an ebook-only item).
            if (e.code == 404) error("This item has no audio to play (it may be an ebook).") else throw e
        }
        generation++
        if (s.audioTracks.isEmpty()) error("This item has no audio to play (it may be an ebook).")
        this.api = api
        sessionId = s.id
        offsets = s.audioTracks.map { it.startOffset }
        duration = s.duration
        title = s.displayTitle
        author = s.displayAuthor
        listened = 0.0
        pendingSent = 0.0

        val items = s.audioTracks.map { MediaItem.fromUri(api.trackUrl(it.contentUrl)) }
        val idx = indexFor(s.startTime)
        player.setMediaItems(items, idx, ((s.startTime - offsets[idx]) * 1000).toLong())
        player.prepare()
        // If the user left the app while the session request was in flight, stay paused.
        if (inForeground) player.play() else player.pause()
        active = true
        tickJob = scope.launch { tickLoop() }
    }

    private fun indexFor(globalSec: Double): Int =
        offsets.indexOfLast { it <= globalSec }.coerceAtLeast(0)

    private fun globalPosition(): Double {
        val i = player.currentMediaItemIndex.coerceIn(0, (offsets.size - 1).coerceAtLeast(0))
        return (offsets.getOrElse(i) { 0.0 }) + player.currentPosition / 1000.0
    }

    private suspend fun tickLoop() {
        var ticks = 0
        while (true) {
            delay(500)
            position = globalPosition()
            isPlaying = player.isPlaying
            if (isPlaying) listened += 0.5
            // Every ~15s, but only when there is something new to report and no sync is already pending.
            if (++ticks % 30 == 0 && listened - pendingSent > 0.001 && pendingSent < 0.001) syncNow(close = false)
        }
    }

    /**
     * Sends the not-yet-reported listened time (listened - pendingSent) with the current position.
     * The amount is counted as in flight until the request ends: on success it is removed from
     * [listened], on failure it just stops being in flight so the next sync carries it forward.
     */
    private fun syncNow(close: Boolean) {
        val a = api ?: return
        val id = sessionId ?: return
        val gen = generation
        val pos = globalPosition()
        val toSend = (listened - pendingSent).coerceAtLeast(0.0)
        val d = duration
        pendingSent += toSend
        scope.launch {
            val ok = try {
                if (close) a.close(id, pos, toSend, d) else a.sync(id, pos, toSend, d)
                true
            } catch (_: Exception) {
                false
            }
            if (gen == generation) {
                pendingSent = (pendingSent - toSend).coerceAtLeast(0.0)
                if (ok) listened = (listened - toSend).coerceAtLeast(0.0)
            }
        }
    }

    /** Called when the app leaves the foreground: pause (no background playback) and save the position. */
    fun pauseAndSync() {
        if (!active) return
        player.pause()
        isPlaying = false
        syncNow(close = false)
    }

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
            syncNow(close = false)
        } else player.play()
        isPlaying = player.isPlaying
    }

    fun seekBy(deltaSec: Double) {
        val target = (globalPosition() + deltaSec).coerceIn(0.0, duration)
        val i = indexFor(target)
        player.seekTo(i, ((target - offsets[i]) * 1000).toLong())
        position = target
    }

    fun stop() {
        if (!active) return
        tickJob?.cancel()
        syncNow(close = true)
        generation++ // the close request (and any older one) no longer touches the counters
        listened = 0.0
        pendingSent = 0.0
        player.stop()
        player.clearMediaItems()
        active = false
        isPlaying = false
        sessionId = null
    }

    fun release() {
        stop()
        if (playerLazy.isInitialized()) player.release()
    }
}
