package com.saann.app

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ChatStore {
    class Item(val id: String, val title: String, val updated: Long, val preview: String)

    private fun dir(c: Context): File {
        val d = File(c.filesDir, "saann/data/chats")
        d.mkdirs()
        return d
    }

    private fun cur(c: Context) = File(dir(c), "current")
    private fun newId() = java.lang.Long.toString(System.currentTimeMillis(), 36)

    fun currentId(c: Context): String {
        try {
            val f = cur(c)
            if (f.exists()) {
                val s = f.readText().trim()
                if (s.isNotEmpty()) return s
            }
        } catch (e: Exception) {
        }
        val id = newId()
        try {
            val old = File(c.filesDir, "saann/data/chat.json")
            if (old.exists()) {
                old.copyTo(File(dir(c), id + ".json"), true)
                old.delete()
            }
            cur(c).writeText(id)
        } catch (e: Exception) {
        }
        return id
    }

    fun currentFile(c: Context): File = File(dir(c), currentId(c) + ".json")

    fun setCurrent(c: Context, id: String) {
        try { cur(c).writeText(id) } catch (e: Exception) { }
    }

    fun create(c: Context): String {
        val id = newId()
        setCurrent(c, id)
        val all = list(c)
        for (i in 60 until all.size) delete(c, all[i].id)
        return id
    }

    fun delete(c: Context, id: String) {
        try { File(dir(c), id + ".json").delete() } catch (e: Exception) { }
    }

    private fun tidy(s: String) = s.replace(Regex("\\s+"), " ").trim()

    fun list(c: Context): List<Item> {
        val out = ArrayList<Item>()
        val fs = dir(c).listFiles { f -> f.name.endsWith(".json") } ?: return out
        for (f in fs) {
            try {
                val arr = JSONArray(f.readText())
                if (arr.length() == 0) continue
                var title = ""
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.getBoolean("m")) {
                        title = o.getString("t")
                        break
                    }
                }
                if (title.isEmpty()) title = arr.getJSONObject(0).getString("t")
                val last = arr.getJSONObject(arr.length() - 1).getString("t")
                out.add(Item(f.name.removeSuffix(".json"), tidy(title).take(60), f.lastModified(), tidy(last).take(140)))
            } catch (e: Exception) {
            }
        }
        return out.sortedByDescending { it.updated }
    }

    fun rel(ts: Long): String {
        val d = System.currentTimeMillis() - ts
        return when {
            d < 60000L -> "Just now"
            d < 3600000L -> (d / 60000L).toString() + " min ago"
            d < 86400000L -> (d / 3600000L).toString() + " h ago"
            d < 172800000L -> "Yesterday"
            else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(ts))
        }
    }
}

object Recents {
    var onSettings: (() -> Unit)? = null
    private const val PANEL = 0xFF14171C.toInt()
    private const val SEL = 0xFF232830.toInt()
    private const val INK = 0xFFFAF7F2.toInt()
    private const val DIM = 0xFFB8ADA5.toInt()
    private const val CYAN = 0xFFFF7A18.toInt()

