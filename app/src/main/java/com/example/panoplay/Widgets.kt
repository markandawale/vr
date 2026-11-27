package com.example.panoplay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ScrollView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Seek bar used everywhere in the player. A slim amber track with a lens-style thumb that
 * grows while dragging. [centerOrigin] fills from the middle, which suits the audio-sync slider.
 */
internal class CinemaSeekBar(context: Context) : View(context) {
    var max: Int = 1000
    var progress: Int = 0
        set(value) {
            val clamped = value.coerceIn(0, max)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }
    var centerOrigin: Boolean = false

    var onStartScrub: (() -> Unit)? = null
    var onProgressScrub: ((Int) -> Unit)? = null
    var onStopScrub: ((Int) -> Unit)? = null

    private var isDragging = false
    private var dragAmount = 0f
    private var dragAnimator: ValueAnimator? = null
    private val d = resources.displayMetrics.density
    private val sidePad = 12f * d

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pano.withAlpha(Pano.TEXT, 0.16f) }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pano.SUN }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pano.withAlpha(Pano.TEXT, 0.45f) }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pano.SUN }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pano.TEXT }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pano.SUN }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val usable = (w - sidePad * 2f).coerceAtLeast(1f)
        val cy = h * 0.5f
        val half = (3.5f + 2.5f * dragAmount) * d * 0.5f
        val fraction = if (max > 0) (progress.toFloat() / max.toFloat()).coerceIn(0f, 1f) else 0f
        val thumbX = sidePad + usable * fraction

        rect.set(sidePad, cy - half, w - sidePad, cy + half)
        canvas.drawRoundRect(rect, half, half, trackPaint)

        val originX = if (centerOrigin) sidePad + usable * 0.5f else sidePad
        rect.set(Math.min(originX, thumbX), cy - half, Math.max(originX, thumbX), cy + half)
        canvas.drawRoundRect(rect, half, half, fillPaint)

        if (centerOrigin) {
            rect.set(originX - d, cy - 7f * d, originX + d, cy + 7f * d)
            canvas.drawRoundRect(rect, d, d, tickPaint)
        }

        if (dragAmount > 0.01f) {
            glowPaint.alpha = (70f * dragAmount).toInt()
            canvas.drawCircle(thumbX, cy, (11f + 8f * dragAmount) * d, glowPaint)
        }
        val radius = (6.5f + 3f * dragAmount) * d
        canvas.drawCircle(thumbX, cy, radius, thumbPaint)
        canvas.drawCircle(thumbX, cy, radius * 0.42f, corePaint)
    }

    private fun animateDrag(target: Float) {
        dragAnimator?.cancel()
        dragAnimator = ValueAnimator.ofFloat(dragAmount, target).apply {
            duration = 140L
            addUpdateListener {
                dragAmount = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        dragAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val usable = (width - sidePad * 2f).coerceAtLeast(1f)

        fun computeProgress(rawX: Float): Int {
            val ratio = ((rawX - sidePad) / usable).coerceIn(0f, 1f)
            return (ratio * max + 0.5f).toInt().coerceIn(0, max)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                isDragging = true
                animateDrag(1f)
                progress = computeProgress(event.x)
                onStartScrub?.invoke()
                onProgressScrub?.invoke(progress)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    progress = computeProgress(event.x)
                    onProgressScrub?.invoke(progress)
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDragging) {
                    progress = computeProgress(event.x)
                    isDragging = false
                    animateDrag(0f)
                    onStopScrub?.invoke(progress)
                    invalidate()
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}

/**
 * Segmented selector with a sliding amber-outlined thumb. Each segment pairs a drawn glyph
 * with a label, and colours cross-fade as the thumb passes over them.
 */
internal class SegmentedControl(context: Context, private val items: List<Item>) : View(context) {

    class Item(val label: String, val kind: Glyph.Kind)

    var onSelected: ((Int) -> Unit)? = null
    var selectedIndex: Int = 0
        private set

    private val d = resources.displayMetrics.density
    private val glyphs: List<Glyph> = items.map { Glyph(it.kind, Pano.MUTED) }
    private var thumbPos = 0f
    private var animator: ValueAnimator? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pano.RAISED }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = d
        color = Pano.HAIRLINE
    }
    private val thumbFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pano.withAlpha(Pano.SUN, 0.16f) }
    private val thumbEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * d
        color = Pano.withAlpha(Pano.SUN, 0.75f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Pano.medium
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13.5f, resources.displayMetrics)
    }
    private val rect = RectF()

    fun select(index: Int, animate: Boolean = true) {
        val target = index.coerceIn(0, items.size - 1)
        selectedIndex = target
        animator?.cancel()
        if (animate && isLaidOut) {
            animator = ValueAnimator.ofFloat(thumbPos, target.toFloat()).apply {
                duration = 240L
                interpolator = DecelerateInterpolator(1.8f)
                addUpdateListener {
                    thumbPos = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            thumbPos = target.toFloat()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || items.isEmpty()) return

        val radius = h * 0.36f
        rect.set(d * 0.5f, d * 0.5f, w - d * 0.5f, h - d * 0.5f)
        canvas.drawRoundRect(rect, radius, radius, bgPaint)
        canvas.drawRoundRect(rect, radius, radius, edgePaint)

        val inset = 4f * d
        val segmentW = (w - inset * 2f) / items.size
        val thumbLeft = inset + thumbPos * segmentW
        rect.set(thumbLeft, inset, thumbLeft + segmentW, h - inset)
        val thumbRadius = radius - inset + d
        canvas.drawRoundRect(rect, thumbRadius, thumbRadius, thumbFill)
        canvas.drawRoundRect(rect, thumbRadius, thumbRadius, thumbEdge)

        val fm = textPaint.fontMetrics
        val baseline = h * 0.5f - (fm.ascent + fm.descent) * 0.5f
        val glyphSize = 18f * d
        val gap = 7f * d
        for (i in items.indices) {
            val closeness = 1f - Math.min(1f, abs(thumbPos - i))
            val color = Pano.lerp(Pano.MUTED, Pano.SUN, closeness)
            val label = items[i].label
            val textW = textPaint.measureText(label)
            val contentW = glyphSize + gap + textW
            val startX = inset + segmentW * i + (segmentW - contentW) * 0.5f

            val glyph = glyphs[i]
            glyph.tint = color
            glyph.setBounds(
                startX.toInt(),
                (h * 0.5f - glyphSize * 0.5f).toInt(),
                (startX + glyphSize).toInt(),
                (h * 0.5f + glyphSize * 0.5f).toInt()
            )
            glyph.draw(canvas)

            textPaint.color = color
            canvas.drawText(label, startX + glyphSize + gap, baseline, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val inset = 4f * d
                val segmentW = (width - inset * 2f) / items.size
                val index = ((event.x - inset) / segmentW).toInt().coerceIn(0, items.size - 1)
                if (index != selectedIndex) {
                    select(index)
                    onSelected?.invoke(index)
                }
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}

/** Compact on/off switch: faint track when off, amber track with a dark thumb when on. */
internal class PanoSwitch(context: Context) : View(context) {
    var checked: Boolean = false
        private set
    var onChanged: ((Boolean) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private var pos = 0f
    private var animator: ValueAnimator? = null
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener { toggle() }
    }

    fun setOn(value: Boolean, animate: Boolean = true) {
        checked = value
        animator?.cancel()
        if (animate && isLaidOut) {
            animator = ValueAnimator.ofFloat(pos, if (value) 1f else 0f).apply {
                duration = 180L
                addUpdateListener {
                    pos = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            pos = if (value) 1f else 0f
            invalidate()
        }
    }

    fun toggle() {
        setOn(!checked)
        onChanged?.invoke(checked)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension((50f * d).toInt(), (30f * d).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        trackPaint.color = Pano.lerp(Pano.withAlpha(Pano.TEXT, 0.16f), Pano.SUN, pos)
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, h / 2f, h / 2f, trackPaint)

        val thumbRadius = h / 2f - 4.5f * d
        val x = h / 2f + (w - h) * pos
        thumbPaint.color = Pano.lerp(Pano.MUTED, Pano.ON_SUN, pos)
        canvas.drawCircle(x, h / 2f, thumbRadius, thumbPaint)
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}

/**
 * Empty-state hero: a slowly turning wireframe sphere with the horizon picked out in amber and
 * a viewfinder framing one patch of it. It only animates while it is actually on screen.
 */
internal class GlobeView(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private var spinning = false
    private val reduceMotion: Boolean = try {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (_: Exception) {
        false
    }

    private fun linePaint(color: Int, widthDp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = widthDp * d
        this.color = color
    }

    private val frontPaint = linePaint(Pano.withAlpha(Pano.IRIS, 0.50f), 1.1f)
    private val backPaint = linePaint(Pano.withAlpha(Pano.IRIS, 0.10f), 1.0f)
    private val horizonFront = linePaint(Pano.withAlpha(Pano.SUN, 0.95f), 1.8f)
    private val horizonBack = linePaint(Pano.withAlpha(Pano.SUN, 0.16f), 1.4f)
    private val rimPaint = linePaint(Pano.withAlpha(Pano.IRIS, 0.60f), 1.3f)
    private val bracketPaint = linePaint(Pano.SUN, 2.4f)
    private val playPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 2f * d
        color = Pano.TEXT
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var glow: RadialGradient? = null

    private val frontPath = Path()
    private val backPath = Path()
    private val horizonFrontPath = Path()
    private val horizonBackPath = Path()
    private val bracketPath = Path()
    private val playPath = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val r = Math.min(w, h) / 2f
        glow = RadialGradient(
            w / 2f, h / 2f, Math.max(r * 1.5f, 1f),
            intArrayOf(Pano.withAlpha(Pano.IRIS, 0.30f), Pano.withAlpha(Pano.IRIS, 0f)),
            floatArrayOf(0.2f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        spinning = isVisible
        if (isVisible) postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = Math.min(w, h) / 2f - 8f * d
        if (r <= 12f * d) return
        val cx = w / 2f
        val cy = h / 2f

        glow?.let {
            glowPaint.shader = it
            canvas.drawCircle(cx, cy, r * 1.5f, glowPaint)
        }

        val spin = if (reduceMotion) {
            0.6f
        } else {
            (SystemClock.uptimeMillis() % PERIOD_MS).toFloat() / PERIOD_MS * TWO_PI
        }
        val tilt = -0.30f
        val cosTilt = cos(tilt)
        val sinTilt = sin(tilt)

        frontPath.reset()
        backPath.reset()
        horizonFrontPath.reset()
        horizonBackPath.reset()

        // Lines of longitude, then latitude, projected orthographically onto the screen.
        fun trace(front: Path, back: Path, pointCount: Int, point: (Int) -> FloatArray) {
            var frontDown = false
            var backDown = false
            for (i in 0 until pointCount) {
                val p = point(i)
                val x = p[0]
                val y = p[1]
                val z = p[2]
                val yTilted = y * cosTilt - z * sinTilt
                val zTilted = y * sinTilt + z * cosTilt
                val px = cx + r * x
                val py = cy - r * yTilted
                if (zTilted >= 0f) {
                    if (frontDown) front.lineTo(px, py) else front.moveTo(px, py)
                    frontDown = true
                    backDown = false
                } else {
                    if (backDown) back.lineTo(px, py) else back.moveTo(px, py)
                    backDown = true
                    frontDown = false
                }
            }
        }

        val steps = 30
        for (m in 0 until 12) {
            val lon = m * (TWO_PI / 12f) + spin
            trace(frontPath, backPath, steps + 1) { i ->
                val lat = -HALF_PI + (i.toFloat() / steps) * Math.PI.toFloat()
                floatArrayOf(cos(lat) * sin(lon), sin(lat), cos(lat) * cos(lon))
            }
        }
        val ringSteps = 60
        val latitudes = floatArrayOf(-1.0472f, -0.5236f, 0.5236f, 1.0472f)
        for (lat in latitudes) {
            trace(frontPath, backPath, ringSteps + 1) { i ->
                val lon = (i.toFloat() / ringSteps) * TWO_PI + spin
                floatArrayOf(cos(lat) * sin(lon), sin(lat), cos(lat) * cos(lon))
            }
        }
        trace(horizonFrontPath, horizonBackPath, ringSteps + 1) { i ->
            val lon = (i.toFloat() / ringSteps) * TWO_PI + spin
            floatArrayOf(sin(lon), 0f, cos(lon))
        }

        canvas.drawPath(backPath, backPaint)
        canvas.drawPath(horizonBackPath, horizonBack)
        canvas.drawPath(frontPath, frontPaint)
        canvas.drawPath(horizonFrontPath, horizonFront)
        canvas.drawCircle(cx, cy, r, rimPaint)

        // Viewfinder: the window you look through into the sphere.
        val half = r * 0.40f
        val arm = half * 0.42f
        bracketPath.reset()
        for (sx in intArrayOf(-1, 1)) {
            for (sy in intArrayOf(-1, 1)) {
                bracketPath.moveTo(cx + sx * half, cy + sy * (half - arm))
                bracketPath.lineTo(cx + sx * half, cy + sy * half)
                bracketPath.lineTo(cx + sx * (half - arm), cy + sy * half)
            }
        }
        canvas.drawPath(bracketPath, bracketPaint)

        val s = half * 0.40f
        playPath.reset()
        playPath.moveTo(cx - s * 0.55f, cy - s)
        playPath.lineTo(cx + s * 0.95f, cy)
        playPath.lineTo(cx - s * 0.55f, cy + s)
        playPath.close()
        canvas.drawPath(playPath, playPaint)

        if (spinning && !reduceMotion) postInvalidateDelayed(32L)
    }

    private companion object {
        const val PERIOD_MS = 42000L
        const val TWO_PI = 6.2831855f
        const val HALF_PI = 1.5707964f
    }
}

/**
 * Draws the stage's rounded corners and hairline edge on top of the video. The video surface is
 * never clipped (SurfaceView and GL surfaces ignore outline clipping on some devices); instead the
 * four corner gaps are painted in the page colour.
 */
internal class StageFrameView(
    context: Context,
    private val radiusPx: Float,
    private val maskColor: Int
) : View(context) {
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = maskColor
    }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
        color = Pano.HAIRLINE
    }
    private val corner = Path()
    private val arcBounds = RectF()
    private val edgeBounds = RectF()

    init {
        // Pure decoration: never take touches away from the video underneath.
        isClickable = false
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val r = radiusPx

        corner.reset()
        corner.moveTo(0f, 0f)
        corner.lineTo(r, 0f)
        arcBounds.set(0f, 0f, r * 2f, r * 2f)
        corner.arcTo(arcBounds, 270f, -90f)
        corner.close()

        // Slightly oversized so no sliver of video survives at the antialiased edge.
        for (quarter in 0 until 4) {
            canvas.save()
            when (quarter) {
                0 -> {}
                1 -> { canvas.translate(w, 0f); canvas.rotate(90f) }
                2 -> { canvas.translate(w, h); canvas.rotate(180f) }
                else -> { canvas.translate(0f, h); canvas.rotate(270f) }
            }
            canvas.drawPath(corner, cornerPaint)
            canvas.restore()
        }

        val half = edgePaint.strokeWidth / 2f
        edgeBounds.set(half, half, w - half, h - half)
        canvas.drawRoundRect(edgeBounds, r - half, r - half, edgePaint)
    }
}

/** A ScrollView that grows with its content up to [maxHeightPx]. */
internal class BoundedScrollView(context: Context, private val maxHeightPx: Int) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            widthMeasureSpec,
            View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST)
        )
    }
}
