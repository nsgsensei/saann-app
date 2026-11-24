package com.saann.app

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class SaannBootView(private val activity: Activity) : FrameLayout(activity) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    private val ink = 0xFFF3F0EB.toInt()
    private val dim = 0xFFB0B9CB.toInt()
    private val lavender = 0xFFB9AAFF.toInt()
    private val mint = 0xFF9DD6C4.toInt()

    private val mark = IconView(activity, "saann", lavender)
    private val note = TextView(activity)
    private val progress = View(activity)
    private val close = TextView(activity)
    private var orbit: ValueAnimator? = null
    private var pulse: ValueAnimator? = null
    private var finishing = false
    private var failed = false

    init {
        isClickable = true
        isFocusable = true
        elevation = dp(16).toFloat()
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                0xFF1D2036.toInt(),
                0xFF10131D.toInt(),
                0xFF142E30.toInt()
            )
        )

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(24), dp(28), dp(24))
        }

        val emblem = FrameLayout(activity)
        val halo = View(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(90).toFloat()
                colors = intArrayOf(
                    0x33B9AAFF,
                    0x1472CDB6,
                    0x0072CDB6
                )
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        emblem.addView(
            halo,
            LayoutParams(dp(180), dp(180), Gravity.CENTER)
        )
        mark.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        emblem.addView(
            mark,
            LayoutParams(dp(90), dp(90), Gravity.CENTER)
        )
        content.addView(
            emblem,
            LinearLayout.LayoutParams(dp(180), dp(180))
        )

        content.addView(TextView(activity).apply {
            text = "SAANN"
            textSize = 29f
            letterSpacing = 0.16f
            gravity = Gravity.CENTER
            setTextColor(ink)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(10))
        })

        note.apply {
            text = "Preparing your space…"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(dim)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(
            note,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        progress.background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(lavender, mint, 0xFFE8BC9F.toInt())
        ).apply {
            cornerRadius = dp(2).toFloat()
        }
        content.addView(
            progress,
            LinearLayout.LayoutParams(dp(68), dp(3)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(24)
            }
        )

        close.apply {
            text = "View startup error"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(lavender)
            minimumHeight = dp(48)
            visibility = View.GONE
            setOnClickListener { dismissNow() }
        }
        content.addView(
            close,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52)
            ).apply {
                topMargin = dp(16)
            }
        )

        addView(
            content,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
    }

    // SAANN_BOOT_CREDIT_V1
    private val creatorCredit = TextView(activity).apply {
        text = "By Ascension"
        textSize = 12f
        letterSpacing = 0.08f
        gravity = Gravity.CENTER
        setTextColor(0xFFB0B9CB.toInt())
        alpha = 0.72f
        setPadding(dp(16), dp(8), dp(16), dp(20))
    }

    init {
        addView(
            creatorCredit,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            )
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (finishing || failed) return

        alpha = 0f
        animate()
            .alpha(1f)
            .setDuration(180L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        orbit = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 16000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                mark.rotation = it.animatedValue as Float
            }
            start()
        }
        pulse = ValueAnimator.ofFloat(0.35f, 0.95f).apply {
            duration = 1100L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener {
                progress.alpha = it.animatedValue as Float
            }
            start()
        }
    }

    fun finish(onFinished: () -> Unit) {
        if (finishing) return
        finishing = true
        dispose()
        note.text = "Ready"
        progress.alpha = 1f
        animate()
            .alpha(0f)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                (parent as? ViewGroup)?.removeView(this)
                onFinished()
            }
            .start()
    }

    fun fail() {
        if (finishing) return
        failed = true
        dispose()
        alpha = 1f
        note.text = "Startup couldn't finish.\nOpen the error details below."
        note.setTextColor(0xFFEF8791.toInt())
        progress.visibility = View.GONE
        close.visibility = View.VISIBLE
    }

    private fun dismissNow() {
        dispose()
        (parent as? ViewGroup)?.removeView(this)
    }

    fun dispose() {
        animate().setListener(null)
        animate().cancel()
        orbit?.cancel()
        orbit = null
        pulse?.cancel()
        pulse = null
    }

    override fun onDetachedFromWindow() {
        dispose()
        super.onDetachedFromWindow()
    }
}
