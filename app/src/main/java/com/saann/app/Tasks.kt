package com.saann.app

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray

object Tasks {
    private const val INK = 0xFFF3F0EB.toInt()
    private const val DIM = 0xFFB0B9CB.toInt()
    private const val CYAN = 0xFFE8BC9F.toInt()

    fun show(a: Activity) {
        val den = a.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * den).toInt()
        val (d, box) = Ui2.sheet(a)
        val t = TextView(a)
        t.text = "A little structure"
        t.setTextColor(INK)
        t.textSize = 23f
        t.letterSpacing = 0f
        t.setTypeface(t.typeface, Typeface.BOLD)
        box.addView(t)
        val sv = ScrollView(a)
        val body = LinearLayout(a)
        body.orientation = LinearLayout.VERTICAL
        sv.addView(body)
        sv.isVerticalScrollBarEnabled = false
        box.addView(
            sv,
            LinearLayout.LayoutParams(
                MATCH_PARENT,
                minOf(
                    dp(420),
                    (a.resources.displayMetrics.heightPixels * 0.55f).toInt()
                )
            )
        )

        fun toast(s: String) = a.runOnUiThread { Toast.makeText(a, s, Toast.LENGTH_LONG).show() }

        lateinit var load: () -> Unit

        fun newTask() {
            val ll = LinearLayout(a)
            ll.orientation = LinearLayout.VERTICAL
            ll.setPadding(dp(20), dp(8), dp(20), 0)
            val e1 = EditText(a)
            e1.hint = "What should I do?"
            val e2 = EditText(a)
            e2.hint = "daily 07:30   or   every 60"
            e2.setText("daily 08:00")
            ll.addView(e1)
            ll.addView(e2)
            AlertDialog.Builder(a).setTitle("New task").setView(ll)
                .setPositiveButton("Add") { _, _ ->
                    val req = e1.text.toString().trim()
                    val spec = e2.text.toString().trim()
                    Thread {
                        val r = try { Host.py?.callAttr("task_add", req, spec)?.toString() ?: "ERR:still starting" } catch (e: Throwable) { "ERR:" + e.message }
                        if (r.startsWith("ERR:")) toast(r.drop(4)) else {
                            toast("Task added")
                            a.runOnUiThread { load() }
                        }
                    }.start()
                }.setNegativeButton("Cancel", null).show()
        }

        fun options(id: Int, enabled: Boolean) {
            AlertDialog.Builder(a)
                .setItems(arrayOf("Run now", if (enabled) "Pause" else "Resume", "Delete")) { _, w ->
                    Thread {
                        try {
                            when (w) {
                                0 -> {
                                    toast("Running...")
                                    val r = Host.py?.callAttr("task_run_now", id)?.toString() ?: ""
                                    AgentService.notifyReply(a, "Task finished", r.take(900))
                                }
                                1 -> Host.py?.callAttr("task_toggle", id)
                                else -> Host.py?.callAttr("task_remove", id)
                            }
                        } catch (e: Throwable) {
                            toast("Failed: " + e.message)
                        }
                        a.runOnUiThread { load() }
                    }.start()
                }.show()
        }

        fun render(json: String) {
            body.removeAllViews()
            val add = TextView(a)
            add.text = "+  New task"
            add.setTextColor(CYAN)
            add.textSize = 15f
            add.setPadding(dp(16), dp(14), dp(16), dp(14))
            add.minimumHeight = dp(52)
            add.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(17).toFloat()
                setColor(0xFF352E2C.toInt())
                setStroke(dp(1), 0x44E8BC9F)
            }
            pressFx(add)
            add.setOnClickListener { newTask() }
            body.addView(add)
            val arr = try { JSONArray(json) } catch (e: Exception) { JSONArray() }
            if (arr.length() == 0) {
                val e = TextView(a)
                e.text = "No tasks yet. They run while the Agent is on."
                e.setTextColor(DIM)
                e.textSize = 13f
                body.addView(e)
            }
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val row = LinearLayout(a)
                row.orientation = LinearLayout.VERTICAL
                row.setPadding(dp(16), dp(14), dp(16), dp(14))
                row.minimumHeight = dp(76)
                row.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(0xFF232B38.toInt())
                    setStroke(
                        dp(1),
                        if (o.optBoolean("enabled")) 0x449DD6C4
                        else 0x44E8BC9F
                    )
                }
                row.layoutParams = LinearLayout.LayoutParams(
                    MATCH_PARENT, WRAP_CONTENT
                ).apply {
                    topMargin = dp(10)
                }
                pressFx(row)
                val n = TextView(a)
                n.text = o.optString("name") +
                    (if (o.optBoolean("enabled")) " · Enabled" else " · Paused")
                n.setTextColor(
                    if (o.optBoolean("enabled")) INK else 0xFFE8BC9F.toInt()
                )
                n.textSize = 15f
                row.addView(n)
                val w = TextView(a)
                w.text = o.optString("when") + " · next " + o.optString("next")
                w.setTextColor(DIM)
                w.textSize = 12f
                w.setPadding(0, dp(7), 0, 0)
                row.addView(w)
                if (o.optString("last").isNotEmpty()) {
                    val l = TextView(a)
                    l.text = "last: " + o.optString("last")
                    l.setTextColor(DIM)
                    l.textSize = 12f
                    row.addView(l)
                }
                val id = o.getInt("id")
                val en = o.optBoolean("enabled")
                row.setOnClickListener { options(id, en) }
                body.addView(row)
            }
        }

        load = {
            Thread {
                val j = try { Host.py?.callAttr("tasks_json")?.toString() ?: "[]" } catch (e: Throwable) { "[]" }
                a.runOnUiThread { render(j) }
            }.start()
        }
        d.show()
        d.window?.setLayout(MATCH_PARENT, WRAP_CONTENT)
        load()
    }
}
