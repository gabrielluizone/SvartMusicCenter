package com.matejdro.wearmusiccenter.watch.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import com.matejdro.wearmusiccenter.R
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Volume indicator styled after the stock Wear OS media controls: a vertical arc hugging the
 * left edge of the screen (not the old screen-spanning ring, nor a bottom arc), filling from the
 * bottom upwards as volume increases. Supports both the rotary crown (via [incrementVolume],
 * driven from [MainActivity][com.matejdro.wearmusiccenter.watch.view.MainActivity]'s
 * `onGenericMotionEvent`) and direct touch-drag along the arc itself.
 */
class CircularVolumeBar : android.view.View {
    companion object {
        // Canvas.drawArc() convention: 0deg = 3 o'clock, increasing clockwise. 180deg = 9
        // o'clock (true left) - centering the arc there puts it on the left edge of the screen.
        private const val ARC_START_DEG = 130f
        private const val ARC_SWEEP_DEG = 100f
        private const val ARC_END_DEG = ARC_START_DEG + ARC_SWEEP_DEG
    }

    private val foregroundPaint: Paint = Paint()
    private val backgroundPaint: Paint
    private val circleBounds = RectF()
    private val touchBand = resources.getDimension(R.dimen.seek_bar_touch_band)

    private var isDragging = false

    var onVolumeChanged: ((Float) -> Unit)? = null

    constructor(context: Context?) : this(context, null)
    constructor(context: Context?, attrs: AttributeSet?) : this(context, attrs, 0)
    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int) : this(context, attrs, defStyleAttr, 0)
    constructor(context: Context?, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes)

    init {
        foregroundPaint.style = Paint.Style.STROKE
        // Matches the seek bar's stroke width for visual consistency between the two rings.
        foregroundPaint.strokeWidth = resources.getDimension(R.dimen.seek_bar_width)
        foregroundPaint.strokeCap = Paint.Cap.ROUND
        foregroundPaint.color = resources.getColor(R.color.music_screen_volume_bar_foreground_color, null)
        foregroundPaint.isAntiAlias = true

        backgroundPaint = Paint(foregroundPaint)
        backgroundPaint.color = resources.getColor(R.color.music_screen_volume_bar_background_color, null)
    }

    var volume = 0.5f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    /** Tints the filled arc, e.g. with the same color extracted from the current album art. */
    var progressColor: Int
        get() = foregroundPaint.color
        set(value) {
            foregroundPaint.color = value
            invalidate()
        }

    fun incrementVolume(change: Float) {
        volume = min(1f, max(0f, volume + change))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        val viewSize = min(measuredWidth, measuredHeight).toFloat()

        val circleStroke = foregroundPaint.strokeWidth / 2
        val circleSize = viewSize - foregroundPaint.strokeWidth

        val horizontalMargin = measuredWidth - viewSize
        val verticalMargin = measuredHeight - viewSize

        circleBounds.left = circleStroke + horizontalMargin / 2
        circleBounds.top = circleStroke + verticalMargin / 2
        circleBounds.right = circleBounds.left + circleSize
        circleBounds.bottom = circleBounds.top + circleSize
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.drawArc(circleBounds, ARC_START_DEG, ARC_SWEEP_DEG, false, backgroundPaint)

        if (volume > 0.001f) {
            // Fills from the bottom end of the arc upwards as volume increases.
            canvas.drawArc(circleBounds, ARC_START_DEG, volume * ARC_SWEEP_DEG, false, foregroundPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val centerX = circleBounds.centerX()
        val centerY = circleBounds.centerY()
        val radius = circleBounds.width() / 2
        val dx = event.x - centerX
        val dy = event.y - centerY
        val distanceFromCenter = hypot(dx, dy)

        // Same convention as Canvas.drawArc: 0deg = East, increasing clockwise (screen Y is
        // already "down", so atan2(dy, dx) lines up with it directly, no extra offset needed).
        val angleDeg = (Math.toDegrees(atan2(dy, dx).toDouble()) + 360.0) % 360.0
        val withinArc = angleDeg in ARC_START_DEG.toDouble()..ARC_END_DEG.toDouble()

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (!withinArc || kotlin.math.abs(distanceFromCenter - radius) > touchBand) {
                    return false
                }
                isDragging = true
                // The arc sits right where WearableDrawerLayout watches for an edge swipe/hold
                // to open the drawer - without this, a held drag here could get stolen mid-touch.
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) {
                    return false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDragging) {
                    return false
                }
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            else -> return false
        }

        val fraction = ((angleDeg - ARC_START_DEG) / ARC_SWEEP_DEG).toFloat().coerceIn(0f, 1f)
        volume = fraction
        onVolumeChanged?.invoke(fraction)

        return true
    }
}
