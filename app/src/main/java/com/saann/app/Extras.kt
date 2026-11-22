package com.saann.app

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.util.Locale

// ---------------- voice ----------------
// Today: the mic button uses Android's speech recogniser and the text goes through the normal brain.
// Later: set Voice.recognizer to your own backend (for example whisper) and call Voice.onHeard(text).
// ---------------- attachments ----------------
class Attachment(val name: String, val path: String, val kind: String) {
    @Volatile var text: String = ""
    @Volatile var ready: Boolean = false
}

object Attach {
    private val textExt = setOf("txt", "md", "csv", "json", "py", "kt", "log", "xml", "html", "js", "yaml", "yml", "ini")
    private val imgExt = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")

    private fun kindOf(name: String, mime: String?): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            (mime ?: "").startsWith("image/") || ext in imgExt -> "image"
            ext == "pdf" || mime == "application/pdf" -> "pdf"
            (mime ?: "").startsWith("text/") || ext in textExt -> "text"
            else -> "file"
        }
    }

    fun copyIn(act: Activity, uri: Uri): Attachment? {
        return try {
            var name = "file"
            act.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: "file"
            }
            name = name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(60)
            val dir = File(act.filesDir, "saann/data/uploads")
            dir.mkdirs()
            dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(19)?.forEach { it.delete() }
            val f = File(dir, System.currentTimeMillis().toString() + "-" + name)
            act.contentResolver.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                ?: return null
            if (f.length() > 20L * 1024 * 1024) {
                f.delete()
                return null
            }
            Attachment(name, f.absolutePath, kindOf(name, act.contentResolver.getType(uri)))
        } catch (e: Exception) {
            null
        }
    }

    private fun bitmap(path: String): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, o)
        var s = 1
        while (o.outWidth / s > 2000) s *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = s
        return BitmapFactory.decodeFile(path, o2)
    }

    // run on a background thread
    fun process(a: Attachment) {
        try {
            a.text = when (a.kind) {
                "image" -> {
                    val b = bitmap(a.path)
                    if (b == null) "(could not open the image)" else Vision.look(b)
                }
                "text" -> File(a.path).readText().take(6000)
                "pdf" -> "(PDF attached: " + a.name + ". It is read when you send.)"
                else -> "(this file type can't be read yet; it is saved at " + a.path + ")"
            }
        } catch (e: Throwable) {
            a.text = "(could not read it: " + (e.message ?: "error") + ")"
        }
        a.ready = true
    }

    fun compose(text: String, list: List<Attachment>): String {
        val sb = StringBuilder(if (text.isBlank()) "Look at the attachment and tell me what it is." else text)
        for (a in list) {
            sb.append("\n\n[attached ").append(a.kind).append(": ").append(a.name).append(" (file: ").append(a.path).append(")")
            sb.append(if (a.kind == "image") ", text found on it:]\n" else "]\n")
            sb.append(a.text.take(3000))
        }
        return sb.toString()
    }
}

// ---------------- brain detail screen ----------------
object BrainDetail {
    private const val BG = 0xFF05090F.toInt()
    private const val PANEL = 0xFF0B1620.toInt()
    private const val CYAN = 0xFF00E5FF.toInt()
    private const val INK = 0xFFE8FBFF.toInt()
    private const val DIM = 0xFF6FD6E6.toInt()

