package com.saann.app

import com.chaquo.python.PyObject

object Host {
    @Volatile var py: PyObject? = null

    fun respond(text: String, done: (String) -> Unit) {
        Thread {
            val p = py
            val r = if (p == null) {
                "SAANN is still starting. Open the app once first."
            } else {
                try {
                    p.callAttr("respond", text).toString()
                } catch (e: Throwable) {
                    "error: " + (e.message ?: e.toString())
                }
            }
            done(r)
        }.start()
    }

    fun stop() {
        Thread {
            try { py?.callAttr("stop") } catch (e: Throwable) {}
        }.start()
    }
}
