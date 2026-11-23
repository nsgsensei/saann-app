package com.saann.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.MotionEvent
import android.view.View
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class BrainView(context: Context) : View(context), SensorEventListener {

    private enum class Region(val label: String) {
        LANGUAGE("LANGUAGE"),
        VISION("VISION"),
        SENSES("SENSES"),
        CEREBELLUM("CEREBELLUM"),
        MOTOR("MOTOR CORTEX"),
        MEMORY("MEMORY"),
        ATTENTION("ATTENTION")
    }

    private class Node(
        val x: Float,
        val y: Float,
        val z: Float,
        val region: Region
    )

    private class Link(
        val a: Int,
        val b: Int,
        val association: Boolean
    )

    private class Pulse(
        val link: Int,
        var t: Float,
        val speed: Float,
        val color: Int
    )

    private val random = Random(2026L)
    private val nodes = ArrayList<Node>()
    private val links = ArrayList<Link>()
    private val pulses = ArrayList<Pulse>()

    private val activation = HashMap<Region, Float>()
    private val targetActivation = HashMap<Region, Float>()

    private var yaw = 0.55f
    private var pitch = 0.22f
    private var zoom = 1.0f
    private var tiltYaw = 0f
    private var tiltPitch = 0f

    private var activity = 0.34f
    private var time = 0f
    private var running = false
    private var sensorsOn = false

    private var lastX = 0f
    private var lastY = 0f
    private var lastPinch = 0f

    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val loop = object : Runnable {
        override fun run() {
            update()
            postInvalidateOnAnimation()
            postDelayed(this, 16)
        }
    }

    init {
        buildBrain()

        linkPaint.style = Paint.Style.STROKE
        linkPaint.strokeCap = Paint.Cap.ROUND
        pulsePaint.style = Paint.Style.FILL
        glowPaint.style = Paint.Style.FILL
        nodePaint.style = Paint.Style.FILL

        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        labelPaint.color = Color.argb(215, 225, 245, 255)

        for (region in Region.values()) {
            activation[region] = 0.20f
            targetActivation[region] = 0.20f
        }
    }

    private fun buildBrain() {
        val centers = HashMap<Region, FloatArray>()

        centers[Region.LANGUAGE] = floatArrayOf(0.92f, 0.38f, 0.10f)
        centers[Region.VISION] = floatArrayOf(-0.92f, 0.48f, -0.18f)
        centers[Region.SENSES] = floatArrayOf(0.02f, 0.82f, -0.62f)
        centers[Region.CEREBELLUM] = floatArrayOf(0.02f, -0.88f, -0.38f)
        centers[Region.MOTOR] = floatArrayOf(0.78f, -0.48f, 0.38f)
        centers[Region.MEMORY] = floatArrayOf(-0.78f, -0.38f, 0.38f)
        centers[Region.ATTENTION] = floatArrayOf(0.02f, 0.06f, 0.78f)

        val regionNodes = HashMap<Region, ArrayList<Int>>()

        for (region in Region.values()) {
            val center = centers[region] ?: floatArrayOf(0f, 0f, 0f)
            val ids = ArrayList<Int>()

            for (i in 0 until 8) {
                val angle = (i.toFloat() / 8f) * 6.2831853f
                val radius = 0.17f + 0.05f * sin(angle * 2.7f + region.ordinal)

                val x = center[0] + cos(angle) * radius
                val y = center[1] + sin(angle) * radius * 0.85f
                val z = center[2] + sin(angle * 1.7f) * radius * 0.75f

                nodes.add(Node(x, y, z, region))
                ids.add(nodes.size - 1)
            }

            regionNodes[region] = ids

            for (i in ids.indices) {
                val a = ids[i]
                val b = ids[(i + 1) % ids.size]
                links.add(Link(a, b, false))

                if (i % 2 == 0) {
                    val c = ids[(i + 3) % ids.size]
                    links.add(Link(a, c, false))
                }
            }
        }

        val attention = regionNodes[Region.ATTENTION] ?: ArrayList()

        for (region in Region.values()) {
            if (region == Region.ATTENTION) continue
            val ids = regionNodes[region] ?: continue

            for (k in 0 until 3) {
                val a = attention[random.nextInt(attention.size)]
                val b = ids[random.nextInt(ids.size)]
                links.add(Link(a, b, true))
            }
        }

        val associations = arrayOf(
            Region.LANGUAGE to Region.MEMORY,
            Region.LANGUAGE to Region.ATTENTION,
            Region.VISION to Region.MEMORY,
            Region.VISION to Region.ATTENTION,
            Region.SENSES to Region.CEREBELLUM,
            Region.SENSES to Region.ATTENTION,
            Region.CEREBELLUM to Region.MOTOR,
            Region.MOTOR to Region.ATTENTION,
            Region.MEMORY to Region.ATTENTION,
            Region.LANGUAGE to Region.SENSES,
            Region.VISION to Region.LANGUAGE,
            Region.MOTOR to Region.MEMORY
        )

        for (pair in associations) {
            val a = regionNodes[pair.first] ?: continue
            val b = regionNodes[pair.second] ?: continue

            for (k in 0 until 3) {
                links.add(
                    Link(
                        a[random.nextInt(a.size)],
                        b[random.nextInt(b.size)],
                        true
                    )
                )
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        post(loop)
        enableSensors()
    }

    fun stop() {
        running = false
        removeCallbacks(loop)
        disableSensors()
    }

    fun pulse(text: String) {
        val message = text.lowercase()
        val active = HashSet<Region>()

        active.add(Region.ATTENTION)

        if (containsAny(
                message,
                "language", "text", "word", "say", "speak", "chat",
                "reply", "grammar", "translate", "write", "read", "ask"
            )
        ) {
            active.add(Region.LANGUAGE)
        }

        if (containsAny(
                message,
                "image", "photo", "camera", "see", "visual", "picture",
                "scan", "look", "vision", "color"
            )
        ) {
            active.add(Region.VISION)
        }

        if (containsAny(
                message,
                "sensor", "touch", "hear", "sound", "audio", "mic",
                "voice", "feel", "sense", "listen"
            )
        ) {
            active.add(Region.SENSES)
        }

        if (containsAny(
                message,
                "balance", "motion", "coordination", "timing", "rhythm",
                "movement", "posture"
            )
        ) {
            active.add(Region.CEREBELLUM)
        }

        if (containsAny(
                message,
                "move", "action", "motor", "gesture", "control",
                "drive", "run", "click", "press"
            )
        ) {
            active.add(Region.MOTOR)
        }

        if (containsAny(
                message,
                "remember", "memory", "history", "store", "recall",
                "context", "previous", "earlier", "save"
            )
        ) {
            active.add(Region.MEMORY)
        }

        if (active.size == 1) {
            active.add(Region.LANGUAGE)
        }

        activity = min(1.45f, activity + 0.45f)

        for (region in Region.values()) {
            if (active.contains(region)) {
                targetActivation[region] = 1.35f
            } else {
                targetActivation[region] = 0.20f
            }
        }

        val count = 9 + (abs(text.hashCode()) % 10)

        for (i in 0 until count) {
            if (links.isEmpty()) break
            pulses.add(
                Pulse(
                    random.nextInt(links.size),
                    random.nextFloat() * 0.25f,
                    0.011f + random.nextFloat() * 0.019f,
                    regionColor(active.random())
                )
            )
        }
    }

    private fun containsAny(text: String, vararg words: String): Boolean {
        for (word in words) {
            if (text.contains(word)) return true
        }
        return false
    }

    private fun regionColor(region: Region): Int {
        return when (region) {
            Region.LANGUAGE -> Color.rgb(95, 205, 255)
            Region.VISION -> Color.rgb(135, 255, 190)
            Region.SENSES -> Color.rgb(255, 205, 105)
            Region.CEREBELLUM -> Color.rgb(175, 150, 255)
            Region.MOTOR -> Color.rgb(255, 120, 150)
            Region.MEMORY -> Color.rgb(255, 175, 95)
            Region.ATTENTION -> Color.rgb(235, 235, 255)
        }
    }

    private fun enableSensors() {
        if (sensorsOn) return
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor != null) {
            sensorManager?.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_GAME
            )
            sensorsOn = true
        }
    }

    private fun disableSensors() {
        if (!sensorsOn) return
        sensorManager?.unregisterListener(this)
        sensorsOn = false
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

        val matrix = FloatArray(9)
        val orientation = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        SensorManager.getOrientation(matrix, orientation)

        tiltYaw = orientation[2] * 0.30f
        tiltPitch = orientation[1] * 0.30f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    lastPinch = pinchDistance(event)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val distance = pinchDistance(event)
                    if (lastPinch > 0f) {
                        zoom = (zoom * distance / lastPinch).coerceIn(0.55f, 2.20f)
                    }
                    lastPinch = distance
                } else {
                    yaw += (event.x - lastX) * 0.0065f
                    pitch = (pitch + (event.y - lastY) * 0.0065f)
                        .coerceIn(-1.15f, 1.15f)
                }

                lastX = event.x
                lastY = event.y
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lastPinch = 0f
            }
        }

        return true
    }

    private fun pinchDistance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    private fun update() {
        time += 0.016f

        if (lastPinch == 0f) {
            yaw += 0.0021f
        }

        yaw += (tiltYaw - yaw * 0.06f) * 0.02f
        pitch += (tiltPitch - pitch * 0.06f) * 0.02f

        activity = max(0.34f, activity * 0.988f)

        for (region in Region.values()) {
            val current = activation[region] ?: 0.20f
            val target = targetActivation[region] ?: 0.20f
            activation[region] = current + (target - current) * 0.055f
        }

        val iterator = pulses.iterator()
        while (iterator.hasNext()) {
            val pulse = iterator.next()
            pulse.t += pulse.speed * (0.75f + activity)
            if (pulse.t > 1f) iterator.remove()
        }

        if (activity > 0.30f && random.nextFloat() < activity * 0.34f && links.isNotEmpty()) {
            val link = links[random.nextInt(links.size)]
            val regionA = nodes[link.a].region
            val regionB = nodes[link.b].region

            pulses.add(
                Pulse(
                    links.indexOf(link),
                    0f,
                    0.012f + random.nextFloat() * 0.016f,
                    if (link.association) {
                        Color.rgb(240, 240, 255)
                    } else {
                        regionColor(
                            if (random.nextBoolean()) regionA else regionB
                        )
                    }
                )
            )
        }
    }

    private fun project(
        x: Float,
        y: Float,
        z: Float,
        centerX: Float,
        centerY: Float,
        scale: Float
    ): FloatArray {
        val cy = cos(yaw)
        val sy = sin(yaw)
        val x1 = x * cy - z * sy
        val z1 = x * sy + z * cy

        val cp = cos(pitch)
        val sp = sin(pitch)
        val y1 = y * cp - z1 * sp
        val z2 = y * sp + z1 * cp

        val focal = 3.1f
        val perspective = focal / max(0.35f, focal - z2 * 0.85f)

        return floatArrayOf(
            centerX + x1 * scale * perspective,
            centerY + y1 * scale * perspective,
            z2
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return

        val centerX = width * 0.5f
        val centerY = height * 0.5f
        val base = min(width, height) * 0.40f * zoom

        val projected = Array(nodes.size) {
            val node = nodes[it]
            project(node.x, node.y, node.z, centerX, centerY, base)
        }

        val breath = 0.5f + 0.5f * sin(time * 1.7f)
        val energy = 0.20f + activity * 0.80f

        for (i in links.indices) {
            val link = links[i]
            val a = projected[link.a]
            val b = projected[link.b]

            val regionA = nodes[link.a].region
            val regionB = nodes[link.b].region

            val regionEnergy = max(
                activation[regionA] ?: 0.20f,
                activation[regionB] ?: 0.20f
            )

            val depth = (a[2] + b[2]) * 0.5f
            val near = (1f - (depth + 1.1f) / 2.2f).coerceIn(0.10f, 1f)

            val alpha = (20f + 74f * energy * near + 30f * breath +
                65f * regionEnergy * near).toInt().coerceIn(0, 215)

            val color = if (link.association) {
                Color.argb(alpha, 225, 225, 255)
            } else {
                val base = regionColor(regionA)
                Color.argb(
                    alpha,
                    Color.red(base),
                    Color.green(base),
                    Color.blue(base)
                )
            }

            linkPaint.color = color
            linkPaint.strokeWidth = 1.4f + 2.3f * energy * near +
                2.1f * regionEnergy * near
            canvas.drawLine(a[0], a[1], b[0], b[1], linkPaint)
        }

        for (pulse in pulses) {
            if (pulse.link >= links.size) continue
            val link = links[pulse.link]
            val a = projected[link.a]
            val b = projected[link.b]

            val x = a[0] + (b[0] - a[0]) * pulse.t
            val y = a[1] + (b[1] - a[1]) * pulse.t
            val fade = (1f - abs(pulse.t - 0.5f) * 2f).coerceIn(0f, 1f)
            val radius = 2.7f + 5.8f * fade * (0.65f + activity)

            pulsePaint.color = pulse.color
            pulsePaint.alpha = (55f + 185f * fade * (0.45f + activity)).toInt()
                .coerceIn(0, 255)
            canvas.drawCircle(x, y, radius, pulsePaint)
        }

        for (i in nodes.indices) {
            val node = nodes[i]
            val point = projected[i]
            val depth = point[2]
            val near = (1f - (depth + 1.1f) / 2.2f).coerceIn(0.12f, 1f)
            val regionEnergy = activation[node.region] ?: 0.20f

            val wave = 0.5f + 0.5f * sin(
                time * 2.1f + node.region.ordinal * 0.55f + i * 0.22f
            )

            val radius = (2.4f + 4.0f * near) *
                (0.92f + 0.28f * wave + 0.55f * activity) *
                (0.80f + 0.55f * regionEnergy)

            val color = regionColor(node.region)

            glowPaint.shader = RadialGradient(
                point[0],
                point[1],
                radius * 3.4f,
                Color.argb(
                    (105f * near * energy * regionEnergy).toInt().coerceIn(0, 255),
                    Color.red(color),
                    Color.green(color),
                    Color.blue(color)
                ),
                Color.argb(0, Color.red(color), Color.green(color), Color.blue(color)),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(point[0], point[1], radius * 3.4f, glowPaint)

            nodePaint.color = color
            nodePaint.alpha = (95f + 145f * near * energy * regionEnergy)
                .toInt().coerceIn(0, 255)
            canvas.drawCircle(point[0], point[1], radius, nodePaint)
        }

        val density = resources.displayMetrics.density
        labelPaint.textSize = 10.5f * density

        for (region in Region.values()) {
            var sumX = 0f
            var sumY = 0f
            var sumZ = 0f
            var count = 0

            for (i in nodes.indices) {
                if (nodes[i].region != region) continue
                sumX += projected[i][0]
                sumY += projected[i][1]
                sumZ += projected[i][2]
                count++
            }

            if (count == 0) continue

            val x = sumX / count
            val y = sumY / count
            val z = sumZ / count

            val near = (1f - (z + 1.1f) / 2.2f).coerceIn(0.18f, 1f)
            val regionEnergy = activation[region] ?: 0.20f

            labelPaint.alpha = (55f + 175f * near * (0.45f + regionEnergy))
                .toInt().coerceIn(0, 255)

            canvas.drawText(
                region.label,
                x,
                y - 14f * density * (0.75f + regionEnergy * 0.55f),
                labelPaint
            )
        }
    }
}
