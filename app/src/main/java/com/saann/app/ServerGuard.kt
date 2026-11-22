package com.saann.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL

object ServerGuard {
    const val RUN = "com.termux.permission.RUN_COMMAND"
    private const val HOME = "/data/data/com.termux/files/home/saann"
    @Volatile private var ctx: Context? = null
    @Volatile private var lastLaunch = 0L
    @Volatile var onStatus: ((String) -> Unit)? = null
    private var watcher: Thread? = null

    fun init(c: Context) {
        ctx = c.applicationContext
        if (watcher != null) return
        val t = Thread {
            while (true) {
                try { Thread.sleep(20000) } catch (e: Exception) { return@Thread }
                try {
                    if (hasPerm() && (AgentService.appVisible || AgentService.running) && !(up(8080) && up(8081))) launch()
                } catch (e: Exception) {
                }
            }
        }
        t.isDaemon = true
        t.start()
        watcher = t
    }

    fun hasPerm(): Boolean {
        val c = ctx ?: return false
        return Build.VERSION.SDK_INT < 23 || c.checkSelfPermission(RUN) == PackageManager.PERMISSION_GRANTED
    }

    fun up(port: Int): Boolean {
        return try {
            val cn = URL("http://127.0.0.1:" + port + "/health").openConnection() as HttpURLConnection
            cn.connectTimeout = 700
            cn.readTimeout = 1200
            val ok = cn.responseCode == 200 && cn.inputStream.bufferedReader().readText().contains("ok")
            cn.disconnect()
            ok
        } catch (e: Exception) {
            false
        }
    }

    fun mainUp(): Boolean = up(8080)

    private fun launch(): Boolean {
        val c = ctx ?: return false
        if (!hasPerm()) return false
        if (System.currentTimeMillis() - lastLaunch < 45000) return true
        return try {
            val i = Intent("com.termux.RUN_COMMAND")
            i.setClassName("com.termux", "com.termux.app.RunCommandService")
            i.putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
            i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf(HOME + "/ensure_llm.sh"))
            i.putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
            i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            lastLaunch = System.currentTimeMillis()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun ensure(secs: Int, say: ((String) -> Unit)?): Boolean {
        if (mainUp()) {
            if (!up(8081)) launch()
            return true
        }
        if (!hasPerm()) {
            say?.invoke("Allow SAANN to run commands in Termux to start the models")
            return false
        }
        if (!launch()) {
            say?.invoke("Couldn't start the models (is Termux installed, with allow-external-apps on?)")
            return false
        }
        val end = System.currentTimeMillis() + secs * 1000L
        var n = 0
        while (System.currentTimeMillis() < end) {
            if (mainUp()) return true
            say?.invoke("Starting models... " + (n * 2) + "s")
            try { Thread.sleep(2000) } catch (e: Exception) { return false }
            n++
        }
        return false
    }

    fun kick() {
        if (!hasPerm()) return
        Thread {
            val ok = ensure(120) { s -> onStatus?.invoke(s) }
            onStatus?.invoke(if (ok) "Ready" else "Models offline")
        }.start()
    }

    fun looksDown(r: String): Boolean {
        val l = r.lowercase()
        return l.contains("connection refused") || l.contains("errno 111") || l.contains("urlopen error") ||
            l.contains("failed to connect") || l.contains("couldn't connect") || l.contains("could not connect") ||
            l.contains("connection reset") || l.contains("max retries") || l.contains("server not running")
    }
}
