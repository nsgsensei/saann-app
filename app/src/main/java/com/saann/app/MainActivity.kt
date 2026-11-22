package com.saann.app

import android.Manifest
import android.app.Activity
import android.graphics.Color
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

class MainActivity : Activity() {
    companion object {
        @Volatile var onStatus: ((String) -> Unit)? = null
        private const val BG = 0xFF05090F.toInt()
        private const val PANEL = 0xFF0B1620.toInt()
        private const val CYAN = 0xFF00E5FF.toInt()
        private const val INK = 0xFFE8FBFF.toInt()
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var chat: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var orb: OrbView
    private lateinit var status: TextView
    private lateinit var py: PyObject
    private var ready = false
    private var busy = false
    private var typing: TextView? = null
    private var dotStep = 0
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
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), 0, 0, 0)
        val title = TextView(this)
        title.text = "SAANN"
        title.setTextColor(INK)
        title.textSize = 18f
        title.letterSpacing = 0.25f
        title.setTypeface(title.typeface, android.graphics.Typeface.BOLD)
        status = TextView(this)
        status.setTextColor(0xFF6FD6E6.toInt())
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

        // messages
        scroll = ScrollView(this)
        scroll.isVerticalScrollBarEnabled = false
        chat = LinearLayout(this)
        chat.orientation = LinearLayout.VERTICAL
        chat.setPadding(dp(12), dp(12), dp(12), dp(12))
        scroll.addView(chat)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

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

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        onStatus = { s -> runOnUiThread { setStatus(s) } }

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
                    addMsg("error: " + e.toString(), false)
                }
            }
        }.start()

        send.setOnClickListener {
            val t = input.text.toString().trim()
            if (t.isEmpty() || !ready || busy) return@setOnClickListener
            input.setText("")
            addMsg(t, true)
            if (t.startsWith("/bar")) {
                addMsg(BarPrefs.command(this, t), false)
                return@setOnClickListener
            }
            setBusy(true)
            setStatus("Working...")
            showTyping()
            Host.respond(t) { r ->
                runOnUiThread {
                    hideTyping()
                    addMsg(r, false)
                    setBusy(false)
                    setStatus("Ready")
                }
            }
        }
    }

    override fun onDestroy() {
        onStatus = null
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun startBar() {
        addMsg(BarPrefs.start(this), false)
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

    private fun bubble(text: String, mine: Boolean): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(if (mine) INK else 0xFFCFEFF7.toInt())
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

    private fun addMsg(text: String, mine: Boolean) {
        chat.addView(bubble(text, mine))
        scrollDown()
    }

    private fun showTyping() {
        val t = bubble("thinking", false)
        t.alpha = 0.75f
        chat.addView(t)
        typing = t
        dotStep = 0
        ui.postDelayed(dotTask, 350)
        scrollDown()
    }

    private fun hideTyping() {
        ui.removeCallbacks(dotTask)
        typing?.let { chat.removeView(it) }
        typing = null
    }

    private fun scrollDown() {
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
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
