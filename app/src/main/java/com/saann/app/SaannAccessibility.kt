package com.saann.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.sin

class SaannAccessibility : AccessibilityService() {
    companion object {
        @Volatile var instance: SaannAccessibility? = null
    }

    private val exec = Executors.newSingleThreadExecutor()

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---------- basics ----------
    private fun size(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = (getSystemService(Context.WINDOW_SERVICE) as WindowManager).maximumWindowMetrics.bounds
            return Pair(b.width(), b.height())
        }
        val m = resources.displayMetrics
        return Pair(m.widthPixels, m.heightPixels)
    }

    fun info(): String {
        val s = size()
        return "${s.first}x${s.second} px, app: ${rootInActiveWindow?.packageName ?: "unknown"}"
    }

    private fun clean(label: String): String =
        label.replace(Regex("\\s*@\\(.*\\)\\s*$"), "").trim()

    private fun onScreen(x: Int, y: Int) {
        val (w, h) = size()
        require(x in 0 until w && y in 0 until h) { "position ($x,$y) is off the screen (${w}x$h)" }
    }

    // ---------- reading ----------
    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "ERR:no screen content available"
        val sb = StringBuilder("app: ${root.packageName}\n")
        var count = 0
        val r = Rect()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || count >= 80 || depth > 30) return
            if (n.isVisibleToUser) {
                val label = if (n.isPassword) "(password field)"
                    else (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
                if (label.isNotEmpty() || n.isEditable) {
                    val kind = when {
                        n.isEditable -> "input"
                        n.isCheckable -> if (n.isChecked) "toggle(on)" else "toggle(off)"
                        n.isClickable -> "button"
                        n.isScrollable -> "list"
                        else -> "text"
                    }
                    n.getBoundsInScreen(r)
                    sb.append("- ").append(kind).append(": ")
                        .append(label.take(80).replace("\n", " "))
                        .append(" @(").append(r.centerX()).append(",").append(r.centerY()).append(")\n")
                    count++
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return sb.toString()
    }

    private fun find(label: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val l = clean(label)
        val found = root.findAccessibilityNodeInfosByText(l).filter { it.isVisibleToUser }
        return found.firstOrNull { (it.text ?: it.contentDescription)?.toString().equals(l, true) }
            ?: found.firstOrNull()
    }

    // ---------- acting on named items ----------
    fun click(label: String): String {
        if (rootInActiveWindow == null) return "ERR:no screen content available"
        val l = clean(label)
        val node = find(label) ?: return "ERR:nothing on screen matches \"$l\""
        var t: AccessibilityNodeInfo? = node
        var hops = 0
        while (t != null && !t.isClickable && hops < 6) { t = t.parent; hops++ }
        if (t != null && t.isClickable && t.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return "clicked $l"
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        return if (tapAt(r.centerX().toFloat(), r.centerY().toFloat(), 50)) "tapped $l"
        else "ERR:could not click \"$l\""
    }

    fun type(text: String): String {
        val root = rootInActiveWindow ?: return "ERR:no screen content available"
        val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return "ERR:no text field is focused; click the field first"
        if (f.isPassword) return "ERR:I don't type into password fields; type it yourself"
        val b = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) "typed" else "ERR:could not type"
    }

    fun paste(): String {
        val f = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return "ERR:no text field is focused; click the field first"
        if (f.isPassword) return "ERR:I don't paste into password fields"
        return if (f.performAction(AccessibilityNodeInfo.ACTION_PASTE)) "pasted" else "ERR:could not paste"
    }

    fun enter(): String {
        val f = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return "ERR:no text field is focused"
        if (Build.VERSION.SDK_INT >= 30 &&
            f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)) {
            return "pressed enter"
        }
        return "ERR:could not press enter on this field"
    }

    fun copyText(label: String): String {
        if (rootInActiveWindow == null) return "ERR:no screen content available"
        val node = find(label) ?: return "ERR:nothing on screen matches \"${clean(label)}\""
        if (node.isPassword) return "ERR:password fields can't be copied"
        val t = (node.text ?: node.contentDescription)?.toString().orEmpty()
        if (t.isEmpty()) return "ERR:that item has no text"
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("saann", t))
        return "copied: " + t.take(200)
    }

    // ---------- gestures ----------
    private fun line(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): GestureDescription.StrokeDescription {
        val p = Path()
        p.moveTo(x1, y1)
        if (x1 != x2 || y1 != y2) p.lineTo(x2, y2)
        return GestureDescription.StrokeDescription(p, 0, ms)
    }

    private fun dispatch(g: GestureDescription): Boolean {
        val ok = booleanArrayOf(false)
        val latch = CountDownLatch(1)
        val cb = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { ok[0] = true; latch.countDown() }
            override fun onCancelled(d: GestureDescription?) { latch.countDown() }
        }
        if (!dispatchGesture(g, cb, null)) return false
        latch.await(4, TimeUnit.SECONDS)
        return ok[0]
    }

    fun tapAt(x: Float, y: Float, ms: Long): Boolean =
        dispatch(GestureDescription.Builder().addStroke(line(x, y, x, y, ms)).build())

    fun tap(x: Int, y: Int, times: Int, ms: Long): String {
        onScreen(x, y)
        for (i in 0 until times) {
            if (!tapAt(x.toFloat(), y.toFloat(), ms)) return "ERR:tap failed"
            if (i < times - 1) Thread.sleep(90)
        }
        return if (ms >= 500) "long-pressed ($x,$y)" else "tapped ($x,$y) x$times"
    }

    fun drag(x1: Int, y1: Int, x2: Int, y2: Int): String {
        onScreen(x1, y1)
        onScreen(x2, y2)
        val g = GestureDescription.Builder()
            .addStroke(line(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), 450)).build()
        return if (dispatch(g)) "dragged" else "ERR:drag failed"
    }

    // angle in degrees: 0 = right, 90 = up, 180 = left, 270 = down
    fun swipeAngle(x: Int, y: Int, angle: Int, dist: Int): String {
        onScreen(x, y)
        val (w, h) = size()
        val rad = Math.toRadians(angle.toDouble())
        val x2 = (x + dist * cos(rad)).toFloat().coerceIn(0f, (w - 1).toFloat())
        val y2 = (y - dist * sin(rad)).toFloat().coerceIn(0f, (h - 1).toFloat())
        val g = GestureDescription.Builder().addStroke(line(x.toFloat(), y.toFloat(), x2, y2, 350)).build()
        return if (dispatch(g)) "swiped $dist px at $angle degrees" else "ERR:swipe failed"
    }

    // percent above 100 zooms in, below 100 zooms out
    fun pinch(x: Int, y: Int, percent: Int): String {
        onScreen(x, y)
        val (w, _) = size()
        val s0 = 150f
        val s1 = (150f * percent / 100f).coerceAtLeast(20f)
        fun cx(v: Float) = v.coerceIn(0f, (w - 1).toFloat())
        val yy = y.toFloat()
        val g = GestureDescription.Builder()
            .addStroke(line(cx(x - s0), yy, cx(x - s1), yy, 450))
            .addStroke(line(cx(x + s0), yy, cx(x + s1), yy, 450))
            .build()
        return if (dispatch(g)) "pinched to $percent%" else "ERR:pinch failed"
    }

    fun swipe(dir: String): String {
        val (w, h) = size()
        val fw = w.toFloat()
        val fh = h.toFloat()
        val s = when (dir) {
            "up" -> line(fw / 2, fh * 0.7f, fw / 2, fh * 0.3f, 300)
            "down" -> line(fw / 2, fh * 0.3f, fw / 2, fh * 0.7f, 300)
            "left" -> line(fw * 0.8f, fh / 2, fw * 0.2f, fh / 2, 300)
            "right" -> line(fw * 0.2f, fh / 2, fw * 0.8f, fh / 2, 300)
            else -> return "ERR:direction must be up, down, left or right"
        }
        return if (dispatch(GestureDescription.Builder().addStroke(s).build())) "swiped $dir"
        else "ERR:swipe failed"
    }

    private fun findScrollable(n: AccessibilityNodeInfo?, depth: Int = 0): AccessibilityNodeInfo? {
        if (n == null || depth > 30) return null
        if (n.isScrollable && n.isVisibleToUser) return n
        for (i in 0 until n.childCount) {
            val f = findScrollable(n.getChild(i), depth + 1)
            if (f != null) return f
        }
        return null
    }

    // down = show content below, up = show content above
    fun scroll(dir: String): String {
        if (dir !in listOf("up", "down", "left", "right")) return "ERR:direction must be up, down, left or right"
        val forward = dir == "down" || dir == "right"
        val node = findScrollable(rootInActiveWindow)
        if (node != null) {
            val a = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (node.performAction(a)) return "scrolled $dir"
        }
        val opposite = when (dir) { "down" -> "up"; "up" -> "down"; "right" -> "left"; else -> "right" }
        return swipe(opposite)
    }

    fun button(name: String): String {
        val a = when (name) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            else -> return "ERR:name must be back, home, recents or notifications"
        }
        return if (performGlobalAction(a)) "pressed $name" else "ERR:could not press $name"
    }

    fun system(action: String): String {
        val a = when (action) {
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock" -> GLOBAL_ACTION_LOCK_SCREEN
            "power" -> GLOBAL_ACTION_POWER_DIALOG
            "screenshot" -> GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> return "ERR:action must be quick_settings, lock, power or screenshot"
        }
        return if (performGlobalAction(a)) "done: $action" else "ERR:could not do $action"
    }

    // ---------- screenshots ----------
    fun capture(): Bitmap {
        if (Build.VERSION.SDK_INT < 30) throw IllegalStateException("screenshots need Android 11 or newer")
        var reason = "screenshot failed"
        for (attempt in 0..1) {
            val latch = CountDownLatch(1)
            var bmp: Bitmap? = null
            var code = 0
            takeScreenshot(Display.DEFAULT_DISPLAY, exec, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                    try {
                        val hb = r.hardwareBuffer
                        val hw = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)
                        bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        hb.close()
                    } catch (e: Exception) {
                    }
                    latch.countDown()
                }

                override fun onFailure(errorCode: Int) {
                    code = errorCode
                    latch.countDown()
                }
            })
            latch.await(6, TimeUnit.SECONDS)
            val got = bmp
            if (got != null) return got
            reason = when (code) {
                2 -> "no screenshot permission: switch SAANN off and on again in Accessibility"
                3 -> "screenshots too close together"
                5 -> "this window blocks screenshots"
                else -> "screenshot failed (code $code)"
            }
            if (code == 3) Thread.sleep(1200) else break
        }
        throw IllegalStateException(reason)
    }

    // hides SAANN's own bar so it is not in the picture
    fun captureClean(): Bitmap {
        val bar = OverlayService.instance
        bar?.hideForShot(true)
        Thread.sleep(250)
        try {
            return capture()
        } finally {
            bar?.hideForShot(false)
        }
    }

    fun saveShot(b: Bitmap): String {
        val dir = File(filesDir, "saann/data/shots")
        dir.mkdirs()
        dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(9)?.forEach { it.delete() }
        val f = File(dir, "shot-" + System.currentTimeMillis() + ".jpg")
        f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        return f.absolutePath
    }
}
