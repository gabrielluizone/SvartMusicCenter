package com.matejdro.wearmusiccenter.actions.playback

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.PersistableBundle
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.appcompat.content.res.AppCompatResources
import com.matejdro.wearmusiccenter.R
import com.matejdro.wearmusiccenter.actions.ActionHandler
import com.matejdro.wearmusiccenter.actions.SelectableAction
import com.matejdro.wearmusiccenter.music.MusicService
import javax.inject.Inject

/**
 * Cycles repeat mode (off -> all -> one -> off). See [ShuffleAction] for why this goes through
 * [MediaControllerCompat] instead of the framework `MediaController` we use everywhere else -
 * repeat mode simply doesn't exist on the framework API.
 */
class RepeatAction : SelectableAction {
    constructor(context: Context) : super(context)
    constructor(context: Context, bundle: PersistableBundle) : super(context, bundle)

    override fun retrieveTitle(): String = context.getString(R.string.action_repeat)
    override val defaultIcon: Drawable
        get() = AppCompatResources.getDrawable(context, com.matejdro.common.R.drawable.action_repeat)!!

    class Handler @Inject constructor(private val service: MusicService) : ActionHandler<RepeatAction> {
        override suspend fun handleAction(action: RepeatAction) {
            val controller = service.currentMediaController ?: return
            val compatController = MediaControllerCompat(
                    service,
                    MediaSessionCompat.Token.fromToken(controller.sessionToken)
            )

            val nextMode = when (compatController.repeatMode) {
                PlaybackStateCompat.REPEAT_MODE_NONE -> PlaybackStateCompat.REPEAT_MODE_ALL
                PlaybackStateCompat.REPEAT_MODE_ALL -> PlaybackStateCompat.REPEAT_MODE_ONE
                else -> PlaybackStateCompat.REPEAT_MODE_NONE
            }

            compatController.transportControls.setRepeatMode(nextMode)
        }
    }
}
