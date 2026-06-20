package com.matejdro.wearmusiccenter.actions.playback

import android.content.Context
import android.graphics.drawable.Drawable
import android.media.session.PlaybackState
import android.os.PersistableBundle
import androidx.appcompat.content.res.AppCompatResources
import com.matejdro.wearmusiccenter.R
import com.matejdro.wearmusiccenter.actions.ActionHandler
import com.matejdro.wearmusiccenter.actions.SelectableAction
import com.matejdro.wearmusiccenter.music.MusicService
import javax.inject.Inject

/**
 * Toggles a "like"/"favorite" custom action exposed by the currently playing app's
 * MediaSession, e.g. YouTube Music's or Retro Music's thumbs-up/favorite button.
 *
 * There is no standardized MediaSession API for "like" - it is surfaced as one of the
 * app-defined [android.media.session.PlaybackState.CustomAction]s, so the best a generic
 * remote like this can do is look for a custom action whose name/id looks like a like button,
 * and guess whether it's currently active from whether its label reads like "like" or "unlike".
 * This is inherently best-effort and may not work identically on every app.
 */
class LikeAction : SelectableAction {
    constructor(context: Context) : super(context)
    constructor(context: Context, bundle: PersistableBundle) : super(context, bundle)

    override fun retrieveTitle(): String = context.getString(R.string.action_like)
    override val defaultIcon: Drawable
        get() = AppCompatResources.getDrawable(context, com.matejdro.common.R.drawable.action_like)!!

    companion object {
        private val LIKE_NAME_HINTS = listOf("like", "thumb", "favorite", "favourite", "love")
        private val ALREADY_LIKED_HINTS = listOf("unlike", "remove", "undo", "unfavorite", "unfavourite")

        fun findLikeCustomAction(playbackState: PlaybackState): PlaybackState.CustomAction? {
            return playbackState.customActions.orEmpty().firstOrNull { customAction ->
                LIKE_NAME_HINTS.any { hint ->
                    customAction.action.contains(hint, ignoreCase = true) ||
                            customAction.name.toString().contains(hint, ignoreCase = true)
                }
            }
        }

        /** Best-effort guess at whether the track is *currently* liked, based on whether the
         *  matched custom action's label/id reads like "remove/undo like" (already liked) rather
         *  than "like" (not liked yet). Not all apps expose enough information to tell. */
        fun isCurrentlyLiked(playbackState: PlaybackState): Boolean {
            val action = findLikeCustomAction(playbackState) ?: return false
            return ALREADY_LIKED_HINTS.any { hint ->
                action.action.contains(hint, ignoreCase = true) ||
                        action.name.toString().contains(hint, ignoreCase = true)
            }
        }
    }

    class Handler @Inject constructor(private val service: MusicService) : ActionHandler<LikeAction> {
        override suspend fun handleAction(action: LikeAction) {
            val playbackState = service.currentMediaController?.playbackState ?: return
            val likeAction = findLikeCustomAction(playbackState) ?: return

            service.currentMediaController?.transportControls
                    ?.sendCustomAction(likeAction.action, likeAction.extras)
        }
    }
}
