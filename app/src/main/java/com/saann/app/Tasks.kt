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
    private const val INK = 0xFFF5F3EE.toInt()
    private const val DIM = 0xFF8B93A1.toInt()
    private const val CYAN = 0xFF00E5FF.toInt()

    fun show(a: Activity) {
        val den = a.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * den).toInt()
        val (d, box) = Ui2.sheet(a)
        val t = TextView(a)
        t.text = "TASKS"
        t.setTextColor(INK)
        t.textSize = 15f
        t.letterSpacing = 0.2f
        t.setTypeface(t.typeface, Typeface.BOLD)
        box.addView(t)
        val sv = ScrollView(a)
        val body = LinearLayout(a)
        body.orientation = LinearLayout.VERTICAL
        sv.addView(body)
        box.addView(sv, LinearLayout.LayoutParams(MATCH_PARENT, dp(340)))

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
            add.setPadding(0, dp(12), 0, dp(12))
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
                row.setPadding(0, dp(8), 0, dp(8))
                val n = TextView(a)
                n.text = o.optString("name") + (if (o.optBoolean("enabled")) "" else "  (paused)")
                n.setTextColor(INK)
                n.textSize = 15f
                row.addView(n)
                val w = TextView(a)
                w.text = o.optString("when") + " · next " + o.optString("next")
                w.setTextColor(DIM)
                w.textSize = 12f
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
        load()
    }
}
