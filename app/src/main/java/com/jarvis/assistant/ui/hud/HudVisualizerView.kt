package com.jarvis.assistant.ui.hud

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.jarvis.assistant.data.model.ConversationState
import kotlin.math.cos
import kotlin.math.sin

/**
 * High-performance HUD Visualizer View that surrounds the existing JARVIS Orb.
 * Renders futuristic concentric rings, segmented arc tracks, angular ticks,
 * and state-reactive scanning lines.
 */
class HudVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var rotationAngle = 0f
    private var reverseRotationAngle = 0f
    private var scanAngle = 0f
    private var glowPulse = 0f
    private var audioLevel = 0f
    private var currentState: ConversationState = ConversationState.IDLE

    // Pre-allocated paints to guarantee zero allocations in onDraw
    private val cyanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00F2FE")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val cyanThinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#154252")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }

    private val cyanGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00F2FE")
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val amberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFA000")
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFA000")
        textSize = 18f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.25f
        textAlign = Paint.Align.CENTER
    }

    private val textCyanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00F2FE")
        textSize = 18f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        letterSpacing = 0.25f
        textAlign = Paint.Align.CENTER
    }

    private val arcBoundsOuter = RectF()
    private val arcBoundsSegments = RectF()
    private val arcBoundsInner = RectF()
    private val arcBoundsMiddle = RectF()

    private var animator: ValueAnimator? = null

    init {
        startAnimator()
    }

    private fun startAnimator() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 16000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                val fraction = anim.animatedFraction
                val speedMultiplier = when (currentState) {
                    ConversationState.THINKING, ConversationState.EXECUTING -> 3.0f
                    ConversationState.WAKE_UP -> 2.5f
                    ConversationState.LISTENING -> 1.8f
                    ConversationState.SPEAKING -> 1.5f
                    ConversationState.IDLE -> 1.0f
                    ConversationState.STANDBY -> 0.2f
                    ConversationState.OFF, ConversationState.ERROR -> 0.0f
                }
                rotationAngle = (rotationAngle + 0.35f * speedMultiplier) % 360f
                reverseRotationAngle = (reverseRotationAngle - 0.25f * speedMultiplier) % 360f
                scanAngle = (scanAngle + 2.5f * speedMultiplier) % 360f
                glowPulse = ((sin(fraction * Math.PI * 4) + 1.0) / 2.0).toFloat()
                invalidate()
            }
            start()
        }
    }

    fun setConversationState(state: ConversationState) {
        if (currentState != state) {
            currentState = state
            invalidate()
        }
    }

    fun setAudioLevel(level: Float) {
        audioLevel = level.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f
        val cy = h / 2f
        val minDim = minOf(w, h) / 2f

        val outerRadius = minDim * 0.95f
        arcBoundsOuter.set(cx - outerRadius, cy - outerRadius, cx + outerRadius, cy + outerRadius)

        val segRadius = minDim * 0.86f
        arcBoundsSegments.set(cx - segRadius, cy - segRadius, cx + segRadius, cy + segRadius)

        val midRadius = minDim * 0.76f
        arcBoundsMiddle.set(cx - midRadius, cy - midRadius, cx + midRadius, cy + midRadius)

        val innerRadius = minDim * 0.62f
        arcBoundsInner.set(cx - innerRadius, cy - innerRadius, cx + innerRadius, cy + innerRadius)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        if (cx <= 0 || cy <= 0) return

        val minDim = minOf(width, height) / 2f
        val outerRadius = minDim * 0.95f
        val segRadius = minDim * 0.86f
        val midRadius = minDim * 0.76f

        // Opacity and accent color depending on state
        val alphaMultiplier = when (currentState) {
            ConversationState.LISTENING -> 1.0f
            ConversationState.THINKING, ConversationState.EXECUTING -> 1.0f
            ConversationState.SPEAKING -> (0.85f + audioLevel * 0.15f)
            ConversationState.WAKE_UP -> 1.0f
            ConversationState.IDLE -> 0.75f
            ConversationState.STANDBY -> 0.4f
            ConversationState.OFF, ConversationState.ERROR -> 0.2f
        }

        cyanPaint.alpha = (220 * alphaMultiplier).toInt()
        cyanThinPaint.alpha = (140 * alphaMultiplier).toInt()
        amberPaint.alpha = (230 * alphaMultiplier).toInt()
        textPaint.alpha = (210 * alphaMultiplier).toInt()
        textCyanPaint.alpha = (230 * alphaMultiplier).toInt()

        // 1. Draw outer thin concentric circle
        canvas.drawCircle(cx, cy, outerRadius, cyanThinPaint)

        // 2. Draw rotating outer tick marks (compass / HUD notches)
        canvas.save()
        canvas.rotate(rotationAngle, cx, cy)
        for (i in 0 until 72) {
            val deg = i * 5f
            val rad = Math.toRadians(deg.toDouble())
            val isMajor = i % 6 == 0
            val tickLen = if (isMajor) 14f else 6f
            val startR = outerRadius - tickLen
            val endR = outerRadius

            val x1 = (cx + startR * cos(rad)).toFloat()
            val y1 = (cy + startR * sin(rad)).toFloat()
            val x2 = (cx + endR * cos(rad)).toFloat()
            val y2 = (cy + endR * sin(rad)).toFloat()

            if (isMajor) {
                cyanPaint.strokeWidth = 2.5f
                canvas.drawLine(x1, y1, x2, y2, cyanPaint)
            } else {
                cyanThinPaint.strokeWidth = 1.2f
                canvas.drawLine(x1, y1, x2, y2, cyanThinPaint)
            }
        }
        canvas.restore()

        // 3. Draw outer cyan bracket arcs
        canvas.save()
        canvas.rotate(rotationAngle * 0.6f, cx, cy)
        cyanPaint.strokeWidth = 3f
        canvas.drawArc(arcBoundsOuter, 15f, 50f, false, cyanPaint)
        canvas.drawArc(arcBoundsOuter, 105f, 50f, false, cyanPaint)
        canvas.drawArc(arcBoundsOuter, 195f, 50f, false, cyanPaint)
        canvas.drawArc(arcBoundsOuter, 285f, 50f, false, cyanPaint)
        canvas.restore()

        // 4. Draw segmented amber energy level arc bars (matching reference design)
        canvas.save()
        canvas.rotate(reverseRotationAngle, cx, cy)
        amberPaint.strokeWidth = 6f
        // Amber energy segments on the lower right & lower left
        canvas.drawArc(arcBoundsSegments, 40f, 15f, false, amberPaint)
        canvas.drawArc(arcBoundsSegments, 60f, 18f, false, amberPaint)
        canvas.drawArc(arcBoundsSegments, 82f, 22f, false, amberPaint)

        canvas.drawArc(arcBoundsSegments, 110f, 16f, false, amberPaint)
        canvas.drawArc(arcBoundsSegments, 130f, 18f, false, amberPaint)

        // Segmented cyan arcs along the upper perimeter
        cyanPaint.strokeWidth = 4f
        canvas.drawArc(arcBoundsSegments, 200f, 35f, false, cyanPaint)
        canvas.drawArc(arcBoundsSegments, 245f, 40f, false, cyanPaint)
        canvas.drawArc(arcBoundsSegments, 295f, 55f, false, cyanPaint)
        canvas.restore()

        // 5. Draw middle dashed / notch ring
        canvas.save()
        canvas.rotate(rotationAngle * 1.2f, cx, cy)
        cyanThinPaint.strokeWidth = 2f
        for (i in 0 until 36) {
            val angle = i * 10f
            canvas.drawArc(arcBoundsMiddle, angle, 5f, false, cyanThinPaint)
        }
        canvas.restore()

        // 6. Thinking mode scanning ray / radar sweep
        if (currentState == ConversationState.THINKING) {
            canvas.save()
            canvas.rotate(scanAngle, cx, cy)
            cyanGlowPaint.alpha = 180
            cyanGlowPaint.strokeWidth = 2.5f
            canvas.drawLine(cx, cy, cx + midRadius, cy, cyanGlowPaint)
            canvas.restore()
        }

        // 7. Speaking audio-reactive pulse ring
        if (currentState == ConversationState.SPEAKING && audioLevel > 0.05f) {
            cyanGlowPaint.alpha = (audioLevel * 200).toInt().coerceIn(30, 220)
            cyanGlowPaint.strokeWidth = 3f + audioLevel * 4f
            val pulseRadius = midRadius + audioLevel * 10f
            canvas.drawCircle(cx, cy, pulseRadius, cyanGlowPaint)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animator?.isStarted != true) {
            startAnimator()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            if (animator?.isStarted != true) {
                startAnimator()
            }
        } else {
            animator?.cancel()
        }
    }
}
