package dev.pulsestage.wear

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/** A decorative dial, not an ECG or a heart-rate zone indicator. Redraws only on updates. */
class PulseDialView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private var bpm: Int? = null
    private var live = false
    private var label = "HEART RATE"
    private val mint = Color.rgb(184, 243, 177)

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }

    fun update(value: Int?, streaming: Boolean, state: String) {
        if (bpm == value && live == streaming && label == state) return
        bpm = value
        live = streaming
        label = state
        contentDescription = "${value?.let { "$it BPM" } ?: "Belum ada pembacaan BPM"}. $state"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val unit = height / 110f
        val center = width / 2f
        bounds.set(center - 53 * unit, 3 * unit, center + 53 * unit, 109 * unit)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2 * unit
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.rgb(40, 58, 44)
        canvas.drawArc(bounds, 127f, 106f, false, paint)
        canvas.drawArc(bounds, -53f, 106f, false, paint)
        paint.color = if (live) mint else Color.rgb(85, 112, 89)
        canvas.drawArc(bounds, 151f, 57f, false, paint)
        canvas.drawArc(bounds, -29f, 57f, false, paint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textSize = 9 * unit
        paint.color = if (live) mint else Color.rgb(155, 176, 159)
        canvas.drawText(label, center, 21 * unit, paint)
        paint.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        paint.textSize = 49 * unit
        paint.color = Color.rgb(239, 247, 240)
        canvas.drawText(bpm?.toString() ?: "--", center, 72 * unit, paint)
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textSize = 10 * unit
        paint.color = Color.rgb(170, 189, 175)
        canvas.drawText("BPM", center, 93 * unit, paint)
    }
}
