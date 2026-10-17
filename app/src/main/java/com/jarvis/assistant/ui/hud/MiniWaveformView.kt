package com.jarvis.assistant.ui.hud

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

/**
 * Lightweight dynamic waveform line for HUD telemetry cards (System Status, Network).
 * Draws an electric cyan oscilloscope/signal wave.
 */
class MiniWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val wavePath = Path()
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00F2FE")
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * context.resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private var phase = 0f
    private var isWaveActive = true
    private var animator: ValueAnimator? = null

    init {
        startAnimator()
    }

    private fun startAnimator() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                phase = anim.animatedFraction * (Math.PI * 2).toFloat()
                invalidate()
            }
            start()
        }
    }

    fun setActive(active: Boolean) {
        isWaveActive = active
        if (active) {
            wavePaint.color = Color.parseColor("#00F2FE")
        } else {
            wavePaint.color = Color.parseColor("#37515F")
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        wavePath.reset()
        val midY = h / 2f
        val amplitude = if (isWaveActive) h * 0.38f else h * 0.08f

        // Draw an electrocardiogram / telemetry spike waveform
        val points = 24
        val stepX = w / (points - 1)

        wavePath.moveTo(0f, midY)
        for (i in 1 until points) {
            val x = i * stepX
            val normX = i.toFloat() / points
            // Spike in the middle, small undulating ripples on edges
            val envelope = sin(normX * Math.PI).toFloat()
            val yOffset = if (i in 8..15) {
                // telemetry pulse spike
                val spikePhase = (phase * 1.5f + i * 0.8f)
                sin(spikePhase).toFloat() * amplitude * envelope
            } else {
                sin(phase + i * 0.4f).toFloat() * (amplitude * 0.25f)
            }
            wavePath.lineTo(x, midY + yOffset)
        }

        canvas.drawPath(wavePath, wavePaint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animator?.isStarted != true) startAnimator()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }
}
