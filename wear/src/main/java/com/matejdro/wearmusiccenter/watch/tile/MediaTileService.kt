package com.matejdro.wearmusiccenter.watch.tile

import android.content.Context
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER
import androidx.wear.protolayout.LayoutElementBuilders.Row
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Button
import androidx.wear.protolayout.material.ButtonColors
import androidx.wear.protolayout.material.ButtonDefaults
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.Wearable
import com.google.common.util.concurrent.ListenableFuture
import com.matejdro.wearmusiccenter.common.CommPaths
import com.matejdro.wearmusiccenter.proto.MusicState
import com.matejdro.wearmusiccenter.watch.view.MainActivity
import com.matejdro.wearutils.messages.sendMessageToNearestClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.tasks.await

/**
 * Glanceable quick-control Tile: shows the current track + artist and play/pause, skip prev/next
 * buttons, without opening the app. Tapping the text opens Lyra on the watch.
 *
 * The Tile is a pure proxy like [com.matejdro.wearmusiccenter.watch.communication.WatchMediaSession]:
 * it reads the latest music-state [androidx.wear.protolayout] DataItem the phone already publishes,
 * and forwards transport controls back to the phone over the Data Layer. Button taps use a
 * [ActionBuilders.LoadAction] so the framework re-requests the Tile; we read the clicked id from
 * [RequestBuilders.TileRequest.getCurrentState] and dispatch the matching control before rebuilding.
 */
class MediaTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> = scope.future {
        // Dispatch any pending click first, then render the freshest state we can read.
        dispatchClick(requestParams.currentState.lastClickableId)
        val state = readMusicState()

        val layout = buildLayout(this@MediaTileService, state)

        TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(REFRESH_INTERVAL_MS)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            .build()
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> = scope.future {
        ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .addIdToImageMapping(ICON_PREV, resourceById(com.matejdro.common.R.drawable.action_skip_prev))
            .addIdToImageMapping(ICON_NEXT, resourceById(com.matejdro.common.R.drawable.action_skip_next))
            .addIdToImageMapping(ICON_PLAY, resourceById(com.matejdro.common.R.drawable.action_play))
            .addIdToImageMapping(ICON_PAUSE, resourceById(com.matejdro.common.R.drawable.action_pause))
            .build()
    }

    private suspend fun dispatchClick(clickedId: String?) {
        val path = when (clickedId) {
            ID_PLAY_PAUSE -> CommPaths.MESSAGE_TOGGLE_PLAY_PAUSE
            ID_SKIP_NEXT -> CommPaths.MESSAGE_SKIP_NEXT
            ID_SKIP_PREV -> CommPaths.MESSAGE_SKIP_PREVIOUS
            else -> return
        }

        val messageClient = Wearable.getMessageClient(this)
        val nodeClient = Wearable.getNodeClient(this)
        messageClient.sendMessageToNearestClient(nodeClient, path)
    }

