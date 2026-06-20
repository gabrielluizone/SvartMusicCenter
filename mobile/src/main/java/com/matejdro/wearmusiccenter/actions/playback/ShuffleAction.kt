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
 * Toggles shuffle on/off. Shuffle/repeat were never added to the bare framework
 * `android.media.session` API - they only exist on the AndroidX media-compat layer
 * (`MediaControllerCompat`/`PlaybackStateCompat`), which nearly every modern player app
 * (including YouTube Music) builds its session on internally. Wrapping the framework
 * `MediaSession.Token` we already have via [MediaSessionCompat.Token.fromToken] gets us a
 * [MediaControllerCompat] for the *same* session without needing a different session lookup path.
 */
class ShuffleAction : SelectableAction {
    constructor(context: Context) : super(context)
    constructor(context: Context, bundle: PersistableBundle) : super(context, bundle)

    override fun retrieveTitle(): String = context.getString(R.string.action_shuffle)
    override val defaultIcon: Drawable
        get() = AppCompatResources.getDrawable(context, com.matejdro.common.R.drawable.action_shuffle)!!

    class Handler @Inject constructor(private val service: MusicService) : ActionHandler<ShuffleAction> {
        override suspend fun handleAction(action: ShuffleAction) {
            val controller = service.currentMediaController ?: return
            val compatController = MediaControllerCompat(
                    service,
                    MediaSessionCompat.Token.fromToken(controller.sessionToken)
            )

            val isShuffling = compatController.shuffleMode != PlaybackStateCompat.SHUFFLE_MODE_NONE
            val newMode = if (isShuffling) {
                PlaybackStateCompat.SHUFFLE_MODE_NONE
            } else {
                PlaybackStateCompat.SHUFFLE_MODE_ALL
            }

            compatController.transportControls.setShuffleMode(newMode)
        }
    }
}
