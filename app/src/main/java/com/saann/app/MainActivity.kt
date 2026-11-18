package com.saann.app

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

class MainActivity : Activity() {
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var py: PyObject
    private var ready = false

    private val keep = setOf(
        "config/mind.json", "config/personality.json",
        "config/license.json", "config/models.json"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
        }
        scroll = ScrollView(this)
        log = TextView(this).apply {
            textSize = 16f
            setTextIsSelectable(true)
        }
        scroll.addView(log)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        input = EditText(this).apply {
            hint = "Talk to SAANN"
            maxLines = 4
        }
        send = Button(this).apply {
            text = "Send"
            isEnabled = false
        }
        row.addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(send)
        root.addView(row)
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        add("SAANN", "Starting up...")

        Thread {
            try {
                copyDir("saann", File(filesDir, "saann"), "")
                if (!Python.isStarted()) Python.start(AndroidPlatform(this))
                py = Python.getInstance().getModule("app_main")
                py.callAttr("init", filesDir.absolutePath, Bridge(this))
                runOnUiThread {
                    ready = true
                    send.isEnabled = true
                    add("SAANN", "Ready.")
                }
            } catch (e: Throwable) {
                runOnUiThread { add("error", e.toString()) }
            }
        }.start()

        send.setOnClickListener {
            val t = input.text.toString().trim()
            if (t.isEmpty() || !ready) return@setOnClickListener
            input.setText("")
            add("you", t)
            send.isEnabled = false
            Thread {
                val r = try {
                    py.callAttr("respond", t).toString()
                } catch (e: Throwable) {
                    "error: " + e.message
                }
                runOnUiThread {
                    add("SAANN", r)
                    send.isEnabled = true
                }
            }.start()
        }
    }

    private fun add(who: String, text: String) {
        log.append("\n$who: $text\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun copyDir(assetPath: String, dest: File, rel: String) {
        val list = assets.list(assetPath) ?: return
        if (list.isEmpty()) {
            if (rel in keep && dest.exists()) return
            dest.parentFile?.mkdirs()
            assets.open(assetPath).use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        } else {
            dest.mkdirs()
            for (n in list) {
                copyDir("$assetPath/$n", File(dest, n), if (rel.isEmpty()) n else "$rel/$n")
            }
        }
    }
}
