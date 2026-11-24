package com.saann.app

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

object SetupFinish {
    private val bg = Color.rgb(16, 19, 29)
    private val ink = Color.rgb(243, 240, 235)
    private val dim = Color.rgb(169, 178, 199)
    private val accent = Color.rgb(185, 170, 255)

    fun show(a: Activity) {
        if (a.isFinishing || a.isDestroyed) return

        val density = a.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val dialog = Dialog(
            a,
            android.R.style.Theme_Black_NoTitleBar_Fullscreen
        )
        val handler = Handler(Looper.getMainLooper())

        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(24), dp(28), dp(24))
            setBackgroundColor(bg)
        }

        val mark = CompletionMark(a)
        root.addView(
            mark,
            LinearLayout.LayoutParams(dp(148), dp(148))
        )

        val content = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            alpha = 0f
            translationY = dp(12).toFloat()
        }

        fun label(value: String, size: Float, color: Int): TextView {
            return TextView(a).apply {
                text = value
                textSize = size
                setTextColor(color)
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, dp(8))
            }
        }

        content.addView(
            label("SAANN is ready", 27f, ink).apply {
                setTypeface(null, Typeface.BOLD)
            }
        )

        content.addView(
            label(
                "Your connection is set up.\nLet's get started.",
                15f,
                dim
            )
        )

        val start = Button(a).apply {
            text = "Start using SAANN"
            isAllCaps = false
            textSize = 15f
            setTextColor(ink)
            background = GradientDrawable().apply {
                setColor(accent)
                cornerRadius = dp(16).toFloat()
            }
        }

        val buttonParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(52)
        ).apply {
            topMargin = dp(24)
        }
        content.addView(start, buttonParams)

        content.addView(
            label("Opening chat shortly…", 12f, dim)
        )

        root.addView(
            content,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        dialog.setContentView(root)
        dialog.setCancelable(true)

        val reveal = Runnable {
            if (dialog.isShowing && !a.isFinishing && !a.isDestroyed) {
                content.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(420L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }

        val close = Runnable {
            if (dialog.isShowing) dialog.dismiss()
        }

        start.setOnClickListener { dialog.dismiss() }

        dialog.setOnDismissListener {
            handler.removeCallbacks(reveal)
            handler.removeCallbacks(close)
            content.animate().cancel()
            mark.stop()
        }

        dialog.show()
        dialog.window?.apply {
            setLayout(-1, -1)
            statusBarColor = bg
            navigationBarColor = bg
        }

        mark.start()
        handler.postDelayed(reveal, 950L)
        handler.postDelayed(close, 4800L)
    }

    private class CompletionMark(a: Activity) : View(a) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = RectF()
        private val check = Path()
        private var progress = 0f
        private var animator: ValueAnimator? = null

        fun start() {
            stop()
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1400L
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun stop() {
            animator?.cancel()
            animator = null
        }

        override fun onDetachedFromWindow() {
            stop()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val cx = width / 2f
            val cy = height / 2f
            val radius = minOf(width, height) * 0.32f

            paint.style = Paint.Style.FILL
            paint.color = Color.argb(
                (12 + 16 * progress).toInt(),
                185, 170, 255
            )
            canvas.drawCircle(
                cx, cy,
                radius * (1.12f + 0.10f * progress),
                paint
            )

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f * density
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND

            ring.set(
                cx - radius, cy - radius,
                cx + radius, cy + radius
            )

            paint.color = Color.rgb(39, 46, 65)
            canvas.drawCircle(cx, cy, radius, paint)

            paint.color = accent
            val sweep = (progress / 0.72f).coerceIn(0f, 1f)
            canvas.drawArc(ring, -90f, 360f * sweep, false, paint)

            val t = ((progress - 0.55f) / 0.45f).coerceIn(0f, 1f)
            if (t <= 0f) return

            val x1 = cx - radius * 0.42f
            val y1 = cy
            val x2 = cx - radius * 0.08f
            val y2 = cy + radius * 0.30f
            val x3 = cx + radius * 0.46f
            val y3 = cy - radius * 0.32f

            check.reset()
            check.moveTo(x1, y1)

            if (t < 0.4f) {
                val part = t / 0.4f
                check.lineTo(
                    x1 + (x2 - x1) * part,
                    y1 + (y2 - y1) * part
                )
            } else {
                check.lineTo(x2, y2)
                val part = (t - 0.4f) / 0.6f
                check.lineTo(
                    x2 + (x3 - x2) * part,
                    y2 + (y3 - y2) * part
                )
            }

            paint.color = ink
            paint.strokeWidth = 3f * density
            canvas.drawPath(check, paint)
        }
    }
}
