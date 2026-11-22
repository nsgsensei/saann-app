package com.saann.app

import com.chaquo.python.PyObject

object Host {
    @Volatile var py: PyObject? = null

    fun respond(text: String, done: (String) -> Unit) {
        Thread {
            val p = py
            var r: String
            if (p == null) {
                r = "SAANN is still starting. Open the app once first."
            } else {
                var tried = false
                if (!ServerGuard.mainUp()) {
                    tried = true
                    ServerGuard.ensure(90) { s -> ServerGuard.onStatus?.invoke(s) }
                }
                r = call(p, text)
                if (!tried && ServerGuard.looksDown(r)) {
                    ServerGuard.onStatus?.invoke("Starting models...")
                    if (ServerGuard.ensure(90) { s -> ServerGuard.onStatus?.invoke(s) }) r = call(p, text)
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
