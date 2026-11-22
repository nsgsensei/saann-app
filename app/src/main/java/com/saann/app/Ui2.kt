package com.saann.app

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class IconView(ctx: Context, val kind: String, var tint: Int = 0xFF00E5FF.toInt(), val disc: Int = 0) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val f = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val s = minOf(w, h)
        if (disc != 0) { f.color = disc; c.drawCircle(w / 2, h / 2, s / 2, f) }
        val k = s * (if (disc != 0) 0.038f else 0.042f)
        c.save(); c.translate(w / 2, h / 2)
        p.color = tint; p.strokeWidth = maxOf(2f * resources.displayMetrics.density, s * 0.06f)
        f.color = tint
        when (kind) {
            "plus" -> { c.drawLine(-8 * k, 0f, 8 * k, 0f, p); c.drawLine(0f, -8 * k, 0f, 8 * k, p) }
            "up" -> { c.drawLine(0f, 8 * k, 0f, -8 * k, p); c.drawLine(-7 * k, -1 * k, 0f, -8 * k, p); c.drawLine(7 * k, -1 * k, 0f, -8 * k, p) }
            "mic" -> {
                c.drawRoundRect(RectF(-4 * k, -9 * k, 4 * k, 3 * k), 4 * k, 4 * k, p)
                c.drawArc(RectF(-8 * k, -6 * k, 8 * k, 8 * k), 0f, 180f, false, p)
                c.drawLine(0f, 8 * k, 0f, 11 * k, p)
            }
            "chat" -> {
                c.drawRoundRect(RectF(-9 * k, -8 * k, 9 * k, 5 * k), 4 * k, 4 * k, p)
                val t = Path(); t.moveTo(-3 * k, 5 * k); t.lineTo(-5 * k, 10 * k); t.lineTo(2 * k, 5 * k); c.drawPath(t, p)
            }
            "folder" -> {
                val t = Path(); t.moveTo(-9 * k, -5 * k); t.lineTo(-3 * k, -5 * k); t.lineTo(-1 * k, -2 * k); t.lineTo(9 * k, -2 * k)
                t.lineTo(9 * k, 8 * k); t.lineTo(-9 * k, 8 * k); t.close(); c.drawPath(t, p)
            }
            "tasks" -> {
                c.drawRoundRect(RectF(-8 * k, -8 * k, 8 * k, 8 * k), 3 * k, 3 * k, p)
                val t = Path(); t.moveTo(-4 * k, 0f); t.lineTo(-1 * k, 3 * k); t.lineTo(4 * k, -3 * k); c.drawPath(t, p)
            }
            "library" -> {
                c.drawRoundRect(RectF(-9 * k, -8 * k, -4 * k, 8 * k), 1.5f * k, 1.5f * k, p)
                c.drawRoundRect(RectF(-3 * k, -8 * k, 2 * k, 8 * k), 1.5f * k, 1.5f * k, p)
                c.save(); c.rotate(-15f, 7 * k, 8 * k); c.drawRoundRect(RectF(4 * k, -8 * k, 9 * k, 8 * k), 1.5f * k, 1.5f * k, p); c.restore()
            }
            "photo" -> {
                c.drawRoundRect(RectF(-9 * k, -7 * k, 9 * k, 7 * k), 3 * k, 3 * k, p)
                c.drawCircle(-4 * k, -2 * k, 1.8f * k, f)
                val t = Path(); t.moveTo(-9 * k, 6 * k); t.lineTo(-2 * k, 0f); t.lineTo(3 * k, 4 * k); t.lineTo(5 * k, 2 * k); t.lineTo(9 * k, 6 * k); c.drawPath(t, p)
            }
            "file" -> {
                val t = Path(); t.moveTo(-6 * k, -9 * k); t.lineTo(2 * k, -9 * k); t.lineTo(7 * k, -4 * k); t.lineTo(7 * k, 9 * k); t.lineTo(-6 * k, 9 * k); t.close(); c.drawPath(t, p)
                c.drawLine(2 * k, -9 * k, 2 * k, -4 * k, p); c.drawLine(2 * k, -4 * k, 7 * k, -4 * k, p)
            }
            "camera" -> {
                c.drawRoundRect(RectF(-9 * k, -5 * k, 9 * k, 8 * k), 3 * k, 3 * k, p)
                c.drawLine(-4 * k, -5 * k, -2 * k, -8 * k, p); c.drawLine(-2 * k, -8 * k, 2 * k, -8 * k, p); c.drawLine(2 * k, -8 * k, 4 * k, -5 * k, p)
                c.drawCircle(0f, 1.5f * k, 3.5f * k, p)
            }
            "shot" -> {
                c.drawRoundRect(RectF(-5 * k, -9 * k, 5 * k, 9 * k), 2.5f * k, 2.5f * k, p)
                c.drawLine(-1.5f * k, 6 * k, 1.5f * k, 6 * k, p)
            }
        }
        c.restore()
    }
}

