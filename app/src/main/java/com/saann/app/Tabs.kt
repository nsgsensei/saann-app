package com.saann.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.util.Date

// The Projects, Tasks, Library and Setup pages.
object Tabs {
    private const val PANEL = 0xFF0B1620.toInt()
    private const val CYAN = 0xFF00E5FF.toInt()
    private const val INK = 0xFFE8FBFF.toInt()
    private const val DIM = 0xFF6FD6E6.toInt()

    private fun dp(a: Activity, v: Int): Int = (v * a.resources.displayMetrics.density).toInt()

    // ---------- tasks history (written by the chat after an action) ----------
    fun record(filesDir: File, q: String, r: String, steps: Int) {
        if (steps <= 0) return
        try {
            val f = File(filesDir, "saann/data/tasks.json")
            f.parentFile?.mkdirs()
            val arr = try { JSONArray(f.readText()) } catch (e: Exception) { JSONArray() }
            arr.put(JSONObject().put("t", System.currentTimeMillis()).put("q", q.take(160)).put("r", r.take(240)).put("s", steps))
            while (arr.length() > 100) arr.remove(0)
            f.writeText(arr.toString())
        } catch (e: Exception) {
        }
    }

    fun build(a: Activity, kind: Int, onUse: (Attachment) -> Unit): View {
        val sc = ScrollView(a)
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(a, 16), dp(a, 12), dp(a, 16), dp(a, 16))
        sc.addView(col)
        when (kind) {
            1 -> projects(a, col)
            2 -> tasks(a, col)
            3 -> setup(a, col)
            else -> library(a, col, onUse)
        }
        return sc
    }

    private fun txt(a: Activity, parent: LinearLayout, s: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(a)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
        parent.addView(t)
        return t
    }

    private fun heading(a: Activity, col: LinearLayout, title: String, sub: String) {
        txt(a, col, title, 26f, INK, true)
        val s = txt(a, col, sub, 13f, DIM)
        s.setPadding(0, dp(a, 2), 0, dp(a, 8))
    }

    private fun card(a: Activity, col: LinearLayout): LinearLayout {
        val c = LinearLayout(a)
        c.orientation = LinearLayout.VERTICAL
        c.setPadding(dp(a, 14), dp(a, 12), dp(a, 14), dp(a, 12))
        val g = GradientDrawable()
        g.cornerRadius = dp(a, 16).toFloat()
        g.setColor(PANEL)
        g.setStroke(dp(a, 1), 0x5500E5FF)
        c.background = g
        val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        lp.topMargin = dp(a, 8)
        col.addView(c, lp)
        return c
    }

    // ---------- library ----------
    private fun library(a: Activity, col: LinearLayout, onUse: (Attachment) -> Unit) {
        col.removeAllViews()
        heading(a, col, "Library", "Your uploads and screenshots. Tap one to attach it, hold to delete.")
        val files = ArrayList<File>()
        for (d in listOf("uploads", "shots")) {
            File(a.filesDir, "saann/data/" + d).listFiles()?.let { files.addAll(it) }
        }
        files.sortByDescending { it.lastModified() }
        if (files.isEmpty()) {
            txt(a, col, "Nothing here yet. Use + in the chat to add photos, files or screenshots.", 14f, INK)
            return
        }
        for (f in files.take(40)) {
            val c = card(a, col)
            c.orientation = LinearLayout.HORIZONTAL
            c.gravity = Gravity.CENTER_VERTICAL
            val name = f.name.replaceFirst(Regex("^\\d+-"), "")
            val kind = Attach.kindOf(name, null)
            if (kind == "image") {
                val o = BitmapFactory.Options()
                o.inSampleSize = 8
                val iv = ImageView(a)
                iv.setImageBitmap(BitmapFactory.decodeFile(f.absolutePath, o))
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                c.addView(iv, LinearLayout.LayoutParams(dp(a, 56), dp(a, 56)))
            } else {
                c.addView(IconView(a, "file", DIM), LinearLayout.LayoutParams(dp(a, 56), dp(a, 56)))
            }
            val info = LinearLayout(a)
            info.orientation = LinearLayout.VERTICAL
            info.setPadding(dp(a, 12), 0, 0, 0)
            txt(a, info, name.take(34), 14f, INK, true)
            val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            txt(a, info, (f.length() / 1024).toString() + " KB · " + df.format(Date(f.lastModified())), 11f, DIM)
            c.addView(info)
            c.setOnClickListener { onUse(Attachment(name, f.absolutePath, kind)) }
            c.setOnLongClickListener {
                AlertDialog.Builder(a).setTitle("Delete this file?").setPositiveButton("Delete") { _, _ ->
                    f.delete()
                    library(a, col, onUse)
                }.setNegativeButton("Cancel", null).show()
                true
            }
        }
    }

    // ---------- tasks ----------
    private fun tasks(a: Activity, col: LinearLayout) {
        col.removeAllViews()
        heading(a, col, "Tasks", "What SAANN did for you on this phone")
        val f = File(a.filesDir, "saann/data/tasks.json")
        val arr = try { JSONArray(f.readText()) } catch (e: Exception) { JSONArray() }
        if (arr.length() == 0) {
            txt(a, col, "No tasks yet. Ask SAANN to do something on your phone and it will show up here.", 14f, INK)
            return
        }
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.getJSONObject(i)
            val c = card(a, col)
            txt(a, c, o.optString("q"), 14f, INK, true)
            txt(a, c, o.optString("r"), 12f, DIM)
            txt(a, c, o.optInt("s").toString() + " steps · " + df.format(Date(o.optLong("t"))), 11f, DIM)
        }
        val clear = txt(a, col, "Clear history", 13f, CYAN, true)
        clear.setPadding(0, dp(a, 14), 0, 0)
        clear.setOnClickListener {
            f.delete()
            tasks(a, col)
        }
    }

    // ---------- projects ----------
    private fun pfile(a: Activity): File = File(a.filesDir, "saann/data/projects.json")

    private fun pread(a: Activity): JSONObject =
        try { JSONObject(pfile(a).readText()) } catch (e: Exception) { JSONObject().put("active", "").put("items", JSONArray()) }

    private fun pwrite(a: Activity, o: JSONObject) {
        try {
            pfile(a).parentFile?.mkdirs()
            pfile(a).writeText(o.toString())
        } catch (e: Exception) {
        }
    }

    private fun projects(a: Activity, col: LinearLayout) {
        col.removeAllViews()
        heading(a, col, "Projects", "Group notes and instructions by topic")
        val o = pread(a)
        val items = o.optJSONArray("items") ?: JSONArray()
        val active = o.optString("active")
        val nb = txt(a, col, "+  New project", 14f, CYAN, true)
        nb.setPadding(0, dp(a, 4), 0, dp(a, 4))
        nb.setOnClickListener { editor(a, col, -1) }
        for (i in 0 until items.length()) {
            val p = items.getJSONObject(i)
            val c = card(a, col)
            val n = p.optString("n")
            txt(a, c, n + (if (n == active) "   ● active" else ""), 15f, INK, true)
            if (p.optString("d").isNotEmpty()) txt(a, c, p.optString("d").take(120), 12f, DIM)
            c.setOnClickListener { menu(a, col, i) }
        }
        val note = txt(a, col, "Projects are stored on this phone. They are not connected to the brain yet.", 11f, DIM)
        note.setPadding(0, dp(a, 14), 0, 0)
    }

    private fun editor(a: Activity, col: LinearLayout, idx: Int) {
        val o = pread(a)
        val items = o.optJSONArray("items") ?: JSONArray()
        val cur = if (idx >= 0) items.getJSONObject(idx) else null
        val box = LinearLayout(a)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(a, 20), dp(a, 8), dp(a, 20), 0)
        val name = EditText(a)
        name.hint = "Project name"
        name.setText(cur?.optString("n") ?: "")
        val notes = EditText(a)
        notes.hint = "Notes or instructions"
        notes.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        notes.setText(cur?.optString("d") ?: "")
        box.addView(name)
        box.addView(notes)
        AlertDialog.Builder(a).setTitle(if (idx < 0) "New project" else "Edit project").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val n = name.text.toString().trim()
                if (n.isNotEmpty()) {
                    val obj = JSONObject().put("n", n).put("d", notes.text.toString().trim())
                    if (idx < 0) items.put(obj) else items.put(idx, obj)
                    o.put("items", items)
                    pwrite(a, o)
                    projects(a, col)
                }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun menu(a: Activity, col: LinearLayout, idx: Int) {
        val o = pread(a)
        val items = o.optJSONArray("items") ?: JSONArray()
        val p = items.getJSONObject(idx)
        val isActive = o.optString("active") == p.optString("n")
        AlertDialog.Builder(a).setTitle(p.optString("n"))
            .setItems(arrayOf(if (isActive) "Deactivate" else "Make active", "Edit", "Delete")) { _, w ->
                if (w == 0) {
                    o.put("active", if (isActive) "" else p.optString("n"))
                    pwrite(a, o)
                    projects(a, col)
                } else if (w == 1) {
                    editor(a, col, idx)
                } else {
                    items.remove(idx)
                    o.put("items", items)
                    if (isActive) o.put("active", "")
                    pwrite(a, o)
                    projects(a, col)
                }
            }.show()
    }

    // ---------- online help setup (taps instead of typed commands) ----------
    private fun pill(a: Activity, label: String, onClick: () -> Unit): TextView {
        val t = TextView(a)
        t.text = label
        t.textSize = 13f
        t.setTextColor(CYAN)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(a, 8), dp(a, 10), dp(a, 8), dp(a, 10))
        val g = GradientDrawable()
        g.cornerRadius = dp(a, 20).toFloat()
        g.setColor(0x1A00E5FF)
        g.setStroke(dp(a, 1), 0x8800E5FF.toInt())
        t.background = g
        pressFx(t)
        t.setOnClickListener { onClick() }
        return t
    }

    private fun pillRow(a: Activity, c: LinearLayout, vararg items: Pair<String, () -> Unit>) {
        val row = LinearLayout(a)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, dp(a, 10), 0, 0)
        for ((i, item) in items.withIndex()) {
            val lp = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            if (i > 0) lp.leftMargin = dp(a, 8)
            row.addView(pill(a, item.first, item.second), lp)
        }
        c.addView(row)
    }

    private fun setup(a: Activity, col: LinearLayout) {
        col.removeAllViews()
        heading(a, col, "Online help", "Optional. SAANN works fully offline. Connect these for harder questions, photos, PDFs and drawing.")
        val mode = txt(a, col, "Checking...", 12f, DIM)
        val out = card(a, col)
        val outText = txt(a, out, "Tap a button below. Instructions and results show here.", 13f, INK)
        val names = listOf(Triple("groq", "Groq", "Fast replies, streamed as they are written"),
            Triple("gemini", "Google Gemini", "Stronger answers, reads photos and PDFs"),
            Triple("cloudflare", "Cloudflare", "Draws images, backup photo reader"))
        val stat = HashMap<String, TextView>()
        fun call(cmd: String, after: Boolean) {
            outText.text = "Working..."
            Thread {
                val r = try {
                    Host.py?.callAttr("respond", cmd)?.toString() ?: "SAANN is still starting."
                } catch (e: Throwable) {
                    "error: " + (e.message ?: "unknown")
                }
                a.runOnUiThread {
                    outText.text = r
                    if (after) refresh(a, mode, stat)
                }
            }.start()
        }
        for (n in names) {
            val c = card(a, col)
            txt(a, c, n.second, 16f, INK, true)
            txt(a, c, n.third, 12f, DIM)
            stat[n.first] = txt(a, c, "", 12f, CYAN)
            pillRow(a, c, Pair("1  Open site") { call("/setup " + n.first + " open", false) },
                Pair("2  Paste key") { call("/key " + n.first + " paste", true) })
        }
        val c = card(a, col)
        txt(a, c, "Use online help", 16f, INK, true)
        txt(a, c, "Private things (screen, clipboard, location, texts) never go online.", 12f, DIM)
        pillRow(a, c, Pair("Turn on") { call("/cloud on", true) }, Pair("Turn off") { call("/cloud off", true) },
            Pair("Test") { call("/cloud test", false) })
        refresh(a, mode, stat)
    }

    private fun refresh(a: Activity, mode: TextView, stat: Map<String, TextView>) {
        Thread {
            val r = try { Host.py?.callAttr("respond", "/cloud")?.toString() ?: "" } catch (e: Throwable) { "" }
            a.runOnUiThread {
                if (!r.contains("Mode:")) {
                    mode.text = "Online help isn't installed in this version yet."
                    return@runOnUiThread
                }
                mode.text = r.lineSequence().firstOrNull { it.startsWith("Mode:") } ?: ""
                for ((id, tv) in stat) {
                    val line = r.lineSequence().firstOrNull { it.lowercase().startsWith(id) || it.lowercase().startsWith("google " + id) }
                    tv.text = if (line == null) "" else if (line.contains("not connected")) "Not connected" else "Connected · " + line.substringAfter(": ")
                }
            }
        }.start()
    }
}
