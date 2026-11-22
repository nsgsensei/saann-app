package com.saann.app

import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executors

// Takes a small, private, in-memory picture of the screen after each screen action.
// It never saves the picture, and it skips password screens and money or password apps.
object Preview {
    @Volatile var listener: ((Bitmap?, String) -> Unit)? = null
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var last = 0L
    @Volatile private var queued = false
    private val block = listOf(
        "bank", "wallet", "paypal", "authenticator", "bitwarden", "lastpass",
        "1password", "keepass", "permissioncontroller", "packageinstaller"
    )

    private fun hasPassword(n: AccessibilityNodeInfo?, depth: Int): Boolean {
        if (n == null || depth > 30) return false
        if (n.isPassword && n.isVisibleToUser) return true
        for (i in 0 until n.childCount) {
            if (hasPassword(n.getChild(i), depth + 1)) return true
        }
        return false
    }

    fun snap() {
        if (listener == null || SaannAccessibility.instance == null || queued) return
        queued = true
        exec.execute {
            try {
                val wait = 1300 - (System.currentTimeMillis() - last)
                if (wait > 0) Thread.sleep(wait)
                queued = false
                last = System.currentTimeMillis()
                val l = listener ?: return@execute
                val s = SaannAccessibility.instance ?: return@execute
                val root = s.rootInActiveWindow
                val pkg = root?.packageName?.toString() ?: ""
                if (block.any { pkg.lowercase().contains(it) } || hasPassword(root, 0)) {
                    l(null, "preview hidden: private screen")
                    return@execute
                }
                val full = s.capture()
                val w = 540
                val h = (full.height * w / full.width.toFloat()).toInt().coerceAtLeast(1)
                val small = Bitmap.createScaledBitmap(full, w, h, true)
                if (small !== full) full.recycle()
                l(small, "app: " + pkg)
            } catch (e: Exception) {
                queued = false
                listener?.invoke(null, "no preview: " + (e.message ?: "unavailable"))
            }
        }
    }
}
