package com.saann.app

import android.app.Activity
import android.content.Intent
import android.provider.Settings

object Screen {
    private fun nums(s: String, n: Int): List<Int> {
        val p = s.split(",").map { it.trim().toInt() }
        require(p.size == n) { "expected $n numbers" }
        return p
    }

    fun call(act: Activity, name: String, a: String, b: String): String {
        return try {
            when (name) {
                "a11y_settings" -> {
                    act.runOnUiThread { act.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    "ok"
                }
                "live_step" -> Live.step(a)
                "stream" -> Live.stream(a)
                "open_app" -> openApp(act, a)
                "list_apps" -> listApps(act)
                else -> {
                    val s = SaannAccessibility.instance
                        ?: return "ERR:accessibility is off. Turn SAANN on in Settings > Accessibility."
                    val r = when (name) {
                        "screen_read" -> return s.readScreen()
                        "screen_info" -> return s.info()
                        "screen_shot" -> {
                            val bmp = s.captureClean()
                            return "saved " + s.saveShot(bmp) + " (${bmp.width}x${bmp.height})"
                        }
                        "screen_look" -> return Vision.look(s.captureClean())
                        "screen_copy" -> return s.copyText(a)
                        "screen_click" -> s.click(a)
                        "screen_type" -> s.type(a)
                        "screen_paste" -> s.paste()
                        "screen_enter" -> s.enter()
                        "screen_swipe" -> s.swipe(a)
                        "screen_scroll" -> s.scroll(a)
                        "screen_button" -> s.button(a)
                        "system" -> s.system(a)
                        "screen_tap" -> { val n = nums(a, 2); s.tap(n[0], n[1], 1, 50) }
                        "screen_double_tap" -> { val n = nums(a, 2); s.tap(n[0], n[1], 2, 50) }
                        "screen_long_press" -> { val n = nums(a, 2); s.tap(n[0], n[1], 1, 800) }
                        "screen_drag" -> { val n = nums(a, 4); s.drag(n[0], n[1], n[2], n[3]) }
                        "screen_swipe_angle" -> { val n = nums(a, 4); s.swipeAngle(n[0], n[1], n[2], n[3]) }
                        "screen_pinch" -> { val n = nums(a, 3); s.pinch(n[0], n[1], n[2]) }
                        else -> return "ERR:not available in the app yet: $name"
                    }
                    Thread.sleep(700)
                    r
                }
            }
        } catch (e: Exception) {
            "ERR:" + (e.message ?: e.toString())
        }
    }

    private fun listApps(act: Activity): String {
        val pm = act.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(q, 0).map { it.loadLabel(pm).toString() }
            .distinct().sorted().take(80).joinToString(", ")
    }

    private fun openApp(act: Activity, name: String): String {
        val pm = act.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(q, 0)
        val want = name.trim().lowercase()
        val hit = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == want }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(want) }
            ?: return "ERR:no installed app named \"$name\""
        val intent = pm.getLaunchIntentForPackage(hit.activityInfo.packageName)
            ?: return "ERR:can't launch that app"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        act.runOnUiThread { act.startActivity(intent) }
        Thread.sleep(1200)
        return "opened " + hit.loadLabel(pm)
    }
}
