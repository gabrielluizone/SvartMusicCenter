package com.matejdro.wearmusiccenter.watch.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import androidx.appcompat.widget.AppCompatTextView

/**
 * TextView that can be displayed either as a filled text or as an outline.
 */
class OutlineTextView : AppCompatTextView {
    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr)

    /**
     * Outline width in pixels,
     * equivalent to 1dp by default
     */
    var outlineWidth = context.resources.displayMetrics.density * 1
        set(value) {
            field = value
            invalidate()
        }

    /**
     * When *false*, normal text will be displayed.
     * When *true*, only outline of the text will be displayed.
     */
    var displayTextOutline: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    private var smartSizingEnabled = false
    private var smartMaxSizePx = 0f
    private var smartMinSizePx = 0f

    // Captured once, the first time smart sizing runs - the XML-configured cap (e.g. maxLines=2)
    // that wrapping should respect before giving up and switching to a scrolling single line.
    private var wrapMaxLines = 1
    private var marqueeActive = false

    /**
     * Shrinks the text size (down to [minSizeSp]) only as much as needed so that no single word
     * ever has to be broken mid-word to fit the available width - unlike the framework's
     * `autoSizeText`, which only optimizes for the text block as a whole and will happily force a
     * line break in the middle of a word once its size floor is hit.
     *
     * If the text is so long that it still wouldn't fit within the configured `maxLines` even at
     * [minSizeSp], shrinking further would just make it unpleasantly tiny - instead this falls
     * back to a single line at [minSizeSp] that scrolls horizontally (marquee) end to end.
     */
    fun enableSmartWordSizing(maxSizeSp: Float, minSizeSp: Float) {
        smartSizingEnabled = true
        smartMaxSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, maxSizeSp, resources.displayMetrics)
        smartMinSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, minSizeSp, resources.displayMetrics)
        wrapMaxLines = maxLines
        requestSmartResize()
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        requestSmartResize()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        requestSmartResize()
    }

    private fun requestSmartResize() {
        if (!smartSizingEnabled || width == 0) {
            return
        }
        post { applySmartTextSize() }
    }

    private fun applySmartTextSize() {
        val availableWidth = (width - paddingLeft - paddingRight).toFloat()
        if (availableWidth <= 0) {
            return
        }

        val words = text.toString().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) {
            return
        }

        val measurePaint = Paint(paint)
        val stepPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, resources.displayMetrics)

        var sizePx = smartMaxSizePx
        var fits = false

        while (sizePx > smartMinSizePx) {
            measurePaint.textSize = sizePx

            if (fitsWithinLineLimit(words, measurePaint, availableWidth)) {
                fits = true
                break
            }

            sizePx -= stepPx
        }

        if (!fits) {
            measurePaint.textSize = smartMinSizePx
            fits = fitsWithinLineLimit(words, measurePaint, availableWidth)
            sizePx = smartMinSizePx
        }

        if (fits) {
            disableMarquee()
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
        } else {
            enableMarquee(smartMinSizePx)
        }
    }

    /**
     * Whether [words] fit at [paint]'s current text size without needing more than [wrapMaxLines]
     * lines or breaking a word mid-way. Deliberately NOT also checking the view's own height: for
     * a `wrap_content` height view, that height is itself a *consequence* of the current
     * text/size (computed on a previous layout pass, often before this resize even runs), not a
     * real independent constraint - using it as a ceiling caused titles that comfortably fit in
     * (say) 2 lines to be wrongly rejected and fall back to marquee instead.
     */
    private fun fitsWithinLineLimit(words: List<String>, paint: Paint, maxWidth: Float): Boolean {
        val widestWord = words.maxOf { paint.measureText(it) }
        if (widestWord > maxWidth) {
            return false
        }

        return estimateWrappedLineCount(words, paint, maxWidth) <= wrapMaxLines
    }

    private fun enableMarquee(sizePx: Float) {
        if (!marqueeActive) {
            marqueeActive = true
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
        }
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
    }

    private fun disableMarquee() {
        if (marqueeActive) {
            marqueeActive = false
            isSingleLine = false
            maxLines = wrapMaxLines
            ellipsize = TextUtils.TruncateAt.END
            isSelected = false
        }
    }

    // Marquee only animates while the view is considered focused; this view normally never
    // receives real focus (the four-way touch layout below claims it instead).
    override fun isFocused(): Boolean = (marqueeActive && !marqueePaused) || super.isFocused()

    private var marqueePaused = false

    /**
     * Freezes a scrolling title in place (e.g. for ambient mode, where a continuously animating
     * marquee would be both a burn-in risk and a pointless battery drain) without forgetting that
     * it's in marquee mode - [setMarqueePaused] (false) picks the animation back up afterwards.
     */
    fun setMarqueePaused(paused: Boolean) {
        if (marqueeActive) {
            marqueePaused = paused
            isSelected = !paused
        }
    }

    private fun estimateWrappedLineCount(words: List<String>, paint: Paint, maxWidth: Float): Int {
        var lines = 1
        var currentLineWidth = 0f
        val spaceWidth = paint.measureText(" ")

        for (word in words) {
            val wordWidth = paint.measureText(word)
            val neededWidth = if (currentLineWidth == 0f) wordWidth else currentLineWidth + spaceWidth + wordWidth

            if (neededWidth > maxWidth && currentLineWidth > 0f) {
                lines++
                currentLineWidth = wordWidth
            } else {
                currentLineWidth = neededWidth
            }
        }

        return lines
    }

    override fun onDraw(canvas: Canvas) {
        if (displayTextOutline) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = outlineWidth
        } else {
            paint.style = Paint.Style.FILL
        }

        super.onDraw(canvas)
    }
}