object Ui2 {
    private const val PANEL = 0xFF1A1D23.toInt()
    private const val CYAN = 0xFF00E5FF.toInt()
    private const val INK = 0xFFF5F3EE.toInt()
    private const val DIM = 0xFF8B93A1.toInt()

    private fun sheet(a: Activity): Pair<Dialog, LinearLayout> {
        val den = a.resources.displayMetrics.density
        val d = Dialog(a)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding((18 * den).toInt(), (12 * den).toInt(), (18 * den).toInt(), (22 * den).toInt())
        val g = GradientDrawable()
        g.setColor(PANEL)
        g.cornerRadii = floatArrayOf(28 * den, 28 * den, 28 * den, 28 * den, 0f, 0f, 0f, 0f)
        g.setStroke((1 * den).toInt(), 0x33FFFFFF)
        box.background = g
        val grab = View(a)
        val gg = GradientDrawable(); gg.cornerRadius = 3 * den; gg.setColor(0x556FD6E6)
        grab.background = gg
        val glp = LinearLayout.LayoutParams((40 * den).toInt(), (4 * den).toInt())
        glp.gravity = Gravity.CENTER_HORIZONTAL
        glp.bottomMargin = (12 * den).toInt()
        box.addView(grab, glp)
        d.setContentView(box)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout(MATCH_PARENT, WRAP_CONTENT)
        d.window?.setGravity(Gravity.BOTTOM)
        return Pair(d, box)
    }

    private fun row(a: Activity, box: LinearLayout, kind: String, label: String, onClick: () -> Unit) {
        val den = a.resources.displayMetrics.density
        val r = LinearLayout(a)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        r.setPadding(0, (10 * den).toInt(), 0, (10 * den).toInt())
        r.addView(IconView(a, kind, CYAN, 0x1F00E5FF), LinearLayout.LayoutParams((46 * den).toInt(), (46 * den).toInt()))
        val t = TextView(a)
        t.text = label; t.setTextColor(INK); t.textSize = 16f
        t.setPadding((16 * den).toInt(), 0, 0, 0)
        r.addView(t, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        r.setOnClickListener { onClick() }
        box.addView(r, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }

    fun addSheet(a: Activity, onCamera: () -> Unit, onMedia: () -> Unit, onFile: () -> Unit, onShot: () -> Unit) {
        val (d, box) = sheet(a)
        val t = TextView(a)
        t.text = "Add to chat"; t.setTextColor(DIM); t.textSize = 13f; t.letterSpacing = 0.1f
        box.addView(t)
        row(a, box, "camera", "Camera") { d.dismiss(); onCamera() }
        row(a, box, "photo", "Photos and videos") { d.dismiss(); onMedia() }
        row(a, box, "file", "Files") { d.dismiss(); onFile() }
        row(a, box, "shot", "Screenshot of another app") { d.dismiss(); onShot() }
        d.show()
    }

    fun panel(a: Activity, title: String, kind: String) {
        val (d, box) = sheet(a)
        val den = a.resources.displayMetrics.density
        val t = TextView(a)
        t.text = title.uppercase(); t.setTextColor(INK); t.textSize = 15f; t.letterSpacing = 0.2f
        t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
        box.addView(t)
        val body = TextView(a)
        body.setTextColor(DIM); body.textSize = 14f
        body.setPadding(0, (12 * den).toInt(), 0, 0)
        if (kind == "library") {
            val fs = File(a.filesDir, "saann/data/uploads").listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
            body.text = if (fs.isEmpty()) "Nothing here yet. Files and screenshots you attach will be listed here."
            else fs.take(30).joinToString("\n") { "• " + it.name.substringAfter('-') }
            body.setTextColor(INK)
            val sv = ScrollView(a); sv.addView(body)
            box.addView(sv, LinearLayout.LayoutParams(MATCH_PARENT, (260 * den).toInt()))
        } else {
            body.text = if (kind == "folder") "Projects are coming soon: group chats, files and notes under one goal."
            else "Tasks are coming soon: things SAANN does for you later, like reminders and routines."
            box.addView(body)
        }
        d.show()
    }

    fun navBar(a: Activity): LinearLayout {
        val den = a.resources.displayMetrics.density
        val bar = LinearLayout(a)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundColor(0xFF0B0D10.toInt())
        bar.setPadding(0, (6 * den).toInt(), 0, (6 * den).toInt())
        val items = listOf(Triple("chat", "Chat", true), Triple("folder", "Projects", false), Triple("tasks", "Tasks", false), Triple("library", "Library", false))
        for ((k, n, on) in items) {
            val c = LinearLayout(a)
            c.orientation = LinearLayout.VERTICAL
            c.gravity = Gravity.CENTER_HORIZONTAL
            c.addView(IconView(a, k, if (on) CYAN else DIM), LinearLayout.LayoutParams((26 * den).toInt(), (26 * den).toInt()))
            val l = TextView(a)
            l.text = n; l.textSize = 11f; l.setTextColor(if (on) CYAN else DIM); l.gravity = Gravity.CENTER
            c.addView(l)
            if (!on) c.setOnClickListener { panel(a, n, k) }
            bar.addView(c, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }
        return bar
    }
}
