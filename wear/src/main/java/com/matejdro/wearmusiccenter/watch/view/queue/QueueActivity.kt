package com.matejdro.wearmusiccenter.watch.view.queue

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.graphics.Color
import dagger.hilt.android.AndroidEntryPoint

/**
 * Full-screen Compose host for the playback queue. Requests the queue on open, renders
 * [QueueScreen], plays the tapped entry, and finishes on swipe-to-dismiss (which returns to the
 * player instead of exiting the app).
 */
@AndroidEntryPoint
class QueueActivity : ComponentActivity() {
    private val viewModel: QueueViewModel by viewModels()

    companion object {
        /**
         * While this Compose queue is showing (and for a short grace period after it closes),
         * MainActivity must not also auto-open the legacy drawer queue for the same customList
         * data - otherwise the old queue pops up behind/after this one.
         */
        @Volatile
        var suppressLegacyQueueUntil: Long = 0L
    }

    override fun onResume() {
        super.onResume()
        suppressLegacyQueueUntil = Long.MAX_VALUE
    }

    override fun onPause() {
        super.onPause()
        suppressLegacyQueueUntil = System.currentTimeMillis() + 1500
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel.requestQueue()

        setContent {
            val items by viewModel.items.observeAsState(emptyList())
            val accent by viewModel.accentColor.observeAsState(DEFAULT_QUEUE_ACCENT)

            QueueScreen(
                    items = items,
                    accentColor = Color(accent),
                    onItemClick = { entryId ->
                        viewModel.selectItem(entryId)
                        finish()
                    },
                    onDismiss = { finish() }
            )
        }
    }
}
