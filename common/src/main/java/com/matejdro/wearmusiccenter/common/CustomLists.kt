package com.matejdro.wearmusiccenter.common

object CustomLists {
    const val PLAYLIST = "Playlist"

    /**
     * Fallback shown instead of [PLAYLIST] when the playing app doesn't expose its queue
     * (a common restriction on Android 10+ for many apps) - a locally tracked list of
     * recently played tracks rather than the live upcoming queue.
     */
    const val HISTORY = "History"

    const val SPECIAL_ITEM_ERROR = "ErrorItem"
}