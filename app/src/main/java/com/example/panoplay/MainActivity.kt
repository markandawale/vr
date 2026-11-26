package com.example.panoplay

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.database.Cursor
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import java.io.IOException
import java.util.ArrayList
import java.util.IdentityHashMap
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * iOS / visionOS Spatial Cinema Glassmorphism Theme Palette
 */
private object AppleGlassTheme {
    val background = Color.rgb(7, 7, 9)                     // #070709 True OLED Obsidian
    val cardSurface = Color.rgb(20, 20, 24)                 // #141418 iOS Dark Grouped Surface
    val cardElevated = Color.rgb(30, 30, 36)                // #1E1E24 iOS Secondary Grouped
    val chipIdle = Color.rgb(42, 42, 50)                    // #2A2A32 iOS Pill Fill

    // Frosted Translucent Glass Layers
    val glassDark = Color.argb(175, 22, 22, 28)             // Frosted Dark Glass
    val glassDock = Color.argb(195, 18, 18, 24)             // Fullscreen Floating Dock Glass
    val glassPill = Color.argb(140, 48, 48, 58)             // Interactive Glass Pill
    val glassBorder = Color.argb(58, 255, 255, 255)         // Specular Top/Edge Glass Rim
    val glassBorderStrong = Color.argb(95, 255, 255, 255)

    // Monochrome Platinum & Pure White Accents (No Blue!)
    val pureWhite = Color.rgb(255, 255, 255)                // #FFFFFF Active Pill / Primary CTA
    val inkBlack = Color.rgb(12, 12, 15)                    // #0C0C0F Text on White Pill
    val labelPrimary = Color.rgb(245, 245, 247)             // #F5F5F7 Apple Primary Label
    val labelSecondary = Color.rgb(152, 152, 162)           // #9898A2 Apple Secondary Label
    val labelTertiary = Color.rgb(99, 99, 108)              // #63636C Apple Tertiary Label

    val appleMint = Color.rgb(52, 211, 153)                 // Crisp Mint for FPS / Active Toggle
    val appleCoral = Color.rgb(255, 69, 58)                 // iOS System Red/Coral for Errors
    val rippleLight = Color.argb(40, 255, 255, 255)
}

/**
 * Resolves exact byte length from ContentResolver when ContentDataSource returns C.LENGTH_UNSET (-1).
 */
@OptIn(UnstableApi::class)
private class SafeGuardDataSource(
    private val context: Context,
    private val upstream: DataSource
) : DataSource {

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val reportedLength = upstream.open(dataSpec)
        if (reportedLength != C.LENGTH_UNSET.toLong()) {
            return reportedLength
        }
        val totalSize = queryExactFileSize(dataSpec.uri)
        if (totalSize > 0L) {
            val remaining = (totalSize - dataSpec.position).coerceAtLeast(0L)
            return if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                min(remaining, dataSpec.length)
            } else {
                remaining
            }
        }
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (length < 0) {
            throw IOException("Invalid negative read length ($length) from extractor")
        }
        if (offset < 0 || offset >= buffer.size) {
            throw IOException("Invalid buffer offset ($offset) for buffer size ${buffer.size}")
        }
        val safeLength = min(length, buffer.size - offset)
        return upstream.read(buffer, offset, safeLength)
    }

    override fun getUri(): Uri? = upstream.uri

    override fun close() {
        upstream.close()
    }

    private fun queryExactFileSize(uri: Uri): Long {
        if (uri.scheme != "content") return -1L
        var cursor: Cursor? = null
        return try {
            cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else -1L
            } else {
                -1L
            }
        } catch (_: Exception) {
            -1L
        } finally {
            cursor?.close()
        }
    }

    class Factory(private val context: Context) : DataSource.Factory {
        private val baseFactory = DefaultDataSource.Factory(context)
        override fun createDataSource(): DataSource = SafeGuardDataSource(context, baseFactory.createDataSource())
    }
}

/**
 * Apple SF-Symbols Inspired Thin-Stroke Vector Icons
 */
