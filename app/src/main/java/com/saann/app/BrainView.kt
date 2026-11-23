package com.saann.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import java.util.Random

class BrainView(context: Context) : View(context), SensorEventListener {

    private class Node(val x: Float, val y: Float, val z: Float, val layer: Int)

    private class Link(val a: Int, val b: Int)

    private class Pulse(val link: Int, var t: Float, val speed: Float, val color: Int)

    private val random = Random(2026L)
    private val nodes = ArrayList<Node>()
    private val links = ArrayList<Link>()
    private val pulses = ArrayList<Pulse>()

    private var yaw = 0.55f
    private var pitch = 0.22f
    private var zoom = 1.0f
    private var tiltYaw = 0f
    private var tiltPitch = 0f

    private var activity = 0.18f
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
        buildNetwork()
        linkPaint.style = Paint.Style.STROKE
        linkPaint.strokeCap = Paint.Cap.ROUND
        pulsePaint.style = Paint.Style.FILL
        glowPaint.style = Paint.Style.FILL
        nodePaint.style = Paint.Style.FILL
    }

    private fun buildNetwork() {
        val layers = intArrayOf(10, 18, 18, 12, 6)
        val first = ArrayList<Int>()

        for (layer in layers.indices) {
            val count = layers[layer]
            val ids = ArrayList<Int>()

            for (i in 0 until count) {
                val angle = (i.toFloat() / count) * 6.2831853f
                val spread = 0.62f + layer * 0.045f
                val radius = 0.42f + 0.16f * sin(angle * 2.3f + layer)
                val x = (layer - (layers.size - 1) / 2f) * 0.42f
                val y = cos(angle) * radius * spread
                val z = sin(angle) * radius * spread
                nodes.add(Node(x, y, z, layer))
                ids.add(nodes.size - 1)
            }

            if (layer > 0) {
                val previous = first
                for (id in ids) {
                    val connections = 2 + random.nextInt(3)
                    for (k in 0 until connections) {
                        val target = previous[random.nextInt(previous.size)]
                        links.add(Link(target, id))
                    }
                }
            }

            first.clear()
            first.addAll(ids)
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
        activity = min(1.35f, activity + 0.42f)

        val seed = abs(text.hashCode())
        val hue = (seed % 360) / 360f
        val color = Color.HSVToColor(
            floatArrayOf(hue * 360f, 0.72f, 1f)
        )

        val count = 7 + (seed % 9)
        for (i in 0 until count) {
            if (links.isEmpty()) break
            pulses.add(
                Pulse(
                    random.nextInt(links.size),
                    random.nextFloat() * 0.25f,
                    0.010f + random.nextFloat() * 0.018f,
                    color
                )
            )
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

        activity = max(0.16f, activity * 0.986f)

        val iterator = pulses.iterator()
        while (iterator.hasNext()) {
            val pulse = iterator.next()
            pulse.t += pulse.speed * (0.75f + activity)
            if (pulse.t > 1f) iterator.remove()
        }

        if (activity > 0.30f && random.nextFloat() < activity * 0.20f && links.isNotEmpty()) {
            pulses.add(
                Pulse(
                    random.nextInt(links.size),
                    0f,
                    0.012f + random.nextFloat() * 0.016f,
                    Color.HSVToColor(
                        floatArrayOf(
                            (time * 22f + random.nextInt(70)) % 360f,
                            0.68f,
                            1f
                        )
                    )
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
        val base = min(width, height) * 0.31f * zoom

        val projected = Array(nodes.size) {
            val node = nodes[it]
            project(node.x, node.y, node.z, centerX, centerY, base)
        }

        val breath = 0.5f + 0.5f * sin(time * 1.7f)
        val energy = 0.16f + activity * 0.84f

        for (i in links.indices) {
            val link = links[i]
            val a = projected[link.a]
            val b = projected[link.b]

            val depth = (a[2] + b[2]) * 0.5f
            val near = (1f - (depth + 1.1f) / 2.2f).coerceIn(0.10f, 1f)

            val alpha = (18f + 62f * energy * near + 24f * breath).toInt()
                .coerceIn(0, 190)

            linkPaint.color = Color.argb(alpha, 90, 190, 255)
            linkPaint.strokeWidth = 1.1f + 1.7f * energy * near
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
            val radius = 2.2f + 4.6f * fade * (0.55f + activity)

            pulsePaint.color = pulse.color
            pulsePaint.alpha = (55f + 180f * fade * (0.45f + activity)).toInt()
                .coerceIn(0, 255)
            canvas.drawCircle(x, y, radius, pulsePaint)
        }

        for (i in nodes.indices) {
            val node = nodes[i]
            val point = projected[i]
            val depth = point[2]
            val near = (1f - (depth + 1.1f) / 2.2f).coerceIn(0.12f, 1f)

            val wave = 0.5f + 0.5f * sin(
                time * 2.1f + node.layer * 0.55f + i * 0.22f
            )

            val radius = (2.0f + 3.2f * near) *
                (0.82f + 0.20f * wave + 0.42f * activity)

            val hue = 190f + node.layer * 16f + wave * 22f
            val color = Color.HSVToColor(
                floatArrayOf(hue % 360f, 0.72f, 1f)
            )

            glowPaint.shader = RadialGradient(
                point[0],
                point[1],
                radius * 3.4f,
                Color.argb((90f * near * energy).toInt(), 120, 220, 255),
                Color.argb(0, 120, 220, 255),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(point[0], point[1], radius * 3.4f, glowPaint)

            nodePaint.color = color
            nodePaint.alpha = (95f + 145f * near * energy).toInt().coerceIn(0, 255)
            canvas.drawCircle(point[0], point[1], radius, nodePaint)
        }
    }
}
