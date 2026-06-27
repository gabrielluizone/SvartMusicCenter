package com.matejdro.wearmusiccenter.watch.communication

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.VolumeProviderCompat
import com.matejdro.wearmusiccenter.proto.MusicState
import com.matejdro.wearmusiccenter.watch.view.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private const val VOLUME_MAX = 100
private const val VOLUME_ADJUST_STEP = 5

/**
 * Watch-side [MediaSessionCompat] that mirrors the phone's now-playing state and forwards transport
 * controls back to the phone over the Data Layer.
 *
 * The watch plays nothing itself, so this is a pure proxy: it exists so the system "Media Controls"
 * app and the Wear OS media surfaces can display and control the music the phone is playing. State
 * flows phone -> watch via [update]; control flows watch -> phone via [PhoneConnection].
 */
class WatchMediaSession(
        context: Context,
        private val phoneConnection: PhoneConnection,
        private val scope: CoroutineScope
) {
    private val callback = object : MediaSessionCompat.Callback() {
        // The phone only exposes a play/pause TOGGLE. The system calls onPlay only while paused and
        // onPause only while playing, so forwarding both to the toggle produces the right result.
        override fun onPlay() = forward { phoneConnection.togglePlayPause() }
        override fun onPause() = forward { phoneConnection.togglePlayPause() }
        override fun onSkipToNext() = forward { phoneConnection.sendSkipNext() }
        override fun onSkipToPrevious() = forward { phoneConnection.sendSkipPrevious() }
        override fun onSeekTo(pos: Long) {
            phoneConnection.sendSeek(pos)
        }
    }

    private val volumeProvider =
            object : VolumeProviderCompat(VOLUME_CONTROL_ABSOLUTE, VOLUME_MAX, 0) {
                override fun onSetVolumeTo(volume: Int) {
                    currentVolume = volume.coerceIn(0, VOLUME_MAX)
                    phoneConnection.sendVolume(currentVolume / VOLUME_MAX.toFloat())
                }

                override fun onAdjustVolume(direction: Int) {
                    currentVolume = (currentVolume + direction * VOLUME_ADJUST_STEP)
                            .coerceIn(0, VOLUME_MAX)
                    phoneConnection.sendVolume(currentVolume / VOLUME_MAX.toFloat())
                }
            }

    private val session = MediaSessionCompat(context, "WatchMusicCenter").apply {
        // Flags tell the system this session handles both transport controls (play/pause/skip) and
        // hardware media buttons. Without them, Wear OS recents doesn't show the current track
        // under the app name (the system only surfaces metadata from flagged, active sessions).
        @Suppress("DEPRECATION")
        setFlags(
            MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS or
            MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
        )
        // Link the session to the main activity so the system knows which task it belongs to.
        // This makes the Wear OS recents card show the currently playing song name.
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        setSessionActivity(
            PendingIntent.getActivity(
                context, 0, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        setCallback(callback)
        setPlaybackToRemote(volumeProvider)
    }

    val sessionToken: MediaSessionCompat.Token
        get() = session.sessionToken

    /** Pushes the latest phone state into the session. A null [state] deactivates the session. */
    fun update(state: MusicState?, albumArt: Bitmap?) {
        if (state == null) {
            session.isActive = false
            return
        }

        session.isActive = true

        session.setMetadata(
                MediaMetadataCompat.Builder().apply {
                    putString(MediaMetadataCompat.METADATA_KEY_TITLE, state.title)
                    putString(MediaMetadataCompat.METADATA_KEY_ARTIST, state.artist)
                    if (state.durationMs > 0) {
                        putLong(MediaMetadataCompat.METADATA_KEY_DURATION, state.durationMs)
                    }
                    if (albumArt != null) {
                        putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArt)
                    }
                }.build()
        )

        var actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
        if (state.seekable) {
            actions = actions or PlaybackStateCompat.ACTION_SEEK_TO
        }

        session.setPlaybackState(
                PlaybackStateCompat.Builder()
                        .setActions(actions)
                        .setState(
                                if (state.playing) PlaybackStateCompat.STATE_PLAYING
                                else PlaybackStateCompat.STATE_PAUSED,
                                state.positionMs,
                                state.playbackSpeed
                        )
                        .build()
        )

        volumeProvider.currentVolume = (state.volume * VOLUME_MAX).toInt().coerceIn(0, VOLUME_MAX)
    }

    fun release() {
        session.isActive = false
        session.release()
    }

    private fun forward(block: suspend () -> Unit) {
        scope.launch { block() }
    }
}
