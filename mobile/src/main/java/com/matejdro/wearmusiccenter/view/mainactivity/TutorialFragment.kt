package com.matejdro.wearmusiccenter.view.mainactivity

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.fragment.app.Fragment
import com.matejdro.wearmusiccenter.R
import com.matejdro.wearmusiccenter.view.TitledActivity
import com.matejdro.common.R as commonR

/**
 * "Guide" tab — a static, scrollable walkthrough of everything the watch app can do (screen-zone
 * taps, multi-press gestures, physical buttons, rotary, swipes, the Tile and system media controls).
 * Purely informational, so it needs no DI or view model.
 */
class TutorialFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_tutorial, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindCard(view, R.id.card_taps, R.drawable.ic_nav_actions,
            R.string.tutorial_taps_title, R.string.tutorial_taps_desc)
        bindCard(view, R.id.card_gestures, R.drawable.ic_nav_tutorial,
            R.string.tutorial_gestures_title, R.string.tutorial_gestures_desc)
        bindCard(view, R.id.card_buttons, R.drawable.ic_settings,
            R.string.tutorial_buttons_title, R.string.tutorial_buttons_desc)
        bindCard(view, R.id.card_rotary, commonR.drawable.button_turn_cw,
            R.string.tutorial_rotary_title, R.string.tutorial_rotary_desc)
        bindCard(view, R.id.card_swipe_up, R.drawable.ic_playlist_play,
            R.string.tutorial_swipe_up_title, R.string.tutorial_swipe_up_desc)
        bindCard(view, R.id.card_exit, commonR.drawable.button_back,
            R.string.tutorial_exit_title, R.string.tutorial_exit_desc)
        bindCard(view, R.id.card_tile, R.drawable.ic_music_note,
            R.string.tutorial_tile_title, R.string.tutorial_tile_desc)
        bindCard(view, R.id.card_system, R.drawable.ic_nav_playing,
            R.string.tutorial_system_title, R.string.tutorial_system_desc)
    }

    override fun onStart() {
        super.onStart()

        (activity as? TitledActivity)?.updateActivityTitle(getString(R.string.tutorial_header))
    }

    private fun bindCard(
        root: View,
        cardId: Int,
        @DrawableRes icon: Int,
        @StringRes title: Int,
        @StringRes description: Int
    ) {
        val card = root.findViewById<View>(cardId)
        card.findViewById<ImageView>(R.id.tutorial_card_icon).setImageResource(icon)
        card.findViewById<TextView>(R.id.tutorial_card_title).setText(title)
        card.findViewById<TextView>(R.id.tutorial_card_desc).setText(description)
    }
}