    fun show(a: Activity, onOpen: (String) -> Unit, onNew: () -> Unit, onGone: (String) -> Unit) {
        val den = a.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * den).toInt()
        val d = Dialog(a)
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = LinearLayout(a)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(PANEL)
        root.setPadding(dp(14), dp(26), dp(14), dp(10))
        val top = LinearLayout(a)
        top.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(a)
        title.text = "Recents"
        title.setTextColor(INK)
        title.textSize = 22f
        title.setTypeface(title.typeface, Typeface.BOLD)
        top.addView(title, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val gear = IconView(a, "gear", DIM)
        gear.setOnClickListener {
            d.dismiss()
            onSettings?.invoke()
        }
        top.addView(gear, LinearLayout.LayoutParams(dp(34), dp(34)))
        val x = IconView(a, "close", DIM)
        x.setOnClickListener { d.dismiss() }
        top.addView(x, LinearLayout.LayoutParams(dp(34), dp(34)))
        root.addView(top)
        val pill = LinearLayout(a)
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.setPadding(dp(14), dp(10), dp(14), dp(10))
        val pg = GradientDrawable()
        pg.cornerRadius = dp(22).toFloat()
        pg.setColor(SEL)
        pill.background = pg
        pill.addView(IconView(a, "plus", CYAN), LinearLayout.LayoutParams(dp(22), dp(22)))
        val pt = TextView(a)
        pt.text = "New chat"
        pt.setTextColor(INK)
        pt.textSize = 15f
        pt.setPadding(dp(10), 0, 0, 0)
        pill.addView(pt)
        pill.setOnClickListener {
            d.dismiss()
            onNew()
        }
        val plp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        plp.topMargin = dp(14)
        plp.bottomMargin = dp(12)
        root.addView(pill, plp)
        val lab = TextView(a)
        lab.text = "RECENT"
        lab.setTextColor(DIM)
        lab.textSize = 11f
        lab.letterSpacing = 0.15f
        lab.setPadding(dp(4), 0, 0, dp(6))
        root.addView(lab)
        val sv = ScrollView(a)
        val list = LinearLayout(a)
        list.orientation = LinearLayout.VERTICAL
        sv.addView(list)
        root.addView(sv, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        fun fill() {
            list.removeAllViews()
            val now = ChatStore.currentId(a)
            val items = ChatStore.list(a)
            if (items.isEmpty()) {
                val e = TextView(a)
                e.text = "No chats yet. Say hello!"
                e.setTextColor(DIM)
                e.textSize = 14f
                e.setPadding(dp(4), dp(8), 0, 0)
                list.addView(e)
            }
            for (ch in items) {
                val row = LinearLayout(a)
                row.orientation = LinearLayout.VERTICAL
                row.setPadding(dp(12), dp(10), dp(12), dp(10))
                if (ch.id == now) {
                    val g = GradientDrawable()
                    g.cornerRadius = dp(14).toFloat()
                    g.setColor(SEL)
                    row.background = g
                }
                val t = TextView(a)
                t.text = ch.title
                t.setTextColor(INK)
                t.textSize = 15f
                t.maxLines = 1
                t.ellipsize = TextUtils.TruncateAt.END
                if (ch.id == now) t.setTypeface(t.typeface, Typeface.BOLD)
                row.addView(t)
                val p = TextView(a)
                p.text = ch.preview
                p.setTextColor(DIM)
                p.textSize = 13f
                p.maxLines = 2
                p.ellipsize = TextUtils.TruncateAt.END
                row.addView(p)
                val tm = TextView(a)
                tm.text = ChatStore.rel(ch.updated)
                tm.setTextColor(0xFF69707D.toInt())
                tm.textSize = 11f
                tm.setPadding(0, dp(3), 0, 0)
                row.addView(tm)
                val id = ch.id
                row.setOnClickListener {
                    d.dismiss()
                    onOpen(id)
                }
                row.setOnLongClickListener {
                    AlertDialog.Builder(a).setMessage("Delete this chat?")
                        .setPositiveButton("Delete") { _, _ ->
                            ChatStore.delete(a, id)
                            onGone(id)
                            fill()
                        }.setNegativeButton("Cancel", null).show()
                    true
                }
                val lp = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
                lp.bottomMargin = dp(2)
                list.addView(row, lp)
            }
        }
        fill()
        d.setContentView(root)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.window?.setLayout((a.resources.displayMetrics.widthPixels * 0.86f).toInt(), MATCH_PARENT)
        d.window?.setGravity(Gravity.START)
        d.show()
    }

    fun library(a: Activity) {
        val den = a.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * den).toInt()
        val (d, box) = Ui2.sheet(a)
        val t = TextView(a)
        t.text = "LIBRARY"
        t.setTextColor(INK)
        t.textSize = 15f
        t.letterSpacing = 0.2f
        t.setTypeface(t.typeface, Typeface.BOLD)
        box.addView(t)
        val fs = File(a.filesDir, "saann/data/uploads").listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (fs.isEmpty()) {
            val e = TextView(a)
            e.text = "Nothing here yet. Files, photos and screenshots you attach will be listed here."
            e.setTextColor(DIM)
            e.textSize = 14f
            e.setPadding(0, dp(12), 0, 0)
            box.addView(e)
        } else {
            val sv = ScrollView(a)
            val col = LinearLayout(a)
            col.orientation = LinearLayout.VERTICAL
            sv.addView(col)
            for (f in fs.take(40)) {
                val row = LinearLayout(a)
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding(0, dp(8), 0, dp(8))
                val isImg = Previewer.kindOf(f.name) == "image"
                row.addView(IconView(a, if (isImg) "photo" else "file", CYAN, 0x1FFF7A18), LinearLayout.LayoutParams(dp(42), dp(42)))
                val tx = LinearLayout(a)
                tx.orientation = LinearLayout.VERTICAL
                tx.setPadding(dp(12), 0, 0, 0)
                val n = TextView(a)
                n.text = f.name.substringAfter('-')
                n.setTextColor(INK)
                n.textSize = 15f
                n.maxLines = 1
                n.ellipsize = TextUtils.TruncateAt.END
                tx.addView(n)
                val s = TextView(a)
                s.text = (f.length() / 1024).toString() + " KB · " + ChatStore.rel(f.lastModified())
                s.setTextColor(DIM)
                s.textSize = 12f
                tx.addView(s)
                row.addView(tx, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                row.setOnClickListener { Previewer.showFile(a, f) }
                col.addView(row)
            }
            box.addView(sv, LinearLayout.LayoutParams(MATCH_PARENT, dp(320)))
        }
        d.show()
    }
}

object Previewer {
    private const val BG = 0xFF080808.toInt()
    private const val INK = 0xFFFAF7F2.toInt()
    private const val DIM = 0xFFB8ADA5.toInt()
    private const val CYAN = 0xFFFF7A18.toInt()
    private val imgExt = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
    private val textExt = setOf("txt", "md", "csv", "json", "py", "kt", "log", "xml", "html", "js", "yaml", "yml", "ini")

