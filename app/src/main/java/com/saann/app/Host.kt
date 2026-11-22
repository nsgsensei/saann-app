package com.saann.app

import com.chaquo.python.PyObject

object Host {
    @Volatile var py: PyObject? = null

    private fun report(): String =
        "main (8080): " + (if (ServerGuard.up(8080)) "up" else "down") + "\n" +
            "fast (8081): " + (if (ServerGuard.up(8081)) "up" else "down") + "\n" +
            "Termux permission: " + (if (ServerGuard.hasPerm()) "granted" else "NOT granted")

    fun respond(text: String, done: (String) -> Unit) {
        Thread {
            val p = py
            val tl = text.trim().lowercase()
            var r: String
            if (tl == "/servers" || tl == "/servers start") {
                if (tl == "/servers start") ServerGuard.ensure(75) { s -> ServerGuard.onStatus?.invoke(s) }
                r = report() + "\n\nIf they stay down, run in Termux: bash ~/saann/ensure_llm.sh ; tail ~/saann/data/ensure.log"
            } else if (p == null) {
                r = "SAANN is still starting. Open the app once first."
            } else {
                r = call(p, text)
                if (ServerGuard.looksDown(r)) {
                    ServerGuard.onStatus?.invoke("Starting models...")
                    if (ServerGuard.ensure(75) { s -> ServerGuard.onStatus?.invoke(s) }) {
                        r = call(p, text)
                    } else {
                        r = r + "\n\nThe models are not running. Type /servers for details."
                    }
                    ServerGuard.onStatus?.invoke("Ready")
                }
            }
            done(r)
        }.start()
    }

    private fun call(p: PyObject, text: String): String = try {
        p.callAttr("respond", text).toString()
    } catch (e: Throwable) {
        "error: " + (e.message ?: e.toString())
    }

    fun snapshot(): String = try {
        py?.callAttr("brain_snapshot")?.toString() ?: "{}"
    } catch (e: Throwable) {
        "{}"
    }

    fun stop() {
        Thread {
            try { py?.callAttr("stop") } catch (e: Throwable) {}
        }.start()
    }
}
