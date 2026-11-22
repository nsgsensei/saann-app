package com.saann.app

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {
    companion object {
        @Volatile var onStatus: ((String) -> Unit)? = null
        private const val BG = 0xFF05090F.toInt()
        private const val PANEL = 0xFF0B1620.toInt()
        private const val CYAN = 0xFF00E5FF.toInt()
        private const val INK = 0xFFE8FBFF.toInt()
        private const val DIM = 0xFF6FD6E6.toInt()
        private const val BODY = 0xFFCFEFF7.toInt()
        private const val BAD = 0xFFFF8A80.toInt()
    }

    // The "what I am doing" card: steps, plus a live picture of the screen.
    private class Card(
        val box: LinearLayout,
        val head: TextView,
        val body: LinearLayout,
        val img: ImageView,
        val cap: TextView
    ) {
        var steps = 0
        var open = true
        var done = false
        var shot: Bitmap? = null
        val lines = ArrayList<TextView>()

        fun refresh() {
            head.text = if (done) {
                (if (open) "▾ " else "▸ ") + steps + (if (steps == 1) " step" else " steps") +
                    (if (open) " · hide" else " · show")
            } else {
                "Working · step " + steps
            }
            body.visibility = if (open) View.VISIBLE else View.GONE
            img.visibility = if (open && shot != null) View.VISIBLE else View.GONE
            cap.visibility = if (open && cap.text.isNotEmpty()) View.VISIBLE else View.GONE
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var orb: OrbView
    private lateinit var brain: BrainView
    private lateinit var status: TextView
    private lateinit var jump: TextView
    private lateinit var py: PyObject
    private var ready = false
    private var busy = false
    private var typing: TextView? = null
    private var dotStep = 0
    private var card: Card? = null
    private var lastCard: Card? = null
    private val hist = JSONArray()
    private val dotTask = object : Runnable {
        override fun run() {
            val t = typing ?: return
            dotStep = (dotStep + 1) % 4
            t.text = "thinking" + ".".repeat(dotStep)
            ui.postDelayed(this, 350)
        }
    }

    private val keep = setOf(
        "config/mind.json", "config/personality.json",
        "config/license.json", "config/models.json"
    )

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)
        root.fitsSystemWindows = true

        // header
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(dp(16), dp(10), dp(16), dp(10))
        orb = OrbView(this)
        head.addView(orb, LinearLayout.LayoutParams(dp(46), dp(46)))
        orb.setOnClickListener { toggleBrain() }
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), 0, 0, 0)
        val title = TextView(this)
        title.text = "SAANN"
        title.setTextColor(INK)
        title.textSize = 18f
        title.letterSpacing = 0.25f
        title.setTypeface(title.typeface, Typeface.BOLD)
        status = TextView(this)
        status.setTextColor(DIM)
        status.textSize = 12f
        status.maxLines = 1
        status.text = "Starting up..."
        col.addView(title)
        col.addView(status)
        head.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        root.addView(head)

        val line = View(this)
        line.setBackgroundColor(0x4400E5FF)
        root.addView(line, LinearLayout.LayoutParams(MATCH_PARENT, dp(1)))
        brain = BrainView(this)
        brain.visibility = View.GONE
        root.addView(brain, LinearLayout.LayoutParams(MATCH_PARENT, dp(240)))

        // messages, with a "jump to latest" button
        val holder = FrameLayout(this)
        scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = true
        scroll.isScrollbarFadingEnabled = true
        scroll.overScrollMode = View.OVER_SCROLL_ALWAYS
        chat = LinearLayout(this)
        chat.orientation = LinearLayout.VERTICAL
        chat.setPadding(dp(12), dp(12), dp(12), dp(12))
        scroll.addView(chat)
        holder.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        jump = TextView(this)
        jump.text = "↓"
        jump.gravity = Gravity.CENTER
        jump.textSize = 20f
        jump.setTextColor(BG)
        val jb = GradientDrawable()
        jb.shape = GradientDrawable.OVAL
        jb.setColor(CYAN)
        jump.background = jb
        jump.alpha = 0.92f
        jump.visibility = View.GONE
        jump.setOnClickListener {
            scrollDown()
            jump.visibility = View.GONE
        }
        val jl = FrameLayout.LayoutParams(dp(44), dp(44))
        jl.gravity = Gravity.BOTTOM or Gravity.END
        jl.setMargins(0, 0, dp(14), dp(12))
        holder.addView(jump, jl)
        root.addView(holder, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        scroll.viewTreeObserver.addOnScrollChangedListener {
            jump.visibility = if (nearBottom()) View.GONE else View.VISIBLE
        }

        // input
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(dp(10), dp(8), dp(10), dp(10))
        input = EditText(this)
        input.hint = "Talk to SAANN"
        input.setHintTextColor(0x886FD6E6.toInt())
        input.setTextColor(INK)
        input.textSize = 15f
        input.maxLines = 4
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
        input.setPadding(dp(16), dp(10), dp(16), dp(10))
        val ib = GradientDrawable()
        ib.cornerRadius = dp(22).toFloat()
        ib.setColor(PANEL)
        ib.setStroke(dp(1), 0x6600E5FF)
        input.background = ib
        bar.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        send = Button(this)
        send.text = "Send"
        send.setAllCaps(false)
        send.setTextColor(BG)
        send.textSize = 14f
        val sb = GradientDrawable()
        sb.cornerRadius = dp(22).toFloat()
        sb.setColor(CYAN)
        send.background = sb
        send.minHeight = 0
        send.minimumHeight = 0
        send.minWidth = 0
        send.minimumWidth = 0
        send.setPadding(dp(18), dp(11), dp(18), dp(11))
        send.isEnabled = false
        send.alpha = 0.4f
        val sl = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        sl.leftMargin = dp(8)
        bar.addView(send, sl)
        root.addView(bar)
        setContentView(root)

        loadHistory()
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        onStatus = { s -> runOnUiThread { setStatus(s); brain.pulse(s) } }
        Live.onStep = { phase, tool, args, ok, res ->
            runOnUiThread { handleStep(phase, tool, args, ok, res) }
        }
        Preview.listener = { b, note -> runOnUiThread { onPreview(b, note) } }

        Thread {
            try {
                copyDir("saann", File(filesDir, "saann"), "")
                if (!Python.isStarted()) Python.start(AndroidPlatform(this))
                py = Python.getInstance().getModule("app_main")
                py.callAttr("init", filesDir.absolutePath, Bridge(this))
                Host.py = py
                runOnUiThread {
                    ready = true
                    setBusy(false)
                    setStatus("Ready")
                    startBar()
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    setStatus("Error")
                    addMsg("error: " + e.toString(), false, false)
                }
            }
        }.start()

        send.setOnClickListener {
            val t = input.text.toString().trim()
            if (t.isEmpty() || !ready || busy) return@setOnClickListener
            input.setText("")
            if (t == "/clear") {
                clearChat()
                return@setOnClickListener
            }
            addMsg(t, true)
            if (t.startsWith("/bar")) {
                addMsg(BarPrefs.command(this, t), false, false)
                return@setOnClickListener
            }
            card = null
            setBusy(true)
            setStatus("Working...")
            showTyping()
            Host.respond(t) { r ->
                runOnUiThread {
                    hideTyping()
                    finishCard()
                    addMsg(r, false)
                    setBusy(false)
                    setStatus("Ready")
                }
            }
        }
    }

    override fun onDestroy() {
        onStatus = null
        Live.onStep = null
        Preview.listener = null
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun startBar() {
        addMsg(BarPrefs.start(this), false, false)
    }

    private fun toggleBrain() {
        if (brain.visibility == View.VISIBLE) {
            brain.visibility = View.GONE
            brain.stop()
        } else {
            brain.visibility = View.VISIBLE
            brain.start()
        }
    }

    private fun setBusy(b: Boolean) {
        busy = b
        orb.speed = if (b) 1.7f else 0.35f
        send.isEnabled = ready && !b
        send.alpha = if (ready && !b) 1f else 0.4f
    }

    private fun setStatus(s: String) {
        status.text = s.take(60)
    }

    // ---------- scrolling ----------
    private fun nearBottom(): Boolean {
        val c = scroll.getChildAt(0) ?: return true
        return scroll.scrollY + scroll.height >= c.height - dp(90)
    }

    private fun scrollDown() {
        scroll.post { scroll.smoothScrollBy(0, 1000000) }
    }

    // Puts a view in the chat. Stays at the bottom only if you were already there.
    private fun place(v: View, force: Boolean) {
        val stick = force || nearBottom()
        chat.addView(v)
        if (stick) scrollDown() else jump.visibility = View.VISIBLE
    }

    // ---------- messages ----------
    private fun bubble(text: String, mine: Boolean): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(if (mine) INK else BODY)
        tv.textSize = 15f
        tv.setTextIsSelectable(true)
        tv.maxWidth = (resources.displayMetrics.widthPixels * 0.82f).toInt()
        tv.setPadding(dp(14), dp(10), dp(14), dp(10))
        val r = dp(18).toFloat()
        val s = dp(4).toFloat()
        val g = GradientDrawable()
        g.cornerRadii = if (mine) floatArrayOf(r, r, r, r, s, s, r, r) else floatArrayOf(r, r, r, r, r, r, s, s)
        g.setColor(if (mine) 0x2200E5FF else PANEL)
        g.setStroke(dp(1), if (mine) 0xCC00B8D4.toInt() else 0x5500E5FF)
        tv.background = g
        val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        lp.gravity = if (mine) Gravity.END else Gravity.START
        lp.topMargin = dp(5)
        lp.bottomMargin = dp(5)
        tv.layoutParams = lp
        return tv
    }

    private fun addMsg(text: String, mine: Boolean, save: Boolean = true) {
        place(bubble(text, mine), mine)
        if (save) remember(text, mine)
    }

    private fun showTyping() {
        val t = bubble("thinking", false)
        t.alpha = 0.75f
        place(t, true)
        typing = t
        dotStep = 0
        ui.postDelayed(dotTask, 350)
    }

    private fun hideTyping() {
        ui.removeCallbacks(dotTask)
        typing?.let { chat.removeView(it) }
        typing = null
    }

    // ---------- chat history (kept only on this phone) ----------
    private fun histFile(): File = File(filesDir, "saann/data/chat.json")

    private fun loadHistory() {
        try {
            val f = histFile()
            if (!f.exists()) return
            val a = JSONArray(f.readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                chat.addView(bubble(o.getString("t"), o.getBoolean("m")))
                hist.put(o)
            }
            scrollDown()
        } catch (e: Exception) {
        }
    }

    private fun remember(text: String, mine: Boolean) {
        try {
            hist.put(JSONObject().put("m", mine).put("t", text.take(4000)))
            while (hist.length() > 100) hist.remove(0)
            val f = histFile()
            f.parentFile?.mkdirs()
            f.writeText(hist.toString())
        } catch (e: Exception) {
        }
    }

    private fun clearChat() {
        chat.removeAllViews()
        while (hist.length() > 0) hist.remove(0)
        try { histFile().delete() } catch (e: Exception) { }
        card = null
        lastCard = null
        typing = null
        addMsg("Chat cleared.", false, false)
    }

    // ---------- what SAANN is doing ----------
    private fun newCard(): Card {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(12), dp(10), dp(12), dp(10))
        val g = GradientDrawable()
        g.cornerRadius = dp(16).toFloat()
        g.setColor(PANEL)
        g.setStroke(dp(1), 0x5500E5FF)
        box.background = g
        val head = TextView(this)
        head.setTextColor(DIM)
        head.textSize = 12f
        head.setTypeface(head.typeface, Typeface.BOLD)
        val img = ImageView(this)
        img.adjustViewBounds = true
        img.maxHeight = dp(220)
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        img.setPadding(0, dp(8), 0, dp(4))
        img.visibility = View.GONE
        val cap = TextView(this)
        cap.setTextColor(0x996FD6E6.toInt())
        cap.textSize = 11f
        cap.visibility = View.GONE
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL
        box.addView(head)
        box.addView(img, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        box.addView(cap)
        box.addView(body)
        val lp = LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.82f).toInt(), WRAP_CONTENT)
        lp.gravity = Gravity.START
        lp.topMargin = dp(5)
        lp.bottomMargin = dp(5)
        box.layoutParams = lp
        val c = Card(box, head, body, img, cap)
        head.setOnClickListener {
            if (c.done) {
                c.open = !c.open
                c.refresh()
            }
        }
        img.setOnClickListener { c.shot?.let { b -> showFull(b) } }
        c.refresh()
        place(box, false)
        return c
    }

    private fun handleStep(phase: String, tool: String, args: String, ok: Boolean, result: String) {
        brain.pulse(tool)
        if (typing != null) hideTyping()
        val c = card ?: newCard().also {
            card = it
            lastCard = it
        }
        val stick = nearBottom()
        val a = args.trim('{', '}').replace("\"", "").take(60)
        val label = tool + (if (a.isEmpty()) "" else "  " + a)
        if (phase == "start") {
            c.steps++
            val tv = TextView(this)
            tv.textSize = 12f
            tv.setTextColor(BODY)
            tv.setPadding(0, dp(4), 0, dp(2))
            tv.text = "● " + label
            c.body.addView(tv)
            c.lines.add(tv)
            if (c.lines.size > 8) c.body.removeView(c.lines.removeAt(0))
        } else {
            val tv = c.lines.lastOrNull()
            if (tv != null) {
                val r = result.replace("\n", " ").take(140)
                tv.text = (if (ok) "✓ " else "✗ ") + label + (if (r.isEmpty()) "" else "\n    " + r)
                tv.setTextColor(if (ok) BODY else BAD)
            }
            if (ok && tool == "screen_shot") showNewestShot()
        }
        c.refresh()
        if (stick) scrollDown()
    }

    private fun finishCard() {
        val c = card ?: return
        if (c.steps == 0) {
            chat.removeView(c.box)
        } else {
            c.done = true
            c.open = false
            c.refresh()
        }
        card = null
    }

    private fun onPreview(b: Bitmap?, note: String) {
        val c = card ?: lastCard ?: return
        val stick = nearBottom()
        c.shot = b
        c.img.setImageBitmap(b)
        c.cap.text = note.take(60)
        c.refresh()
        if (stick) scrollDown()
    }

    // ---------- pictures ----------
    private fun decode(path: String, maxW: Int): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(path, o)
        var s = 1
        while (o.outWidth / s > maxW * 2) s *= 2
        val o2 = BitmapFactory.Options()
        o2.inSampleSize = s
        return BitmapFactory.decodeFile(path, o2)
    }

    private fun showNewestShot() {
        val dir = File(filesDir, "saann/data/shots")
        val f = dir.listFiles()?.maxByOrNull { it.lastModified() } ?: return
        val b = decode(f.absolutePath, 720) ?: return
        val iv = ImageView(this)
        iv.setImageBitmap(b)
        iv.adjustViewBounds = true
        iv.maxHeight = dp(360)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.setPadding(dp(3), dp(3), dp(3), dp(3))
        val g = GradientDrawable()
        g.cornerRadius = dp(14).toFloat()
        g.setColor(PANEL)
        g.setStroke(dp(1), 0x5500E5FF)
        iv.background = g
        iv.setOnClickListener { decode(f.absolutePath, 1440)?.let { big -> showFull(big) } }
        val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        lp.gravity = Gravity.START
        lp.topMargin = dp(5)
        lp.bottomMargin = dp(5)
        iv.layoutParams = lp
        place(iv, false)
    }

    private fun showFull(b: Bitmap) {
        val d = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val iv = ImageView(this)
        iv.setImageBitmap(b)
        iv.scaleType = ImageView.ScaleType.FIT_CENTER
        iv.setBackgroundColor(Color.BLACK)
        iv.setOnClickListener { d.dismiss() }
        d.setContentView(iv)
        d.show()
    }

    private fun copyDir(assetPath: String, dest: File, rel: String) {
        val list = assets.list(assetPath) ?: return
        if (list.isEmpty()) {
            if (rel in keep && dest.exists()) return
            dest.parentFile?.mkdirs()
            assets.open(assetPath).use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        } else {
            dest.mkdirs()
            for (n in list) {
                copyDir("$assetPath/$n", File(dest, n), if (rel.isEmpty()) n else "$rel/$n")
            }
        }
    }
}
