package com.saann.app

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.Settings

object BarPrefs {
    private val defaults = linkedMapOf(
        "bg" to "#101014", "accent" to "#00E5A0", "text" to "#FFFFFF",
        "opacity" to "92", "width" to "140", "height" to "30", "top" to "8",
        "radius" to "18", "ewidth" to "340", "collapse" to "6"
    )
    private val ranges = mapOf(
        "opacity" to (30..100), "width" to (80..300), "height" to (20..60),
        "top" to (0..120), "radius" to (4..30), "ewidth" to (220..420),
        "collapse" to (0..60)
    )
    private val themes = mapOf(
        "dark" to mapOf("bg" to "#101014", "accent" to "#00E5A0", "text" to "#FFFFFF"),
        "light" to mapOf("bg" to "#F2F2F5", "accent" to "#0A7F5A", "text" to "#111111"),
        "neon" to mapOf("bg" to "#0B0020", "accent" to "#FF2BD6", "text" to "#F5E9FF"),
        "ocean" to mapOf("bg" to "#06202B", "accent" to "#2DD4FF", "text" to "#E6F7FF"),
        "ember" to mapOf("bg" to "#1A0D08", "accent" to "#FF8A3D", "text" to "#FFF1E6")
    )

    private fun sp(c: Context) = c.getSharedPreferences("bar", Context.MODE_PRIVATE)

    fun str(c: Context, k: String): String = sp(c).getString(k, null) ?: defaults.getValue(k)
    fun int(c: Context, k: String): Int = str(c, k).toIntOrNull() ?: defaults.getValue(k).toInt()
    fun color(c: Context, k: String): Int =
        try { Color.parseColor(str(c, k)) } catch (e: Exception) { Color.parseColor(defaults.getValue(k)) }

    private fun validColor(v: String): Boolean =
        try { Color.parseColor(v); true } catch (e: Exception) { false }

    private fun refresh() { OverlayService.instance?.rebuild() }

    fun start(c: Context): String {
        if (!Settings.canDrawOverlays(c)) {
            val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + c.packageName))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            c.startActivity(i)
            return "Turn on \"Display over other apps\" for SAANN, go back, then type /bar on"
        }
        val i = Intent(c, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        return "bar on"
    }

    private fun help(): String =
        "usage: /bar on | off | reset | theme NAME | bg #HEX | accent #HEX | text #HEX | " +
        "opacity | width | height | top | radius | ewidth | collapse NUMBER. Themes: " +
        themes.keys.joinToString(", ")

    private fun summary(c: Context): String =
        "bar: " + defaults.keys.joinToString(", ") { it + "=" + str(c, it) } + "\n" + help()

    fun command(c: Context, line: String): String {
        val a = line.trim().split(Regex("\\s+"))
        val cmd = a.getOrNull(1) ?: return summary(c)
        val arg = a.getOrNull(2)
        return when {
            cmd == "on" -> start(c)
            cmd == "off" -> {
                c.stopService(Intent(c, OverlayService::class.java))
                "bar hidden. Type /bar on to show it"
            }
            cmd == "reset" -> {
                sp(c).edit().clear().apply()
                refresh()
                "bar reset to defaults"
            }
            cmd == "theme" -> {
                val t = if (arg == null) null else themes[arg]
                if (t == null) {
                    "themes: " + themes.keys.joinToString(", ") + ". Use /bar theme NAME"
                } else {
                    val e = sp(c).edit()
                    for ((k, v) in t) e.putString(k, v)
                    e.apply()
                    refresh()
                    "theme $arg applied"
                }
            }
            cmd == "bg" || cmd == "accent" || cmd == "text" -> {
                if (arg == null || !validColor(arg)) {
                    "usage: /bar $cmd #RRGGBB"
                } else {
                    sp(c).edit().putString(cmd, arg).apply()
                    refresh()
                    "$cmd set to $arg"
                }
            }
            ranges.containsKey(cmd) -> {
                val n = arg?.toIntOrNull()
                val r = ranges.getValue(cmd)
                if (n == null) {
                    "usage: /bar $cmd NUMBER (${r.first} to ${r.last})"
                } else {
                    val v = n.coerceIn(r.first, r.last)
                    sp(c).edit().putString(cmd, v.toString()).apply()
                    refresh()
                    "$cmd set to $v"
                }
            }
            else -> help()
        }
    }
}
