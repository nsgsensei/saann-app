package com.saann.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OverlayService : Service() {
    companion object {
        @Volatile var instance: OverlayService? = null
    }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var root: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var expanded = false
    private var asking: String? = null
    private var busy = false
    private var status = "SAANN ready"
    private val lines = ArrayList<String>()
    private var latch: CountDownLatch? = null
    @Volatile private var answer = "n"
    private var titleView: TextView? = null
    private var logView: TextView? = null
    private val collapseTask = Runnable {
        if (asking == null && !busy && expanded) {
            expanded = false
            build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startAsForeground()
        build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        root?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        root = null
        latch?.countDown()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("saann_bar", "SAANN bar", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction("stop"),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "saann_bar")
            .setContentTitle("SAANN is running")
            .setContentText("Tap to open the app")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    "Hide bar", stop
                ).build()
            )
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(7, n)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun weighted(): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        p.setMargins(dp(3), 0, dp(3), 0)
        return p
    }

    private fun button(label: String, fill: Int, ink: Int, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.setAllCaps(false)
        b.textSize = 13f
        b.setTextColor(ink)
        val g = GradientDrawable()
        g.cornerRadius = dp(14).toFloat()
        g.setColor(fill)
        b.background = g
        b.minHeight = 0
        b.minimumHeight = 0
        b.minWidth = 0
        b.minimumWidth = 0
        b.setPadding(dp(14), dp(6), dp(14), dp(6))
        b.setOnClickListener { onClick() }
        return b
    }

    fun rebuild() { ui.post { build() } }

    private fun build() {
        root?.let { try { wm.removeView(it) } catch (e: Exception) {} }
        val bg = BarPrefs.color(this, "bg")
        val accent = BarPrefs.color(this, "accent")
        val fg = BarPrefs.color(this, "text")
        val a8 = BarPrefs.int(this, "opacity") * 255 / 100
        val isOpen = expanded || asking != null

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val shape = GradientDrawable()
        shape.setColor(Color.argb(a8, Color.red(bg), Color.green(bg), Color.blue(bg)))
        shape.cornerRadius = dp(if (isOpen) 22 else BarPrefs.int(this, "radius")).toFloat()
        box.background = shape
        box.setPadding(dp(14), dp(5), dp(14), dp(5))
        box.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.minimumHeight = dp(BarPrefs.int(this, "height") - 10)
        val dotShape = GradientDrawable()
        dotShape.shape = GradientDrawable.OVAL
        dotShape.setColor(
            when {
                asking != null -> Color.parseColor("#FFB020")
                busy -> accent
                else -> Color.parseColor("#6B6B78")
            }
        )
        val dot = View(this)
        dot.background = dotShape
        val dotLp = LinearLayout.LayoutParams(dp(8), dp(8))
        dotLp.rightMargin = dp(8)
        head.addView(dot, dotLp)
        val title = TextView(this)
        title.setTextColor(fg)
        title.textSize = 12f
        title.maxLines = 1
        title.ellipsize = TextUtils.TruncateAt.END
        title.text = status
        head.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.setOnClickListener {
            if (asking == null) {
                expanded = !expanded
                ui.removeCallbacks(collapseTask)
                build()
            }
        }
        titleView = title
        box.addView(head)

        logView = null
        if (isOpen) {
            val log = TextView(this)
            log.setTextColor(fg)
            log.alpha = 0.7f
            log.textSize = 11f
            log.maxLines = 7
            log.text = lines.joinToString("\n")
            log.setPadding(0, dp(6), 0, dp(6))
            box.addView(log)
            logView = log

            val q = asking
            if (q != null) {
                val msg = TextView(this)
                msg.setTextColor(fg)
                msg.textSize = 13f
                msg.text = q
                msg.setPadding(0, 0, 0, dp(8))
                box.addView(msg)
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.addView(button("Allow", accent, bg) { resolve("y") }, weighted())
                row.addView(button("Deny", Color.parseColor("#C0392B"), Color.WHITE) { resolve("n") }, weighted())
                box.addView(row)
            } else {
                val input = EditText(this)
                input.hint = "Tell SAANN what to do"
                input.setHintTextColor(Color.argb(120, Color.red(fg), Color.green(fg), Color.blue(fg)))
                input.setTextColor(fg)
                input.textSize = 14f
                input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                input.setSingleLine(true)
                input.imeOptions = EditorInfo.IME_ACTION_SEND
                input.setOnTouchListener { _, ev ->
                    if (ev.action == MotionEvent.ACTION_DOWN) {
                        typable(true)
                        input.postDelayed({
                            input.requestFocus()
                            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                        }, 200)
                    }
                    false
                }
                input.setOnEditorActionListener { _, id, _ ->
                    if (id == EditorInfo.IME_ACTION_SEND) {
                        submit(input.text.toString())
                        true
                    } else {
                        false
                    }
                }
                val row1 = LinearLayout(this)
                row1.orientation = LinearLayout.HORIZONTAL
                row1.gravity = Gravity.CENTER_VERTICAL
                row1.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row1.addView(button("Go", accent, bg) { submit(input.text.toString()) })
                box.addView(row1)

                val row2 = LinearLayout(this)
                row2.orientation = LinearLayout.HORIZONTAL
                row2.setPadding(0, dp(6), 0, 0)
                row2.addView(button("Stop", Color.parseColor("#C0392B"), Color.WHITE) { stopTask() }, weighted())
                row2.addView(button("Open app", Color.parseColor("#3A3A44"), Color.WHITE) { openApp() }, weighted())
                box.addView(row2)
            }
        }

        val maxW = resources.displayMetrics.widthPixels - dp(16)
        val w = minOf(dp(BarPrefs.int(this, if (isOpen) "ewidth" else "width")), maxW)
        val p = WindowManager.LayoutParams(
            w, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        p.y = dp(BarPrefs.int(this, "top"))
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try {
            wm.addView(box, p)
            root = box
            params = p
        } catch (e: Exception) {
            root = null
            stopSelf()
        }
    }

    // Lets the keyboard work only while you are typing in the bar.
    private fun typable(on: Boolean) {
        val p = params ?: return
        val r = root ?: return
        p.flags = if (on) {
            p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        try { wm.updateViewLayout(r, p) } catch (e: Exception) {}
    }

    private fun push(line: String) {
        lines.add(line.take(160))
        while (lines.size > 8) lines.removeAt(0)
    }

    private fun autoCollapse() {
        ui.removeCallbacks(collapseTask)
        val s = BarPrefs.int(this, "collapse")
        if (s > 0) ui.postDelayed(collapseTask, s * 1000L)
    }

    // Called from Python while a task runs.
    fun setStatus(t: String) {
        ui.post {
            status = t.take(80)
            busy = !(t.startsWith("Done") || t.startsWith("Stopped"))
            push(t)
            if (expanded || asking != null) {
                titleView?.text = status
                logView?.text = lines.joinToString("\n")
            } else {
                build()
            }
        }
    }

    // Called from Python: shows Allow / Deny on the bar and waits.
    fun confirm(msg: String): String {
        val l = CountDownLatch(1)
        latch = l
        answer = "n"
        ui.post {
            asking = msg.take(300)
            status = "Needs your approval"
            ui.removeCallbacks(collapseTask)
            build()
        }
        val got = try { l.await(120, TimeUnit.SECONDS) } catch (e: InterruptedException) { false }
        ui.post {
            asking = null
            build()
        }
        return if (got) answer else "n"
    }

    private fun resolve(a: String) {
        answer = a
        push(if (a == "y") "approved" else "denied")
        latch?.countDown()
    }

    private fun stopTask() {
        answer = "n"
        latch?.countDown()
        Host.stop()
        status = if (busy) "Stopping..." else "Nothing running"
        push("stop pressed")
        build()
    }

    private fun openApp() {
        val i = Intent(this, MainActivity::class.java)
        i.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
        )
        startActivity(i)
    }

    private fun submit(raw: String) {
        val t = raw.trim()
        if (t.isEmpty()) return
        typable(false)
        if (t.startsWith("/bar")) {
            val reply = BarPrefs.command(this, t)
            if (t.trim() == "/bar off") return
            push(reply)
            expanded = true
            build()
            autoCollapse()
            return
        }
        expanded = false
        push("you: $t")
        busy = true
        status = "Working..."
        build()
        Host.respond(t) { r ->
            ui.post {
                busy = false
                push(r)
                status = r.lineSequence().firstOrNull()?.take(70) ?: "Done"
                expanded = true
                build()
                autoCollapse()
            }
        }
    }
}
