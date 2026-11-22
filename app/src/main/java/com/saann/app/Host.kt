package com.saann.app

import com.chaquo.python.PyObject

// Cloud-only: no local model servers are started or waited for.
object Host {
    @Volatile var py: PyObject? = null

    fun respond(text: String, done: (String) -> Unit) {
        Thread {
            val p = py
            val tl = text.trim().lowercase()
            val r: String
            if (p == null) {
                r = "SAANN is still starting. Open the app once first."
            } else if (tl == "/servers") {
                r = "Local models are off to save battery and time.\n\nOnline help:\n" + call(p, "/cloud")
            } else {
                val x = call(p, text)
                r = if (ServerGuard.looksDown(x))
                    "I can't reach a model right now. Local models are off, so connect online help (Groq, Gemini or Cloudflare). Type /cloud to see what's connected."
                else x
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
