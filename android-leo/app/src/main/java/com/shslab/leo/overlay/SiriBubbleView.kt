package com.shslab.leo.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import com.shslab.leo.voice.SherpaTtsManager
import kotlin.math.min
import kotlin.math.sin

/**
 * ══════════════════════════════════════════
 *  SIRI-STYLE GLOWING BUBBLE
 *
 *  Self-animating circular view that pulses to
 *  the live mic amplitude reported by SherpaTtsManager.
 *  Three-color SHS LAB palette: cyan / magenta / yellow.
 * ══════════════════════════════════════════
 */
class SiriBubbleView(ctx: Context) : View(ctx) {

    private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.parseColor("#00E5FF")
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFFF")
        style = Paint.Style.FILL
    }

    private var phase = 0f

    init {
        // Drive a continuous redraw via choreographer-style invalidation
        post(object : Runnable {
            override fun run() {
                phase += 0.10f
                invalidate()
                postDelayed(this, 33L) // ~30 fps, light on CPU
            }
        })
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val baseR = min(w, h) / 2f * 0.55f

        val amp = SherpaTtsManager.amplitude().coerceAtLeast(0.05f)
        val pulse = 1f + amp * 0.55f + sin(phase) * 0.05f
        val r = baseR * pulse

        // Outer halo
        gradientPaint.shader = RadialGradient(
            cx, cy, r * 1.7f,
            intArrayOf(
                Color.argb(220, 0, 229, 255),
                Color.argb(140, 233, 30, 140),
                Color.argb(0, 255, 214, 0)
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r * 1.7f, gradientPaint)

        // Mid ring
        ringPaint.color = Color.parseColor("#E91E8C")
        canvas.drawCircle(cx, cy, r * 1.15f, ringPaint)

        ringPaint.color = Color.parseColor("#00E5FF")
        canvas.drawCircle(cx, cy, r, ringPaint)

        // Bright core
        canvas.drawCircle(cx, cy, r * 0.45f, corePaint)
    }
}