private class SpatialIconDrawable(
    private val iconType: Type,
    private var tintColor: Int
) : Drawable() {

    enum class Type {
        SPEED, RECENTER, MOTION, INFO, SETTINGS, FOLDER_OPEN,
        FULLSCREEN, BACK_CHEVRON, ROTATE, SPATIAL_EYE
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tintColor
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tintColor
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val oval = RectF()

    override fun setTint(tintColor: Int) {
        this.tintColor = tintColor
        fillPaint.color = tintColor
        strokePaint.color = tintColor
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w * 0.5f
        val cy = h * 0.5f
        val s = min(w, h)

        fillPaint.color = tintColor
        strokePaint.color = tintColor

        when (iconType) {
            Type.RECENTER -> {
                strokePaint.strokeWidth = s * 0.085f
                val r = s * 0.28f
                canvas.drawCircle(cx, cy, r, strokePaint)
                canvas.drawCircle(cx, cy, s * 0.085f, fillPaint)
                val t1 = r + s * 0.03f
                val t2 = s * 0.44f
                canvas.drawLine(cx, cy - t1, cx, cy - t2, strokePaint)
                canvas.drawLine(cx, cy + t1, cx, cy + t2, strokePaint)
                canvas.drawLine(cx - t1, cy, cx - t2, cy, strokePaint)
                canvas.drawLine(cx + t1, cy, cx + t2, cy, strokePaint)
            }
            Type.MOTION -> {
                strokePaint.strokeWidth = s * 0.085f
                val r = s * 0.39f
                canvas.drawCircle(cx, cy, r, strokePaint)
                val tip = r * 0.58f
                val side = r * 0.20f
                val diag = 0.7071f
                path.reset()
                path.moveTo(cx + tip * diag, cy - tip * diag)
                path.lineTo(cx + side * diag, cy + side * diag)
                path.lineTo(cx - tip * diag, cy + tip * diag)
                path.lineTo(cx - side * diag, cy - side * diag)
                path.close()
                canvas.drawPath(path, fillPaint)
            }
            Type.SPEED -> {
                val gaugeCy = cy + s * 0.14f
                val outerR = s * 0.38f
                strokePaint.strokeWidth = s * 0.09f
                oval.set(cx - outerR, gaugeCy - outerR, cx + outerR, gaugeCy + outerR)
                canvas.drawArc(oval, 185f, 170f, false, strokePaint)
                canvas.drawCircle(cx, gaugeCy, s * 0.07f, fillPaint)
                canvas.drawLine(cx, gaugeCy, cx + s * 0.23f, gaugeCy - s * 0.25f, strokePaint)
            }
            Type.INFO -> {
                strokePaint.strokeWidth = s * 0.085f
                canvas.drawCircle(cx, cy, s * 0.38f, strokePaint)
                canvas.drawCircle(cx, cy - s * 0.16f, s * 0.048f, fillPaint)
                canvas.drawLine(cx, cy - s * 0.02f, cx, cy + s * 0.19f, strokePaint)
            }
            Type.SETTINGS -> {
                strokePaint.strokeWidth = s * 0.085f
                canvas.drawCircle(cx, cy, s * 0.15f, strokePaint)
                val rIn = s * 0.25f
                val rOut = s * 0.38f
                for (i in 0 until 8) {
                    val angle = Math.toRadians(i * 45.0)
                    val cosA = kotlin.math.cos(angle).toFloat()
                    val sinA = kotlin.math.sin(angle).toFloat()
                    canvas.drawLine(cx + rIn * cosA, cy + rIn * sinA, cx + rOut * cosA, cy + rOut * sinA, strokePaint)
                }
            }
            Type.FOLDER_OPEN -> {
                strokePaint.strokeWidth = s * 0.09f
                canvas.drawLine(cx, cy - s * 0.26f, cx, cy + s * 0.26f, strokePaint)
                canvas.drawLine(cx - s * 0.26f, cy, cx + s * 0.26f, cy, strokePaint)
            }
            Type.FULLSCREEN -> {
                strokePaint.strokeWidth = s * 0.09f
                val p = s * 0.22f
                val l = s * 0.20f
                canvas.drawLine(p, p + l, p, p, strokePaint)
                canvas.drawLine(p, p, p + l, p, strokePaint)
                canvas.drawLine(w - p - l, p, w - p, p, strokePaint)
                canvas.drawLine(w - p, p, w - p, p + l, strokePaint)
                canvas.drawLine(p, h - p - l, p, h - p, strokePaint)
                canvas.drawLine(p, h - p, p + l, h - p, strokePaint)
                canvas.drawLine(w - p - l, h - p, w - p, h - p, strokePaint)
                canvas.drawLine(w - p, h - p - l, w - p, h - p, strokePaint)
            }
            Type.BACK_CHEVRON -> {
                strokePaint.strokeWidth = s * 0.105f
                canvas.drawLine(w * 0.60f, h * 0.24f, w * 0.34f, cy, strokePaint)
                canvas.drawLine(w * 0.34f, cy, w * 0.60f, h * 0.76f, strokePaint)
            }
            Type.ROTATE -> {
                strokePaint.strokeWidth = s * 0.085f
                oval.set(w * 0.20f, h * 0.28f, w * 0.80f, h * 0.72f)
                canvas.drawRoundRect(oval, s * 0.10f, s * 0.10f, strokePaint)
            }
            Type.SPATIAL_EYE -> {
                strokePaint.strokeWidth = s * 0.085f
                oval.set(w * 0.12f, h * 0.28f, w * 0.88f, h * 0.72f)
                canvas.drawRoundRect(oval, s * 0.18f, s * 0.18f, strokePaint)
                canvas.drawCircle(w * 0.34f, cy, s * 0.085f, fillPaint)
                canvas.drawCircle(w * 0.66f, cy, s * 0.085f, fillPaint)
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

/**
 * Smooth Rounded Apple-Style Transport Icons
 */
private class TransportIconDrawable(
    private val iconType: Type,
    private val tintColor: Int
) : Drawable() {

    enum class Type { PLAY, PAUSE, REWIND, FORWARD }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tintColor
        style = Paint.Style.FILL
    }
    private val path = Path()
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w * 0.5f
        val cy = h * 0.5f

        when (iconType) {
            Type.PLAY -> {
                val halfH = h * 0.24f
                val leftX = cx - w * 0.13f
                val rightX = cx + w * 0.23f
                path.reset()
                path.moveTo(leftX, cy - halfH)
                path.lineTo(rightX, cy)
                path.lineTo(leftX, cy + halfH)
                path.close()
                canvas.drawPath(path, paint)
            }
            Type.PAUSE -> {
                val barW = w * 0.09f
                val halfH = h * 0.22f
                val gap = w * 0.068f
                rect.set(cx - gap - barW, cy - halfH, cx - gap, cy + halfH)
                canvas.drawRoundRect(rect, barW * 0.5f, barW * 0.5f, paint)
                rect.set(cx + gap, cy - halfH, cx + gap + barW, cy + halfH)
                canvas.drawRoundRect(rect, barW * 0.5f, barW * 0.5f, paint)
            }
            Type.REWIND -> {
                val halfH = h * 0.28f
                val triW = halfH * 1.22f
                val overlap = triW * 0.18f
                val totalW = triW * 2f - overlap
                val startX = cx - totalW * 0.5f

                path.reset()
                path.moveTo(startX, cy)
                path.lineTo(startX + triW, cy - halfH)
                path.lineTo(startX + triW, cy + halfH)
                path.close()
                canvas.drawPath(path, paint)

                val rTipX = startX + triW - overlap
                path.reset()
                path.moveTo(rTipX, cy)
                path.lineTo(rTipX + triW, cy - halfH)
                path.lineTo(rTipX + triW, cy + halfH)
                path.close()
                canvas.drawPath(path, paint)
            }
            Type.FORWARD -> {
                val halfH = h * 0.28f
                val triW = halfH * 1.22f
                val overlap = triW * 0.18f
                val totalW = triW * 2f - overlap
                val startX = cx - totalW * 0.5f

                path.reset()
                path.moveTo(startX, cy - halfH)
                path.lineTo(startX + triW, cy)
                path.lineTo(startX, cy + halfH)
                path.close()
                canvas.drawPath(path, paint)

                val rBaseX = startX + triW - overlap
                path.reset()
                path.moveTo(rBaseX, cy - halfH)
                path.lineTo(rBaseX + triW, cy)
                path.lineTo(rBaseX, cy + halfH)
                path.close()
                canvas.drawPath(path, paint)
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}

/**
 * Apple tvOS / iOS Glass Scrubber Bar (Expands smoothly when touched)
 */
private class GlassScrubberBar(context: Context) : View(context) {
    var max: Int = 1000
    var progress: Int = 0
        set(value) {
            val clamped = value.coerceIn(0, max)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    var onStartScrub: (() -> Unit)? = null
    var onProgressScrub: ((Int) -> Unit)? = null
    var onStopScrub: ((Int) -> Unit)? = null

    private var isDragging = false
    private val density = resources.displayMetrics.density

    private val trackBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(65, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val trackFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AppleGlassTheme.pureWhite
        style = Paint.Style.FILL
    }
    private val thumbGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(55, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val thumbDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AppleGlassTheme.pureWhite
        style = Paint.Style.FILL
    }
    private val trackRect = RectF()
    private val fillRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val sidePad = 8f * density
        val usableWidth = (w - sidePad * 2f).coerceAtLeast(1f)
        val centerY = h * 0.5f

        val trackHeight = (if (isDragging) 7.5f else 4f) * density
        val halfTrack = trackHeight * 0.5f
        val fraction = if (max > 0) (progress.toFloat() / max.toFloat()).coerceIn(0f, 1f) else 0f
        val thumbX = sidePad + usableWidth * fraction

        trackRect.set(sidePad, centerY - halfTrack, w - sidePad, centerY + halfTrack)
        canvas.drawRoundRect(trackRect, halfTrack, halfTrack, trackBgPaint)

        if (thumbX > sidePad) {
            fillRect.set(sidePad, centerY - halfTrack, thumbX, centerY + halfTrack)
            canvas.drawRoundRect(fillRect, halfTrack, halfTrack, trackFillPaint)
        }

        if (isDragging) {
            canvas.drawCircle(thumbX, centerY, 15f * density, thumbGlowPaint)
        }
        val dotRadius = (if (isDragging) 8.5f else 6f) * density
        canvas.drawCircle(thumbX, centerY, dotRadius, thumbDotPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val sidePad = 8f * density
        val usableWidth = (width - sidePad * 2f).coerceAtLeast(1f)

        fun computeProgress(rawX: Float): Int {
            val ratio = ((rawX - sidePad) / usableWidth).coerceIn(0f, 1f)
            return (ratio * max + 0.5f).toInt().coerceIn(0, max)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                isDragging = true
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

private class TapDetectFrameLayout(context: Context) : FrameLayout(context) {
    var onVideoSingleTap: (() -> Unit)? = null
    var onVideoDoubleTap: ((Float, Float) -> Unit)? = null
    var shouldIgnoreTap: ((Float, Float) -> Boolean)? = null

    var onBrightnessSwipeStart: (() -> Float)? = null
    var onBrightnessSwipe: ((Float) -> Unit)? = null
    var onBrightnessSwipeEnd: (() -> Unit)? = null

    private var startX = 0f
    private var startY = 0f
    private var downTimeMs = 0L
    private var movedBeyondSlop = false
    private var multiTouchOccurred = false
    private var activeGesture = GestureMode.NONE
    private var startLevel = 0.5f

    private enum class GestureMode { NONE, BRIGHTNESS }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (activeGesture == GestureMode.NONE && shouldIgnoreTap?.invoke(e.x, e.y) != true) {
                    onVideoDoubleTap?.invoke(e.x, e.y)
                }
                return true
            }
        }
    )

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val tapSlop = 14f * resources.displayMetrics.density
        val slideSlop = 6f * resources.displayMetrics.density
        val ignoredAtPoint = shouldIgnoreTap?.invoke(event.x, event.y) == true
        if (!ignoredAtPoint && activeGesture == GestureMode.NONE) {
            gestureDetector.onTouchEvent(event)
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                downTimeMs = SystemClock.uptimeMillis()
                movedBeyondSlop = false
                multiTouchOccurred = false
                activeGesture = GestureMode.NONE
                return super.dispatchTouchEvent(event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouchOccurred = true
                finishVerticalGesture()
                return super.dispatchTouchEvent(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount > 1 || multiTouchOccurred) {
                    finishVerticalGesture()
                    return super.dispatchTouchEvent(event)
                }
                val dx = event.x - startX
                val dy = event.y - startY
                if (dx * dx + dy * dy > tapSlop * tapSlop) {
                    movedBeyondSlop = true
                }

                if (activeGesture == GestureMode.NONE &&
                    abs(dy) > slideSlop &&
                    abs(dy) > abs(dx) * 1.25f &&
                    shouldIgnoreTap?.invoke(startX, startY) != true
                ) {
                    if (startX < width * 0.28f) {
                        activeGesture = GestureMode.BRIGHTNESS
                        startLevel = onBrightnessSwipeStart?.invoke() ?: 0.5f
                        cancelChildTouch(event)
                    }
                }

                if (activeGesture == GestureMode.BRIGHTNESS) {
                    val travelHeight = (height * 0.65f).coerceAtLeast(100f)
                    val delta = -dy / travelHeight
                    onBrightnessSwipe?.invoke((startLevel + delta).coerceIn(0.01f, 1.0f))
                    return true
                }
                return super.dispatchTouchEvent(event)
            }
            MotionEvent.ACTION_UP -> {
                if (activeGesture != GestureMode.NONE) {
                    finishVerticalGesture()
                    return true
                }
                val handled = super.dispatchTouchEvent(event)
                val elapsed = SystemClock.uptimeMillis() - downTimeMs
                if (!movedBeyondSlop &&
                    !multiTouchOccurred &&
                    elapsed < 320L &&
                    shouldIgnoreTap?.invoke(startX, startY) != true &&
                    shouldIgnoreTap?.invoke(event.x, event.y) != true
                ) {
                    onVideoSingleTap?.invoke()
                }
                return handled
            }
            MotionEvent.ACTION_CANCEL -> {
                downTimeMs = 0L
                if (activeGesture != GestureMode.NONE) {
                    finishVerticalGesture()
                    return true
                }
                return super.dispatchTouchEvent(event)
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun cancelChildTouch(event: MotionEvent) {
        val cancelEvent = MotionEvent.obtain(event).apply {
            action = MotionEvent.ACTION_CANCEL
        }
        super.dispatchTouchEvent(cancelEvent)
        cancelEvent.recycle()
    }

    private fun finishVerticalGesture() {
        if (activeGesture == GestureMode.BRIGHTNESS) {
            onBrightnessSwipeEnd?.invoke()
        }
        activeGesture = GestureMode.NONE
    }
}

@OptIn(UnstableApi::class)
private class TwsAudioRenderer(
    context: Context,
    mediaCodecSelector: MediaCodecSelector,
    enableDecoderFallback: Boolean,
    eventHandler: Handler,
    eventListener: AudioRendererEventListener,
    audioSink: AudioSink,
    private val getOffsetUs: () -> Long
) : MediaCodecAudioRenderer(
    context,
    mediaCodecSelector,
    enableDecoderFallback,
    eventHandler,
    eventListener,
    audioSink
) {
    override fun processOutputBuffer(
        positionUs: Long,
        elapsedRealtimeUs: Long,
        codec: androidx.media3.exoplayer.mediacodec.MediaCodecAdapter?,
        buffer: java.nio.ByteBuffer?,
        bufferIndex: Int,
        bufferFlags: Int,
        sampleCount: Int,
        bufferPresentationTimeUs: Long,
        isDecodeOnlyBuffer: Boolean,
        isLastBuffer: Boolean,
        format: androidx.media3.common.Format
    ): Boolean {
        val shiftedPtsUs = (bufferPresentationTimeUs + getOffsetUs()).coerceAtLeast(0L)
        return super.processOutputBuffer(
            positionUs,
            elapsedRealtimeUs,
            codec,
            buffer,
            bufferIndex,
            bufferFlags,
            sampleCount,
            shiftedPtsUs,
            isDecodeOnlyBuffer,
            isLastBuffer,
            format
        )
    }
}

@OptIn(UnstableApi::class)
class MainActivity : Activity(), SensorEventListener {
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var vrSurfaceView: VrVideoSurfaceView
    private var vrOutputSurface: Surface? = null
    private var pendingMediaUri: Uri? = null
    private var activePlaybackUri: Uri? = null
    private var knownTotalDurationMs: Long = 0L
    private var lastRequestedSeekMs: Long = -1L
    private var consecutiveErrorCount = 0
    private var lastErrorTimeMs = 0L

    private lateinit var videoFrame: FrameLayout
    private lateinit var videoCardWrapper: FrameLayout
    private lateinit var placeholder: LinearLayout
    private lateinit var centerHud: TextView
    private lateinit var fpsBadge: TextView
    private lateinit var titleView: TextView
    private lateinit var playButton: ImageView
    private lateinit var seekBar: GlassScrubberBar
    private lateinit var currentTimeView: TextView
    private lateinit var durationView: TextView
    private lateinit var fullscreenButton: ImageView

    // Fullscreen Glass Controls
    private lateinit var fullscreenTopBar: LinearLayout
    private lateinit var fullscreenCenterControls: LinearLayout
    private lateinit var fullscreenBottomDock: LinearLayout
    private lateinit var overlayPlayButton: ImageView
    private lateinit var overlaySeekBar: GlassScrubberBar
    private lateinit var fullscreenTitleView: TextView
    private lateinit var fullscreenCurrentTime: TextView
    private lateinit var fullscreenDuration: TextView
    private lateinit var rootLayout: LinearLayout
    private lateinit var speedButton: TextView
    private lateinit var overlaySpeedButton: TextView
    private lateinit var overlayRecenterButton: TextView
    private lateinit var overlayMotionButton: TextView
    private lateinit var overlayRotateButton: TextView

    private val playVectorInk by lazy { TransportIconDrawable(TransportIconDrawable.Type.PLAY, AppleGlassTheme.inkBlack) }
    private val pauseVectorInk by lazy { TransportIconDrawable(TransportIconDrawable.Type.PAUSE, AppleGlassTheme.inkBlack) }
    private val overlayPlayVector by lazy { TransportIconDrawable(TransportIconDrawable.Type.PLAY, AppleGlassTheme.inkBlack) }
    private val overlayPauseVector by lazy { TransportIconDrawable(TransportIconDrawable.Type.PAUSE, AppleGlassTheme.inkBlack) }

    private val fullscreenChrome = mutableListOf<View>()
    private var isFullscreen = false
    private var fullscreenControlsVisible = true
    private var normalRootPadding = intArrayOf(0, 0, 0, 0)
    private var currentBrightness = -1f
    private var lastRenderedBrightnessPercent = -1
    private var lastRenderedFrames = 0
    private var lastFpsCheckTimeMs = 0L
    private var showFpsEnabled = false
    private var showModeBadgeEnabled = false

    // Gyroscope Motion Tracking State
    private var isMotionEnabled = false
    private var lastGyroTimestampNs = 0L
    private val sensorManager by lazy {
        getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }
    private val gyroSensor: Sensor? by lazy {
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    }

    @Volatile
    private var audioOffsetMs = 0

    private val speedSteps = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private var currentSpeed = 1.0f
    private var isFullscreenLandscape = false

    private var isScrubbing = false
    private var currentProjection = VrVideoSurfaceView.Projection.VR180
    private var stereoLayout = VrVideoSurfaceView.StereoLayout.SBS_LEFT
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideCenterHudRunnable = Runnable {
        if (::centerHud.isInitialized) {
            centerHud.visibility = View.GONE
        }
    }

    private val progressUpdater = object : Runnable {
        override fun run() {
            if (::player.isInitialized) {
                val rawDuration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
                if (rawDuration > 0L) knownTotalDurationMs = rawDuration
                val duration = if (rawDuration > 0L) rawDuration else knownTotalDurationMs
                val position = player.currentPosition.coerceAtLeast(0L)
                val progressPermille = if (duration > 0L) ((position * 1000L) / duration).toInt() else 0
                if (!isScrubbing) {
                    seekBar.progress = progressPermille
                    currentTimeView.text = formatTime(position)
                }
                durationView.text = formatTime(duration)
                if (::overlaySeekBar.isInitialized) {
                    if (!isScrubbing) {
                        overlaySeekBar.progress = progressPermille
                        fullscreenCurrentTime.text = formatTime(position)
                    }
                    fullscreenDuration.text = formatTime(duration)
                }
                if (showFpsEnabled) {
                    updateLiveFpsBadge()
                }
            }
            mainHandler.postDelayed(this, 250L)
        }
    }

    private fun updateLiveFpsBadge() {
        if (!::fpsBadge.isInitialized || !showFpsEnabled) return
        val now = SystemClock.uptimeMillis()
        var liveFps = if (currentProjection != VrVideoSurfaceView.Projection.FLAT && ::vrSurfaceView.isInitialized) {
            vrSurfaceView.getMeasuredVideoFps()
        } else {
            0
        }

        val counters = player.videoDecoderCounters
        if (counters != null) {
            counters.ensureUpdated()
            val totalRendered = counters.renderedOutputBufferCount
            if (lastFpsCheckTimeMs > 0L) {
                val elapsed = now - lastFpsCheckTimeMs
                if (elapsed >= 500L) {
                    val deltaFrames = (totalRendered - lastRenderedFrames).coerceAtLeast(0)
                    val decoderFps = ((deltaFrames * 1000f) / elapsed + 0.5f).toInt()
                    if (liveFps == 0 || currentProjection == VrVideoSurfaceView.Projection.FLAT) {
                        liveFps = decoderFps
                    }
                    lastRenderedFrames = totalRendered
                    lastFpsCheckTimeMs = now
                }
            } else {
                lastRenderedFrames = totalRendered
                lastFpsCheckTimeMs = now
            }
        }

        if (liveFps <= 0 && player.isPlaying) {
            val formatFps = player.videoFormat?.frameRate ?: -1f
            if (formatFps > 0f) {
                liveFps = (formatFps + 0.5f).toInt()
            }
        }

        fpsBadge.text = if (player.isPlaying && liveFps > 0) "$liveFps FPS" else "-- FPS"
    }

    private fun applyFpsVisibility() {
        if (!::fpsBadge.isInitialized) return
        if (!showFpsEnabled) {
            fpsBadge.visibility = View.GONE
        } else {
            fpsBadge.visibility = if (isFullscreen && !fullscreenControlsVisible) View.GONE else View.VISIBLE
            updateLiveFpsBadge()
        }
    }

    private fun applyModeBadgeVisibility() {
        if (!::modeBadge.isInitialized) return
        if (!showModeBadgeEnabled) {
            modeBadge.visibility = View.GONE
        } else {
            modeBadge.visibility = if (isFullscreen && !fullscreenControlsVisible) View.GONE else View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        showFpsEnabled = prefs.getBoolean(KEY_SHOW_FPS, false)
        showModeBadgeEnabled = prefs.getBoolean(KEY_SHOW_MODE_BADGE, false)
        audioOffsetMs = prefs.getInt(KEY_AUDIO_OFFSET_MS, 0).coerceIn(-1000, 1000)

        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = AppleGlassTheme.background
        window.navigationBarColor = AppleGlassTheme.background
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                audioSink: AudioSink,
                eventHandler: Handler,
                eventListener: AudioRendererEventListener,
                out: ArrayList<Renderer>
            ) {
                out.add(
                    TwsAudioRenderer(
                        context = context,
                        mediaCodecSelector = mediaCodecSelector,
                        enableDecoderFallback = enableDecoderFallback,
                        eventHandler = eventHandler,
                        eventListener = eventListener,
                        audioSink = audioSink,
                        getOffsetUs = { audioOffsetMs * 1000L }
                    )
                )
            }
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val dataSourceFactory = SafeGuardDataSource.Factory(this)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000,
                50_000,
                1_500,
                3_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .build()

        player.setSeekParameters(SeekParameters.CLOSEST_SYNC)

        buildInterface()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playButton.setImageDrawable(if (isPlaying) pauseVectorInk else playVectorInk)
                if (::overlayPlayButton.isInitialized) {
                    overlayPlayButton.setImageDrawable(if (isPlaying) overlayPauseVector else overlayPlayVector)
                }
                if (!isPlaying && ::fpsBadge.isInitialized) {
                    fpsBadge.text = "-- FPS"
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    consecutiveErrorCount = 0
                    lastRequestedSeekMs = -1L
                    placeholder.visibility = View.GONE
                    val resolvedName = currentVideoName.ifBlank { "Now Playing" }
                    titleView.text = resolvedName
                    if (::fullscreenTitleView.isInitialized) fullscreenTitleView.text = resolvedName
                }
                if (playbackState == Player.STATE_ENDED) {
                    playButton.setImageDrawable(playVectorInk)
                    if (::overlayPlayButton.isInitialized) {
                        overlayPlayButton.setImageDrawable(overlayPlayVector)
                    }
                    if (::fpsBadge.isInitialized) fpsBadge.text = "-- FPS"
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (::vrSurfaceView.isInitialized) {
                    vrSurfaceView.setVideoSize(
                        videoSize.width,
                        videoSize.height,
                        videoSize.unappliedRotationDegrees,
                        videoSize.pixelWidthHeightRatio
                    )
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val uri = activePlaybackUri
                val now = SystemClock.uptimeMillis()
                if (now - lastErrorTimeMs > 5000L) {
                    consecutiveErrorCount = 0
                }
                lastErrorTimeMs = now

                if (uri != null && consecutiveErrorCount < 4) {
                    consecutiveErrorCount++
                    val exactPosition = if (lastRequestedSeekMs >= 0L) {
                        lastRequestedSeekMs
                    } else {
                        player.currentPosition.coerceAtLeast(0L)
                    }
                    recoverStreamAt(exactPosition)
                    return
                }

                showFullErrorDialog(error)
                if (activePlaybackUri == null) {
                    placeholder.visibility = View.VISIBLE
                }
            }
        })
        mainHandler.post(progressUpdater)
    }

    private fun recoverStreamAt(targetPositionMs: Long) {
        val uri = activePlaybackUri ?: return
        val exactTarget = targetPositionMs.coerceAtLeast(0L)
        lastRequestedSeekMs = exactTarget
        mainHandler.postDelayed({
            if (::player.isInitialized && activePlaybackUri == uri) {
                rebindActiveSurface()
                player.stop()
                player.clearMediaItems()
                player.setMediaItem(MediaItem.fromUri(uri))
                player.prepare()
                if (exactTarget > 0L) {
                    player.seekTo(exactTarget)
                }
                player.playWhenReady = true
            }
        }, 100L)
    }

    private fun buildCompleteStackTrace(throwable: Throwable): String {
        val sb = StringBuilder()
        val visited = IdentityHashMap<Throwable, Boolean>()

        fun appendThrowable(t: Throwable, prefix: String) {
            if (visited.put(t, true) != null) {
                sb.appendLine("${prefix}[CIRCULAR REFERENCE: ${t.javaClass.name}: ${t.message}]")
                return
            }
            sb.appendLine("$prefix${t.javaClass.name}: ${t.message ?: "null"}")
            for (element in t.stackTrace) {
                sb.appendLine("    at $element")
            }
            for (suppressed in t.suppressed) {
                appendThrowable(suppressed, "Suppressed: ")
            }
            t.cause?.let { cause ->
                appendThrowable(cause, "Caused by: ")
            }
        }

        appendThrowable(throwable, "")
        return sb.toString()
    }

    private fun showFullErrorDialog(error: PlaybackException) {
        val untruncatedTrace = buildCompleteStackTrace(error)
        val summary = buildString {
            appendLine("Error Code: ${error.errorCodeName} (${error.errorCode})")
            appendLine("Timestamp: ${formatTime(player.currentPosition.coerceAtLeast(0L))}")
            appendLine("Message: ${error.message ?: "None"}")
            error.cause?.let { cause ->
                appendLine("Root Cause: ${cause.javaClass.name}: ${cause.message}")
            }
            appendLine()
            appendLine("--- Complete Stack Trace (Untruncated) ---")
            append(untruncatedTrace)
        }

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(18))
            background = glassBox(AppleGlassTheme.cardSurface, 28, AppleGlassTheme.glassBorder)
        }

        val title = appleText("Playback Error", 17f, AppleGlassTheme.appleCoral, weight = 600).apply {
            setPadding(0, 0, 0, dp(12))
        }
        outer.addView(title)

        val scrollView = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            background = solidPill(AppleGlassTheme.background, 16)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val logText = TextView(this).apply {
            text = summary
            textSize = 10.5f
            setTextColor(AppleGlassTheme.labelPrimary)
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        scrollView.addView(logText)
        val maxScrollHeight = (resources.displayMetrics.heightPixels * 0.50f).toInt()
        outer.addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxScrollHeight))

        val dialog = AlertDialog.Builder(this)
            .setView(outer)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val copyBtn = glassButton("Copy Log", highlighted = false).apply {
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("PanoPlay Error", summary))
                Toast.makeText(this@MainActivity, "Error log copied", Toast.LENGTH_SHORT).show()
            }
        }
        val closeBtn = glassButton("Close", highlighted = true).apply {
            setOnClickListener { dialog.dismiss() }
        }
        btnRow.addView(copyBtn, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(10) })
        btnRow.addView(closeBtn, LinearLayout.LayoutParams(0, dp(44), 1f))
        outer.addView(
            btnRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
            }
        )

        dialog.show()
    }

    private fun performSafeSeek(targetMs: Long) {
        val clamped = targetMs.coerceAtLeast(0L)
        lastRequestedSeekMs = clamped
        consecutiveErrorCount = 0

        val state = player.playbackState
        if (state == Player.STATE_IDLE || state == Player.STATE_ENDED || player.playerError != null) {
            recoverStreamAt(clamped)
        } else {
            player.seekTo(clamped)
            if (!player.isPlaying) {
                player.playWhenReady = true
            }
        }
    }

    private fun setPillLeftIcon(view: TextView, icon: Drawable, tintColor: Int) {
        icon.setColorFilter(tintColor, PorterDuff.Mode.SRC_IN)
        val iconSize = dp(16)
        icon.setBounds(0, 0, iconSize, iconSize)
        view.compoundDrawablePadding = dp(7)
        view.setCompoundDrawables(icon, null, null, null)
    }

    private fun setMotionTrackingEnabled(enabled: Boolean) {
        if (enabled && gyroSensor == null) {
            Toast.makeText(this, "Gyroscope sensor unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        isMotionEnabled = enabled
        lastGyroTimestampNs = 0L
        if (enabled) {
            sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME)
        } else {
            sensorManager.unregisterListener(this)
        }
        if (::overlayMotionButton.isInitialized) {
            val textColor = if (enabled) AppleGlassTheme.inkBlack else AppleGlassTheme.labelPrimary
            overlayMotionButton.background = rippleGlassPill(
                if (enabled) AppleGlassTheme.pureWhite else AppleGlassTheme.glassPill,
                22,
                if (enabled) AppleGlassTheme.pureWhite else AppleGlassTheme.glassBorder
            )
            overlayMotionButton.setTextColor(textColor)
            setPillLeftIcon(
                overlayMotionButton,
                SpatialIconDrawable(SpatialIconDrawable.Type.MOTION, textColor),
                textColor
            )
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!isMotionEnabled || event == null || event.sensor.type != Sensor.TYPE_GYROSCOPE) return
        if (currentProjection == VrVideoSurfaceView.Projection.FLAT) return

        val ts = event.timestamp
        if (lastGyroTimestampNs != 0L) {
            val dtSec = (ts - lastGyroTimestampNs) * 1e-9f
            if (dtSec > 0f && dtSec < 0.25f) {
                val rotX = event.values[0]
                val rotY = event.values[1]

                @Suppress("DEPRECATION")
                val displayRotation = windowManager.defaultDisplay?.rotation ?: Surface.ROTATION_0
                val yawRateRad: Float
                val pitchRateRad: Float
                when (displayRotation) {
                    Surface.ROTATION_90 -> {
                        yawRateRad = -rotX
                        pitchRateRad = rotY
                    }
                    Surface.ROTATION_270 -> {
                        yawRateRad = rotX
                        pitchRateRad = -rotY
                    }
                    Surface.ROTATION_180 -> {
                        yawRateRad = -rotY
                        pitchRateRad = -rotX
                    }
                    else -> {
                        yawRateRad = rotY
                        pitchRateRad = rotX
                    }
                }

                val filteredYaw = if (abs(yawRateRad) < 0.015f) 0f else yawRateRad
                val filteredPitch = if (abs(pitchRateRad) < 0.015f) 0f else pitchRateRad

                if (filteredYaw != 0f || filteredPitch != 0f) {
                    val deltaYawDeg = -Math.toDegrees((filteredYaw * dtSec).toDouble()).toFloat() * 1.15f
                    val deltaPitchDeg = Math.toDegrees((filteredPitch * dtSec).toDouble()).toFloat() * 1.15f
                    vrSurfaceView.applyGyroDelta(deltaYawDeg, deltaPitchDeg)
                }
            }
        }
        lastGyroTimestampNs = ts
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun rebindActiveSurface() {
        if (currentProjection == VrVideoSurfaceView.Projection.FLAT) {
            playerView.player = player
        } else {
            val activeSurface = vrOutputSurface ?: vrSurfaceView.getVideoSurface()
            if (activeSurface != null && activeSurface.isValid) {
                player.setVideoSurface(activeSurface)
            }
        }
    }

    private var currentVideoName: String = ""

    private fun showCenterHud(message: String, autoHideMs: Long = 0L, leftDrawable: Drawable? = null) {
        mainHandler.removeCallbacks(hideCenterHudRunnable)
        if (leftDrawable != null) {
            val iconSize = dp(18)
            leftDrawable.setBounds(0, 0, iconSize, iconSize)
            if (centerHud.compoundDrawables[0] !== leftDrawable) {
                centerHud.compoundDrawablePadding = dp(7)
                centerHud.setCompoundDrawables(leftDrawable, null, null, null)
            }
        } else if (centerHud.compoundDrawables[0] != null) {
            centerHud.setCompoundDrawables(null, null, null, null)
        }
        if (centerHud.text.toString() != message) {
            centerHud.text = message
        }
        if (centerHud.visibility != View.VISIBLE) {
            centerHud.visibility = View.VISIBLE
        }
        if (autoHideMs > 0L) {
            mainHandler.postDelayed(hideCenterHudRunnable, autoHideMs)
        }
    }

    private fun readInitialBrightness(): Float {
        if (currentBrightness in 0.01f..1.0f) return currentBrightness
        val winBrightness = window.attributes.screenBrightness
        if (winBrightness in 0.01f..1.0f) {
            currentBrightness = winBrightness
            return currentBrightness
        }
        val sysBrightness = try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (_: Exception) {
            0.5f
        }
        currentBrightness = sysBrightness.coerceIn(0.05f, 1.0f)
        return currentBrightness
    }

    private fun applyBrightness(value: Float) {
        val clamped = value.coerceIn(0.01f, 1.0f)
        val percent = (clamped * 100f).roundToInt().coerceIn(1, 100)
        if (percent == lastRenderedBrightnessPercent && abs(clamped - currentBrightness) < 0.005f) {
            return
        }
        lastRenderedBrightnessPercent = percent
        currentBrightness = clamped
        val lp = window.attributes
        lp.screenBrightness = clamped
        window.attributes = lp

        showCenterHud("Brightness  $percent%")
    }

    private fun formatSpeedLabel(speed: Float): String =
        if (speed == 1.0f || speed == 2.0f) "${speed.toInt()}.0x" else "${speed}x"

    private fun showSpeedPicker(anchor: View) {
        val popup = PopupMenu(this, anchor)
        speedSteps.forEachIndexed { index, speed ->
            val label = formatSpeedLabel(speed)
            val title = if (speed == currentSpeed) "✓  $label" else "     $label"
            popup.menu.add(0, index, index, title)
        }
        popup.setOnMenuItemClickListener { item ->
            val selected = speedSteps.getOrNull(item.itemId) ?: return@setOnMenuItemClickListener false
            currentSpeed = selected
            player.playbackParameters = PlaybackParameters(selected)
            val label = formatSpeedLabel(selected)
            speedButton.text = label
            if (::overlaySpeedButton.isInitialized) overlaySpeedButton.text = label
            showCenterHud("Speed  $label", 800L)
            true
        }
        popup.show()
    }

    private fun toggleFullscreenOrientation() {
        if (!isFullscreen) return
        isFullscreenLandscape = !isFullscreenLandscape
        requestedOrientation = if (isFullscreenLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        if (::overlayRotateButton.isInitialized) {
            overlayRotateButton.text = if (isFullscreenLandscape) "Portrait" else "Landscape"
        }
    }

    private fun handleDoubleTap(x: Float) {
        if (!isFullscreen) return
        val w = videoFrame.width.coerceAtLeast(1)
        if (x < w * 0.28f) {
            performSafeSeek((player.currentPosition - 10_000L).coerceAtLeast(0L))
            showCenterHud("-10s", 750L)
        } else if (x > w * 0.72f) {
            val end = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: Long.MAX_VALUE
            performSafeSeek((player.currentPosition + 10_000L).coerceAtMost(end))
            showCenterHud("+10s", 750L)
        }
    }

    private fun formatOffsetLabel(ms: Int): String = when {
        ms > 0 -> "+$ms ms"
        ms < 0 -> "$ms ms"
        else -> "0 ms"
    }

    private fun openSettingsDialog() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(20))
            background = glassBox(AppleGlassTheme.cardSurface, 30, AppleGlassTheme.glassBorder)
        }

        val dialogTitle = appleText("Settings", 19f, AppleGlassTheme.labelPrimary, weight = 600).apply {
            setPadding(0, 0, 0, dp(16))
        }
        container.addView(dialogTitle)

        // 1. Live FPS Toggle
        val fpsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = glassBox(AppleGlassTheme.cardElevated, 20, AppleGlassTheme.glassBorder)
        }
        val fpsTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val fpsLabel = appleText("Live Frame Rate", 14.5f, AppleGlassTheme.labelPrimary, weight = 500)
        val fpsSub = appleText("Display real-time FPS badge", 11.5f, AppleGlassTheme.labelSecondary, weight = 400).apply {
            setPadding(0, dp(3), 0, 0)
        }
        fpsTextCol.addView(fpsLabel)
        fpsTextCol.addView(fpsSub)
        fpsRow.addView(fpsTextCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val fpsSwitch = Switch(this).apply {
            isChecked = showFpsEnabled
            thumbTintList = ColorStateList.valueOf(AppleGlassTheme.pureWhite)
            trackTintList = ColorStateList.valueOf(AppleGlassTheme.appleMint)
            setOnCheckedChangeListener { _, isChecked ->
                showFpsEnabled = isChecked
                prefs.edit().putBoolean(KEY_SHOW_FPS, isChecked).apply()
                applyFpsVisibility()
            }
        }
        fpsRow.addView(fpsSwitch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        container.addView(fpsRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 2. Projection Badge Toggle
        val modeBadgeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = glassBox(AppleGlassTheme.cardElevated, 20, AppleGlassTheme.glassBorder)
        }
        val modeBadgeTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val modeBadgeLabel = appleText("Projection Pill", 14.5f, AppleGlassTheme.labelPrimary, weight = 500)
        val modeBadgeSub = appleText("Show 180° / 360° & Eye badge", 11.5f, AppleGlassTheme.labelSecondary, weight = 400).apply {
            setPadding(0, dp(3), 0, 0)
        }
        modeBadgeTextCol.addView(modeBadgeLabel)
        modeBadgeTextCol.addView(modeBadgeSub)
        modeBadgeRow.addView(modeBadgeTextCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val modeBadgeSwitch = Switch(this).apply {
            isChecked = showModeBadgeEnabled
            thumbTintList = ColorStateList.valueOf(AppleGlassTheme.pureWhite)
            trackTintList = ColorStateList.valueOf(AppleGlassTheme.appleMint)
            setOnCheckedChangeListener { _, isChecked ->
                showModeBadgeEnabled = isChecked
                prefs.edit().putBoolean(KEY_SHOW_MODE_BADGE, isChecked).apply()
                applyModeBadgeVisibility()
            }
        }
        modeBadgeRow.addView(modeBadgeSwitch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        container.addView(
            modeBadgeRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            }
        )

        // 3. Audio Sync Offset Card
        val audioCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = glassBox(AppleGlassTheme.cardElevated, 20, AppleGlassTheme.glassBorder)
        }
        val audioHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val audioTitle = appleText("Audio Sync", 14.5f, AppleGlassTheme.labelPrimary, weight = 500)
        val audioValueText = appleText(formatOffsetLabel(audioOffsetMs), 13f, AppleGlassTheme.pureWhite, weight = 600, tabular = true).apply {
            gravity = Gravity.END
        }
        audioHeader.addView(audioTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        audioHeader.addView(audioValueText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        audioCard.addView(audioHeader)

        val audioHint = appleText("+ms if audio is early  •  -ms if audio is late", 11f, AppleGlassTheme.labelSecondary, weight = 400).apply {
            setPadding(0, dp(3), 0, dp(10))
        }
        audioCard.addView(audioHint)

        val audioSeekBar = SeekBar(this).apply {
            max = 2000
            progress = (audioOffsetMs + 1000).coerceIn(0, 2000)
            progressTintList = ColorStateList.valueOf(AppleGlassTheme.pureWhite)
            thumbTintList = ColorStateList.valueOf(AppleGlassTheme.pureWhite)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    val newOffset = value - 1000
                    audioOffsetMs = newOffset
                    audioValueText.text = formatOffsetLabel(newOffset)
                    if (fromUser) {
                        prefs.edit().putInt(KEY_AUDIO_OFFSET_MS, newOffset).apply()
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    if (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING) {
                        performSafeSeek(player.currentPosition)
                    }
                }
            })
        }
        audioCard.addView(audioSeekBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)))

        fun applyExactOffset(targetMs: Int) {
            val clamped = targetMs.coerceIn(-1000, 1000)
            audioOffsetMs = clamped
            audioSeekBar.progress = clamped + 1000
            audioValueText.text = formatOffsetLabel(clamped)
            prefs.edit().putInt(KEY_AUDIO_OFFSET_MS, clamped).apply()
            if (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING) {
                performSafeSeek(player.currentPosition)
            }
        }

        fun stepChip(label: String, deltaMs: Int): TextView = appleText(label, 11f, AppleGlassTheme.labelPrimary, weight = 500, tabular = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(7), dp(8), dp(7))
            background = rippleGlassPill(AppleGlassTheme.chipIdle, 12, AppleGlassTheme.glassBorder)
            setOnClickListener { applyExactOffset(audioOffsetMs + deltaMs) }
        }

        val fineTuneRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val minus5 = stepChip("-5ms", -5)
        val minus1 = stepChip("-1ms", -1)
        val resetBtn = appleText("Reset", 11f, AppleGlassTheme.inkBlack, weight = 600).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(7), dp(10), dp(7))
            background = rippleGlassPill(AppleGlassTheme.pureWhite, 12, AppleGlassTheme.pureWhite)
            setOnClickListener { applyExactOffset(0) }
        }
        val plus1 = stepChip("+1ms", 1)
        val plus5 = stepChip("+5ms", 5)

        val chips = listOf(minus5, minus1, resetBtn, plus1, plus5)
        chips.forEachIndexed { idx, chip ->
            fineTuneRow.addView(
                chip,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx > 0) leftMargin = dp(5)
                }
            )
        }
        audioCard.addView(
            fineTuneRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        )

        container.addView(
            audioCard,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            }
        )

        val dialog = AlertDialog.Builder(this)
            .setView(container)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val closeBtn = glassButton("Done", highlighted = true).apply {
            setOnClickListener { dialog.dismiss() }
        }
        container.addView(
            closeBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
                topMargin = dp(18)
            }
        )

        dialog.show()
    }

    private fun chooseVideoForMetadata() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_METADATA)
    }

    private fun inspectAndShowMetadata(uri: Uri) {
        val fileName = queryDisplayName(uri).ifBlank { "Unknown video" }
        val fileSizeBytes = queryFileSize(uri)
        val fileSizeText = formatFileSize(fileSizeBytes)

        var width = 0
        var height = 0
        var rotation = 0
        var durationMs = 0L
        var overallBitrate = 0L
        var mimeContainer = "MP4 / Video"

        var videoCodec = "Unknown"
        var videoFpsText = "Unknown"
        var videoBitrateText = "Unknown"
        var colorInfoText = "Standard (SDR)"

        var audioCodec = "None"
        var audioSampleRateText = "Unknown"
        var audioChannelsText = "Unknown"
        var audioBitrateText = "Unknown"

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, uri)
            width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            overallBitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.let {
                if (it.isNotBlank()) mimeContainer = it
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val frameRateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                val parsedFps = frameRateStr?.toFloatOrNull()
                if (parsedFps != null && parsedFps > 0f) {
                    videoFpsText = String.format(Locale.US, "%.2f FPS", parsedFps)
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(this, uri, null)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/")) {
                    videoCodec = friendlyCodecName(mime)
                    if (width == 0 && format.containsKey(MediaFormat.KEY_WIDTH)) {
                        width = format.getInteger(MediaFormat.KEY_WIDTH)
                    }
                    if (height == 0 && format.containsKey(MediaFormat.KEY_HEIGHT)) {
                        height = format.getInteger(MediaFormat.KEY_HEIGHT)
                    }
                    if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        val fps = try {
                            format.getFloat(MediaFormat.KEY_FRAME_RATE)
                        } catch (_: Exception) {
                            format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                        }
                        if (fps > 0f) {
                            videoFpsText = if (fps % 1f == 0f) "${fps.toInt()} FPS"
                            else String.format(Locale.US, "%.3f FPS", fps)
                        }
                    }
                    if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        val vBitrate = format.getInteger(MediaFormat.KEY_BIT_RATE).toLong()
                        if (vBitrate > 0L) videoBitrateText = formatBitrate(vBitrate)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && format.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
                        val std = format.getInteger(MediaFormat.KEY_COLOR_STANDARD)
                        colorInfoText = when (std) {
                            MediaFormat.COLOR_STANDARD_BT2020 -> "BT.2020 (HDR / Wide Gamut)"
                            MediaFormat.COLOR_STANDARD_BT709 -> "BT.709 (HD)"
                            MediaFormat.COLOR_STANDARD_BT601_PAL,
                            MediaFormat.COLOR_STANDARD_BT601_NTSC -> "BT.601 (SD)"
                            else -> "Standard"
                        }
                    }
                } else if (mime.startsWith("audio/")) {
                    audioCodec = friendlyCodecName(mime)
                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        val hz = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (hz > 0) audioSampleRateText = String.format(Locale.US, "%.1f kHz", hz / 1000f)
                    }
                    if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        val ch = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        audioChannelsText = when (ch) {
                            1 -> "1 (Mono)"
                            2 -> "2 (Stereo)"
                            6 -> "6 (5.1 Surround)"
                            else -> "$ch Channels"
                        }
                    }
                    if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        val aBitrate = format.getInteger(MediaFormat.KEY_BIT_RATE).toLong()
                        if (aBitrate > 0L) audioBitrateText = formatBitrate(aBitrate)
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                extractor.release()
            } catch (_: Exception) {
            }
        }

        if (videoBitrateText == "Unknown" && overallBitrate > 0L) {
            videoBitrateText = "${formatBitrate(overallBitrate)} (Overall)"
        }

        val qualityTier = classifyResolutionTier(width, height)
        val resolutionDisplay = if (width > 0 && height > 0) {
            "$qualityTier  ($width × $height)"
        } else {
            "Unknown"
        }
        val aspectRatioText = if (width > 0 && height > 0) {
            String.format(Locale.US, "%.2f : 1", width.toFloat() / height.toFloat())
        } else {
            "Unknown"
        }

        showMetadataDialog(
            fileName = fileName,
            rows = listOf(
                "GENERAL" to null,
                "File Size" to fileSizeText,
                "Container" to mimeContainer,
                "Duration" to if (durationMs > 0L) formatTime(durationMs) else "Unknown",
                "Overall Bitrate" to if (overallBitrate > 0L) formatBitrate(overallBitrate) else "Unknown",
                "VIDEO STREAM" to null,
                "Quality / Resolution" to resolutionDisplay,
                "Frame Rate (FPS)" to videoFpsText,
                "Video Codec" to videoCodec,
                "Video Bitrate" to videoBitrateText,
                "Aspect Ratio" to aspectRatioText,
                "Color Space" to colorInfoText,
                "Rotation" to "$rotation°",
                "AUDIO STREAM" to null,
                "Audio Codec" to audioCodec,
                "Channels" to audioChannelsText,
                "Sample Rate" to audioSampleRateText,
                "Audio Bitrate" to audioBitrateText
            )
        )
    }

    private fun showMetadataDialog(fileName: String, rows: List<Pair<String, String?>>) {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(18))
            background = glassBox(AppleGlassTheme.cardSurface, 30, AppleGlassTheme.glassBorder)
        }

        val headerTitle = appleText("Media Info", 19f, AppleGlassTheme.labelPrimary, weight = 600)
        val fileLabel = appleText(fileName, 12f, AppleGlassTheme.labelSecondary, weight = 500).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(0, dp(4), 0, dp(14))
        }
        outer.addView(headerTitle)
        outer.addView(fileLabel)

        val scrollView = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
        }
        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        rows.forEach { (key, value) ->
            if (value == null) {
                val sectionHeader = appleText(key, 10.5f, AppleGlassTheme.labelSecondary, weight = 600).apply {
                    letterSpacing = 0.08f
                    setPadding(dp(4), dp(12), 0, dp(6))
                }
                listContainer.addView(sectionHeader)
            } else {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    background = solidPill(AppleGlassTheme.cardElevated, 14)
                }
                val kView = appleText(key, 12f, AppleGlassTheme.labelSecondary, weight = 400)
                val vView = appleText(value, 12f, AppleGlassTheme.labelPrimary, weight = 600, tabular = true).apply {
                    gravity = Gravity.END
                }
                row.addView(kView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.44f))
                row.addView(vView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.56f))
                listContainer.addView(
                    row,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(5)
                    }
                )
            }
        }

        scrollView.addView(listContainer)
        val maxScrollHeight = (resources.displayMetrics.heightPixels * 0.50f).toInt()
        outer.addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxScrollHeight))

        val dialog = AlertDialog.Builder(this)
            .setView(outer)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val closeBtn = glassButton("Done", highlighted = true).apply {
            setOnClickListener { dialog.dismiss() }
        }
        outer.addView(
            closeBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply {
                topMargin = dp(16)
            }
        )
        dialog.show()
    }

    private fun classifyResolutionTier(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return "Unknown"
        val shortSide = min(width, height)
        val longSide = maxOf(width, height)
        return when {
            shortSide >= 2160 || longSide >= 3840 -> "4K UHD (2160p)"
            shortSide >= 1440 || longSide >= 2560 -> "2K QHD (1440p)"
            shortSide >= 1080 || longSide >= 1920 -> "Full HD (1080p)"
            shortSide >= 720 || longSide >= 1280 -> "HD (720p)"
            shortSide >= 480 -> "SD (480p)"
            else -> "${shortSide}p"
        }
    }

    private fun friendlyCodecName(mime: String): String = when (mime.lowercase(Locale.US)) {
        "video/avc" -> "AVC / H.264"
        "video/hevc" -> "HEVC / H.265"
        "video/av01" -> "AV1"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/x-vnd.on2.vp8" -> "VP8"
        "video/mp4v-es" -> "MPEG-4 Visual"
        "audio/mp4a-latm" -> "AAC"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/ac3" -> "Dolby AC-3"
        "audio/eac3" -> "Dolby E-AC-3"
        "audio/mpeg" -> "MP3"
        "audio/flac" -> "FLAC"
        else -> mime
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return "Unknown"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.2f MB", mb)
            else -> String.format(Locale.US, "%.1f KB", kb)
        }
    }

    private fun formatBitrate(bps: Long): String {
        if (bps <= 0L) return "Unknown"
        val kbps = bps / 1000.0
        val mbps = kbps / 1000.0
        return if (mbps >= 1.0) String.format(Locale.US, "%.2f Mbps", mbps)
        else String.format(Locale.US, "%.0f kbps", kbps)
    }

    private fun buildInterface() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(AppleGlassTheme.background)
            setPadding(dp(16), dp(8), dp(16), dp(12))
        }
        rootLayout = root
        normalRootPadding = intArrayOf(dp(16), dp(8), dp(16), dp(12))

        // --- iOS Style Clean Top Navigation Header ---
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(8))
        }

        val brandIconBox = FrameLayout(this).apply {
            background = glassBox(AppleGlassTheme.cardElevated, 14, AppleGlassTheme.glassBorder)
        }
        val brandIcon = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageDrawable(SpatialIconDrawable(SpatialIconDrawable.Type.SPATIAL_EYE, AppleGlassTheme.labelPrimary))
        }
        brandIconBox.addView(brandIcon, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        header.addView(brandIconBox, LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(10) })

        val brandColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val brand = appleText("PanoPlay", 20f, AppleGlassTheme.labelPrimary, weight = 600).apply {
            letterSpacing = -0.02f
        }
        val subtitle = appleText("Spatial Cinema", 11.5f, AppleGlassTheme.labelSecondary, weight = 400)
        brandColumn.addView(brand)
        brandColumn.addView(subtitle)
        header.addView(brandColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val metadataButton = appleIconCircleButton(SpatialIconDrawable.Type.INFO, "Media Info") {
            chooseVideoForMetadata()
        }
        header.addView(metadataButton, LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(8) })

        val settingsButton = appleIconCircleButton(SpatialIconDrawable.Type.SETTINGS, "Settings") {
            openSettingsDialog()
        }
        header.addView(settingsButton, LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(8) })

        val openButton = appleText("Open", 13.5f, AppleGlassTheme.inkBlack, weight = 600).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(18), 0)
            background = rippleGlassPill(AppleGlassTheme.pureWhite, 20, AppleGlassTheme.pureWhite)
            isClickable = true
            isFocusable = true
            setPillLeftIcon(this, SpatialIconDrawable(SpatialIconDrawable.Type.FOLDER_OPEN, AppleGlassTheme.inkBlack), AppleGlassTheme.inkBlack)
            setOnClickListener { chooseVideo() }
        }
        header.addView(openButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        fullscreenChrome.add(header)

        // --- iOS Rounded Cinema Stage with Subtle Specular Glass Rim ---
        videoCardWrapper = FrameLayout(this).apply {
            background = glassBox(Color.BLACK, 26, AppleGlassTheme.glassBorder)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val radius = if (isFullscreen) 0f else dp(26).toFloat()
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
        }

        videoFrame = TapDetectFrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            onVideoDoubleTap = { x, _ -> handleDoubleTap(x) }
            onBrightnessSwipeStart = {
                lastRenderedBrightnessPercent = -1
                readInitialBrightness()
            }
            onBrightnessSwipe = { level -> applyBrightness(level) }
            onBrightnessSwipeEnd = {
                mainHandler.removeCallbacks(hideCenterHudRunnable)
                mainHandler.postDelayed(hideCenterHudRunnable, 800L)
            }
        }

        playerView = LayoutInflater.from(this).inflate(R.layout.video_player, videoFrame, false) as PlayerView
        playerView.player = player
        playerView.visibility = View.GONE
        videoFrame.addView(
            playerView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        vrSurfaceView = VrVideoSurfaceView(this).apply {
            setProjection(currentProjection)
            setStereoLayout(stereoLayout)
            surfaceEvents = object : VrVideoSurfaceView.SurfaceEvents {
                override fun onVideoSurfaceAvailable(surface: Surface) {
                    vrOutputSurface = surface
                    if (currentProjection != VrVideoSurfaceView.Projection.FLAT) {
                        player.setVideoSurface(surface)
                        pendingMediaUri?.let { uri ->
                            pendingMediaUri = null
                            startPlayback(uri)
                        }
                    }
                }

                override fun onVideoSurfaceDestroyed(surface: Surface) {
                    if (vrOutputSurface == surface) {
                        vrOutputSurface = null
                    }
                    if (currentProjection != VrVideoSurfaceView.Projection.FLAT) {
                        player.clearVideoSurface(surface)
                    }
                }
            }
        }
        videoFrame.addView(
            vrSurfaceView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        // Floating Glass HUD Pill
        centerHud = appleText("Brightness  50%", 13f, AppleGlassTheme.labelPrimary, weight = 600, tabular = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = glassBox(AppleGlassTheme.glassDock, 24, AppleGlassTheme.glassBorderStrong)
            elevation = dp(8).toFloat()
            visibility = View.GONE
        }
        videoFrame.addView(
            centerHud,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )

        val viewBadge = appleText("180° VR · LEFT EYE", 10.5f, AppleGlassTheme.labelPrimary, weight = 600).apply {
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = glassBox(AppleGlassTheme.glassDark, 20, AppleGlassTheme.glassBorder)
            elevation = dp(4).toFloat()
            visibility = if (showModeBadgeEnabled) View.VISIBLE else View.GONE
        }
        val badgeParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ).apply {
            setMargins(dp(14), dp(14), 0, 0)
        }
        videoFrame.addView(viewBadge, badgeParams)
        modeBadge = viewBadge

        fpsBadge = appleText("-- FPS", 10.5f, AppleGlassTheme.appleMint, weight = 600, tabular = true).apply {
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = glassBox(AppleGlassTheme.glassDark, 20, AppleGlassTheme.glassBorder)
            elevation = dp(4).toFloat()
            visibility = if (showFpsEnabled) View.VISIBLE else View.GONE
        }
        val fpsBadgeParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply {
            setMargins(0, dp(14), dp(14), 0)
        }
        videoFrame.addView(fpsBadge, fpsBadgeParams)

        // Frosted Glass Fullscreen Expand Button
        fullscreenButton = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageDrawable(SpatialIconDrawable(SpatialIconDrawable.Type.FULLSCREEN, AppleGlassTheme.labelPrimary))
            background = rippleGlassPill(AppleGlassTheme.glassDark, 22, AppleGlassTheme.glassBorderStrong)
            elevation = dp(5).toFloat()
            setPadding(dp(11), dp(11), dp(11), dp(11))
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleFullscreen() }
            contentDescription = "Enter full screen"
        }
        val fullscreenParams = FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(14), dp(14))
        }
        videoFrame.addView(fullscreenButton, fullscreenParams)

        // =========================================================================
        // 1. FULLSCREEN TOP BAR (iOS Floating Back Pill + Title)
        // =========================================================================
        fullscreenTopBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(16), dp(18), dp(14))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(175, 0, 0, 0), Color.TRANSPARENT)
            )
            elevation = dp(4).toFloat()
            visibility = View.GONE
        }
        val exitFullscreenBtn = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageDrawable(SpatialIconDrawable(SpatialIconDrawable.Type.BACK_CHEVRON, AppleGlassTheme.labelPrimary))
            background = rippleGlassPill(AppleGlassTheme.glassDark, 20, AppleGlassTheme.glassBorderStrong)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            isClickable = true
            isFocusable = true
            contentDescription = "Exit full screen"
            setOnClickListener { if (isFullscreen) toggleFullscreen() }
        }
        fullscreenTitleView = appleText("Now Playing", 15f, AppleGlassTheme.labelPrimary, weight = 600).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setShadowLayer(6f, 0f, 2f, Color.argb(160, 0, 0, 0))
        }
        fullscreenTopBar.addView(exitFullscreenBtn, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
            rightMargin = dp(12)
        })
        fullscreenTopBar.addView(fullscreenTitleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        videoFrame.addView(
            fullscreenTopBar,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        )

        // =========================================================================
        // 2. FULLSCREEN CENTER GLASS ORBS (-10s, Play/Pause, +10s)
        // =========================================================================
        fullscreenCenterControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            elevation = dp(6).toFloat()
            visibility = View.GONE
        }
        val centerRewind = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageDrawable(TransportIconDrawable(TransportIconDrawable.Type.REWIND, AppleGlassTheme.labelPrimary))
            background = rippleGlassPill(AppleGlassTheme.glassDark, 26, AppleGlassTheme.glassBorderStrong)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            isClickable = true
            isFocusable = true
            contentDescription = "Rewind 10 seconds"
            setOnClickListener { performSafeSeek((player.currentPosition - 10_000L).coerceAtLeast(0L)) }
        }
        overlayPlayButton = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageDrawable(overlayPlayVector)
            background = rippleGlassPill(AppleGlassTheme.pureWhite, 36, AppleGlassTheme.pureWhite)
            setPadding(dp(18), dp(18), dp(18), dp(18))
            elevation = dp(8).toFloat()
            isClickable = true
            isFocusable = true
            contentDescription = "Play or pause"
            setOnClickListener {
                if (player.isPlaying) player.pause() else {
                    if (player.playbackState == Player.STATE_ENDED) performSafeSeek(0L)
                    player.play()
                }
            }
        }
        val centerForward = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageDrawable(TransportIconDrawable(TransportIconDrawable.Type.FORWARD, AppleGlassTheme.labelPrimary))
            background = rippleGlassPill(AppleGlassTheme.glassDark, 26, AppleGlassTheme.glassBorderStrong)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            isClickable = true
            isFocusable = true
            contentDescription = "Forward 10 seconds"
            setOnClickListener {
                val end = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: Long.MAX_VALUE
                performSafeSeek((player.currentPosition + 10_000L).coerceAtMost(end))
            }
        }
        fullscreenCenterControls.addView(centerRewind, LinearLayout.LayoutParams(dp(52), dp(52)).apply {
            rightMargin = dp(26)
        })
        fullscreenCenterControls.addView(overlayPlayButton, LinearLayout.LayoutParams(dp(72), dp(72)))
        fullscreenCenterControls.addView(centerForward, LinearLayout.LayoutParams(dp(52), dp(52)).apply {
            leftMargin = dp(26)
        })
        videoFrame.addView(
            fullscreenCenterControls,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )

        // =========================================================================
        // 3. FULLSCREEN BOTTOM FLOATING iOS FROSTED GLASS DOCK
        // =========================================================================
        fullscreenBottomDock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = glassBox(AppleGlassTheme.glassDock, 28, AppleGlassTheme.glassBorderStrong)
            elevation = dp(10).toFloat()
            visibility = View.GONE
            applyFrostedBlurIfSupported(this)
        }

        overlaySeekBar = GlassScrubberBar(this).apply {
            max = 1000
            onStartScrub = { isScrubbing = true }
            onProgressScrub = { value ->
                val d = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: knownTotalDurationMs
                val previewMs = (value.toLong() * d) / 1000L
                fullscreenCurrentTime.text = formatTime(previewMs)
                showCenterHud(formatTime(previewMs))
            }
            onStopScrub = { value ->
                val d = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: knownTotalDurationMs
                if (d > 0L) {
                    performSafeSeek((value.toLong() * d) / 1000L)
                }
                isScrubbing = false
                mainHandler.removeCallbacks(hideCenterHudRunnable)
                mainHandler.postDelayed(hideCenterHudRunnable, 350L)
            }
        }

        val fullscreenSeekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fullscreenCurrentTime = appleText("00:00", 12f, AppleGlassTheme.labelPrimary, weight = 600, tabular = true).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
        }
        fullscreenDuration = appleText("00:00", 12f, AppleGlassTheme.labelSecondary, weight = 500, tabular = true).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        fullscreenSeekRow.addView(fullscreenCurrentTime, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply {
            rightMargin = dp(8)
        })
        fullscreenSeekRow.addView(overlaySeekBar, LinearLayout.LayoutParams(0, dp(32), 1f))
        fullscreenSeekRow.addView(fullscreenDuration, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32)).apply {
            leftMargin = dp(8)
        })
        fullscreenBottomDock.addView(
            fullscreenSeekRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32))
        )

        val bottomPillsScroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, dp(10), 0, 0)
        }
        val bottomPillsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        overlaySpeedButton = appleGlassPill("1.0x", SpatialIconDrawable.Type.SPEED) {
            showSpeedPicker(it)
        }
        overlayRecenterButton = appleGlassPill("Recenter", SpatialIconDrawable.Type.RECENTER) {
            if (currentProjection != VrVideoSurfaceView.Projection.FLAT) {
                vrSurfaceView.resetView()
                showCenterHud("Recentered", 650L)
            }
        }
        overlayMotionButton = appleGlassPill("Motion", SpatialIconDrawable.Type.MOTION) {
            setMotionTrackingEnabled(!isMotionEnabled)
        }
        overlayRotateButton = appleGlassPill("Landscape", SpatialIconDrawable.Type.ROTATE) {
            toggleFullscreenOrientation()
        }

        val textPills = listOf(overlaySpeedButton, overlayRecenterButton, overlayMotionButton, overlayRotateButton)
        textPills.forEachIndexed { idx, pill ->
            bottomPillsRow.addView(
                pill,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)).apply {
                    if (idx < textPills.lastIndex) rightMargin = dp(8)
                }
            )
        }
        bottomPillsScroller.addView(
            bottomPillsRow,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        fullscreenBottomDock.addView(
            bottomPillsScroller,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val dockParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ).apply {
            setMargins(dp(14), 0, dp(14), dp(18))
        }
        videoFrame.addView(fullscreenBottomDock, dockParams)

        // --- Empty State Glass Card ---
        placeholder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            background = glassBox(AppleGlassTheme.glassDark, 28, AppleGlassTheme.glassBorderStrong)
            isClickable = true
            isFocusable = true
            setOnClickListener { chooseVideo() }
        }
        val playCircle = FrameLayout(this).apply {
            background = solidPill(AppleGlassTheme.pureWhite, 30)
        }
        val playGlyph = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageDrawable(playVectorInk)
        }
        playCircle.addView(playGlyph, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))

        val placeholderTitle = appleText("Open a Spatial Video", 16.5f, AppleGlassTheme.labelPrimary, weight = 600).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, dp(5))
        }
        val placeholderSub = appleText("Tap to browse 180°, 360° or 2D videos", 12.5f, AppleGlassTheme.labelSecondary, weight = 400).apply {
            gravity = Gravity.CENTER
        }
        placeholder.addView(playCircle, LinearLayout.LayoutParams(dp(60), dp(60)))
        placeholder.addView(placeholderTitle)
        placeholder.addView(placeholderSub)

        val placeholderParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ).apply {
            setMargins(dp(28), 0, dp(28), 0)
        }
        videoFrame.addView(placeholder, placeholderParams)

        videoCardWrapper.addView(
            videoFrame,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        root.addView(videoCardWrapper, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // --- Clean Apple-Style Media Title ---
        titleView = appleText("No Video Selected", 14.5f, AppleGlassTheme.labelPrimary, weight = 600).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(4), dp(12), dp(4), dp(8))
        }
        root.addView(titleView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        fullscreenChrome.add(titleView)

        // --- iOS Segmented Control 1: Projection Mode ---
        val modeContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = glassBox(AppleGlassTheme.cardSurface, 18, AppleGlassTheme.glassBorder)
        }
        flatChip = modeChip("Flat 2D", VrVideoSurfaceView.Projection.FLAT)
        oneEightyChip = modeChip("180° VR", VrVideoSurfaceView.Projection.VR180)
        threeSixtyChip = modeChip("360° VR", VrVideoSurfaceView.Projection.VR360)
        modeContainer.addView(flatChip, LinearLayout.LayoutParams(0, dp(36), 1f).apply { rightMargin = dp(3) })
        modeContainer.addView(oneEightyChip, LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
        modeContainer.addView(threeSixtyChip, LinearLayout.LayoutParams(0, dp(36), 1f).apply { leftMargin = dp(3) })
        root.addView(modeContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(8)
        })
        fullscreenChrome.add(modeContainer)

        // --- iOS Segmented Control 2: Stereo Eye Layout ---
        val stereoContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = glassBox(AppleGlassTheme.cardSurface, 16, AppleGlassTheme.glassBorder)
        }
        monoChip = stereoChip("Mono", VrVideoSurfaceView.StereoLayout.MONO)
        leftEyeChip = stereoChip("SBS Left", VrVideoSurfaceView.StereoLayout.SBS_LEFT)
        rightEyeChip = stereoChip("SBS Right", VrVideoSurfaceView.StereoLayout.SBS_RIGHT)
        stereoContainer.addView(monoChip, LinearLayout.LayoutParams(0, dp(32), 1f).apply { rightMargin = dp(3) })
        stereoContainer.addView(leftEyeChip, LinearLayout.LayoutParams(0, dp(32), 1f).apply { leftMargin = dp(3); rightMargin = dp(3) })
        stereoContainer.addView(rightEyeChip, LinearLayout.LayoutParams(0, dp(32), 1f).apply { leftMargin = dp(3) })
        root.addView(stereoContainer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(10)
        })
        fullscreenChrome.add(stereoContainer)

        // --- iOS Frosted Playback Deck Card ---
        val playbackDeck = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            background = glassBox(AppleGlassTheme.cardSurface, 26, AppleGlassTheme.glassBorder)
        }

        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        currentTimeView = appleText("00:00", 11.5f, AppleGlassTheme.labelSecondary, weight = 500, tabular = true).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
        }
        durationView = appleText("00:00", 11.5f, AppleGlassTheme.labelSecondary, weight = 500, tabular = true).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        seekBar = GlassScrubberBar(this).apply {
            max = 1000
            onStartScrub = { isScrubbing = true }
            onProgressScrub = { progress ->
                val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: knownTotalDurationMs
                currentTimeView.text = formatTime((duration * progress) / 1000L)
            }
            onStopScrub = { progress ->
                val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: knownTotalDurationMs
                if (duration > 0L) {
                    performSafeSeek((progress / 1000f * duration).toLong())
                }
                isScrubbing = false
            }
        }
        seekRow.addView(currentTimeView, LinearLayout.LayoutParams(dp(42), dp(30)))
        seekRow.addView(seekBar, LinearLayout.LayoutParams(0, dp(30), 1f))
        seekRow.addView(durationView, LinearLayout.LayoutParams(dp(42), dp(30)))
        playbackDeck.addView(seekRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)))

        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        speedButton = appleText("1.0x", 12.5f, AppleGlassTheme.labelPrimary, weight = 600, tabular = true).apply {
            gravity = Gravity.CENTER
            background = rippleGlassPill(AppleGlassTheme.chipIdle, 16, AppleGlassTheme.glassBorder)
            isClickable = true
            isFocusable = true
            setOnClickListener { showSpeedPicker(this) }
        }
        val backButton = appleText("-10s", 12.5f, AppleGlassTheme.labelPrimary, weight = 600, tabular = true).apply {
            gravity = Gravity.CENTER
            background = rippleGlassPill(AppleGlassTheme.chipIdle, 16, AppleGlassTheme.glassBorder)
            isClickable = true
            isFocusable = true
            setOnClickListener { performSafeSeek((player.currentPosition - 10_000L).coerceAtLeast(0L)) }
        }
        playButton = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageDrawable(playVectorInk)
            background = rippleGlassPill(AppleGlassTheme.pureWhite, 25, AppleGlassTheme.pureWhite)
            setPadding(dp(13), dp(13), dp(13), dp(13))
            isClickable = true
            isFocusable = true
            contentDescription = "Play or pause"
            setOnClickListener {
                if (player.isPlaying) player.pause() else {
                    if (player.playbackState == Player.STATE_ENDED) performSafeSeek(0L)
                    player.play()
                }
            }
        }
        val forwardButton = appleText("+10s", 12.5f, AppleGlassTheme.labelPrimary, weight = 600, tabular = true).apply {
            gravity = Gravity.CENTER
            background = rippleGlassPill(AppleGlassTheme.chipIdle, 16, AppleGlassTheme.glassBorder)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val end = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: Long.MAX_VALUE
                performSafeSeek((player.currentPosition + 10_000L).coerceAtMost(end))
            }
        }
        val recenterMiniBtn = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageDrawable(SpatialIconDrawable(SpatialIconDrawable.Type.RECENTER, AppleGlassTheme.labelPrimary))
            background = rippleGlassPill(AppleGlassTheme.chipIdle, 16, AppleGlassTheme.glassBorder)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            isFocusable = true
            contentDescription = "Recenter VR View"
            setOnClickListener {
                if (currentProjection != VrVideoSurfaceView.Projection.FLAT) {
                    vrSurfaceView.resetView()
                    showCenterHud("Recentered", 650L)
                }
            }
        }

        transport.addView(speedButton, LinearLayout.LayoutParams(dp(54), dp(46)).apply { rightMargin = dp(8) })
        transport.addView(backButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(8) })
        transport.addView(playButton, LinearLayout.LayoutParams(dp(74), dp(50)).apply { rightMargin = dp(8) })
        transport.addView(forwardButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(8) })
        transport.addView(recenterMiniBtn, LinearLayout.LayoutParams(dp(46), dp(46)))

        playbackDeck.addView(transport, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(playbackDeck, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        fullscreenChrome.add(playbackDeck)

        val hint = appleText("Drag to Look  •  Pinch to Zoom  •  Left Swipe Brightness", 10.5f, AppleGlassTheme.labelTertiary, weight = 400).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        fullscreenChrome.add(hint)

        setContentView(root)
        root.setOnApplyWindowInsetsListener { view, insets ->
            val topInset: Int
            val bottomInset: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val systemBars = insets.getInsets(WindowInsets.Type.systemBars())
                topInset = systemBars.top
                bottomInset = systemBars.bottom
            } else {
                @Suppress("DEPRECATION")
                val top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                val bottom = insets.systemWindowInsetBottom
                topInset = top
                bottomInset = bottom
            }
            if (isFullscreen) {
                view.setPadding(0, 0, 0, 0)
            } else {
                view.setPadding(dp(16), dp(8) + topInset, dp(16), dp(10) + bottomInset)
            }
            insets
        }
        root.requestApplyInsets()
        updateModeChips()
    }

    private lateinit var modeBadge: TextView
    private lateinit var flatChip: TextView
    private lateinit var oneEightyChip: TextView
    private lateinit var threeSixtyChip: TextView
    private lateinit var monoChip: TextView
    private lateinit var leftEyeChip: TextView
    private lateinit var rightEyeChip: TextView

    private fun stereoChip(label: String, layout: VrVideoSurfaceView.StereoLayout): TextView =
        appleText(label, 11.5f, AppleGlassTheme.labelSecondary, weight = 600).apply {
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener {
                stereoLayout = layout
                vrSurfaceView.setStereoLayout(layout)
                updateModeChips()
                updateBadgeText()
            }
        }

    private fun modeChip(label: String, projection: VrVideoSurfaceView.Projection): TextView =
        appleText(label, 12.5f, AppleGlassTheme.labelSecondary, weight = 600).apply {
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setOnClickListener { setProjection(projection) }
        }

    private fun setProjection(projection: VrVideoSurfaceView.Projection) {
        currentProjection = projection
        vrSurfaceView.setProjection(projection)
        vrSurfaceView.setStereoLayout(stereoLayout)

        if (projection == VrVideoSurfaceView.Projection.FLAT) {
            vrSurfaceView.visibility = View.GONE
            playerView.visibility = View.VISIBLE
            playerView.player = player
        } else {
            playerView.player = null
            playerView.visibility = View.GONE
            vrSurfaceView.visibility = View.VISIBLE
            val activeSurface = vrOutputSurface ?: vrSurfaceView.getVideoSurface()
            if (activeSurface != null && activeSurface.isValid) {
                player.setVideoSurface(activeSurface)
            }
        }
        updateBadgeText()
        updateModeChips()
    }

    private fun updateBadgeText() {
        val projectionLabel = when (currentProjection) {
            VrVideoSurfaceView.Projection.FLAT -> "FLAT 2D"
            VrVideoSurfaceView.Projection.VR180 -> "180° VR"
            VrVideoSurfaceView.Projection.VR360 -> "360° VR"
        }
        modeBadge.text = if (currentProjection != VrVideoSurfaceView.Projection.FLAT) {
            val eyeLabel = when (stereoLayout) {
                VrVideoSurfaceView.StereoLayout.MONO -> "MONO"
                VrVideoSurfaceView.StereoLayout.SBS_LEFT -> "LEFT EYE"
                VrVideoSurfaceView.StereoLayout.SBS_RIGHT -> "RIGHT EYE"
            }
            "$projectionLabel · $eyeLabel"
        } else {
            projectionLabel
        }
    }

    private fun updateModeChips() {
        fun styleSegment(view: TextView, active: Boolean, radiusDp: Int) {
            view.background = if (active) {
                rippleGlassPill(AppleGlassTheme.pureWhite, radiusDp, AppleGlassTheme.pureWhite)
            } else {
                rippleGlassPill(Color.TRANSPARENT, radiusDp, Color.TRANSPARENT)
            }
            view.setTextColor(if (active) AppleGlassTheme.inkBlack else AppleGlassTheme.labelSecondary)
        }
        styleSegment(flatChip, currentProjection == VrVideoSurfaceView.Projection.FLAT, 14)
        styleSegment(oneEightyChip, currentProjection == VrVideoSurfaceView.Projection.VR180, 14)
        styleSegment(threeSixtyChip, currentProjection == VrVideoSurfaceView.Projection.VR360, 14)
        styleSegment(monoChip, stereoLayout == VrVideoSurfaceView.StereoLayout.MONO, 12)
        styleSegment(leftEyeChip, stereoLayout == VrVideoSurfaceView.StereoLayout.SBS_LEFT, 12)
        styleSegment(rightEyeChip, stereoLayout == VrVideoSurfaceView.StereoLayout.SBS_RIGHT, 12)
    }

    private fun chooseVideo() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_VIDEO)
    }

    @Deprecated("Uses the Storage Access Framework for broad Android version support")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        if (requestCode == REQUEST_METADATA) {
            inspectAndShowMetadata(uri)
            return
        }

        if (requestCode != REQUEST_VIDEO) return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        currentVideoName = queryDisplayName(uri).ifBlank { "Selected Video" }
        stereoLayout = VrVideoSurfaceView.StereoLayout.SBS_LEFT
        updateModeChips()
        setProjection(VrVideoSurfaceView.Projection.VR180)
        titleView.text = currentVideoName
        if (::fullscreenTitleView.isInitialized) fullscreenTitleView.text = currentVideoName
        placeholder.visibility = View.VISIBLE

        val activeSurface = vrOutputSurface ?: vrSurfaceView.getVideoSurface()
        if (activeSurface != null && activeSurface.isValid) {
            player.setVideoSurface(activeSurface)
            startPlayback(uri)
        } else {
            pendingMediaUri = uri
        }
    }

    private fun startPlayback(uri: Uri) {
        activePlaybackUri = uri
        knownTotalDurationMs = 0L
        lastRequestedSeekMs = -1L
        consecutiveErrorCount = 0
        lastRenderedFrames = 0
        lastFpsCheckTimeMs = 0L
        player.stop()
        player.clearMediaItems()
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.playWhenReady = true
    }

    private fun queryDisplayName(uri: Uri): String {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index).orEmpty() else ""
            } else ""
        } catch (_: Exception) {
            ""
        } finally {
            cursor?.close()
        }
    }

    private fun queryFileSize(uri: Uri): Long {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else 0L
            } else 0L
        } catch (_: Exception) {
            0L
        } finally {
            cursor?.close()
        }
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        fullscreenControlsVisible = true
        fullscreenChrome.forEach { it.visibility = if (isFullscreen) View.GONE else View.VISIBLE }
        fullscreenButton.visibility = if (isFullscreen) View.GONE else View.VISIBLE
        val params = videoCardWrapper.layoutParams as LinearLayout.LayoutParams
        if (isFullscreen) {
            params.topMargin = 0
            params.bottomMargin = 0
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = 0
            params.weight = 1f
            videoCardWrapper.background = solidPill(Color.BLACK, 0)
            videoCardWrapper.invalidateOutline()
            rootLayout.setPadding(0, 0, 0, 0)
            window.setBackgroundDrawableResource(android.R.color.black)
            overlayRotateButton.text = "Landscape"
            overlayPlayButton.setImageDrawable(if (player.isPlaying) overlayPauseVector else overlayPlayVector)
            (modeBadge.layoutParams as FrameLayout.LayoutParams).topMargin = dp(66)
            (fpsBadge.layoutParams as FrameLayout.LayoutParams).topMargin = dp(66)
            enterImmersiveMode()
        } else {
            if (isMotionEnabled) {
                setMotionTrackingEnabled(false)
            }
            if (isFullscreenLandscape) {
                isFullscreenLandscape = false
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            params.height = 0
            params.weight = 1f
            videoCardWrapper.background = glassBox(Color.BLACK, 26, AppleGlassTheme.glassBorder)
            videoCardWrapper.invalidateOutline()
            rootLayout.setPadding(
                normalRootPadding[0], normalRootPadding[1],
                normalRootPadding[2], normalRootPadding[3]
            )
            (modeBadge.layoutParams as FrameLayout.LayoutParams).topMargin = dp(14)
            (fpsBadge.layoutParams as FrameLayout.LayoutParams).topMargin = dp(14)
            exitImmersiveMode()
        }
        videoCardWrapper.layoutParams = params
        if (isFullscreen) {
            (videoFrame as TapDetectFrameLayout).apply {
                onVideoSingleTap = { setFullscreenControlsVisible(!fullscreenControlsVisible) }
                shouldIgnoreTap = { x, y ->
                    fun inside(view: View): Boolean = view.visibility == View.VISIBLE &&
                        x >= view.left && x <= view.right && y >= view.top && y <= view.bottom
                    inside(fullscreenTopBar) || inside(fullscreenCenterControls) || inside(fullscreenBottomDock)
                }
            }
            setFullscreenControlsVisible(true)
        } else {
            (videoFrame as TapDetectFrameLayout).apply {
                onVideoSingleTap = null
                shouldIgnoreTap = null
            }
            fullscreenControlsVisible = false
            fullscreenTopBar.visibility = View.GONE
            fullscreenCenterControls.visibility = View.GONE
            fullscreenBottomDock.visibility = View.GONE
            fullscreenButton.visibility = View.VISIBLE
            applyModeBadgeVisibility()
            applyFpsVisibility()
        }
    }

    private fun setFullscreenControlsVisible(visible: Boolean) {
        fullscreenControlsVisible = visible
        val visibility = if (visible) View.VISIBLE else View.GONE
        fullscreenTopBar.visibility = visibility
        fullscreenCenterControls.visibility = visibility
        fullscreenBottomDock.visibility = visibility
        if (showModeBadgeEnabled) {
            modeBadge.visibility = visibility
        } else {
            modeBadge.visibility = View.GONE
        }
        if (showFpsEnabled) {
            fpsBadge.visibility = visibility
        } else {
            fpsBadge.visibility = View.GONE
        }
    }

    @Suppress("DEPRECATION")
    private fun enterImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun exitImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.systemBars())
            window.setDecorFitsSystemWindows(true)
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    @Deprecated("Use OnBackInvokedDispatcher on newer Android versions")
    override fun onBackPressed() {
        if (isFullscreen) {
            toggleFullscreen()
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::vrSurfaceView.isInitialized) vrSurfaceView.onResume()
        if (isMotionEnabled && gyroSensor != null) {
            lastGyroTimestampNs = 0L
            sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        sensorManager.unregisterListener(this)
        if (::vrSurfaceView.isInitialized) vrSurfaceView.onPause()
        if (::player.isInitialized) player.pause()
        super.onPause()
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        mainHandler.removeCallbacks(progressUpdater)
        mainHandler.removeCallbacks(hideCenterHudRunnable)
        if (::playerView.isInitialized) playerView.player = null
        if (::vrSurfaceView.isInitialized) vrSurfaceView.release()
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    // --- Apple Glassmorphism & Typography Factories ---

    private fun applyFrostedBlurIfSupported(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                view.setRenderEffect(RenderEffect.createBlurEffect(24f, 24f, Shader.TileMode.CLAMP))
            } catch (_: Throwable) {
            }
        }
    }

    private fun appleIconCircleButton(
        iconType: SpatialIconDrawable.Type,
        desc: String,
        onClick: () -> Unit
    ): ImageView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setImageDrawable(SpatialIconDrawable(iconType, AppleGlassTheme.labelPrimary))
        background = rippleGlassPill(AppleGlassTheme.cardElevated, 20, AppleGlassTheme.glassBorder)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        isClickable = true
        isFocusable = true
        contentDescription = desc
        setOnClickListener { onClick() }
    }

    private fun appleGlassPill(
        label: String,
        iconType: SpatialIconDrawable.Type,
        onClick: (View) -> Unit
    ): TextView = appleText(label, 12f, AppleGlassTheme.labelPrimary, weight = 600).apply {
        gravity = Gravity.CENTER
        maxLines = 1
        isSingleLine = true
        setPadding(dp(14), dp(7), dp(16), dp(7))
        background = rippleGlassPill(AppleGlassTheme.glassPill, 20, AppleGlassTheme.glassBorder)
        isClickable = true
        isFocusable = true
        setPillLeftIcon(this, SpatialIconDrawable(iconType, AppleGlassTheme.labelPrimary), AppleGlassTheme.labelPrimary)
        setOnClickListener { onClick(this) }
    }

    private fun glassButton(label: String, highlighted: Boolean): TextView =
        appleText(
            label,
            13.5f,
            if (highlighted) AppleGlassTheme.inkBlack else AppleGlassTheme.labelPrimary,
            weight = 600
        ).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), 0, dp(18), 0)
            background = if (highlighted) {
                rippleGlassPill(AppleGlassTheme.pureWhite, 23, AppleGlassTheme.pureWhite)
            } else {
                rippleGlassPill(AppleGlassTheme.chipIdle, 23, AppleGlassTheme.glassBorder)
            }
            isClickable = true
            isFocusable = true
        }

    private fun appleText(
        label: String,
        sizeSp: Float,
        color: Int,
        weight: Int = 400,
        tabular: Boolean = false
    ): TextView = TextView(this).apply {
        text = label
        textSize = sizeSp
        setTextColor(color)
        typeface = when {
            weight >= 600 -> Typeface.create("sans-serif-medium", Typeface.BOLD)
            weight >= 500 -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        if (tabular) {
            fontFeatureSettings = "tnum"
        }
        includeFontPadding = false
    }

    private fun solidPill(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun glassBox(fillColor: Int, radiusDp: Int, borderColor: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fillColor)
        cornerRadius = dp(radiusDp).toFloat()
        if (borderColor != Color.TRANSPARENT) {
            setStroke(dp(1), borderColor)
        }
    }

    private fun rippleGlassPill(fillColor: Int, radiusDp: Int, borderColor: Int): RippleDrawable {
        val content = glassBox(fillColor, radiusDp, borderColor)
        val mask = solidPill(Color.WHITE, radiusDp)
        return RippleDrawable(ColorStateList.valueOf(AppleGlassTheme.rippleLight), content, mask)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = (milliseconds / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    companion object {
        private const val REQUEST_VIDEO = 4102
        private const val REQUEST_METADATA = 4103
        private const val PREFS_NAME = "panoplay_settings"
        private const val KEY_SHOW_FPS = "show_live_fps"
        private const val KEY_SHOW_MODE_BADGE = "show_mode_badge"
        private const val KEY_AUDIO_OFFSET_MS = "audio_offset_ms"
    }
}