    private suspend fun readMusicState(): MusicState? {
        return try {
            val buffer = Wearable.getDataClient(this).dataItems.await()
            try {
                val item = buffer.firstOrNull { it.uri.path == CommPaths.DATA_MUSIC_STATE }
                item?.let { MusicState.parseFrom(it.data) }
            } finally {
                buffer.release()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun buildLayout(context: Context, state: MusicState?): LayoutElementBuilders.LayoutElement {
        val hasMusic = state != null && !state.error
        val title = if (hasMusic && state!!.title.isNotBlank()) state.title else context.getString(
            com.matejdro.wearmusiccenter.R.string.tile_nothing_playing
        )
        val artist = if (hasMusic) state!!.artist else ""
        val playing = hasMusic && state!!.playing

        val openAppClickable = Clickable.Builder()
            .setId(ID_OPEN_APP)
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(context.packageName)
                            .setClassName(MainActivity::class.java.name)
                            .build()
                    )
                    .build()
            )
            .build()

        val textColumn = Column.Builder()
            .setWidth(expand())
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .setModifiers(
                androidx.wear.protolayout.ModifiersBuilders.Modifiers.Builder()
                    .setClickable(openAppClickable)
                    .build()
            )
            .addContent(
                Text.Builder(context, title)
                    .setTypography(Typography.TYPOGRAPHY_TITLE3)
                    .setColor(argb(COLOR_ON_SURFACE))
                    .setMaxLines(1)
                    .build()
            )
            .apply {
                if (artist.isNotBlank()) {
                    addContent(
                        Text.Builder(context, artist)
                            .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                            .setColor(argb(COLOR_SECONDARY))
                            .setMaxLines(1)
                            .build()
                    )
                }
            }
            .build()

        val controlsRow = Row.Builder()
            .setWidth(wrap())
            .addContent(controlButton(context, ID_SKIP_PREV, ICON_PREV, accent = false))
            .addContent(Spacer.Builder().setWidth(dp(8f)).build())
            .addContent(
                controlButton(
                    context,
                    ID_PLAY_PAUSE,
                    if (playing) ICON_PAUSE else ICON_PLAY,
                    accent = true
                )
            )
            .addContent(Spacer.Builder().setWidth(dp(8f)).build())
            .addContent(controlButton(context, ID_SKIP_NEXT, ICON_NEXT, accent = false))
            .build()

        return androidx.wear.protolayout.LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
            .setModifiers(
                androidx.wear.protolayout.ModifiersBuilders.Modifiers.Builder()
                    .setBackground(
                        androidx.wear.protolayout.ModifiersBuilders.Background.Builder()
                            .setColor(argb(COLOR_BACKGROUND))
                            .build()
                    )
                    .build()
            )
            .addContent(
                Column.Builder()
                    .setWidth(expand())
                    .setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
                    .addContent(textColumn)
                    .addContent(Spacer.Builder().setHeight(dp(12f)).build())
                    .addContent(controlsRow)
                    .build()
            )
            .build()
    }

    private fun controlButton(
        context: Context,
        clickId: String,
        iconId: String,
        accent: Boolean
    ): Button {
        val clickable = Clickable.Builder()
            .setId(clickId)
            .setOnClick(ActionBuilders.LoadAction.Builder().build())
            .build()

        val colors = if (accent) {
            ButtonColors(COLOR_ACCENT, COLOR_BACKGROUND)
        } else {
            ButtonColors(COLOR_SURFACE, COLOR_ON_SURFACE)
        }

        return Button.Builder(context, clickable)
            .setIconContent(iconId)
            .setButtonColors(colors)
            .setSize(if (accent) ButtonDefaults.LARGE_SIZE else ButtonDefaults.DEFAULT_SIZE)
            .build()
    }

    private fun resourceById(resId: Int): ResourceBuilders.ImageResource {
        return ResourceBuilders.ImageResource.Builder()
            .setAndroidResourceByResId(
                ResourceBuilders.AndroidImageResourceByResId.Builder()
                    .setResourceId(resId)
                    .build()
            )
            .build()
    }

    companion object {
        private const val RESOURCES_VERSION = "1"
        private const val REFRESH_INTERVAL_MS = 30_000L

        private const val ID_OPEN_APP = "open_app"
        private const val ID_PLAY_PAUSE = "tile_play_pause"
        private const val ID_SKIP_NEXT = "tile_skip_next"
        private const val ID_SKIP_PREV = "tile_skip_prev"

        private const val ICON_PREV = "ic_prev"
        private const val ICON_NEXT = "ic_next"
        private const val ICON_PLAY = "ic_play"
        private const val ICON_PAUSE = "ic_pause"

        private const val COLOR_BACKGROUND = 0xFF000000.toInt()
        private const val COLOR_SURFACE = 0xFF1E1E1E.toInt()
        private const val COLOR_ON_SURFACE = 0xFFF0F0F0.toInt()
        private const val COLOR_SECONDARY = 0xFFB0B0B0.toInt()
        private const val COLOR_ACCENT = 0xFF87A89F.toInt()
    }
}
