package com.saann.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.widget.HorizontalScrollView
import android.widget.Toast
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
        private const val BG = 0xFF080808.toInt()
        private const val PANEL = 0xFF151515.toInt()
        private const val CYAN = 0xFFFF7A18.toInt()
        private const val INK = 0xFFFAF7F2.toInt()
        private const val DIM = 0xFFB8ADA5.toInt()
        private const val BODY = 0xFFEDE6DF.toInt()
        private const val BAD = 0xFFEF4444.toInt()
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
    private lateinit var send: View
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
    private val pending = ArrayList<Attachment>()
    private var camUri: android.net.Uri? = null
    private lateinit var chips: LinearLayout
    private val SAANN_ORANGE = 0xFFFF7A18.toInt()
    private var agentTv: TextView? = null
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
        head.setPadding(dp(10), dp(6), dp(10), dp(6))
        orb = OrbView(this)
        val menu = IconView(this, "menu", INK)
        menu.setOnClickListener { openRecents() }
        head.addView(menu, LinearLayout.LayoutParams(dp(34), dp(34)))
        head.addView(orb, LinearLayout.LayoutParams(dp(32), dp(32)))
        orb.setOnClickListener { toggleBrain() }
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), 0, 0, 0)
        val title = TextView(this)
        title.text = "SAANN"
        title.setTextColor(INK)
        title.textSize = 17f
        title.letterSpacing = 0.12f
        title.setTypeface(title.typeface, Typeface.BOLD)
        status = TextView(this)
        status.setTextColor(DIM)
        status.textSize = 12f
        status.maxLines = 1
        status.text = "Starting up..."
        col.addView(title)
        col.addView(status)
        head.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val det = TextView(this)
        det.text = "BRAIN ›"
        det.setTextColor(CYAN)
        det.textSize = 12f
        det.letterSpacing = 0.15f
        det.setPadding(dp(10), dp(8), dp(4), dp(8))
        det.setOnClickListener { BrainDetail.show(this) }
        val ag = TextView(this)
        ag.text = agentLabel()
        ag.setTextColor(if (AgentService.listening) SAANN_ORANGE else CYAN)
        ag.textSize = 12f
        ag.maxLines = 1
        ag.letterSpacing = 0.15f
        ag.setPadding(dp(8), dp(8), dp(4), dp(8))
        ag.setOnClickListener { cycleAgent() }
        agentTv = ag
        head.addView(ag)
        val setup = TextView(this)
        setup.text = "SETUP"
        setup.setTextColor(SAANN_ORANGE)
        setup.textSize = 12f
        setup.letterSpacing = 0.15f
        setup.setPadding(dp(10), dp(8), dp(4), dp(8))
        setup.setOnClickListener { Onboarding.show(this) }
        head.addView(setup)
        head.addView(det)
        root.addView(head)

        val line = View(this)
        line.setBackgroundColor(0x33FF7A18)
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

        // input: one row (+, text, mic, send) in a rounded card, chips on top
        val inCard = LinearLayout(this)
        inCard.orientation = LinearLayout.VERTICAL
        inCard.setPadding(dp(8), dp(6), dp(8), dp(6))
        val cg = GradientDrawable()
        cg.cornerRadius = dp(28).toFloat()
        cg.setColor(PANEL)
        cg.setStroke(dp(1), 0x55FF7A18)
        inCard.background = cg
        chips = LinearLayout(this)
        chips.orientation = LinearLayout.HORIZONTAL
        val chipScroll = HorizontalScrollView(this)
        chipScroll.isHorizontalScrollBarEnabled = false
        chipScroll.addView(chips)
        inCard.addView(chipScroll, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        input = EditText(this)
        input.hint = "Message SAANN"
        input.setHintTextColor(0x88B8ADA5.toInt())
        input.setTextColor(INK)
        input.textSize = 16f
        input.maxLines = 5
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
        input.background = null
        input.setPadding(dp(6), dp(8), dp(6), dp(8))
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val plus = IconView(this, "plus", CYAN)
        plus.setOnClickListener { attachMenu() }
        row.addView(plus, LinearLayout.LayoutParams(dp(40), dp(40)))
        row.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val mic = IconView(this, "mic", CYAN)
        mic.setOnClickListener { startVoice() }
        row.addView(mic, LinearLayout.LayoutParams(dp(40), dp(40)))
        val up = IconView(this, "wave", BG, CYAN)
        pressFx(plus)
        pressFx(mic)
        pressFx(up)
        send = up
        send.isEnabled = false
        send.alpha = 0.4f
        val lt = android.animation.LayoutTransition()
        lt.enableTransitionType(android.animation.LayoutTransition.CHANGING)
        inCard.layoutTransition = lt
        input.setOnFocusChangeListener { _, f ->
            val va = android.animation.ValueAnimator.ofObject(android.animation.ArgbEvaluator(),
                if (f) 0x33FFFFFF else 0xCCFF7A18.toInt(), if (f) 0xCCFF7A18.toInt() else 0x33FFFFFF)
            va.duration = 160
            va.addUpdateListener { cg.setStroke(dp(if (f) 2 else 1), it.animatedValue as Int) }
            va.start()
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { updateAction() }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        val slp = LinearLayout.LayoutParams(dp(38), dp(38))
        slp.leftMargin = dp(4)
        row.addView(send, slp)
        inCard.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val bar = FrameLayout(this)
        bar.setPadding(dp(10), dp(6), dp(10), dp(6))
        bar.addView(inCard, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        root.addView(Ui2.navBar(this), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(root)

        loadHistory()
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        ServerGuard.init(this)
        Voice.restore(this)
        Ui2.onChatTap = { openRecents() }
        Recents.onSettings = { Settings.show(this, { cycleAgent() }, { agentLabel() }) }
        ServerGuard.onStatus = { s -> runOnUiThread { setStatus(s) } }
        ServerGuard.kick()
        onStatus = { s -> runOnUiThread { setStatus(s); brain.pulse(s) } }
        Live.onStream = { s -> onStreamText(s) }
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
                // SAANN_CONNECTED_STARTUP_V1
                Onboarding.restore(this)
                if (!Onboarding.needed(this)) {
                    py.callAttr("setup_api", "finish", "{}")
                }
                runOnUiThread {
                    ready = true
                    setBusy(false)
                    setStatus("Ready")
                    startBar()
                    if (Onboarding.needed(this) && !isFinishing && !isDestroyed) {
                        Onboarding.show(this)
                    }
                }
            } catch (e: Throwable) {
                runOnUiThread {
                    setStatus("Error")
                    addMsg("error: " + e.toString(), false, false)
                }
            }
        }.start()

        send.setOnClickListener {
            val t0 = input.text.toString().trim()
            if (t0.isEmpty() && pending.isEmpty()) {
                if (ready && !busy) startVoice()
                return@setOnClickListener
            }
            if (!ready || busy) return@setOnClickListener
            input.clearFocus()
            val keyboard = getSystemService(
                android.content.Context.INPUT_METHOD_SERVICE
            ) as android.view.inputmethod.InputMethodManager
            keyboard.hideSoftInputFromWindow(input.windowToken, 0)
            scroll.postDelayed({ scrollDown() }, 180)
            send.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            if (pending.any { !it.ready }) {
                Toast.makeText(this, "Still reading the attachment...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val t = if (pending.isEmpty()) t0 else Attach.compose(t0, pending)
            val shown = if (pending.isEmpty()) t0 else (if (t0.isEmpty()) "" else t0 + "\n") + "📎 " + pending.joinToString(", ") { it.name }
            val outgoingAttachments = pending.toList()
            pending.clear()
            refreshChips()
            input.setText("")
            if (t.startsWith("/voice")) {
                addMsg(t, true, false)
                addMsg(Voice.command(this, t.removePrefix("/voice")), false, false)
                return@setOnClickListener
            }
            if (t.startsWith("/bg ")) {
                addMsg(t, true)
                AgentService.run(this, t.drop(4).trim())
                addMsg("On it in the background. I'll notify you when it's done.", false, false)
                return@setOnClickListener
            }
            if (t == "/clear") {
                clearChat()
                return@setOnClickListener
            }
            addMsg(shown, true)
            rememberAttachments(outgoingAttachments)
            for (attachment in outgoingAttachments) {
                val preview = attachmentCard(attachment, false)
                val lp = preview.layoutParams as LinearLayout.LayoutParams
                lp.gravity = Gravity.END
                preview.layoutParams = lp
                place(preview, true)
            }
            if (t.startsWith("/bar")) {
                addMsg(BarPrefs.command(this, t), false, false)
                return@setOnClickListener
            }
            card = null
            setBusy(true)
            setStatus("Working...")
            showTyping()
            // SAANN_SESSION_SEND_V6
            val requestSessionId = ChatStore.currentId(this)
            val requestHistory = hist.toString()
            Host.respondChat(t, requestSessionId, requestHistory) { r ->
                runOnUiThread {
                    hideTyping()
                    finishCard()
                    val spoken = r.replace(Regex("\\[image: .+?\\]"), "").trim()
                    addReply(r)
                    VoiceWindow.onReply(spoken)
                    AgentService.notifyIfAway(this, spoken)
                    Voice.speak(spoken)
                    setBusy(false)
                    setStatus("Ready")
                }
            }
        }
    }

    // streaming: words appear in the thinking bubble as they are written
    private fun onStreamText(s: String) {
        runOnUiThread {
            typing?.let {
                ui.removeCallbacks(dotTask)
                it.alpha = 1f
                it.text = s
                if (nearBottom()) scrollDown()
            }
        }
    }

    // a reply may carry "[image: /path]" lines (generated pictures); show them inline
    private fun addReply(r: String) {
        val rx = Regex("\\[image: (.+?)\\]")
        val paths = rx.findAll(r).map { it.groupValues[1] }.toList()
        val clean = r.replace(rx, "")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

        if (clean.isNotEmpty() || paths.isNotEmpty()) {
            addMsg(
                if (clean.isNotEmpty()) clean else "Generated image",
                false
            )

            if (paths.isNotEmpty() && hist.length() > 0) {
                val images = JSONArray()
                for (path in paths) images.put(path)
                hist.optJSONObject(hist.length() - 1)?.put("images", images)
                saveHistoryMetadata()
            }
        }

        for (path in paths) addImage(path)
    }

    private fun addImage(path: String) {
        val o = android.graphics.BitmapFactory.Options()
        o.inJustDecodeBounds = true
        android.graphics.BitmapFactory.decodeFile(path, o)
        var s = 1
        while (o.outWidth / s > 1200) s *= 2
        val o2 = android.graphics.BitmapFactory.Options()
        o2.inSampleSize = s
        val bm = android.graphics.BitmapFactory.decodeFile(path, o2) ?: return
        val iv = android.widget.ImageView(this)
        iv.setImageBitmap(bm)
        iv.adjustViewBounds = true
        iv.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        val lp = LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * 0.78f).toInt(), WRAP_CONTENT)
        lp.gravity = Gravity.START
        lp.topMargin = dp(5)
        lp.bottomMargin = dp(5)
        iv.layoutParams = lp
        iv.setOnClickListener { Previewer.showFile(this, File(path)) }
        place(iv, true)
    }

    private fun agentLabel(): String =
        if (!AgentService.running) "AGENT OFF" else if (AgentService.listening) "AGENT LIVE" else "AGENT ON"

    private fun cycleAgent() {
        if (!AgentService.running) {
            AgentService.start(this, false)
        } else if (!AgentService.listening) {
            if (Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 8)
                return
            }
            AgentService.start(this, true)
        } else {
            AgentService.stop(this)
        }
        ui.postDelayed({ agentTv?.text = agentLabel() }, 700)
    }

    override fun onResume() {
        super.onResume()
        AgentService.appVisible = true
        AgentService.runDue(this)
        AgentService.onReply = { q, r ->
            runOnUiThread {
                if (q.isNotEmpty()) addMsg(q, true)
                addMsg(r, false)
            }
        }
        agentTv?.text = agentLabel()
        for (m in AgentService.drain()) {
            if (m.first.isNotEmpty()) addMsg(m.first, true)
            addMsg(m.second, false)
        }
    }

    override fun onPause() {
        VoiceWindow.dismiss()
        AgentService.appVisible = false
        super.onPause()
    }

    override fun onDestroy() {
        VoiceWindow.dismiss()
        onStatus = null
        Live.onStep = null
        Live.onStream = null
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

    private fun updateAction() {
        val v = send as? IconView ?: return
        val want = if (input.text.isNotEmpty() || pending.isNotEmpty()) "up" else "wave"
        if (v.kind != want) {
            v.kind = want
            v.invalidate()
            v.animate().scaleX(1.18f).scaleY(1.18f).setDuration(90).withEndAction {
                v.animate().scaleX(1f).scaleY(1f).setDuration(140)
                    .setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
            }.start()
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
        val a0 = v.alpha
        v.alpha = 0f
        v.translationY = dp(12).toFloat()
        chat.addView(v)
        v.animate().alpha(a0).translationY(0f).setDuration(220)
            .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        if (stick) scrollDown() else jump.visibility = View.VISIBLE
    }

    // ---------- messages ----------
    // SAANN_CHAT_EXPERIENCE_V3
    private fun bubble(text: String, mine: Boolean): TextView {
        val tv = TextView(this)
        tv.text = text
        tv.setTextColor(if (mine) INK else BODY)
        tv.textSize = 16f
        tv.setLineSpacing(dp(3).toFloat(), 1.05f)
        tv.setTextIsSelectable(true)

        val lp = LinearLayout.LayoutParams(
            if (mine) WRAP_CONTENT else MATCH_PARENT,
            WRAP_CONTENT
        )
        lp.gravity = if (mine) Gravity.END else Gravity.START
        lp.topMargin = dp(if (mine) 10 else 14)
        lp.bottomMargin = dp(4)

        if (mine) {
            tv.maxWidth = (resources.displayMetrics.widthPixels * 0.86f).toInt()
            tv.setPadding(dp(14), dp(10), dp(14), dp(10))
            tv.background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xFF241A15.toInt())
                setStroke(dp(1), 0x55FF7A18)
            }
        } else {
            tv.setPadding(dp(4), dp(4), dp(4), dp(8))
            tv.background = null
        }
        tv.layoutParams = lp
        // SAANN_REPLY_STYLE_V10
        if (!mine) ReplyStyle.apply(tv, text)
        return tv
    }

    private fun addMsg(text: String, mine: Boolean, save: Boolean = true) {
        place(bubble(text, mine), mine)
        place(actions(text, mine), mine)
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
    private fun histFile(): File = ChatStore.currentFile(this)

    private fun loadHistory() {
        try {
            val f = histFile()
            if (!f.exists()) return
            val a = JSONArray(f.readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                chat.addView(bubble(o.getString("t"), o.getBoolean("m")))
                chat.addView(actions(o.getString("t"), o.getBoolean("m")))
                restoreRichItems(o)
                hist.put(o)
            }
            scrollDown()
        } catch (e: Exception) {
        }
    }

    // SAANN_RICH_HISTORY_V10
    private fun saveHistoryMetadata() {
        try {
            val f = histFile()
            f.parentFile?.mkdirs()
            f.writeText(hist.toString())
        } catch (_: Exception) {
            Toast.makeText(
                this, "Could not save attachment history",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun rememberAttachments(list: List<Attachment>) {
        if (list.isEmpty() || hist.length() == 0) return
        val item = hist.optJSONObject(hist.length() - 1) ?: return
        if (!item.optBoolean("m")) return

        val records = JSONArray()
        for (a in list) {
            records.put(
                JSONObject()
                    .put("name", a.name)
                    .put("path", a.path)
                    .put("kind", a.kind)
            )
        }
        item.put("attachments", records)
        saveHistoryMetadata()
    }

    private fun restoreRichItems(item: JSONObject) {
        val records = item.optJSONArray("attachments")
        if (records != null) {
            for (i in 0 until records.length()) {
                val record = records.optJSONObject(i) ?: continue
                val path = record.optString("path")
                if (!File(path).isFile) {
                    val missing = TextView(this)
                    missing.text = "Attachment unavailable: " +
                        record.optString("name")
                    missing.setTextColor(DIM)
                    missing.textSize = 12f
                    chat.addView(missing)
                    continue
                }

                val attachment = Attachment(
                    record.optString("name"),
                    path,
                    record.optString("kind", "file")
                )
                attachment.ready = true
                val preview = attachmentCard(attachment, false)
                val lp = preview.layoutParams as LinearLayout.LayoutParams
                lp.gravity = Gravity.END
                preview.layoutParams = lp
                chat.addView(preview)
            }
        }

        val images = item.optJSONArray("images")
        if (images != null) {
            for (i in 0 until images.length()) {
                val path = images.optString(i)
                if (File(path).isFile) addImage(path)
            }
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

    private fun openRecents() {
        Recents.show(this, { id -> openChat(id) }, { newChat() }, { id -> if (id == ChatStore.currentId(this)) newChat() })
    }

    private fun resetChatViews() {
        chat.removeAllViews()
        while (hist.length() > 0) hist.remove(0)
        card = null
        lastCard = null
        typing = null
    }

    private fun openChat(id: String) {
        if (busy) {
            Toast.makeText(this, "Wait for the reply to finish first", Toast.LENGTH_SHORT).show()
            return
        }
        ChatStore.setCurrent(this, id)
        resetChatViews()
        loadHistory()
        scrollDown()
    }

    private fun newChat() {
        if (busy) {
            Toast.makeText(this, "Wait for the reply to finish first", Toast.LENGTH_SHORT).show()
            return
        }
        ChatStore.create(this)
        resetChatViews()
        addMsg("New chat. What's on your mind?", false, false)
    }

    private fun actions(text: String, mine: Boolean): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val lp = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        lp.gravity = if (mine) Gravity.END else Gravity.START
        lp.leftMargin = dp(6)
        lp.rightMargin = dp(6)
        row.layoutParams = lp
        val cp = IconView(this, "copy", DIM)
        cp.setOnClickListener {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("SAANN", text))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }
        row.addView(cp, LinearLayout.LayoutParams(dp(30), dp(26)))
        if (!mine) {
            val sp = IconView(this, "speaker", DIM)
            sp.setOnClickListener { Voice.speakThen(this, text) { } }
            row.addView(sp, LinearLayout.LayoutParams(dp(30), dp(26)))
        }
        return row
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
        g.setStroke(dp(1), 0x33FFFFFF)
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
        cap.setTextColor(0x99B8ADA5.toInt())
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
        g.setStroke(dp(1), 0x33FFFFFF)
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

    private fun startVoice() {
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7)
            return
        }
        VoiceWindow.show(this) { t ->
            if (!ready || busy) {
                false
            } else {
                input.setText(t)
                send.performClick()
                true
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 || requestCode == 9) ServerGuard.kick()
        if (requestCode == 8 && grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) AgentService.start(this, true)
        if (requestCode == 7 && grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) startVoice()
    }

    private fun iconBtn(label: String, onClick: () -> Unit): TextView {
        val b = TextView(this)
        b.text = label
        b.gravity = Gravity.CENTER
        b.textSize = 18f
        b.setTextColor(CYAN)
        val g = GradientDrawable()
        g.shape = GradientDrawable.OVAL
        g.setColor(PANEL)
        g.setStroke(dp(1), 0x33FFFFFF)
        b.background = g
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(dp(40), dp(40))
        lp.leftMargin = dp(4)
        lp.rightMargin = dp(4)
        b.layoutParams = lp
        return b
    }

    private fun attachmentCard(a: Attachment, compact: Boolean): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(8), dp(8), dp(8), dp(8))
        box.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(0xFF151515.toInt())
            setStroke(dp(1), 0x55FF7A18)
        }

        val width = dp(if (compact) 148 else 248)

        if (a.kind == "image") {
            val bitmap = decode(a.path, if (compact) 240 else 640)
            if (bitmap != null) {
                val image = ImageView(this)
                image.setImageBitmap(bitmap)
                image.scaleType = ImageView.ScaleType.CENTER_CROP
                box.addView(
                    image,
                    LinearLayout.LayoutParams(
                        MATCH_PARENT,
                        dp(if (compact) 88 else 164)
                    )
                )
            }
        }

        val label = TextView(this)
        label.text = (
            if (a.ready) "" else "Processing · "
        ) + a.name
        label.textSize = 12f
        label.setTextColor(0xFFFAF7F2.toInt())
        label.maxLines = 2
        label.ellipsize = android.text.TextUtils.TruncateAt.END
        label.setPadding(dp(3), dp(7), dp(3), dp(3))
        box.addView(label)

        val lp = LinearLayout.LayoutParams(width, WRAP_CONTENT)
        lp.rightMargin = dp(8)
        lp.topMargin = dp(6)
        lp.bottomMargin = dp(6)
        box.layoutParams = lp

        box.setOnClickListener {
            if (compact) {
                Previewer.show(
                    this, a.name, a.path, a.kind, a.text
                ) {
                    pending.remove(a)
                    refreshChips()
                    updateAction()
                }
            } else {
                Previewer.showFile(this, File(a.path))
            }
        }
        return box
    }

    private fun refreshChips() {
        chips.removeAllViews()
        for (a in pending.toList()) {
            chips.addView(attachmentCard(a, true))
        }
    }

    private fun addAttachment(a: Attachment) {
        pending.add(a)
        refreshChips()
        updateAction()
        Thread {
            Attach.process(a)
            runOnUiThread { refreshChips() }
        }.start()
    }

    private fun attachMenu() {
        Ui2.addSheet(this, { pickCamera() }, { pickMedia() }, { pickFile() }, { shotAttach() })
    }

    private fun pickCamera() {
        try {
            val v = android.content.ContentValues()
            v.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "saann-photo-" + System.currentTimeMillis() + ".jpg")
            v.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
            if (uri == null) {
                Toast.makeText(this, "Camera storage unavailable", Toast.LENGTH_LONG).show()
                return
            }
            camUri = uri
            val i = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
            i.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, uri)
            startActivityForResult(i, 4419)
        } catch (e: Exception) {
            Toast.makeText(this, "No camera app found", Toast.LENGTH_LONG).show()
        }
    }

    private fun pickMedia() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
        startActivityForResult(i, 4418)
    }

    private fun attachMenuOld() {
        AlertDialog.Builder(this)
            .setItems(arrayOf("Screenshot of another app (3 s)", "Photo or file")) { _, w ->
                if (w == 0) shotAttach() else pickFile()
            }.show()
    }

    private fun shotAttach() {
        val s = SaannAccessibility.instance
        if (s == null) {
            Toast.makeText(this, "Turn SAANN on in Settings > Accessibility first", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Switch to the screen you want. Capturing in 3 s...", Toast.LENGTH_LONG).show()
        moveTaskToBack(true)
        Thread {
            try {
                Thread.sleep(3000)
                val a = Attachment("screenshot.jpg", s.saveShot(s.captureClean()), "image")
                runOnUiThread {
                    try {
                        startActivity(Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    } catch (e: Exception) {
                    }
                    addAttachment(a)
                }
            } catch (e: Throwable) {
                runOnUiThread { Toast.makeText(this, e.message ?: "screenshot failed", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        startActivityForResult(i, 4418)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val heard = Voice.result(requestCode, resultCode, data)
        if (heard != null) {
            input.setText(heard)
            if (Voice.autoSend) send.performClick()
            return
        }
        if (requestCode == 4419) {
            val cu = camUri
            camUri = null
            if (cu == null) return
            if (resultCode != RESULT_OK) {
                try { contentResolver.delete(cu, null, null) } catch (e: Exception) { }
                return
            }
            Thread {
                val a = Attach.copyIn(this, cu)
                try { contentResolver.delete(cu, null, null) } catch (e: Exception) { }
                runOnUiThread {
                    if (a == null) Toast.makeText(this, "Could not read the photo", Toast.LENGTH_LONG).show()
                    else addAttachment(a)
                }
            }.start()
            return
        }
        if (requestCode == 4418 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            Thread {
                val a = Attach.copyIn(this, uri)
                runOnUiThread {
                    if (a == null) Toast.makeText(this, "Could not add that file (20 MB max)", Toast.LENGTH_LONG).show()
                    else addAttachment(a)
                }
            }.start()
        }
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
