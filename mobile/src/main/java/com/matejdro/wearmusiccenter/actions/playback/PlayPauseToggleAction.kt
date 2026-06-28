package com.matejdro.wearmusiccenter.actions.playback

import android.content.Context
import android.graphics.drawable.Drawable
import android.media.AudioManager
import android.os.PersistableBundle
import android.view.KeyEvent
import androidx.appcompat.content.res.AppCompatResources
import com.matejdro.wearmusiccenter.R
import com.matejdro.wearmusiccenter.actions.ActionHandler
import com.matejdro.wearmusiccenter.actions.SelectableAction
import com.matejdro.wearmusiccenter.music.MusicService
import javax.inject.Inject

/**
 * Toggles play/pause with a single button - the most useful mapping for watches with only one
 * physical button. Sends [KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE]; if no controller is active yet it
 * falls back to the system [AudioManager] so playback can still be resumed (mirrors [PlayAction]).
 */
class PlayPauseToggleAction : SelectableAction {
    constructor(context: Context) : super(context)
    constructor(context: Context, bundle: PersistableBundle) : super(context, bundle)

    override fun retrieveTitle(): String = context.getString(R.string.action_play_pause)
    override val defaultIcon: Drawable
        get() = AppCompatResources.getDrawable(context, com.matejdro.common.R.drawable.action_play_pause)!!

    class Handler @Inject constructor(private val service: MusicService) : ActionHandler<PlayPauseToggleAction> {
        override suspend fun handleAction(action: PlayPauseToggleAction) {
            val mediaController = service.currentMediaController

            if (mediaController != null) {
                mediaController.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                mediaController.dispatchMediaButtonEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                return
            }

            val audioService = service.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioService.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
            audioService.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        }
    }
}
