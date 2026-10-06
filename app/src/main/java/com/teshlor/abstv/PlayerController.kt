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
    private val player = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build(),
            true,
        )
        .build()
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
    private var syncInFlight = false

    suspend fun start(api: AbsApi, itemId: String) {
        stop()
        val s = api.play(itemId)
        if (s.audioTracks.isEmpty()) error("This book has no audio tracks")
        this.api = api
        sessionId = s.id
        offsets = s.audioTracks.map { it.startOffset }
        duration = s.duration
        title = s.displayTitle
        author = s.displayAuthor
        listened = 0.0

        val items = s.audioTracks.map { MediaItem.fromUri(api.trackUrl(it.contentUrl)) }
        val idx = indexFor(s.startTime)
        player.setMediaItems(items, idx, ((s.startTime - offsets[idx]) * 1000).toLong())
        player.prepare()
        player.play()
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
            if (++ticks % 30 == 0) syncNow(close = false) // every ~15s
        }
    }

    private fun syncNow(close: Boolean, force: Boolean = false) {
        val a = api ?: return
        val id = sessionId ?: return
        if (!close && !force && syncInFlight) return
        val pos = globalPosition()
        // If a sync is already in flight its time is on the wire; don't send it twice.
        val l = if (syncInFlight) 0.0 else listened
        val d = duration
        if (close) listened = 0.0 else syncInFlight = true
        scope.launch {
            try {
                if (close) a.close(id, pos, l, d) else a.sync(id, pos, l, d)
                // Only drop the time that was actually sent; a failure carries it forward.
                if (!close) listened = (listened - l).coerceAtLeast(0.0)
            } catch (_: Exception) {
            } finally {
                if (!close) syncInFlight = false
            }
        }
    }

    /** Called when the app leaves the foreground: pause (no background playback) and save the position. */
    fun pauseAndSync() {
        if (!active) return
        player.pause()
        isPlaying = false
        syncNow(close = false, force = true)
    }

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
            syncNow(close = false, force = true)
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
        player.stop()
        player.clearMediaItems()
        active = false
        isPlaying = false
        sessionId = null
    }

    fun release() {
        stop()
        player.release()
    }
}