    fun kindOf(name: String): String {
        val e = name.substringAfterLast('.', "").lowercase()
        return if (e in imgExt) "image" else if (e in textExt) "text" else "file"
    }

    private fun bitmap(path: String): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, o)
        var s = 1
        while (o.outWidth / s > 1600) s *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = s
        return BitmapFactory.decodeFile(path, o2)
    }

    fun showFile(a: Activity, f: File) {
        val k = kindOf(f.name)
        val txt = if (k == "text") try { f.readText().take(20000) } catch (e: Exception) { "" } else ""
        show(a, f.name.substringAfter('-'), f.absolutePath, k, txt, null)
    }

    fun show(a: Activity, name: String, path: String, kind: String, text: String, onRemove: (() -> Unit)?) {
        val den = a.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * den).toInt()
        val d = Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LinearLayout(a)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)
        root.fitsSystemWindows = true
        val top = LinearLayout(a)
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(dp(16), dp(12), dp(12), dp(12))
        val n = TextView(a)
        n.text = name
        n.setTextColor(INK)
        n.textSize = 15f
        n.maxLines = 1
        n.ellipsize = TextUtils.TruncateAt.END
        top.addView(n, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val x = IconView(a, "close", INK)
        x.setOnClickListener { d.dismiss() }
        top.addView(x, LinearLayout.LayoutParams(dp(36), dp(36)))
        root.addView(top)

        fun textView(s: String, mono: Boolean): ScrollView {
            val sv = ScrollView(a)
            val tv = TextView(a)
            tv.text = s
            tv.setTextColor(INK)
            tv.textSize = 13f
            tv.setTextIsSelectable(true)
            if (mono) tv.typeface = Typeface.MONOSPACE
            tv.setPadding(dp(16), dp(8), dp(16), dp(8))
            sv.addView(tv)
            return sv
        }

        if (kind == "image") {
            val bm = try { bitmap(path) } catch (e: Throwable) { null }
            if (bm != null) {
                val iv = ImageView(a)
                iv.setImageBitmap(bm)
                iv.scaleType = ImageView.ScaleType.FIT_CENTER
                root.addView(iv, LinearLayout.LayoutParams(MATCH_PARENT, 0, 3f))
            } else {
                root.addView(textView("Couldn't open this image.", false), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            }
            if (text.isNotBlank()) {
                val lab = TextView(a)
                lab.text = "TEXT FOUND ON IT"
                lab.setTextColor(DIM)
                lab.textSize = 11f
                lab.letterSpacing = 0.15f
                lab.setPadding(dp(16), dp(8), 0, 0)
                root.addView(lab)
                root.addView(textView(text, false), LinearLayout.LayoutParams(MATCH_PARENT, 0, 2f))
            }
        } else if (kind == "text") {
            root.addView(textView(text, true), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        } else {
            root.addView(textView("No preview for this type of file yet.\n\n" + name, false), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }

        val bar = LinearLayout(a)
        bar.gravity = Gravity.CENTER
        bar.setPadding(dp(12), dp(8), dp(12), dp(14))
        fun btn(label: String, onClick: () -> Unit) {
            val b = TextView(a)
            b.text = label
            b.setTextColor(INK)
            b.textSize = 14f
            b.gravity = Gravity.CENTER
            b.setPadding(dp(22), dp(11), dp(22), dp(11))
            val g = GradientDrawable()
            g.cornerRadius = dp(22).toFloat()
            g.setColor(0xFF151515.toInt())
            g.setStroke(dp(1), 0x33FFFFFF)
            b.background = g
            b.setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
            lp.leftMargin = dp(6)
            lp.rightMargin = dp(6)
            bar.addView(b, lp)
        }
        if (text.isNotBlank()) {
            btn("Copy text") {
                val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("SAANN", text))
                Toast.makeText(a, "Copied", Toast.LENGTH_SHORT).show()
            }
        }
        if (onRemove != null) {
            btn("Remove") {
                onRemove()
                d.dismiss()
            }
        }
        if (bar.childCount > 0) root.addView(bar)
        d.setContentView(root)
        d.show()
    }
}
