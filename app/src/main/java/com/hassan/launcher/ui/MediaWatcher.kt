package com.hassan.launcher.ui

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.hassan.launcher.service.NotificationBadgeService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MediaInfo(
    val controller: MediaController,
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val playing: Boolean,
    val packageName: String,
)

class MediaWatcher(private val context: Context) {
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, NotificationBadgeService::class.java)
    private val _info = MutableStateFlow<MediaInfo?>(null)
    val info: StateFlow<MediaInfo?> = _info
    private var controllers: List<MediaController> = emptyList()
    private var started = false

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { attach(it.orEmpty()) }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onSessionDestroyed() = publish()
    }

    fun start() {
        if (started || !NotificationBadgeService.isEnabled(context)) return
        started = true
        runCatching {
            manager.addOnActiveSessionsChangedListener(sessionsListener, component)
            attach(manager.getActiveSessions(component))
        }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { manager.removeOnActiveSessionsChangedListener(sessionsListener) }
        controllers.forEach { runCatching { it.unregisterCallback(callback) } }
        controllers = emptyList()
        _info.value = null
    }

    private fun attach(list: List<MediaController>) {
        controllers.forEach { runCatching { it.unregisterCallback(callback) } }
        controllers = list
        controllers.forEach { runCatching { it.registerCallback(callback) } }
        publish()
    }

    private fun publish() {
        val c = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers.firstOrNull { it.metadata != null && it.playbackState != null && it.playbackState?.state != PlaybackState.STATE_NONE }
        val meta = c?.metadata
        if (c == null || meta == null) {
            _info.value = null
            return
        }
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        if (title.isBlank()) {
            _info.value = null
            return
        }
        _info.value = MediaInfo(
            controller = c,
            title = title,
            artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: "",
            art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
            playing = c.playbackState?.state == PlaybackState.STATE_PLAYING,
            packageName = c.packageName,
        )
    }
}
