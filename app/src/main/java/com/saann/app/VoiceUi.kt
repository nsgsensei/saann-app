package com.saann.app

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

// The glowing orb: rings follow your voice while listening, spin while thinking, pulse while speaking.
class VoiceOrb(ctx: Context) : View(ctx) {
    @Volatile var mode = 0
    @Volatile var level = 0f
    var color = 0xFF00E5FF.toInt()
    private var shown = 0f
    private var t = 0f
    private val den = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t += 0.016f
            val target = when (mode) {
                2 -> 0.30f + 0.45f * abs(sin(t * 5.3f)) * (0.6f + 0.4f * abs(sin(t * 1.7f)))
                1 -> 0.14f + 0.08f * sin(t * 3f)
                else -> 0.08f + level * 0.9f
            }
            shown += (target - shown) * 0.18f
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        anim.start()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val base = min(width, height) * 0.17f
        val red = Color.red(color)
        val grn = Color.green(color)
        val blu = Color.blue(color)
        val glow = base * (2.6f + shown * 2f)
        fill.shader = RadialGradient(cx, cy, glow, Color.argb(90, red, grn, blu), Color.argb(0, red, grn, blu), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, glow, fill)
        for (i in 0..2) {
            ring.strokeWidth = (3f - i) * den
            ring.color = Color.argb(150 - i * 40, red, grn, blu)
            c.drawCircle(cx, cy, base * (1.35f + 0.4f * i) + shown * base * (0.8f + 0.5f * i), ring)
        }
        if (mode == 1) {
            ring.strokeWidth = 3f * den
            ring.color = Color.argb(230, red, grn, blu)
            box.set(cx - base * 1.9f, cy - base * 1.9f, cx + base * 1.9f, cy + base * 1.9f)
            for (k in 0..2) c.drawArc(box, t * 260f + k * 120f, 70f, false, ring)
        }
        val core = base * (1f + 0.22f * shown)
        fill.shader = RadialGradient(cx - core * 0.3f, cy - core * 0.3f, core * 1.3f, Color.WHITE, color, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, core, fill)
        fill.shader = null
    }
}

// The full-screen voice window: listen, think, speak, listen again.
object VoiceWindow {
    private const val CYAN = 0xFF00E5FF.toInt()
    private const val GREEN = 0xFF00E5A0.toInt()
    private const val AMBER = 0xFFFFB020.toInt()
    private const val INK = 0xFFE8FBFF.toInt()
    private const val DIM = 0xFF6FD6E6.toInt()
    private val ui = Handler(Looper.getMainLooper())
    private var dlg: Dialog? = null
    private var rec: SpeechRecognizer? = null
    private var orb: VoiceOrb? = null
    private var label: TextView? = null
    private var sub: TextView? = null
    private var act: Activity? = null
    private var send: ((String) -> Boolean)? = null
    private var live = false
    private var waiting = false
    val isOpen: Boolean get() = live

    fun show(a: Activity, onText: (String) -> Boolean) {
        if (live) return
        act = a
        send = onText
        live = true
        waiting = false
        val den = a.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * den).toInt()
        val d = Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LinearLayout(a)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(0xFF03060A.toInt())
        root.fitsSystemWindows = true
        val top = TextView(a)
        top.text = "SAANN · VOICE"
        top.setTextColor(DIM)
        top.textSize = 12f
        top.letterSpacing = 0.3f
        top.gravity = Gravity.CENTER
        top.setPadding(0, dp(16), 0, 0)
        root.addView(top, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val o = VoiceOrb(a)
        orb = o
        o.setOnClickListener { tap() }
        root.addView(o, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val l = TextView(a)
        l.setTextColor(INK)
        l.textSize = 22f
        l.letterSpacing = 0.08f
        l.gravity = Gravity.CENTER
        l.setTypeface(l.typeface, Typeface.BOLD)
        label = l
        root.addView(l, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val s = TextView(a)
        s.setTextColor(DIM)
        s.textSize = 14f
        s.gravity = Gravity.CENTER
        s.maxLines = 4
        s.setPadding(dp(32), dp(10), dp(32), dp(10))
        sub = s
        root.addView(s, LinearLayout.LayoutParams(MATCH_PARENT, dp(110)))
        val end = TextView(a)
        end.text = "End voice"
        end.setTextColor(INK)
        end.textSize = 15f
        end.gravity = Gravity.CENTER
        val eg = GradientDrawable()
        eg.cornerRadius = dp(26).toFloat()
        eg.setColor(0xFF0B1620.toInt())
        eg.setStroke(dp(1), 0x6600E5FF)
        end.background = eg
        end.setOnClickListener { d.dismiss() }
        val elp = LinearLayout.LayoutParams(dp(180), dp(52))
        elp.gravity = Gravity.CENTER_HORIZONTAL
        elp.bottomMargin = dp(28)
        root.addView(end, elp)
        d.setContentView(root)
        d.setOnDismissListener { close() }
        dlg = d
        d.show()
        listen()
    }

    private fun close() {
        if (!live) return
        live = false
        waiting = false
        try { rec?.destroy() } catch (e: Exception) { }
        rec = null
        Voice.stopSpeak()
        ui.removeCallbacksAndMessages(null)
        dlg = null
        orb = null
    }

    private fun setMode(m: Int, text: String) {
        orb?.mode = m
        orb?.color = when (m) { 1 -> AMBER; 2 -> GREEN; else -> CYAN }
        label?.text = text
    }

    private fun tap() {
        val m = orb?.mode ?: return
        if (m == 2) {
            Voice.stopSpeak()
            waiting = false
            listen()
        } else if (m == 0) {
            listen()
        }
    }

    private fun listen() {
        val a = act ?: return
        if (!live) return
        setMode(0, "Listening")
        sub?.text = ""
        if (!SpeechRecognizer.isRecognitionAvailable(a)) {
            sub?.text = "Speech recognition isn't available on this phone."
            return
        }
        try { rec?.destroy() } catch (e: Exception) { }
        val r = SpeechRecognizer.createSpeechRecognizer(a)
        rec = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rms: Float) { orb?.level = ((rms + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { orb?.level = 0f }
            override fun onEvent(t: Int, b: Bundle?) {}
            override fun onPartialResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t != null && live) sub?.text = t
            }
            override fun onResults(b: Bundle?) {
                orb?.level = 0f
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!live) return
                if (t.isNullOrBlank()) {
                    setMode(0, "Tap the orb to talk")
                } else {
                    submit(t)
                }
            }
            override fun onError(e: Int) {
                orb?.level = 0f
                if (!live) return
                setMode(0, "Tap the orb to talk")
                sub?.text = when (e) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is off for SAANN."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "The speech service needs a connection."
                    else -> "Voice error " + e + "."
                }
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        r.startListening(i)
    }

    private fun submit(t: String) {
        sub?.text = t
        val ok = send?.invoke(t) ?: false
        if (ok) {
            waiting = true
            setMode(1, "Thinking")
        } else {
            setMode(0, "One moment")
            ui.postDelayed({ listen() }, 1500)
        }
    }

    // Called by the chat when SAANN's answer arrives.
    fun onReply(text: String) {
        val a = act ?: return
        if (!live || !waiting) return
        waiting = false
        setMode(2, "Speaking")
        sub?.text = text.take(300)
        Voice.speakThen(a, text) {
            if (live && orb?.mode == 2) listen()
        }
    }
}
