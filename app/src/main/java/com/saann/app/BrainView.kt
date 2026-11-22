package com.saann.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import org.json.JSONObject
import java.util.Random
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class BrainView(ctx: Context) : View(ctx) {
    private class Node(val name: String, val x: Float, val y: Float, val z: Float, val hub: Boolean) {
        var act = 0f
        var target = 0f
        var bump = 0f
        val sat = Array(10) { FloatArray(4) }
        fun eff(): Float = if (act > bump) act else bump
    }

    private class Dot(val node: Node, val sx: Float, val sy: Float, val sz: Float, val z: Float)

    private val d = resources.displayMetrics.density
    private val nodes = LinkedHashMap<String, Node>()
    private val edges = ArrayList<Pair<Node, Node>>()
    private val pos = mapOf(
        "language" to floatArrayOf(-0.62f, 0.10f, 0.20f),
        "visual" to floatArrayOf(0.45f, 0.10f, -0.70f),
        "color" to floatArrayOf(0.70f, 0.30f, -0.35f),
        "audio" to floatArrayOf(-0.55f, -0.40f, 0.05f),
        "touch" to floatArrayOf(0.00f, 0.60f, -0.10f),
        "smell" to floatArrayOf(0.15f, -0.15f, 0.85f),
        "thalamus" to floatArrayOf(0.00f, 0.00f, 0.00f),
        "hippocampus" to floatArrayOf(-0.10f, -0.28f, -0.20f),
        "amygdala" to floatArrayOf(0.22f, -0.32f, 0.20f),
        "cerebellum" to floatArrayOf(0.00f, -0.58f, -0.72f),
        "prefrontal" to floatArrayOf(0.00f, 0.30f, 0.85f),
        "motor" to floatArrayOf(0.00f, 0.62f, 0.28f)
    )
    private val hubs = setOf("thalamus", "hippocampus", "amygdala", "cerebellum", "prefrontal", "motor")
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f * resources.displayMetrics.scaledDensity }

    private var t = 0f
    private var yaw = 0f
    private var pitch = 0.25f
    private var lastX = 0f
    private var lastY = 0f
    private var concepts = 0
    private var novelty = 0f
    private var left = 0f
    private var right = 0f
    @Volatile private var running = false
    private val out = FloatArray(4)

    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t += 0.016f
            for (n in nodes.values) {
                n.act += (n.target - n.act) * 0.15f
                n.bump *= 0.96f
            }
            invalidate()
        }
    }

    init {
        setBackgroundColor(0xFF04070C.toInt())
        for (n in pos.keys) ensure(n)
    }

    private fun ensure(name: String): Node {
        nodes[name]?.let { return it }
        val rnd = Random(name.hashCode().toLong())
        val p = pos[name] ?: run {
            val th = rnd.nextFloat() * 6.2832f
            val ph = (rnd.nextFloat() - 0.5f) * 2.4f
            floatArrayOf(0.95f * cos(th) * cos(ph), 0.95f * sin(ph), 0.95f * sin(th) * cos(ph))
        }
        val n = Node(name, p[0], p[1], p[2], name in hubs)
        for (s in n.sat) {
            s[0] = (rnd.nextFloat() - 0.5f) * 0.34f
            s[1] = (rnd.nextFloat() - 0.5f) * 0.34f
            s[2] = (rnd.nextFloat() - 0.5f) * 0.34f
            s[3] = rnd.nextFloat() * 6.28f
        }
        nodes[name] = n
        rebuildEdges()
        return n
    }

    private fun rebuildEdges() {
        edges.clear()
        val th = nodes["thalamus"] ?: return
        for (n in nodes.values) if (!n.hub) edges.add(Pair(n, th))
        fun link(a: String, b: String) {
            val x = nodes[a]
            val y = nodes[b]
            if (x != null && y != null) edges.add(Pair(x, y))
        }
        link("thalamus", "prefrontal"); link("thalamus", "hippocampus"); link("hippocampus", "amygdala")
        link("prefrontal", "motor"); link("cerebellum", "motor"); link("thalamus", "cerebellum")
        link("amygdala", "prefrontal"); link("language", "prefrontal"); link("visual", "hippocampus")
    }

    fun pulse(textIn: String) {
        val l = textIn.lowercase()
        nodes["motor"]?.bump = 1f
        nodes["prefrontal"]?.bump = 0.8f
        nodes["cerebellum"]?.bump = 0.6f
        if ("screen" in l || "look" in l) nodes["visual"]?.bump = 0.9f
        if ("speak" in l || "listen" in l) nodes["audio"]?.bump = 0.9f
        if ("tap" in l || "swipe" in l || "click" in l || "scroll" in l) nodes["touch"]?.bump = 0.9f
    }

    fun start() {
        if (running) return
        running = true
        Thread {
            while (running) {
                val s = Host.snapshot()
                post { take(s) }
                try { Thread.sleep(500) } catch (e: InterruptedException) { break }
            }
        }.start()
    }

    fun stop() { running = false }

    private fun take(json: String) {
        try {
            val o = JSONObject(json)
            val arr = o.optJSONArray("nodes")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    val name = e.optString("name")
                    if (name.isEmpty()) continue
                    ensure(name).target = e.optDouble("act", 0.0).toFloat().coerceIn(0f, 1f)
                }
            }
            concepts = o.optInt("concepts", concepts)
            novelty = o.optDouble("novelty", novelty.toDouble()).toFloat()
            left = o.optDouble("left", left.toDouble()).toFloat()
            right = o.optDouble("right", right.toDouble()).toFloat()
        } catch (e: Exception) {
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!anim.isStarted) anim.start()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        running = false
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.action) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = ev.x
                lastY = ev.y
            }
            MotionEvent.ACTION_MOVE -> {
                yaw += (ev.x - lastX) * 0.01f
                pitch = (pitch + (ev.y - lastY) * 0.006f).coerceIn(-1.1f, 1.1f)
                lastX = ev.x
                lastY = ev.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    private fun colorOf(name: String): Int = when {
        name == "language" -> 0xFF00E5FF.toInt()
        name == "visual" || name == "color" -> 0xFFB47AFF.toInt()
        name in hubs -> 0xFFFFC857.toInt()
        else -> 0xFF00E5A0.toInt()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val cy = h / 2f
        val sc = min(w, h) * 0.42f
        val ay = yaw + t * 0.35f
        val ca = cos(ay); val sa = sin(ay); val cp = cos(pitch); val sp = sin(pitch)

        fun proj(x: Float, y: Float, z: Float) {
            val x1 = x * ca + z * sa
            val z1 = -x * sa + z * ca
            val y1 = y * cp - z1 * sp
            val z2 = y * sp + z1 * cp
            val k = 2.4f / (2.4f + z2)
            out[0] = cx + x1 * sc * k
            out[1] = cy - y1 * sc * k
            out[2] = k
            out[3] = z2
        }

        // links and travelling pulses
        for ((idx, e) in edges.withIndex()) {
            proj(e.first.x, e.first.y, e.first.z)
            val ax = out[0]; val ay2 = out[1]; val ak = out[2]
            proj(e.second.x, e.second.y, e.second.z)
            val bx = out[0]; val by = out[1]; val bk = out[2]
            val act = if (e.first.eff() > e.second.eff()) e.first.eff() else e.second.eff()
            line.color = colorOf(e.first.name)
            line.alpha = ((22 + 140 * act) * (ak + bk) / 2f).toInt().coerceIn(0, 255)
            line.strokeWidth = (0.8f + 2f * act) * d
            c.drawLine(ax, ay2, bx, by, line)
            if (act > 0.12f) {
                val ph = (t * 0.9f + idx * 0.37f) % 1f
                fill.color = 0xFFE8FBFF.toInt()
                fill.alpha = (120 + 135 * act).toInt().coerceIn(0, 255)
                c.drawCircle(ax + (bx - ax) * ph, ay2 + (by - ay2) * ph, (1.6f + 2.4f * act) * d, fill)
            }
        }

        // nodes and satellite neurons, far ones first
        val dots = ArrayList<Dot>()
        for (n in nodes.values) {
            proj(n.x, n.y, n.z)
            dots.add(Dot(n, out[0], out[1], out[2], out[3]))
        }
        dots.sortByDescending { it.z }
        for (dt in dots) {
            val n = dt.node
            val eff = n.eff()
            val col = colorOf(n.name)
            for (s in n.sat) {
                val o = 0.02f * sin(t * 2f + s[3])
                proj(n.x + s[0] + o, n.y + s[1] + o, n.z + s[2] - o)
                fill.color = col
                fill.alpha = ((35 + 170 * eff) * out[2]).toInt().coerceIn(0, 255)
                c.drawCircle(out[0], out[1], 1.3f * d * out[2], fill)
                if (eff > 0.25f) {
                    line.color = col
                    line.alpha = (45 * eff).toInt()
                    line.strokeWidth = 0.6f * d
                    c.drawLine(dt.sx, dt.sy, out[0], out[1], line)
                }
            }
            val r = (4f + 7f * eff) * d * dt.sz
            fill.color = col
            fill.alpha = (25 + 110 * eff).toInt()
            c.drawCircle(dt.sx, dt.sy, r * 3.2f, fill)
            fill.alpha = (90 + 150 * eff).toInt()
            c.drawCircle(dt.sx, dt.sy, r * 1.6f, fill)
            fill.alpha = 255
            c.drawCircle(dt.sx, dt.sy, r * 0.7f, fill)
            text.color = col
            text.alpha = (110 + 120 * dt.sz.coerceAtMost(1f)).toInt().coerceIn(0, 255)
            c.drawText(n.name, dt.sx + r * 1.8f, dt.sy + r * 0.4f, text)
        }

        text.color = 0xFF6FD6E6.toInt()
        text.alpha = 230
        c.drawText("BRAIN   concepts $concepts   novelty ${"%.2f".format(novelty)}", 10f * d, 16f * d, text)
        c.drawText("L ${"%.2f".format(left)}   R ${"%.2f".format(right)}   drag to rotate", 10f * d, h - 8f * d, text)
    }
}
