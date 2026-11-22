package com.saann.app

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

// The "Add to chat" bottom sheet.
object AddSheet {
    private const val PANEL = 0xFF0B1620.toInt()
    private const val TILE = 0xFF121D28.toInt()
    private const val INK = 0xFFE8FBFF.toInt()
    private const val DIM = 0xFF6FD6E6.toInt()

    fun show(a: Activity, pick: (String) -> Unit) {
        val den = a.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * den).toInt()
        val d = Dialog(a)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(a)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(10), dp(16), dp(24))
        val g = GradientDrawable()
        val rad = dp(28).toFloat()
        g.cornerRadii = floatArrayOf(rad, rad, rad, rad, 0f, 0f, 0f, 0f)
        g.setColor(PANEL)
        root.background = g

        val handle = View(a)
        val hg = GradientDrawable()
        hg.cornerRadius = dp(2).toFloat()
        hg.setColor(0x55FFFFFF)
        handle.background = hg
        val hlp = LinearLayout.LayoutParams(dp(40), dp(4))
        hlp.gravity = Gravity.CENTER_HORIZONTAL
        hlp.bottomMargin = dp(14)
        root.addView(handle, hlp)

        val head = FrameLayout(a)
        val x = IconView(a, "close", INK)
        x.setOnClickListener { d.dismiss() }
        head.addView(x, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER_VERTICAL or Gravity.START))
        val tt = TextView(a)
        tt.text = "Add to chat"
        tt.setTextColor(INK)
        tt.textSize = 18f
        tt.setTypeface(tt.typeface, Typeface.BOLD)
        head.addView(tt, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER))
        root.addView(head, LinearLayout.LayoutParams(MATCH_PARENT, dp(44)))

        fun badge(kind: String, size: Int): View {
            val v = IconView(a, kind, INK)
            val bg = GradientDrawable()
            bg.shape = GradientDrawable.OVAL
            bg.setColor(0xFF1E2C3A.toInt())
            v.background = bg
            v.layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
            return v
        }

        fun tile(kind: String, label: String, key: String): View {
            val t = LinearLayout(a)
            t.orientation = LinearLayout.VERTICAL
            t.gravity = Gravity.CENTER_HORIZONTAL
            t.setPadding(0, dp(16), 0, dp(14))
            val bg = GradientDrawable()
            bg.cornerRadius = dp(18).toFloat()
            bg.setColor(TILE)
            t.background = bg
            t.addView(badge(kind, 48))
            val l = TextView(a)
            l.text = label
            l.setTextColor(INK)
            l.textSize = 15f
            l.setPadding(0, dp(8), 0, 0)
            t.addView(l)
            pressFx(t)
            t.setOnClickListener {
                d.dismiss()
                pick(key)
            }
            return t
        }

        val tiles = LinearLayout(a)
        tiles.orientation = LinearLayout.HORIZONTAL
        val specs = arrayOf(Triple("camera", "Camera", "camera"), Triple("image", "Photos", "photos"), Triple("file", "Files", "files"))
        for (i in specs.indices) {
            val lp = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            lp.leftMargin = if (i == 0) 0 else dp(8)
            tiles.addView(tile(specs[i].first, specs[i].second, specs[i].third), lp)
        }
        val tlp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        tlp.topMargin = dp(14)
        root.addView(tiles, tlp)

        fun item(kind: String, label: String, sub: String, key: String) {
            val r = LinearLayout(a)
            r.orientation = LinearLayout.HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(dp(14), dp(10), dp(14), dp(10))
            val bg = GradientDrawable()
            bg.cornerRadius = dp(18).toFloat()
            bg.setColor(TILE)
            r.background = bg
            r.addView(badge(kind, 42))
            val col = LinearLayout(a)
            col.orientation = LinearLayout.VERTICAL
            col.setPadding(dp(14), 0, 0, 0)
            val l = TextView(a)
            l.text = label
            l.setTextColor(INK)
            l.textSize = 16f
            col.addView(l)
            val s = TextView(a)
            s.text = sub
            s.setTextColor(DIM)
            s.textSize = 12f
            col.addView(s)
            r.addView(col)
            pressFx(r)
            r.setOnClickListener {
                d.dismiss()
                pick(key)
            }
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            lp.topMargin = dp(10)
            root.addView(r, lp)
        }
        item("video", "Record video", "Opens your camera", "video")
        item("shot", "Screenshot", "Switch to any app, captured in 3 seconds", "screenshot")

        d.setContentView(root)
        val w = d.window
        w?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        w?.setLayout(MATCH_PARENT, WRAP_CONTENT)
        w?.setGravity(Gravity.BOTTOM)
        d.show()
    }
}
