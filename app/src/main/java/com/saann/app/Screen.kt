package com.saann.app

import android.app.Activity
import android.content.Intent
import android.provider.Settings

object Screen {
    fun call(act: Activity, name: String, a: String, b: String): String {
        return try {
            when (name) {
                "a11y_settings" -> {
                    act.runOnUiThread { act.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                    "ok"
                }
                "open_app" -> openApp(act, a)
                else -> {
                    val s = SaannAccessibility.instance
                        ?: return "ERR:accessibility is off. Turn SAANN on in Settings > Accessibility."
                    val r = when (name) {
                        "screen_read" -> return s.readScreen()
                        "screen_click" -> s.click(a)
                        "screen_type" -> s.type(a)
                        "screen_swipe" -> s.swipe(a)
                        "screen_button" -> s.button(a)
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
