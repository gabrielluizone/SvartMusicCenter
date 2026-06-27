package com.matejdro.wearmusiccenter.watch.view.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.matejdro.wearmusiccenter.R
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SwipeToDismissBox
import androidx.wear.compose.material3.Text

/** View model for one queue row. [isPlaying] marks the entry the phone reports as currently active. */
data class QueueItemUi(
        val entryId: String,
        val title: String,
        val subtitle: String?,
        val isPlaying: Boolean
)

private val IDLE_PILL_COLOR = Color(0xFF1C1C1E)

/** The app-wide Google Sans typeface, so the queue matches the rest of the watch UI. */
private val GoogleSans = FontFamily(
        Font(R.font.google_sans_regular, FontWeight.Normal),
        Font(R.font.google_sans_bold, FontWeight.Bold)
)

/**
 * Playback queue screen. A [ScalingLazyColumn] of glass pills with the now-playing entry
 * highlighted in [accentColor]. Wrapped in a [SwipeToDismissBox] so swiping right closes only this
 * screen (via [onDismiss]) instead of the whole app.
 */
@Composable
fun QueueScreen(
        items: List<QueueItemUi>,
        accentColor: Color,
        onItemClick: (entryId: String) -> Unit,
        onDismiss: () -> Unit
) {
    SwipeToDismissBox(onDismissed = onDismiss) { isBackground ->
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (!isBackground) {
                QueueList(items, accentColor, onItemClick)
            }
        }
    }
}

@Composable
private fun QueueList(
        items: List<QueueItemUi>,
        accentColor: Color,
        onItemClick: (String) -> Unit
) {
    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 30.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(items, key = { it.entryId }) { item ->
            QueueRow(item, accentColor, onItemClick)
        }
    }
}

@Composable
private fun QueueRow(
        item: QueueItemUi,
        accentColor: Color,
        onItemClick: (String) -> Unit
) {
    val pillColor = if (item.isPlaying) accentColor else IDLE_PILL_COLOR
    val titleColor = if (item.isPlaying) Color.Black else Color.White
    val subtitleColor = titleColor.copy(alpha = 0.7f)

    Row(
            modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .background(pillColor)
                    .clickable { onItemClick(item.entryId) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                    text = item.title,
                    color = titleColor,
                    fontFamily = GoogleSans,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
            )
            if (!item.subtitle.isNullOrBlank()) {
                Text(
                        text = item.subtitle,
                        color = subtitleColor,
                        fontFamily = GoogleSans,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (item.isPlaying) {
            Spacer(Modifier.width(8.dp))
            NowPlayingBars(color = titleColor)
        }
    }
}

/** Tiny three-bar "now playing" glyph drawn without a drawable resource. */
@Composable
private fun NowPlayingBars(color: Color) {
    Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.height(14.dp)
    ) {
        Bar(color, 8.dp)
        Bar(color, 14.dp)
        Bar(color, 6.dp)
    }
}

@Composable
private fun Bar(color: Color, heightDp: androidx.compose.ui.unit.Dp) {
    Box(
            Modifier
                    .width(3.dp)
                    .height(heightDp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 220, heightDp = 220)
@Composable
private fun QueueScreenPreview() {
    MaterialTheme {
        QueueScreen(
                items = listOf(
                        QueueItemUi("1", "clean edge of emptiness", "Wounded Masquerade", false),
                        QueueItemUi("2", "Young", "Vacations", true),
                        QueueItemUi("3", "Time is Nothing", "Bloodbark", false),
                        QueueItemUi("4", "it's Snowing Like It's the End", "gokhanabe01", false)
                ),
                accentColor = Color(0xFFB7C46A),
                onItemClick = {},
                onDismiss = {}
        )
    }
}
