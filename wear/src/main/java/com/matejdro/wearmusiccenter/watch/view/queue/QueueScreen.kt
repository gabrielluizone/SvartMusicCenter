package com.matejdro.wearmusiccenter.watch.view.queue

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.SwipeToDismissBox
import androidx.wear.compose.material3.Text
import com.matejdro.wearmusiccenter.R
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** View model for one queue row. [isPlaying] marks the entry the phone reports as currently active. */
data class QueueItemUi(
        val entryId: String,
        val title: String,
        val subtitle: String?,
        val isPlaying: Boolean
)

// Idle rows are near-black for an OLED-dark look; the now-playing row uses the full album accent.
private val IDLE_PILL_COLOR = Color(0xFF202022)
private const val SUBTITLE_ALPHA = 0.65f

/** The app-wide Google Sans typeface, so the queue matches the rest of the watch UI. */
private val GoogleSans = FontFamily(
        Font(R.font.google_sans_regular, FontWeight.Normal),
        Font(R.font.google_sans_bold, FontWeight.Bold)
)

/**
 * Playback queue screen. A [ScalingLazyColumn] of glass pills (with a now-playing header on top)
 * where the active entry is highlighted with the full album [accentColor] and black text. Wrapped
 * in a [SwipeToDismissBox] so swiping right closes only this screen via [onDismiss].
 */
@Composable
fun QueueScreen(
        items: List<QueueItemUi>,
        accentColor: Color,
        nowPlayingTitle: String?,
        nowPlayingArtist: String?,
        onItemClick: (entryId: String) -> Unit,
        onDismiss: () -> Unit
) {
    SwipeToDismissBox(onDismissed = onDismiss) { isBackground ->
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (!isBackground) {
                QueueList(items, accentColor, nowPlayingTitle, nowPlayingArtist, onItemClick)
            }
        }
    }
}

@Composable
private fun QueueList(
        items: List<QueueItemUi>,
        accentColor: Color,
        nowPlayingTitle: String?,
        nowPlayingArtist: String?,
        onItemClick: (String) -> Unit
) {
    val listState = rememberScalingLazyListState()
    val time by produceState(initialValue = currentTime()) {
        while (true) {
            value = currentTime()
            delay(15_000L)
        }
    }

    Box(Modifier.fillMaxSize()) {
        ScalingLazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 26.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item { QueueHeader(time, nowPlayingTitle, nowPlayingArtist) }
            items(items, key = { it.entryId }) { item ->
                QueueRow(item, accentColor, onItemClick)
            }
        }

        // Minimal scroll position thumb on the right bezel (header + items = items.size + 1).
        val total = items.size + 1
        if (total > 3) {
            val fraction = (listState.centerItemIndex.toFloat() / (total - 1)).coerceIn(0f, 1f)
            Box(
                    Modifier
                            .align(BiasAlignment(horizontalBias = 1f, verticalBias = fraction * 2f - 1f))
                            .padding(end = 2.dp)
                            .width(4.dp)
                            .height(26.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.35f))
            )
        }
    }
}

@Composable
private fun QueueHeader(time: String, title: String?, artist: String?) {
    Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
                text = time,
                color = Color.White.copy(alpha = 0.7f),
                fontFamily = GoogleSans,
                fontSize = 14.sp
        )
        if (!title.isNullOrBlank()) {
            Text(
                    text = title,
                    color = Color.White,
                    fontFamily = GoogleSans,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE)
            )
        }
        if (!artist.isNullOrBlank()) {
            Text(
                    text = artist,
                    color = Color.White.copy(alpha = 0.6f),
                    fontFamily = GoogleSans,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
            )
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
    val subtitleColor = if (item.isPlaying) {
        Color.Black.copy(alpha = 0.7f)
    } else {
        Color.White.copy(alpha = SUBTITLE_ALPHA)
    }

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
                    // Long titles scroll instead of being clipped, like the stock player.
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
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

/** Animated three-bar "now playing" equalizer, drawn without a drawable resource. */
@Composable
private fun NowPlayingBars(color: Color) {
    val transition = rememberInfiniteTransition(label = "equalizer")
    val h1 by transition.animateFloat(
            initialValue = 0.30f, targetValue = 1.0f,
            animationSpec = infiniteRepeatable(tween(480), RepeatMode.Reverse), label = "bar1"
    )
    val h2 by transition.animateFloat(
            initialValue = 1.0f, targetValue = 0.40f,
            animationSpec = infiniteRepeatable(tween(360), RepeatMode.Reverse), label = "bar2"
    )
    val h3 by transition.animateFloat(
            initialValue = 0.55f, targetValue = 0.90f,
            animationSpec = infiniteRepeatable(tween(560), RepeatMode.Reverse), label = "bar3"
    )

    Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.height(16.dp)
    ) {
        Bar(color, h1)
        Bar(color, h2)
        Bar(color, h3)
    }
}

@Composable
private fun Bar(color: Color, heightFraction: Float) {
    Box(
            Modifier
                    .width(3.dp)
                    .fillMaxHeight(heightFraction)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
    )
}

private fun currentTime(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

@Preview(showBackground = true, backgroundColor = 0xFF000000, widthDp = 220, heightDp = 220)
@Composable
private fun QueueScreenPreview() {
    MaterialTheme {
        QueueScreen(
                items = listOf(
                        QueueItemUi("1", "City of Delusion", "Muse", false),
                        QueueItemUi("2", "Viva La Vida", "Coldplay", true),
                        QueueItemUi("3", "Karma Police", "Radiohead", false)
                ),
                accentColor = Color(0xFFD32F2F),
                nowPlayingTitle = "Viva La Vida",
                nowPlayingArtist = "Coldplay",
                onItemClick = {},
                onDismiss = {}
        )
    }
}
