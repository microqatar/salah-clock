package com.microqatar.salahclock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Self-ticking analog clock face that scales to any size (phone, tablet, TV). */
class AnalogClockView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    var accentColor: Int = 0xFFE6B450.toInt()
        set(v) { field = v; invalidate() }
    var handColor: Int = Color.WHITE
        set(v) { field = v; invalidate() }
    var showSeconds: Boolean = true
        set(v) { field = v; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cal = Calendar.getInstance()
    private val tick = Runnable { invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width - paddingLeft - paddingRight
        val h = height - paddingTop - paddingBottom
        if (w <= 0 || h <= 0) return
        val cx = paddingLeft + w / 2f
        val cy = paddingTop + h / 2f
        val r = min(w, h) / 2f * 0.96f

        // face
        paint.style = Paint.Style.FILL
        paint.color = 0x18FFFFFF
        canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.03f
        paint.color = accentColor
        canvas.drawCircle(cx, cy, r, paint)

        // minute / hour ticks
        paint.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 60) {
            val major = i % 5 == 0
            val a = Math.toRadians(i * 6.0)
            val outer = r * 0.92f
            val inner = outer - if (major) r * 0.12f else r * 0.05f
            paint.strokeWidth = if (major) r * 0.025f else r * 0.01f
            paint.color = if (major) handColor else 0x99FFFFFF.toInt()
            canvas.drawLine(
                cx + (sin(a) * inner).toFloat(), cy - (cos(a) * inner).toFloat(),
                cx + (sin(a) * outer).toFloat(), cy - (cos(a) * outer).toFloat(), paint
            )
        }

        // numbers
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = r * 0.15f
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.color = handColor
        val textOffset = (paint.descent() + paint.ascent()) / 2f
        for (n in 1..12) {
            val a = Math.toRadians(n * 30.0)
            val tr = r * 0.66f
            canvas.drawText(n.toString(), cx + (sin(a) * tr).toFloat(), cy - (cos(a) * tr).toFloat() - textOffset, paint)
        }

        // hands
        val now = System.currentTimeMillis()
        cal.timeInMillis = now
        val hr = cal.get(Calendar.HOUR)
        val mn = cal.get(Calendar.MINUTE)
        val sc = cal.get(Calendar.SECOND)
        drawHand(canvas, cx, cy, (hr + mn / 60f + sc / 3600f) * 30f, r * 0.50f, r * 0.08f, r * 0.055f, handColor)
        drawHand(canvas, cx, cy, (mn + sc / 60f) * 6f, r * 0.76f, r * 0.10f, r * 0.035f, handColor)
        if (showSeconds) drawHand(canvas, cx, cy, sc * 6f, r * 0.84f, r * 0.18f, r * 0.012f, accentColor)

        paint.style = Paint.Style.FILL
        paint.color = accentColor
        canvas.drawCircle(cx, cy, r * 0.045f, paint)

        removeCallbacks(tick)
        if (isShown) postDelayed(tick, 1000L - now % 1000L + 5L)
    }

    private fun drawHand(c: Canvas, cx: Float, cy: Float, deg: Float, len: Float, tail: Float, width: Float, color: Int) {
        val a = Math.toRadians(deg.toDouble())
        val sx = sin(a).toFloat()
        val cy2 = cos(a).toFloat()
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = width
        paint.color = color
        c.drawLine(cx - sx * tail, cy + cy2 * tail, cx + sx * len, cy - cy2 * len, paint)
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) invalidate() else removeCallbacks(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }
}
