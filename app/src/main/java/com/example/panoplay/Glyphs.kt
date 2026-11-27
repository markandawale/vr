package com.example.panoplay

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

/**
 * PanoPlay's icon set. Every glyph is drawn as a vector on a 24-unit grid with one stroke
 * weight, so the whole interface shares the same hand and scales cleanly on any screen.
 */
internal class Glyph(
    private val kind: Kind,
    var tint: Int = Pano.TEXT,
    var accent: Int = Pano.SUN
) : Drawable() {

    enum class Kind {
        PLAY, PAUSE, REPLAY10, FORWARD10, BACK, INFO, TUNE, PLUS, FULLSCREEN,
        SPEED, RECENTER, MOTION, ROTATE, LENS, BRIGHTNESS,
        FLAT, DOME180, SPHERE360, MONO, SBS_LEFT, SBS_RIGHT
    }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = BASE_STROKE
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 8.1f
    }
    private val path = Path()
    private val oval = RectF()
    private var alphaValue = 255

    private fun rad(deg: Float): Float = deg * (Math.PI.toFloat() / 180f)

    private fun shade(color: Int, factor: Float = 1f): Int =
        Pano.scaleAlpha(color, factor * alphaValue / 255f)

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val scale = Math.min(b.width(), b.height()) / 24f
        canvas.save()
        canvas.translate(
            b.left + (b.width() - 24f * scale) / 2f,
            b.top + (b.height() - 24f * scale) / 2f
        )
        canvas.scale(scale, scale)

        stroke.strokeWidth = BASE_STROKE
        stroke.color = shade(tint)
        fill.color = shade(tint)

        when (kind) {
            Kind.PLAY -> {
                path.reset()
                path.moveTo(8.2f, 5.6f)
                path.lineTo(18.4f, 12f)
                path.lineTo(8.2f, 18.4f)
                path.close()
                canvas.drawPath(path, fill)
                stroke.strokeWidth = 2.6f
                canvas.drawPath(path, stroke)
            }
            Kind.PAUSE -> {
                oval.set(6.2f, 5f, 10.2f, 19f)
                canvas.drawRoundRect(oval, 1.5f, 1.5f, fill)
                oval.set(13.8f, 5f, 17.8f, 19f)
                canvas.drawRoundRect(oval, 1.5f, 1.5f, fill)
            }
            Kind.REPLAY10 -> drawSeek(canvas, false)
            Kind.FORWARD10 -> drawSeek(canvas, true)
            Kind.BACK -> {
                stroke.strokeWidth = 2.2f
                path.reset()
                path.moveTo(15f, 5f)
                path.lineTo(8f, 12f)
                path.lineTo(15f, 19f)
                canvas.drawPath(path, stroke)
            }
            Kind.INFO -> {
                canvas.drawCircle(12f, 12f, 9f, stroke)
                canvas.drawCircle(12f, 7.9f, 1.25f, fill)
                canvas.drawLine(12f, 11f, 12f, 16.6f, stroke)
            }
            Kind.TUNE -> {
                canvas.drawLine(4f, 7f, 7.1f, 7f, stroke)
                canvas.drawLine(12.7f, 7f, 20f, 7f, stroke)
                canvas.drawCircle(9.9f, 7f, 2.4f, stroke)
                canvas.drawLine(4f, 12f, 12.5f, 12f, stroke)
                canvas.drawLine(18.3f, 12f, 20f, 12f, stroke)
                canvas.drawCircle(15.4f, 12f, 2.4f, stroke)
                canvas.drawLine(4f, 17f, 5.3f, 17f, stroke)
                canvas.drawLine(11.1f, 17f, 20f, 17f, stroke)
                canvas.drawCircle(8.2f, 17f, 2.4f, stroke)
            }
            Kind.PLUS -> {
                stroke.strokeWidth = 2.3f
                canvas.drawLine(12f, 5.5f, 12f, 18.5f, stroke)
                canvas.drawLine(5.5f, 12f, 18.5f, 12f, stroke)
            }
            Kind.FULLSCREEN -> {
                path.reset()
                path.moveTo(4f, 9f); path.lineTo(4f, 4f); path.lineTo(9f, 4f)
                path.moveTo(15f, 4f); path.lineTo(20f, 4f); path.lineTo(20f, 9f)
                path.moveTo(20f, 15f); path.lineTo(20f, 20f); path.lineTo(15f, 20f)
                path.moveTo(9f, 20f); path.lineTo(4f, 20f); path.lineTo(4f, 15f)
                canvas.drawPath(path, stroke)
            }
            Kind.SPEED -> {
                oval.set(3.5f, 4.5f, 20.5f, 21.5f)
                canvas.drawArc(oval, 150f, 240f, false, stroke)
                canvas.drawLine(12f, 13f, 16.3f, 8.6f, stroke)
                canvas.drawCircle(12f, 13f, 1.6f, fill)
            }
            Kind.RECENTER -> {
                canvas.drawCircle(12f, 12f, 5f, stroke)
                canvas.drawCircle(12f, 12f, 1.5f, fill)
                canvas.drawLine(12f, 4f, 12f, 1.8f, stroke)
                canvas.drawLine(12f, 20f, 12f, 22.2f, stroke)
                canvas.drawLine(4f, 12f, 1.8f, 12f, stroke)
                canvas.drawLine(20f, 12f, 22.2f, 12f, stroke)
            }
            Kind.MOTION -> {
                oval.set(9f, 3f, 15f, 21f)
                canvas.drawOval(oval, stroke)
                oval.set(3f, 9f, 21f, 15f)
                canvas.drawOval(oval, stroke)
                canvas.drawCircle(12f, 12f, 1.7f, fill)
            }
            Kind.ROTATE -> {
                oval.set(3.5f, 3.5f, 20.5f, 20.5f)
                canvas.drawArc(oval, -50f, 270f, false, stroke)
                val endAngle = rad(220f)
                val ex = 12f + 8.5f * cos(endAngle)
                val ey = 12f + 8.5f * sin(endAngle)
                arrowHead(canvas, ex, ey, -sin(endAngle), cos(endAngle), 3.6f)
                oval.set(9.6f, 8.2f, 14.4f, 15.8f)
                canvas.drawRoundRect(oval, 1.2f, 1.2f, stroke)
            }
            Kind.LENS -> {
                canvas.drawCircle(12f, 12f, 9.5f, stroke)
                stroke.color = shade(tint, 0.55f)
                oval.set(7.6f, 2.5f, 16.4f, 21.5f)
                canvas.drawOval(oval, stroke)
                stroke.color = shade(accent)
                stroke.strokeWidth = 2.2f
                canvas.drawLine(2.6f, 12f, 21.4f, 12f, stroke)
            }
            Kind.BRIGHTNESS -> {
                canvas.drawCircle(12f, 12f, 3.9f, stroke)
                for (i in 0 until 8) {
                    val a = rad(i * 45f)
                    canvas.drawLine(
                        12f + 6.8f * cos(a), 12f + 6.8f * sin(a),
                        12f + 9.2f * cos(a), 12f + 9.2f * sin(a),
                        stroke
                    )
                }
            }
            Kind.FLAT -> {
                oval.set(3f, 6.5f, 21f, 17.5f)
                canvas.drawRoundRect(oval, 2.8f, 2.8f, stroke)
            }
            Kind.DOME180 -> {
                oval.set(3f, 7.5f, 21f, 25.5f)
                canvas.drawArc(oval, 180f, 180f, false, stroke)
                canvas.drawLine(3f, 16.5f, 21f, 16.5f, stroke)
                canvas.drawCircle(12f, 16.5f, 1.8f, fill)
            }
            Kind.SPHERE360 -> {
                canvas.drawCircle(12f, 12f, 8.8f, stroke)
                stroke.color = shade(tint, 0.6f)
                oval.set(3.2f, 8.8f, 20.8f, 15.2f)
                canvas.drawOval(oval, stroke)
            }
            Kind.MONO -> {
                oval.set(5f, 6.5f, 19f, 17.5f)
                canvas.drawRoundRect(oval, 2.8f, 2.8f, stroke)
            }
            Kind.SBS_LEFT -> {
                oval.set(2.8f, 7f, 11.2f, 17f)
                canvas.drawRoundRect(oval, 2.2f, 2.2f, fill)
                oval.set(12.8f, 7f, 21.2f, 17f)
                canvas.drawRoundRect(oval, 2.2f, 2.2f, stroke)
            }
            Kind.SBS_RIGHT -> {
                oval.set(2.8f, 7f, 11.2f, 17f)
                canvas.drawRoundRect(oval, 2.2f, 2.2f, stroke)
                oval.set(12.8f, 7f, 21.2f, 17f)
                canvas.drawRoundRect(oval, 2.2f, 2.2f, fill)
            }
        }
        canvas.restore()
    }

    /** Circular arrow with a "10" inside; mirrored for the forward variant. */
    private fun drawSeek(canvas: Canvas, forward: Boolean) {
        canvas.save()
        if (forward) canvas.scale(-1f, 1f, 12f, 12f)
        oval.set(3.8f, 5f, 20.2f, 21.4f)
        canvas.drawArc(oval, 270f, 300f, false, stroke)
        arrowHead(canvas, 12f, 5f, -1f, 0f, 3.6f)
        canvas.restore()

        label.color = shade(tint)
        label.typeface = Pano.medium
        val fm = label.fontMetrics
        canvas.drawText("10", 12f, 13.2f - (fm.ascent + fm.descent) / 2f, label)
    }

    /** Draws a chevron whose tip sits at (tipX, tipY) and points along (dirX, dirY). */
    private fun arrowHead(canvas: Canvas, tipX: Float, tipY: Float, dirX: Float, dirY: Float, length: Float) {
        val spread = rad(38f)
        val c = cos(spread)
        val s = sin(spread)
        val bx = -dirX
        val by = -dirY
        val w1x = bx * c - by * s
        val w1y = bx * s + by * c
        val w2x = bx * c + by * s
        val w2y = -bx * s + by * c
        path.reset()
        path.moveTo(tipX + w1x * length, tipY + w1y * length)
        path.lineTo(tipX, tipY)
        path.lineTo(tipX + w2x * length, tipY + w2y * length)
        canvas.drawPath(path, stroke)
    }

    override fun setAlpha(alpha: Int) {
        alphaValue = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        stroke.colorFilter = colorFilter
        fill.colorFilter = colorFilter
        label.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val BASE_STROKE = 1.9f
    }
}
