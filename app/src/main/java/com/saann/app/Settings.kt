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
    private const val INK = 0xFFF3F0EB.toInt()
    private const val DIM = 0xFFA9B2C7.toInt()
    private const val CYAN = 0xFFB9AAFF.toInt()

    fun show(a: Activity, onAgent: () -> Unit, agentLabel: () -> String) {
        val den = a.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * den).toInt()
        val ui = Handler(Looper.getMainLooper())
        val (d, box) = Ui2.sheet(a)
        val t = TextView(a)
        t.text = "Make SAANN yours"
        t.setTextColor(INK)
        t.textSize = 23f
        t.letterSpacing = 0f
        t.setTypeface(t.typeface, Typeface.BOLD)
        box.addView(t)
        // SAANN_SECONDARY_UI_V1
        val scroller = android.widget.ScrollView(a).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
        }
        val content = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroller.addView(content)
        val windowHeight = a.resources.displayMetrics.heightPixels
        box.addView(
            scroller,
            LinearLayout.LayoutParams(
                MATCH_PARENT,
                minOf(dp(520), (windowHeight * 0.62f).toInt())
            )
        )

        var sectionAccent = CYAN

        fun section(title: String, color: Int) {
            sectionAccent = color
            content.addView(TextView(a).apply {
                text = title
                textSize = 12f
                letterSpacing = 0.08f
                setTextColor(color)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(4), dp(22), dp(4), dp(9))
            })
        }

        val prefs = a.getSharedPreferences("saann_voice", 0)

        fun row(title: String, value: () -> String, click: () -> Unit): TextView {
            val r = LinearLayout(a)
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(dp(14), dp(14), dp(14), dp(14))
            r.minimumHeight = dp(56)
            r.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(17).toFloat()
                setColor(0xFF242B3A.toInt())
                setStroke(
                    dp(1),
                    (sectionAccent and 0x00FFFFFF) or 0x22000000
                )
            }
            val n = TextView(a)
            n.text = title
            n.setTextColor(INK)
            n.textSize = 15f
            n.setPadding(0, 0, dp(12), 0)
            r.addView(n, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            val v = TextView(a)
            v.text = value()
            v.setTextColor(sectionAccent)
            v.maxLines = 2
            v.maxWidth = dp(112)
            v.gravity = Gravity.END
            v.textSize = 14f
            r.addView(v)
            r.setOnClickListener {
                click()
                ui.postDelayed({ v.text = value() }, 700)
            }
            pressFx(r)
            content.addView(
                r,
                LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    bottomMargin = dp(7)
                }
            )
            return v
        }

        section("CONNECTIONS", 0xFFB9AAFF.toInt())
        row("Setup & connections", { "Manage" }) {
            d.dismiss()
            Onboarding.show(a)
        }
        row("Models & connection status", { "View" }) {
            d.dismiss()
            SaannConnections.show(a)
        }
        row("Brain details", { "View" }) {
            d.dismiss()
            BrainDetail.show(a)
        }

        section("VOICE & SOUND", 0xFF9DD6C4.toInt())
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
        // SAANN_CLOUD_VOICE_SETTINGS_V2
        row("Cloud voice", {
            if (Keys.flag(a, "cloud_voice_on")) "ElevenLabs" else "Phone"
        }) {
            CloudVoice.show(a)
        }
        section("ASSISTANCE & TASKS", 0xFFE8BC9F.toInt())
        row("Agent", { agentLabel().removePrefix("AGENT ").lowercase().replaceFirstChar { it.uppercase() } }) { onAgent() }
        row("Online help", { "Check" }) {
            Thread {
                val r = try { Host.py?.callAttr("respond", "/cloud")?.toString() ?: "starting..." } catch (e: Throwable) { "error" }
                a.runOnUiThread { Toast.makeText(a, r.take(220), Toast.LENGTH_LONG).show() }
            }.start()
        }
        row("Run tasks now", { "Go" }) { AgentService.runDue(a) }
        d.setOnDismissListener {
            ui.removeCallbacksAndMessages(null)
        }
        d.show()
        d.window?.setLayout(MATCH_PARENT, WRAP_CONTENT)
    }
}
