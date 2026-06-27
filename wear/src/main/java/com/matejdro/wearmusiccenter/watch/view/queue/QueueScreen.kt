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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.SwipeToDismissBox
import androidx.wear.compose.material3.Text
import com.matejdro.wearmusiccenter.R

/** View model for one queue row. [isPlaying] marks the entry the phone reports as currently active. */
data class QueueItemUi(
        val entryId: String,
        val title: String,
        val subtitle: String?,
        val isPlaying: Boolean
)

// Tuned for an OLED-dark background: idle rows are near-black, and the now-playing accent is pulled
// toward black so a vivid album color (e.g. Coldplay red) reads as a deep tone instead of glaring.
private val IDLE_PILL_COLOR = Color(0xFF202022)
private const val ACCENT_DARKEN = 0.30f
private val SUBTITLE_ALPHA = 0.65f

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

    Box(Modifier.fillMaxSize()) {
        ScalingLazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(items, key = { it.entryId }) { item ->
                QueueRow(item, accentColor, onItemClick)
            }
        }

        // Scroll position bar on the right bezel.
        ScrollIndicator(
                state = listState,
                modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

@Composable
private fun QueueRow(
        item: QueueItemUi,
        accentColor: Color,
        onItemClick: (String) -> Unit
) {
    val pillColor = if (item.isPlaying) lerp(accentColor, Color.Black, ACCENT_DARKEN) else IDLE_PILL_COLOR
    val titleColor = Color.White
    val subtitleColor = Color.White.copy(alpha = SUBTITLE_ALPHA)

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
                onItemClick = {},
                onDismiss = {}
        )
    }
}
