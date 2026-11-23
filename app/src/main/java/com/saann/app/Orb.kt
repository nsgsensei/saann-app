package com.saann.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.sin

class OrbView(ctx: Context) : View(ctx) {
    var speed = 0.35f
    var accent = 0xFFE5484D.toInt()
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val box = RectF()
    private var t = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t += 0.016f * speed
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!anim.isStarted) anim.start()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - 3f
        val pulse = 0.5f + 0.5f * sin(t * 12.566f)
        ring.color = accent
        for (i in 0 until 3) {
            val rr = r * (0.55f + 0.22f * i)
            box.set(cx - rr, cy - rr, cx + rr, cy + rr)
            ring.strokeWidth = 2.2f - 0.4f * i
            ring.alpha = 230 - 55 * i
            val dir = if (i % 2 == 0) 1f else -1f
            val start = t * 360f * dir * (1f + 0.45f * i)
            c.drawArc(box, start, 110f + 30f * i, false, ring)
            c.drawArc(box, start + 180f, 70f, false, ring)
        }
        core.color = accent
        core.alpha = (120 + 120 * pulse).toInt()
        c.drawCircle(cx, cy, r * (0.2f + 0.07f * pulse), core)
    }
}
