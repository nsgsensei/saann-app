package com.saann.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class SaannAccessibility : AccessibilityService() {
    companion object {
        @Volatile var instance: SaannAccessibility? = null
    }

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "ERR:no screen content available"
        val sb = StringBuilder("app: ${root.packageName}\n")
        var count = 0
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || count >= 80 || depth > 30) return
            if (n.isVisibleToUser) {
                val label = (n.text ?: n.contentDescription)?.toString()?.trim().orEmpty()
                if (label.isNotEmpty() || n.isEditable) {
                    val kind = when { n.isEditable -> "input"; n.isClickable -> "button"; else -> "text" }
                    sb.append("- ").append(kind).append(": ")
                        .append(label.take(80).replace("\n", " ")).append('\n')
                    count++
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return sb.toString()
    }

    fun click(label: String): String {
        val root = rootInActiveWindow ?: return "ERR:no screen content available"
        val found = root.findAccessibilityNodeInfosByText(label).filter { it.isVisibleToUser }
        val node = found.firstOrNull {
            (it.text ?: it.contentDescription)?.toString().equals(label, true)
        } ?: found.firstOrNull() ?: return "ERR:nothing on screen matches \"$label\""
        var t: AccessibilityNodeInfo? = node
        var hops = 0
        while (t != null && !t.isClickable && hops < 6) { t = t.parent; hops++ }
        if (t != null && t.isClickable && t.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return "clicked $label"
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        val ok = gesture(r.centerX().toFloat(), r.centerY().toFloat(),
            r.centerX().toFloat(), r.centerY().toFloat(), 50)
        return if (ok) "tapped $label" else "ERR:could not click \"$label\""
    }

    fun type(text: String): String {
        val root = rootInActiveWindow ?: return "ERR:no screen content available"
        val f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return "ERR:no text field is focused; click the field first"
        val b = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) "typed" else "ERR:could not type"
    }

    fun swipe(dir: String): String {
        val m = resources.displayMetrics
        val w = m.widthPixels.toFloat()
        val h = m.heightPixels.toFloat()
        val ok = when (dir) {
            "up" -> gesture(w / 2, h * 0.7f, w / 2, h * 0.3f, 300)
            "down" -> gesture(w / 2, h * 0.3f, w / 2, h * 0.7f, 300)
            "left" -> gesture(w * 0.8f, h / 2, w * 0.2f, h / 2, 300)
            "right" -> gesture(w * 0.2f, h / 2, w * 0.8f, h / 2, 300)
            else -> return "ERR:direction must be up, down, left or right"
        }
        return if (ok) "swiped $dir" else "ERR:swipe failed"
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

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
        val p = Path().apply {
            moveTo(x1, y1)
            if (x1 != x2 || y1 != y2) lineTo(x2, y2)
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build()
        return dispatchGesture(g, null, null)
    }
}
