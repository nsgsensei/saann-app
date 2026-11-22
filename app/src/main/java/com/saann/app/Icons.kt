package com.saann.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.Button
import kotlin.math.min

// Soft press feedback: the button shrinks a little while held and springs back.
fun pressFx(v: View) {
    v.setOnTouchListener { view, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> view.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                view.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(OvershootInterpolator(2.2f)).start()
        }
        false
    }
}

// Small line icons drawn in code, so no fonts or image files are needed.
object Icons {
    fun draw(c: Canvas, kind: String, x: Float, y: Float, s: Float, p: Paint) {
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeJoin = Paint.Join.ROUND
        val r = RectF()
        val path = Path()
        when (kind) {
            "plus" -> {
                c.drawLine(x - s, y, x + s, y, p)
                c.drawLine(x, y - s, x, y + s, p)
            }
            "close" -> {
                c.drawLine(x - s, y - s, x + s, y + s, p)
                c.drawLine(x + s, y - s, x - s, y + s, p)
            }
            "arrow" -> {
                c.drawLine(x, y + s, x, y - s, p)
                c.drawLine(x - s * 0.75f, y - s * 0.25f, x, y - s, p)
                c.drawLine(x + s * 0.75f, y - s * 0.25f, x, y - s, p)
            }
            "wave" -> {
                val hs = floatArrayOf(0.35f, 0.75f, 1f, 0.6f, 0.3f)
                for (i in hs.indices) {
                    val xx = x + (i - 2) * s * 0.55f
                    c.drawLine(xx, y - s * hs[i], xx, y + s * hs[i], p)
                }
            }
            "mic" -> {
                r.set(x - s * 0.4f, y - s, x + s * 0.4f, y + s * 0.2f)
                c.drawRoundRect(r, s * 0.4f, s * 0.4f, p)
                r.set(x - s * 0.75f, y - s * 0.55f, x + s * 0.75f, y + s * 0.7f)
                c.drawArc(r, 20f, 140f, false, p)
                c.drawLine(x, y + s * 0.7f, x, y + s, p)
            }
            "camera" -> {
                r.set(x - s, y - s * 0.55f, x + s, y + s * 0.75f)
                c.drawRoundRect(r, s * 0.2f, s * 0.2f, p)
                path.moveTo(x - s * 0.35f, y - s * 0.55f)
                path.lineTo(x - s * 0.15f, y - s * 0.85f)
                path.lineTo(x + s * 0.15f, y - s * 0.85f)
                path.lineTo(x + s * 0.35f, y - s * 0.55f)
                c.drawPath(path, p)
                c.drawCircle(x, y + s * 0.1f, s * 0.35f, p)
            }
            "image" -> {
                r.set(x - s, y - s * 0.85f, x + s, y + s * 0.85f)
                c.drawRoundRect(r, s * 0.2f, s * 0.2f, p)
                c.drawCircle(x - s * 0.4f, y - s * 0.3f, s * 0.18f, p)
                path.moveTo(x - s, y + s * 0.5f)
                path.lineTo(x - s * 0.3f, y)
                path.lineTo(x + s * 0.1f, y + s * 0.4f)
                path.lineTo(x + s * 0.5f, y + s * 0.05f)
                path.lineTo(x + s, y + s * 0.5f)
                c.drawPath(path, p)
            }
            "file" -> {
                path.moveTo(x - s * 0.65f, y - s)
                path.lineTo(x + s * 0.2f, y - s)
                path.lineTo(x + s * 0.65f, y - s * 0.55f)
                path.lineTo(x + s * 0.65f, y + s)
                path.lineTo(x - s * 0.65f, y + s)
                path.close()
                c.drawPath(path, p)
                c.drawLine(x, y + s * 0.5f, x, y - s * 0.15f, p)
                c.drawLine(x - s * 0.3f, y + s * 0.1f, x, y - s * 0.2f, p)
                c.drawLine(x + s * 0.3f, y + s * 0.1f, x, y - s * 0.2f, p)
            }
            "video" -> {
                r.set(x - s, y - s * 0.6f, x + s * 0.35f, y + s * 0.6f)
                c.drawRoundRect(r, s * 0.2f, s * 0.2f, p)
                path.moveTo(x + s * 0.55f, y - s * 0.25f)
                path.lineTo(x + s, y - s * 0.5f)
                path.lineTo(x + s, y + s * 0.5f)
                path.lineTo(x + s * 0.55f, y + s * 0.25f)
                path.close()
                c.drawPath(path, p)
            }
            "shot" -> {
                val k = s * 0.5f
                c.drawLine(x - s, y - s, x - s + k, y - s, p)
                c.drawLine(x - s, y - s, x - s, y - s + k, p)
                c.drawLine(x + s, y - s, x + s - k, y - s, p)
                c.drawLine(x + s, y - s, x + s, y - s + k, p)
                c.drawLine(x - s, y + s, x - s + k, y + s, p)
                c.drawLine(x - s, y + s, x - s, y + s - k, p)
                c.drawLine(x + s, y + s, x + s - k, y + s, p)
                c.drawLine(x + s, y + s, x + s, y + s - k, p)
                c.drawCircle(x, y, s * 0.22f, p)
            }
            "chat" -> {
                r.set(x - s, y - s * 0.8f, x + s, y + s * 0.5f)
                c.drawRoundRect(r, s * 0.4f, s * 0.4f, p)
                path.moveTo(x - s * 0.5f, y + s * 0.5f)
                path.lineTo(x - s * 0.65f, y + s)
                path.lineTo(x - s * 0.05f, y + s * 0.5f)
                c.drawPath(path, p)
            }
            "folder" -> {
                r.set(x - s, y - s * 0.55f, x + s, y + s * 0.75f)
                c.drawRoundRect(r, s * 0.15f, s * 0.15f, p)
                path.moveTo(x - s, y - s * 0.55f)
                path.lineTo(x - s * 0.55f, y - s * 0.85f)
                path.lineTo(x - s * 0.1f, y - s * 0.85f)
                path.lineTo(x + s * 0.15f, y - s * 0.55f)
                c.drawPath(path, p)
            }
            "check" -> {
                c.drawCircle(x, y, s * 0.95f, p)
                path.moveTo(x - s * 0.45f, y)
                path.lineTo(x - s * 0.1f, y + s * 0.35f)
                path.lineTo(x + s * 0.5f, y - s * 0.3f)
                c.drawPath(path, p)
            }
            "library" -> {
                r.set(x - s, y - s * 0.8f, x - s * 0.45f, y + s * 0.8f)
                c.drawRoundRect(r, s * 0.1f, s * 0.1f, p)
                r.set(x - s * 0.25f, y - s * 0.8f, x + s * 0.3f, y + s * 0.8f)
                c.drawRoundRect(r, s * 0.1f, s * 0.1f, p)
                r.set(x + s * 0.5f, y - s * 0.8f, x + s, y + s * 0.8f)
                c.drawRoundRect(r, s * 0.1f, s * 0.1f, p)
            }
            else -> {}
        }
    }
}

class IconView(ctx: Context, private val kind: String, var color: Int) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        p.color = color
        p.strokeWidth = resources.displayMetrics.density * 1.8f
        Icons.draw(canvas, kind, width / 2f, height / 2f, min(width, height) * 0.26f, p)
    }
}

// Round action button: a waveform (voice) when the box is empty, an arrow (send) when it has text.
class ActionButton(ctx: Context) : Button(ctx) {
    var arrow = false
        set(v) {
            if (field != v) {
                field = v
                animate().scaleX(1.18f).scaleY(1.18f).setDuration(90).withEndAction {
                    animate().scaleX(1f).scaleY(1f).setDuration(140).setInterpolator(OvershootInterpolator(2f)).start()
                }.start()
                invalidate()
            }
        }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    init { pressFx(this) }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        p.color = 0xFF05090F.toInt()
        p.strokeWidth = resources.displayMetrics.density * 2.4f
        Icons.draw(canvas, if (arrow) "arrow" else "wave", width / 2f, height / 2f, min(width, height) * 0.22f, p)
    }
}
