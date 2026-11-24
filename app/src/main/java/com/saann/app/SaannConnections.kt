package com.saann.app

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

object SaannConnections {
    private val ink = 0xFFF3F0EB.toInt()
    private val dim = 0xFFA9B2C7.toInt()
    private val mint = 0xFF72CDB6.toInt()
    private val peach = 0xFFEFB596.toInt()
    private val lavender = 0xFFB9AAFF.toInt()

    fun show(a: Activity) {
        if (a.isFinishing || a.isDestroyed) return
        val density = a.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val (dialog, box) = Ui2.sheet(a)

        fun label(text: String, size: Float, color: Int): TextView =
            TextView(a).apply {
                this.text = text
                textSize = size
                setTextColor(color)
                setPadding(0, dp(7), 0, dp(7))
            }

        val header = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            IconView(a, "saann", lavender),
            LinearLayout.LayoutParams(dp(44), dp(44))
        )
        header.addView(label("SAANN connections", 20f, ink).apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), 0, 0, 0)
        })
        box.addView(header)

        box.addView(label(
            "Saved configuration · no live tests run when opened",
            12f, dim
        ))

        val body = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroller = ScrollView(a)
        scroller.addView(body)
        val availableHeight = a.resources.displayMetrics.heightPixels
        box.addView(
            scroller,
            LinearLayout.LayoutParams(
                MATCH_PARENT, minOf(dp(310), (availableHeight * 0.43f).toInt())
            )
        )
        val loading = label("Reading model settings…", 14f, dim)
        body.addView(loading)

        val manage = label("Manage connections  ›", 16f, lavender).apply {
            minHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener {
                dialog.dismiss()
                Onboarding.show(a)
            }
        }
        box.addView(manage)
        dialog.show()
        dialog.window?.setLayout(MATCH_PARENT, WRAP_CONTENT)

        Thread {
            val report = try {
                val engine = Host.py
                if (engine == null) null else JSONObject(
                    engine.callAttr("ui_connection_snapshot").toString()
                )
            } catch (_: Exception) {
                null
            }

            a.runOnUiThread {
                if (!dialog.isShowing || a.isFinishing || a.isDestroyed) {
                    return@runOnUiThread
                }
                body.removeAllViews()
                if (report == null) {
                    body.addView(label(
                        "Configuration is unavailable while the engine starts. " +
                        "Close this sheet and try again shortly.", 14f, peach
                    ))
                } else {
                    val providers = report.optJSONArray("providers")
                    if (providers != null) {
                        for (i in 0 until providers.length()) {
                            val provider = providers.optJSONObject(i) ?: continue
                            val name = provider.optString("name")
                            val configured = provider.optBoolean("configured")
                            body.addView(label(
                                name.replaceFirstChar { it.uppercase() } +
                                " · " + (if (configured) "Credentials saved"
                                else "Not configured"),
                                15f, if (configured) mint else peach
                            ))
                            body.addView(label(
                                "Configured model: " +
                                provider.optString("model").ifBlank { "Not selected" } +
                                "\nLive availability: not checked",
                                12f, dim
                            ))
                        }
                    }
                    body.addView(label(
                        "Image analysis: Gemini or Cloudflare\n" +
                        "Image generation: Cloudflare\n" +
                        "Image editing: Gemini image model\n\n" +
                        "These are pipeline requirements, not verified capabilities. " +
                        "Provider access and quotas still apply.",
                        12f, dim
                    ))
                }
            }
        }.start()
    }
}
