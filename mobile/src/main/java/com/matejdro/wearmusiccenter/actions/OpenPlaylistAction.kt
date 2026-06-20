package com.matejdro.wearmusiccenter.actions

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.PersistableBundle
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.matejdro.wearmusiccenter.R
import com.matejdro.wearmusiccenter.common.CommPaths
import com.matejdro.wearmusiccenter.common.CustomLists
import com.matejdro.wearmusiccenter.music.MusicService
import com.matejdro.wearmusiccenter.proto.CustomList
import kotlinx.coroutines.tasks.await
import javax.inject.Inject

class OpenPlaylistAction : SelectableAction {
    constructor(context: Context) : super(context)
    constructor(context: Context, bundle: PersistableBundle) : super(context, bundle)

    override fun retrieveTitle(): String = context.getString(R.string.open_playlist_menu)
    override val defaultIcon: Drawable
        get() = AppCompatResources.getDrawable(context, com.matejdro.common.R.drawable.action_open_playlist)!!

    class Handler @Inject constructor(private val service: MusicService) : ActionHandler<OpenPlaylistAction> {
        override suspend fun handleAction(action: OpenPlaylistAction) {
            val playlist = service.currentMediaController?.queue?.take(20)
            val putDataRequest = PutDataRequest.create(CommPaths.DATA_CUSTOM_LIST)

            // No per-entry album art thumbnails - the watch UI shows a plain text list (title +
            // artist), matching the stock Wear OS queue look instead of our old icon-row style.
            val listId: String
            val protoList = if (playlist != null && playlist.isNotEmpty()) {
                listId = CustomLists.PLAYLIST
                playlist.map { queueItem ->
                    CustomList.ListEntry.newBuilder()
                            .setEntryId(queueItem.queueId.toString())
                            .setEntryTitle(queueItem.description.title?.toString() ?: "")
                            .setEntrySubtitle(queueItem.description.subtitle?.toString() ?: "")
                            .build()
                }
            } else if (service.recentTrackHistory.isNotEmpty()) {
                // The playing app doesn't expose a live queue (common on Android 10+) - fall
                // back to a locally tracked list of recently played tracks instead.
                listId = CustomLists.HISTORY
                service.recentTrackHistory.mapIndexed { index, entry ->
                    CustomList.ListEntry.newBuilder()
                            .setEntryId(index.toString())
                            .setEntryTitle(entry.title)
                            .setEntrySubtitle(entry.artist)
                            .build()
                }
            } else {
                listId = CustomLists.PLAYLIST
                listOf(
                        CustomList.ListEntry.newBuilder()
                                .setEntryId(CustomLists.SPECIAL_ITEM_ERROR)
                                .setEntryTitle(service.getString(R.string.error_playlist_not_supported))
                                .build()
                )
            }

            val protoData = CustomList.newBuilder()
                    .addAllActions(protoList)
                    .setListId(listId)
                    .setListTimestamp(System.currentTimeMillis())
                    .build()

            putDataRequest.data = protoData.toByteArray()

            Wearable.getDataClient(service).putDataItem(putDataRequest).await()
        }
    }
}