    fun show(act: Activity) {
        val d = Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val den = act.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * den).toInt()
        val ui = Handler(Looper.getMainLooper())
        val root = LinearLayout(act)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)
        root.fitsSystemWindows = true
        val top = LinearLayout(act)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(dp(16), dp(12), dp(16), dp(12))
        val title = TextView(act)
        title.text = "BRAIN"
        title.setTextColor(INK)
        title.textSize = 18f
        title.letterSpacing = 0.25f
        title.setTypeface(title.typeface, Typeface.BOLD)
        top.addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val live = TextView(act)
        live.text = "live"
        live.setTextColor(DIM)
        live.textSize = 12f
        live.setPadding(0, 0, dp(16), 0)
        top.addView(live)
        val close = TextView(act)
        close.text = "✕"
        close.setTextColor(CYAN)
        close.textSize = 20f
        close.setOnClickListener { d.dismiss() }
        top.addView(close)
        root.addView(top)
        val scroll = ScrollView(act)
        val body = LinearLayout(act)
        body.orientation = LinearLayout.VERTICAL
        body.setPadding(dp(12), 0, dp(12), dp(20))
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        d.setContentView(root)

        fun card(): LinearLayout {
            val c = LinearLayout(act)
            c.orientation = LinearLayout.VERTICAL
            c.setPadding(dp(14), dp(12), dp(14), dp(12))
            val g = GradientDrawable()
            g.cornerRadius = dp(16).toFloat()
            g.setColor(PANEL)
            g.setStroke(dp(1), 0x5500E5FF)
            c.background = g
            val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            lp.topMargin = dp(8)
            c.layoutParams = lp
            return c
        }

        fun line(parent: LinearLayout, s: String, size: Float, color: Int, bold: Boolean = false) {
            val t = TextView(act)
            t.text = s
            t.textSize = size
            t.setTextColor(color)
            if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
            parent.addView(t)
        }

        fun num(v: Any?): String {
            if (v is Double) return String.format(Locale.US, "%.3f", v)
            return v.toString()
        }

        fun render(json: String) {
            body.removeAllViews()
            val o = try { JSONObject(json) } catch (e: Exception) { JSONObject().put("error", "bad data") }
            if (o.has("error")) {
                val c = card()
                line(c, "The brain isn't reachable yet: " + o.optString("error"), 13f, INK)
                body.addView(c)
                return
            }
            val sum = card()
            line(sum, "OVERVIEW", 11f, DIM, true)
            val tot = o.optJSONObject("totals")
            if (tot != null) line(sum, tot.optInt("concepts").toString() + " concepts · " + tot.optInt("reflexes") + " reflexes", 14f, INK)
            val cx = o.optJSONObject("cortex")
            if (cx != null) {
                line(sum, "cortex " + cx.optString("size") + " · " + cx.optInt("networks") + " networks · " +
                    String.format(Locale.US, "%,d", cx.optLong("parameters")) + " parameters", 13f, INK)
            }
            val mo = o.optJSONObject("motor")
            if (mo != null) {
                line(sum, "motor cortex · " + mo.optInt("intents") + " intents · " + mo.optInt("neurons") + " neurons · " +
                    mo.optInt("taught") + " taught", 13f, INK)
            }
            val st = o.optJSONObject("state")
            if (st != null) {
                val keys = st.keys()
                val parts = ArrayList<String>()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = st.opt(k)
                    if (v is Number || v is String || v is Boolean) parts.add(k + " " + num(v))
                }
                if (parts.isNotEmpty()) line(sum, parts.joinToString(" · "), 12f, DIM)
            }
            body.addView(sum)

            val regs = o.optJSONArray("regions")
            if (regs != null) {
                var max = 1
                for (i in 0 until regs.length()) max = maxOf(max, regs.getJSONObject(i).optInt("concepts"))
                for (i in 0 until regs.length()) {
                    val r = regs.getJSONObject(i)
                    val c = card()
                    val n = r.optInt("concepts")
                    line(c, r.optString("name").uppercase(), 13f, INK, true)
                    val bar = View(act)
                    val bg = GradientDrawable()
                    bg.cornerRadius = dp(3).toFloat()
                    bg.setColor(CYAN)
                    bar.background = bg
                    val w = maxOf(dp(4), ((act.resources.displayMetrics.widthPixels - dp(64)) * n.toFloat() / max).toInt())
                    val blp = LinearLayout.LayoutParams(w, dp(5))
                    blp.topMargin = dp(6)
                    blp.bottomMargin = dp(6)
                    c.addView(bar, blp)
                    var info = n.toString() + " concepts"
                    if (r.has("trained")) info += " · trained " + r.optInt("trained")
                    if (!r.isNull("loss") && r.has("loss")) info += " · loss " + String.format(Locale.US, "%.3f", r.optDouble("loss"))
                    line(c, info, 12f, DIM)
                    val rec = r.optJSONArray("recent")
                    if (rec != null && rec.length() > 0) {
                        val names = ArrayList<String>()
                        for (j in 0 until rec.length()) names.add(rec.optString(j))
                        line(c, "recent: " + names.joinToString(", "), 12f, INK)
                    }
                    body.addView(c)
                }
            }
        }

        var open = true
        fun load() {
            Thread {
                val r = try {
                    Host.py?.callAttr("brain_view")?.toString() ?: JSONObject().put("error", "still starting").toString()
                } catch (e: Throwable) {
                    JSONObject().put("error", e.message ?: "unknown").toString()
                }
                ui.post { if (open) render(r) }
            }.start()
        }

        val tick = object : Runnable {
            override fun run() {
                if (!open) return
                load()
                ui.postDelayed(this, 3000)
            }
        }
        d.setOnDismissListener {
            open = false
            ui.removeCallbacksAndMessages(null)
        }
        d.show()
        tick.run()
    }
}
