package com.saann.app

import org.json.JSONObject

// Receives "what the agent is doing" events from Python and passes them to the screen.
object Live {
    @Volatile var onStep: ((String, String, String, Boolean, String) -> Unit)? = null

    fun step(json: String): String {
        return try {
            val o = JSONObject(json)
            val phase = o.optString("phase")
            val tool = o.optString("tool")
            onStep?.invoke(phase, tool, o.optString("args"), o.optBoolean("ok", true), o.optString("result"))
            if (phase == "end" && (tool.startsWith("screen_") || tool == "phone_open_app" || tool == "phone_nav")) {
                Preview.snap()
            }
            "ok"
        } catch (e: Exception) {
            "ERR:" + (e.message ?: e.toString())
        }
    }
}
