package com.saann.app

import android.app.Activity
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

object Settings {
    private const val INK = 0xFFF5F3EE.toInt()
    private const val DIM = 0xFF8B93A1.toInt()
    private const val CYAN = 0xFF00E5FF.toInt()

    fun show(a: Activity, onAgent: () -> Unit, agentLabel: () -> String) {
        val den = a.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * den).toInt()
        val ui = Handler(Looper.getMainLooper())
        val (d, box) = Ui2.sheet(a)
        val t = TextView(a)
        t.text = "SETTINGS"
        t.setTextColor(INK)
        t.textSize = 15f
        t.letterSpacing = 0.2f
        t.setTypeface(t.typeface, Typeface.BOLD)
        box.addView(t)
        val prefs = a.getSharedPreferences("saann_voice", 0)

        fun row(title: String, value: () -> String, click: () -> Unit): TextView {
            val r = LinearLayout(a)
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(0, dp(13), 0, dp(13))
            val n = TextView(a)
            n.text = title
            n.setTextColor(INK)
            n.textSize = 16f
            r.addView(n, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val v = TextView(a)
            v.text = value()
            v.setTextColor(CYAN)
            v.textSize = 14f
            r.addView(v)
            r.setOnClickListener {
                click()
                ui.postDelayed({ v.text = value() }, 700)
            }
            box.addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            return v
        }

        row("Speak replies", { if (Voice.speakReplies) "On" else "Off" }) {
            Voice.command(a, if (Voice.speakReplies) "off" else "on")
        }
        row("Voice speed", {
            val r = prefs.getFloat("rate", 0.93f)
            if (r < 0.9f) "Calm" else if (r < 1.0f) "Natural" else "Brisk"
        }) {
            val r = prefs.getFloat("rate", 0.93f)
            val next = if (r < 0.9f) 0.93f else if (r < 1.0f) 1.05f else 0.85f
            Voice.command(a, "rate " + next)
        }
        row("Voice sample", { "Play" }) { Voice.command(a, "test") }
        row("Agent", { agentLabel().removePrefix("AGENT ").lowercase().replaceFirstChar { it.uppercase() } }) { onAgent() }
        row("Online help", { "Check" }) {
            Thread {
                val r = try { Host.py?.callAttr("respond", "/cloud")?.toString() ?: "starting..." } catch (e: Throwable) { "error" }
                a.runOnUiThread { Toast.makeText(a, r.take(220), Toast.LENGTH_LONG).show() }
            }.start()
        }
        row("Run tasks now", { "Go" }) { AgentService.runDue(a) }
        d.show()
    }
}
