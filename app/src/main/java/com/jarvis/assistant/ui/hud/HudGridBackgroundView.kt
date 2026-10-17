package com.jarvis.assistant.ui.hud

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * Background view that renders a subtle, dark blueprint HUD grid with
 * technical crosshairs and a central radial atmospheric cyan glow.
 */
class HudGridBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val gridSpacingDp = 34f
    private var gridSpacingPx = 0f

    private val gridPaint = Paint().apply {
        color = Color.parseColor("#09222E")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val crossPaint = Paint().apply {
        color = Color.parseColor("#123D4F")
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
    }

    private val glowPaint = Paint().apply {
        isDither = true
    }

    init {
        gridSpacingPx = gridSpacingDp * context.resources.displayMetrics.density
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            val cx = w / 2f
            val cy = h * 0.38f // centered slightly higher around the Orb position
            val radius = w * 0.75f
            glowPaint.shader = RadialGradient(
                cx, cy, radius,
                intArrayOf(
                    Color.parseColor("#1800E5FF"), // Faint electric cyan core
                    Color.parseColor("#0C004D5A"), // Deep teal atmosphere
                    Color.parseColor("#00020B10")  // Transparent fade into space
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 1. Draw atmospheric radial glow
        canvas.drawRect(0f, 0f, w, h, glowPaint)

        // 2. Draw subtle vertical & horizontal grid lines
        val spacing = gridSpacingPx
        if (spacing <= 0) return

        var x = (w % spacing) / 2f
        while (x < w) {
            canvas.drawLine(x, 0f, x, h, gridPaint)
            x += spacing
        }

        var y = 0f
        while (y < h) {
            canvas.drawLine(0f, y, w, y, gridPaint)
            y += spacing
        }

        // 3. Draw tiny technical crosshairs (+) at selected intersections
        val crossSize = 3f * resources.displayMetrics.density
        x = (w % spacing) / 2f + spacing * 2
        while (x < w - spacing) {
            y = spacing * 2
            while (y < h - spacing) {
                canvas.drawLine(x - crossSize, y, x + crossSize, y, crossPaint)
                canvas.drawLine(x, y - crossSize, x, y + crossSize, crossPaint)
                y += spacing * 4
            }
            x += spacing * 4
        }
    }
}
