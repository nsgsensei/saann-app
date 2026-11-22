package com.saann.app

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

object Onboarding {
    private val ORANGE = 0xFFFF8A3D.toInt()
    private val PANEL = 0xFF0B1620.toInt()
    private val CYAN = 0xFF00E5FF.toInt()
    private val INK = 0xFFE8FBFF.toInt()
    private val DIM = 0xFF6FD6E6.toInt()

    private val providers = listOf(
        Triple(
            "gemini",
            "Gemini — reasoning, photos and images",
            "Required. One free key lets SAANN think, understand photos, and create images.",
            "https://aistudio.google.com/apikey"
        ),
        Triple(
            "groq",
            "Groq — fast text backup",
            "Optional. SAANN can use it when Gemini is busy or unavailable.",
            "https://console.groq.com/keys"
        ),
        Triple(
            "elevenlabs",
            "ElevenLabs — SAANN’s voice",
            "Optional. Gives SAANN a natural speaking voice.",
            "https://elevenlabs.io/app/settings/api-keys"
        )
    )

    fun needed(c: Context): Boolean {
        return Keys.get(c, "gemini_key").isNullOrBlank() ||
            Keys.get(c, "groq_key").isNullOrBlank() ||
            Keys.get(c, "elevenlabs_key").isNullOrBlank()
    }

    fun show(a: Activity) {
        val den = a.resources.displayMetrics.density
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(
            (22 * den).toInt(), (20 * den).toInt(),
            (22 * den).toInt(), (24 * den).toInt()
        )
        box.background = rounded(PANEL, 26 * den, 0x6600E5FF)

        val title = TextView(a)
        title.text = "SET UP SAANN"
        title.setTextColor(INK)
        title.textSize = 20f
        title.setTypeface(title.typeface, Typeface.BOLD)
        title.letterSpacing = 0.12f
        box.addView(title)

        val sub = TextView(a)
        sub.text = "Connect free AI services. Your keys stay on this phone."
        sub.setTextColor(DIM)
        sub.textSize = 14f
        sub.setPadding(0, (8 * den).toInt(), 0, (16 * den).toInt())
        box.addView(sub)

        for (p in providers) {
            box.addView(card(a, p.first, p.second, p.third, p.fourth))
        }

        val done = Button(a)
        done.text = "SAVE AND CONTINUE"
        done.setTextColor(Color.BLACK)
        done.background = rounded(ORANGE, 16 * den, 0)
        done.setPadding(0, (14 * den).toInt(), 0, (14 * den).toInt())
        done.setOnClickListener {
            if (Keys.get(a, "gemini_key").isNullOrBlank()) {
                Toast.makeText(a, "Add at least the Gemini key", Toast.LENGTH_LONG).show()
            } else {
                Keys.setFlag(a, "onboarded", true)
                Toast.makeText(a, "SAANN is ready", Toast.LENGTH_SHORT).show()
            }
        }

        val doneLp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        doneLp.topMargin = (18 * den).toInt()
        box.addView(done, doneLp)

        val dialog = Dialog(a)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        dialog.setContentView(box)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(MATCH_PARENT, WRAP_CONTENT)
        dialog.show()
    }

    private fun card(
        a: Activity,
        id: String,
        name: String,
        desc: String,
        url: String
    ): LinearLayout {
        val den = a.resources.displayMetrics.density
        val card = LinearLayout(a)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(
            (16 * den).toInt(), (14 * den).toInt(),
            (16 * den).toInt(), (14 * den).toInt()
        )
        card.background = rounded(0xFF101C28.toInt(), 18 * den, 0x33FF8A3D)

        val t = TextView(a)
        t.text = name
        t.setTextColor(INK)
        t.textSize = 16f
        t.setTypeface(t.typeface, Typeface.BOLD)
        card.addView(t)

        val d = TextView(a)
        d.text = desc
        d.setTextColor(DIM)
        d.textSize = 13f
        d.setPadding(0, (4 * den).toInt(), 0, (10 * den).toInt())
        card.addView(d)

        val field = EditText(a)
        field.hint = "Paste $id API key"
        field.setHintTextColor(0x776FD6E6)
        field.setTextColor(INK)
        field.textSize = 14f
        field.background = rounded(0xFF071019.toInt(), 12 * den, 0x3300E5FF)
        field.setPadding(
            (12 * den).toInt(), (10 * den).toInt(),
            (12 * den).toInt(), (10 * den).toInt()
        )
        field.setText(Keys.get(a, "${id}_key") ?: "")
        card.addView(field, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val actions = LinearLayout(a)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.gravity = Gravity.END

        actions.addView(link(a, "GET KEY") {
            try {
                a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                Toast.makeText(a, "Open: $url", Toast.LENGTH_LONG).show()
            }
        })

        actions.addView(link(a, "SAVE") {
            val value = field.text.toString().trim()
            if (value.isEmpty()) {
                Toast.makeText(a, "Paste the key first", Toast.LENGTH_SHORT).show()
            } else {
                Keys.set(a, "${id}_key", value)
                Toast.makeText(a, "$id key saved", Toast.LENGTH_SHORT).show()
            }
        })

        card.addView(actions, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        lp.topMargin = (12 * den).toInt()
        card.layoutParams = lp
        return card
    }

    private fun link(a: Activity, label: String, onClick: () -> Unit): TextView {
        val den = a.resources.displayMetrics.density
        val b = TextView(a)
        b.text = label
        b.setTextColor(ORANGE)
        b.textSize = 13f
        b.setTypeface(b.typeface, Typeface.BOLD)
        b.letterSpacing = 0.08f
        b.setPadding(
            (14 * den).toInt(), (10 * den).toInt(),
            (14 * den).toInt(), (10 * den).toInt()
        )
        b.setOnClickListener { onClick() }
        return b
    }

    private fun rounded(color: Int, radiusDp: Float, stroke: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = radiusDp
        if (stroke != 0) g.setStroke(2, stroke)
        return g
    }
}
