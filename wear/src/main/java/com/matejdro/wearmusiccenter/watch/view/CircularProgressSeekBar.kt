package com.matejdro.wearmusiccenter.watch.view

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import com.matejdro.wearmusiccenter.R
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import com.matejdro.common.R as commonR

/**
 * Thin ring drawn around the very edge of the screen showing playback progress.
 *
 * Only the bezel-hugging band (the drawn ring itself, with a bit of touch slop) responds to
 * touch - everything else passes through untouched - so this can sit on top of
 * [FourWayTouchLayout][com.matejdro.wearmusiccenter.common.view.FourWayTouchLayout] in the
 * view hierarchy without stealing its quadrant taps/gestures. [excludedTouchViews] additionally
 * carves out exact no-go zones (e.g. the quadrant action icons) that would otherwise overlap
 * with the touch band near the bezel.
 */
class CircularProgressSeekBar : View {
    companion object {
        // Slightly longer than MusicViewModel's tick interval so each animation is still
        // in flight (and gets smoothly redirected) when the next tick's value arrives.
        private const val PROGRESS_ANIMATION_DURATION_MS = 600L
    }

    private val foregroundPaint: Paint = Paint()
    private val backgroundPaint: Paint
    private val circleBounds = RectF()

    private var isDragging = false
    private var displayProgress = 0f
    private var progressAnimator: ValueAnimator? = null
    private val touchBand = resources.getDimension(R.dimen.seek_bar_touch_band)

    private val touchLocation = IntArray(2)
    private val excludedViewRect = Rect()

    var seekable: Boolean = false

    /** Views (e.g. the quadrant action icons) that should always win over this ring on overlap. */
    var excludedTouchViews: List<View> = emptyList()

    var onSeekFinished: ((Float) -> Unit)? = null

    /** Fired continuously while the ring is being dragged (including the initial touch-down). */
    var onSeekPreview: ((Float) -> Unit)? = null

    constructor(context: Context) : this(context, null)
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : this(context, attrs, defStyleAttr, 0)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) :
            super(context, attrs, defStyleAttr, defStyleRes)

    init {
        foregroundPaint.style = Paint.Style.STROKE
        foregroundPaint.strokeWidth = resources.getDimension(R.dimen.seek_bar_width)
        foregroundPaint.strokeCap = Paint.Cap.ROUND
        foregroundPaint.color = resources.getColor(commonR.color.accent, null)
        foregroundPaint.isAntiAlias = true

        backgroundPaint = Paint(foregroundPaint)
        backgroundPaint.strokeCap = Paint.Cap.BUTT
        backgroundPaint.color = resources.getColor(R.color.glass_surface_border, null)
    }

    /** Tints the progress arc, e.g. with a color extracted from the current album art. */
    var progressColor: Int
        get() = foregroundPaint.color
        set(value) {
            foregroundPaint.color = value
            invalidate()
        }

    /**
     * Progress fraction, 0f..1f. Ignored while the user is actively dragging the ring.
     *
     * Animates smoothly towards the new value instead of jumping, since this is normally fed a
     * new value only every [com.matejdro.wearmusiccenter.watch.view.MusicViewModel] tick rather
     * than every frame.
     */
    var progress: Float
        get() = displayProgress
        set(value) {
            if (isDragging) return
            animateProgressTo(value.coerceIn(0f, 1f))
        }

    private fun animateProgressTo(target: Float) {
        progressAnimator?.cancel()

        if (kotlin.math.abs(target - displayProgress) < 0.0005f) {
            displayProgress = target
            invalidate()
            return
        }

        progressAnimator = ValueAnimator.ofFloat(displayProgress, target).apply {
            duration = PROGRESS_ANIMATION_DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener {
                displayProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
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

        if (!seekable) {
            return
        }

        canvas.drawArc(circleBounds, 0f, 360f, false, backgroundPaint)
        canvas.drawArc(circleBounds, -90f, displayProgress * 360f, false, foregroundPaint)
    }

    private fun isInsideExcludedView(localX: Float, localY: Float): Boolean {
        if (excludedTouchViews.isEmpty()) {
            return false
        }

        getLocationOnScreen(touchLocation)
        val screenX = (touchLocation[0] + localX).toInt()
        val screenY = (touchLocation[1] + localY).toInt()

        return excludedTouchViews.any { target ->
            target.visibility == View.VISIBLE &&
                    target.getGlobalVisibleRect(excludedViewRect) &&
                    excludedViewRect.contains(screenX, screenY)
        }
    }

    private fun angleToProgress(dx: Float, dy: Float): Float {
        // Angle measured clockwise from straight up, matching the -90f start used in onDraw.
        val angleFromTop = (Math.toDegrees(atan2(dy, dx).toDouble()) + 90.0 + 360.0) % 360.0
        return max(0f, min(1f, (angleFromTop / 360.0).toFloat()))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Once a drag is already in progress, see it through even if seekable flips off for a
        // moment (e.g. a stale tick from the phone) - bailing out mid-gesture is what caused the
        // ring to freeze with no way to finish choosing a position.
        if (!seekable && !isDragging) {
            return false
        }

        val centerX = circleBounds.centerX()
        val centerY = circleBounds.centerY()
        val radius = circleBounds.width() / 2
        val dx = event.x - centerX
        val dy = event.y - centerY
        val distanceFromCenter = hypot(dx, dy)

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (kotlin.math.abs(distanceFromCenter - radius) > touchBand) {
                    return false
                }
                if (isInsideExcludedView(event.x, event.y)) {
                    return false
                }
                // Without this, an animation already in flight from the last MusicViewModel
                // tick keeps overwriting displayProgress on every frame, fighting the drag and
                // making the ring appear to snap back towards wherever it was animating to.
                progressAnimator?.cancel()
                isDragging = true

                // Tells WearableDrawerLayout (which interprets edge swipes/holds as "open the
                // drawer") to back off for the duration of this gesture - without it, a held
                // touch near the bezel could get stolen mid-drag, which is what made the ring
                // appear to freeze and never reach onSeekFinished.
                parent?.requestDisallowInterceptTouchEvent(true)

                displayProgress = angleToProgress(dx, dy)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isDragging) {
                    return false
                }

                // Deliberately NOT re-checking isInsideExcludedView here: the icons sit at the
                // same radius as this ring, so any drag that sweeps past one of the four cardinal
                // points would otherwise get killed mid-gesture - which looked like the ring
                // freezing and snapping back to the live playback position (the next position
                // tick would no longer be held back once isDragging flipped to false). The
                // exclusion only needs to stop a drag from *starting* on top of an icon.
                displayProgress = angleToProgress(dx, dy)
                invalidate()
                onSeekPreview?.invoke(displayProgress)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isDragging) {
                    return false
                }
                isDragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                // A quick tap (no ACTION_MOVE in between) still jumps straight to that position -
                // it just never showed the seek-time overlay, since there was nothing to preview.
                onSeekFinished?.invoke(displayProgress)
                return true
            }
            else -> return false
        }
    }
}
