package com.matejdro.wearmusiccenter.view.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

class HSVColorPickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var hue = 0f
    private var saturation = 1f
    private var value = 1f

    var onColorChanged: ((Int) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = Color.WHITE
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        color = Color.parseColor("#44000000")
    }

    private val svRect = RectF()
    private val hueRect = RectF()

    private val hueBarH = dp(24f)
    private val hueBarGap = dp(16f)
    private val cornerR = dp(8f)

    private var svBitmap: Bitmap? = null
    private var svCanvas: Canvas? = null
    private var lastHue = -1f

    private var activeArea = 0 // 1 = SV, 2 = hue

    fun setColor(color: Int) {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hue = hsv[0]
        saturation = hsv[1]
        value = hsv[2]
        lastHue = -1f
        invalidate()
    }

    fun getColor(): Int = Color.HSVToColor(floatArrayOf(hue, saturation, value))

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val svH = h - hueBarH - hueBarGap
        svRect.set(0f, 0f, w.toFloat(), svH)
        hueRect.set(0f, svH + hueBarGap, w.toFloat(), h.toFloat())
        svBitmap = Bitmap.createBitmap(w, svH.toInt(), Bitmap.Config.ARGB_8888)
        svCanvas = Canvas(svBitmap!!)
        lastHue = -1f
    }

    override fun onDraw(canvas: Canvas) {
        drawSVPanel(canvas)
        drawHueBar(canvas)
        drawSVSelector(canvas)
        drawHueSelector(canvas)
    }

    private fun drawSVPanel(canvas: Canvas) {
        val bm = svBitmap ?: return
        val cv = svCanvas ?: return
        if (lastHue != hue) {
            lastHue = hue
            val hueColor = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
            val satShader = LinearGradient(0f, 0f, svRect.width(), 0f,
                Color.WHITE, hueColor, Shader.TileMode.CLAMP)
            val valShader = LinearGradient(0f, 0f, 0f, svRect.height(),
                Color.WHITE, Color.BLACK, Shader.TileMode.CLAMP)
            paint.shader = ComposeShader(valShader, satShader, PorterDuff.Mode.MULTIPLY)
            cv.drawRect(0f, 0f, svRect.width(), svRect.height(), paint)
            paint.shader = null
        }
        paint.shader = null
        canvas.drawBitmap(bm, 0f, 0f, paint)
        // Rounded corners clip is handled by background or outline; skip for performance
    }

    private fun drawHueBar(canvas: Canvas) {
        val colors = intArrayOf(
            0xFFFF0000.toInt(), 0xFFFFFF00.toInt(), 0xFF00FF00.toInt(),
            0xFF00FFFF.toInt(), 0xFF0000FF.toInt(), 0xFFFF00FF.toInt(), 0xFFFF0000.toInt()
        )
        paint.shader = LinearGradient(hueRect.left, 0f, hueRect.right, 0f,
            colors, null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(hueRect, cornerR, cornerR, paint)
        paint.shader = null
    }

    private fun drawSVSelector(canvas: Canvas) {
        val x = svRect.left + saturation * svRect.width()
        val y = svRect.top + (1f - value) * svRect.height()
        val r = dp(10f)
        shadowPaint.strokeWidth = dp(2f)
        canvas.drawCircle(x, y, r + dp(1f), shadowPaint)
        selectorPaint.strokeWidth = dp(3f)
        canvas.drawCircle(x, y, r, selectorPaint)
    }

    private fun drawHueSelector(canvas: Canvas) {
        val x = hueRect.left + (hue / 360f) * hueRect.width()
        val cy = hueRect.centerY()
        val r = hueRect.height() / 2f + dp(2f)
        shadowPaint.strokeWidth = dp(1.5f)
        canvas.drawCircle(x, cy, r + dp(1f), shadowPaint)
        selectorPaint.strokeWidth = dp(3f)
        canvas.drawCircle(x, cy, r, selectorPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        if (event.action == MotionEvent.ACTION_DOWN) {
            activeArea = when {
                svRect.contains(x, y) -> 1
                hueRect.contains(x, y) -> 2
                else -> 0
            }
        }
        when (activeArea) {
            1 -> {
                saturation = ((x - svRect.left) / svRect.width()).coerceIn(0f, 1f)
                value = 1f - ((y - svRect.top) / svRect.height()).coerceIn(0f, 1f)
                invalidate()
                onColorChanged?.invoke(getColor())
                return true
            }
            2 -> {
                hue = ((x - hueRect.left) / hueRect.width()).coerceIn(0f, 1f) * 360f
                lastHue = -1f
                invalidate()
                onColorChanged?.invoke(getColor())
                return true
            }
        }
        return false
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
