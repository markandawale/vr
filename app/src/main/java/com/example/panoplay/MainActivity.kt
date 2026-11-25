package com.example.panoplay

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.database.Cursor
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
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
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
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
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import java.util.ArrayList
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * Clean Material / Google Files style speaker vector drawable for the translucent Volume HUD.
 */
private class VolumeVectorDrawable(
    private val levelPercent: Int,
    private val density: Float
) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 1.9f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val speakerPath = Path()
    private val waveRect = RectF()

    override fun draw(canvas: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return

        val cy = h * 0.5f
        speakerPath.reset()
        speakerPath.moveTo(w * 0.14f, cy - h * 0.15f)
        speakerPath.lineTo(w * 0.30f, cy - h * 0.15f)
        speakerPath.lineTo(w * 0.48f, cy - h * 0.31f)
        speakerPath.lineTo(w * 0.48f, cy + h * 0.31f)
        speakerPath.lineTo(w * 0.30f, cy + h * 0.15f)
        speakerPath.lineTo(w * 0.14f, cy + h * 0.15f)
        speakerPath.close()
        canvas.drawPath(speakerPath, fillPaint)

        if (levelPercent <= 0) {
            val x1 = w * 0.60f
            val x2 = w * 0.84f
            val y1 = cy - h * 0.14f
            val y2 = cy + h * 0.14f
            canvas.drawLine(x1, y1, x2, y2, strokePaint)
            canvas.drawLine(x2, y1, x1, y2, strokePaint)
        } else {
            val r1 = w * 0.16f
            waveRect.set(w * 0.46f - r1, cy - r1, w * 0.46f + r1, cy + r1)
            canvas.drawArc(waveRect, -42f, 84f, false, strokePaint)

            if (levelPercent > 45) {
                val r2 = w * 0.31f
                waveRect.set(w * 0.46f - r2, cy - r2, w * 0.46f + r2, cy + r2)
                canvas.drawArc(waveRect, -45f, 90f, false, strokePaint)
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
 * Custom sleek cinema-style seek slider for fullscreen mode.
 */
private class CinemaSeekBar(context: Context) : View(context) {
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
        color = Color.argb(85, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val trackFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 79, 94)
        style = Paint.Style.FILL
    }
    private val thumbGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 255, 79, 94)
        style = Paint.Style.FILL
    }
    private val thumbCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val trackRect = RectF()
    private val fillRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val sidePad = 10f * density
        val usableWidth = (w - sidePad * 2f).coerceAtLeast(1f)
        val centerY = h * 0.5f

        val trackHeight = (if (isDragging) 6f else 3.5f) * density
        val halfTrack = trackHeight * 0.5f
        val radius = halfTrack

        val fraction = if (max > 0) (progress.toFloat() / max.toFloat()).coerceIn(0f, 1f) else 0f
        val thumbX = sidePad + usableWidth * fraction

        trackRect.set(sidePad, centerY - halfTrack, w - sidePad, centerY + halfTrack)
        canvas.drawRoundRect(trackRect, radius, radius, trackBgPaint)

        fillRect.set(sidePad, centerY - halfTrack, thumbX, centerY + halfTrack)
        canvas.drawRoundRect(fillRect, radius, radius, trackFillPaint)

        if (isDragging) {
            canvas.drawCircle(thumbX, centerY, 14f * density, thumbGlowPaint)
        }
        val thumbRadius = (if (isDragging) 8.5f else 6.5f) * density
        canvas.drawCircle(thumbX, centerY, thumbRadius, thumbCorePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val sidePad = 10f * density
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

    var onVolumeSwipeStart: (() -> Float)? = null
    var onVolumeSwipe: ((Float) -> Unit)? = null
    var onVolumeSwipeEnd: (() -> Unit)? = null

    private var startX = 0f
    private var startY = 0f
    private var downTimeMs = 0L
    private var movedBeyondSlop = false
    private var multiTouchOccurred = false
    private var activeGesture = GestureMode.NONE
    private var startLevel = 0.5f

    private enum class GestureMode { NONE, BRIGHTNESS, VOLUME }

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (shouldIgnoreTap?.invoke(e.x, e.y) != true) {
                    onVideoDoubleTap?.invoke(e.x, e.y)
                }
                return true
            }
        }
    )

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val slop = 14f * resources.displayMetrics.density
        val ignoredAtPoint = shouldIgnoreTap?.invoke(event.x, event.y) == true
        if (!ignoredAtPoint) {
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
                if (dx * dx + dy * dy > slop * slop) {
                    movedBeyondSlop = true
                }

                if (activeGesture == GestureMode.NONE &&
                    abs(dy) > slop &&
                    abs(dy) > abs(dx) * 1.35f &&
                    shouldIgnoreTap?.invoke(startX, startY) != true
                ) {
                    if (startX < width * 0.28f) {
                        activeGesture = GestureMode.BRIGHTNESS
                        startLevel = onBrightnessSwipeStart?.invoke() ?: 0.5f
                        cancelChildTouch(event)
                    } else if (startX > width * 0.90f) {
                        activeGesture = GestureMode.VOLUME
                        startLevel = onVolumeSwipeStart?.invoke() ?: 0.5f
                        cancelChildTouch(event)
                    }
                }

                if (activeGesture != GestureMode.NONE) {
                    val travelHeight = (height * 0.72f).coerceAtLeast(120f)
                    val delta = -dy / travelHeight
                    if (activeGesture == GestureMode.BRIGHTNESS) {
                        onBrightnessSwipe?.invoke((startLevel + delta).coerceIn(0.01f, 1.0f))
                    } else if (activeGesture == GestureMode.VOLUME) {
                        onVolumeSwipe?.invoke((startLevel + delta).coerceIn(0.0f, 1.0f))
                    }
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
        when (activeGesture) {
            GestureMode.BRIGHTNESS -> onBrightnessSwipeEnd?.invoke()
            GestureMode.VOLUME -> onVolumeSwipeEnd?.invoke()
            GestureMode.NONE -> {}
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
class MainActivity : Activity() {
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var vrSurfaceView: VrVideoSurfaceView
    private var vrOutputSurface: Surface? = null
    private var pendingMediaUri: Uri? = null
    private var activeMediaUri: Uri? = null
    private var ioRetryCount = 0

    private lateinit var videoFrame: FrameLayout
    private lateinit var placeholder: LinearLayout
    private lateinit var centerHud: TextView
    private lateinit var fpsBadge: TextView
    private lateinit var titleView: TextView
    private lateinit var playButton: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var currentTimeView: TextView
    private lateinit var durationView: TextView
    private lateinit var fullscreenButton: TextView

    // Redesigned Fullscreen Controls (Top bar + Center transport + Bottom slider & pills)
    private lateinit var fullscreenTopBar: LinearLayout
    private lateinit var fullscreenCenterControls: LinearLayout
    private lateinit var fullscreenBottomControls: LinearLayout
    private lateinit var overlayPlayButton: TextView
    private lateinit var overlaySeekBar: CinemaSeekBar
    private lateinit var fullscreenTitleView: TextView
    private lateinit var fullscreenCurrentTime: TextView
    private lateinit var fullscreenDuration: TextView
    private lateinit var rootLayout: LinearLayout
    private lateinit var speedButton: TextView
    private lateinit var overlaySpeedButton: TextView
    private lateinit var overlayRotateButton: TextView

    private val fullscreenChrome = mutableListOf<View>()
    private var isFullscreen = false
    private var fullscreenControlsVisible = true
    private var normalRootPadding = intArrayOf(0, 0, 0, 0)
    private var normalVideoMargins = intArrayOf(0, 0)
    private var currentBrightness = -1f
    private var lastRenderedFrames = 0
    private var lastFpsCheckTimeMs = 0L
    private var showFpsEnabled = true
    private var showModeBadgeEnabled = true

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

    private val audioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val progressUpdater = object : Runnable {
        override fun run() {
            if (::player.isInitialized) {
                val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
                val position = player.currentPosition.coerceAtLeast(0L)
                val progressPermille = if (duration > 0L) ((position * 1000L) / duration).toInt() else 0
                if (!isScrubbing) {
                    seekBar.max = 1000
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
        showFpsEnabled = prefs.getBoolean(KEY_SHOW_FPS, true)
        showModeBadgeEnabled = prefs.getBoolean(KEY_SHOW_MODE_BADGE, true)
        audioOffsetMs = prefs.getInt(KEY_AUDIO_OFFSET_MS, 0).coerceIn(-1000, 1000)

        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.rgb(9, 10, 13)
        window.navigationBarColor = Color.rgb(9, 10, 13)
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

        val dataSourceFactory = DefaultDataSource.Factory(this)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .build()

        buildInterface()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playButton.text = if (isPlaying) "Ⅱ" else "▶"
                if (::overlayPlayButton.isInitialized) overlayPlayButton.text = if (isPlaying) "Ⅱ" else "▶"
                if (!isPlaying && ::fpsBadge.isInitialized) {
                    fpsBadge.text = "-- FPS"
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    ioRetryCount = 0
                    placeholder.visibility = View.GONE
                    val resolvedName = currentVideoName.ifBlank { "Now playing" }
                    titleView.text = resolvedName
                    if (::fullscreenTitleView.isInitialized) fullscreenTitleView.text = resolvedName
                }
                if (playbackState == Player.STATE_ENDED) {
                    playButton.text = "▶"
                    if (::overlayPlayButton.isInitialized) overlayPlayButton.text = "▶"
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
                val uri = activeMediaUri
                if (uri != null &&
                    ioRetryCount < 2 &&
                    (error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
                ) {
                    ioRetryCount++
                    val resumePosition = player.currentPosition.coerceAtLeast(0L)
                    mainHandler.postDelayed({
                        if (::player.isInitialized && activeMediaUri == uri) {
                            rebindActiveSurface()
                            player.stop()
                            player.clearMediaItems()
                            player.setMediaItem(MediaItem.fromUri(uri))
                            if (resumePosition > 0L) {
                                player.seekTo(resumePosition)
                            }
                            player.prepare()
                            player.playWhenReady = true
                        }
                    }, 180L)
                    return
                }

                Toast.makeText(
                    this@MainActivity,
                    "Can't play this video: ${error.errorCodeName}",
                    Toast.LENGTH_LONG
                ).show()
                placeholder.visibility = View.VISIBLE
            }
        })
        mainHandler.post(progressUpdater)
    }

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
            centerHud.compoundDrawablePadding = dp(7)
            centerHud.setCompoundDrawables(leftDrawable, null, null, null)
        } else {
            centerHud.setCompoundDrawables(null, null, null, null)
        }
        centerHud.text = message
        centerHud.visibility = View.VISIBLE
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
        currentBrightness = clamped
        val lp = window.attributes
        lp.screenBrightness = clamped
        window.attributes = lp

        val percent = (clamped * 100f).toInt().coerceIn(1, 100)
        showCenterHud("☀  $percent%")
    }

    private fun readCurrentVolumeNormalized(): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return (cur.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    }

    private fun applyVolumeNormalized(value: Float) {
        val clamped = value.coerceIn(0f, 1f)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val targetIndex = (clamped * max + 0.5f).toInt().coerceIn(0, max)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetIndex, 0)
        val percent = ((targetIndex.toFloat() / max.toFloat()) * 100f).toInt().coerceIn(0, 100)
        val speakerDrawable = VolumeVectorDrawable(percent, resources.displayMetrics.density)
        showCenterHud("$percent%", leftDrawable = speakerDrawable)
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
            showCenterHud("⚡  $label", 800L)
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
            overlayRotateButton.text = if (isFullscreenLandscape) "↻  Portrait" else "↻  Landscape"
        }
        val modeText = if (isFullscreenLandscape) "↻  LANDSCAPE" else "↻  PORTRAIT"
        showCenterHud(modeText, 850L)
    }

    private fun handleDoubleTap(x: Float) {
        if (!isFullscreen) return
        val w = videoFrame.width.coerceAtLeast(1)
        if (x < w * 0.28f) {
            player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
            showCenterHud("↶  10s", 750L)
        } else if (x > w * 0.72f) {
            val end = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: Long.MAX_VALUE
            player.seekTo((player.currentPosition + 10_000L).coerceAtMost(end))
            showCenterHud("10s  ↷", 750L)
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
            setPadding(dp(22), dp(18), dp(22), dp(16))
            background = rounded(Color.rgb(20, 23, 29), 18)
        }

        val dialogTitle = text("PLAYER SETTINGS", 14f, Color.WHITE, true).apply {
            letterSpacing = 0.06f
            setPadding(0, 0, 0, dp(14))
        }
        container.addView(dialogTitle)

        // 1. Show Live FPS Toggle
        val fpsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.rgb(28, 31, 38), 14)
        }
        val fpsTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val fpsLabel = text("Show Live FPS", 13f, Color.WHITE, true)
        val fpsSub = text("Display real-time frame rate on top-right", 10f, Color.rgb(152, 158, 170), false).apply {
            setPadding(0, dp(3), 0, 0)
        }
        fpsTextCol.addView(fpsLabel)
        fpsTextCol.addView(fpsSub)
        fpsRow.addView(fpsTextCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val fpsSwitch = Switch(this).apply {
            isChecked = showFpsEnabled
            setOnCheckedChangeListener { _, isChecked ->
                showFpsEnabled = isChecked
                prefs.edit().putBoolean(KEY_SHOW_FPS, isChecked).apply()
                applyFpsVisibility()
            }
        }
        fpsRow.addView(fpsSwitch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        container.addView(fpsRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // 2. Show View Mode Badge Toggle
        val modeBadgeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.rgb(28, 31, 38), 14)
        }
        val modeBadgeTextCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val modeBadgeLabel = text("Show View Mode Badge", 13f, Color.WHITE, true)
        val modeBadgeSub = text("Display 180° / 360° & Eye label on top-left", 10f, Color.rgb(152, 158, 170), false).apply {
            setPadding(0, dp(3), 0, 0)
        }
        modeBadgeTextCol.addView(modeBadgeLabel)
        modeBadgeTextCol.addView(modeBadgeSub)
        modeBadgeRow.addView(modeBadgeTextCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val modeBadgeSwitch = Switch(this).apply {
            isChecked = showModeBadgeEnabled
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

        // 3. Free-Range 1ms Precision Audio Sync Offset (-1000ms to +1000ms)
        val audioCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.rgb(28, 31, 38), 14)
        }
        val audioHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val audioTitle = text("Audio Sync Offset", 13f, Color.WHITE, true)
        val audioValueText = text(formatOffsetLabel(audioOffsetMs), 12f, Color.rgb(255, 79, 94), true).apply {
            gravity = Gravity.END
        }
        audioHeader.addView(audioTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        audioHeader.addView(audioValueText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        audioCard.addView(audioHeader)

        val audioHint = text("Use +ms if audio plays before video  •  -ms if audio is late", 10f, Color.rgb(152, 158, 170), false).apply {
            setPadding(0, dp(3), 0, dp(8))
        }
        audioCard.addView(audioHint)

        val audioSeekBar = SeekBar(this).apply {
            max = 2000
            progress = (audioOffsetMs + 1000).coerceIn(0, 2000)
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(255, 79, 94))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
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
                        player.seekTo(player.currentPosition)
                    }
                }
            })
        }
        audioCard.addView(audioSeekBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(30)))

        fun applyExactOffset(targetMs: Int) {
            val clamped = targetMs.coerceIn(-1000, 1000)
            audioOffsetMs = clamped
            audioSeekBar.progress = clamped + 1000
            audioValueText.text = formatOffsetLabel(clamped)
            prefs.edit().putInt(KEY_AUDIO_OFFSET_MS, clamped).apply()
            if (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING) {
                player.seekTo(player.currentPosition)
            }
        }

        fun stepChip(label: String, deltaMs: Int): TextView = text(label, 10f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = rounded(Color.rgb(42, 46, 56), 9)
            setOnClickListener { applyExactOffset(audioOffsetMs + deltaMs) }
        }

        val fineTuneRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val minus5 = stepChip("-5ms", -5)
        val minus1 = stepChip("-1ms", -1)
        val resetBtn = text("RESET", 10f, Color.rgb(255, 79, 94), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(Color.rgb(42, 46, 56), 9)
            setOnClickListener { applyExactOffset(0) }
        }
        val plus1 = stepChip("+1ms", 1)
        val plus5 = stepChip("+5ms", 5)

        val chips = listOf(minus5, minus1, resetBtn, plus1, plus5)
        chips.forEachIndexed { idx, chip ->
            fineTuneRow.addView(
                chip,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (idx > 0) leftMargin = dp(4)
                }
            )
        }
        audioCard.addView(
            fineTuneRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(6)
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

        val closeBtn = pillButton("DONE", true).apply {
            setOnClickListener { dialog.dismiss() }
        }
        container.addView(
            closeBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply {
                topMargin = dp(16)
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
            setPadding(dp(20), dp(18), dp(20), dp(14))
            background = rounded(Color.rgb(20, 23, 29), 18)
        }

        val headerTitle = text("VIDEO METADATA", 14f, Color.WHITE, true).apply {
            letterSpacing = 0.06f
        }
        val fileLabel = text(fileName, 11f, Color.rgb(255, 79, 94), true).apply {
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            setPadding(0, dp(4), 0, dp(12))
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
                val sectionHeader = text(key, 10f, Color.rgb(120, 235, 165), true).apply {
                    letterSpacing = 0.08f
                    setPadding(dp(4), dp(10), 0, dp(4))
                }
                listContainer.addView(sectionHeader)
            } else {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    background = rounded(Color.rgb(28, 31, 38), 10)
                }
                val kView = text(key, 11f, Color.rgb(162, 168, 180), false)
                val vView = text(value, 11f, Color.WHITE, true).apply {
                    gravity = Gravity.END
                }
                row.addView(kView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.44f))
                row.addView(vView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.56f))
                listContainer.addView(
                    row,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(4)
                    }
                )
            }
        }

        scrollView.addView(listContainer)
        val maxScrollHeight = (resources.displayMetrics.heightPixels * 0.52f).toInt()
        outer.addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxScrollHeight))

        val dialog = AlertDialog.Builder(this)
            .setView(outer)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val closeBtn = pillButton("CLOSE", true).apply {
            setOnClickListener { dialog.dismiss() }
        }
        outer.addView(
            closeBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply {
                topMargin = dp(14)
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
            setBackgroundColor(Color.rgb(9, 10, 13))
            setPadding(dp(18), dp(8), dp(18), dp(10))
        }
        rootLayout = root
        normalRootPadding = intArrayOf(dp(18), dp(8), dp(18), dp(10))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brandColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val brand = text("PANO PLAY", 18f, Color.WHITE, true).apply {
            letterSpacing = 0.08f
        }
        val subtitle = text("YOUR VIDEOS. YOUR VIEW.", 9f, Color.rgb(142, 147, 157), true).apply {
            letterSpacing = 0.12f
            setPadding(0, dp(3), 0, 0)
        }
        brandColumn.addView(brand)
        brandColumn.addView(subtitle)
        header.addView(brandColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val metadataButton = text("ℹ", 16f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(31, 34, 41), 30)
            isClickable = true
            isFocusable = true
            contentDescription = "Inspect video metadata"
            setOnClickListener { chooseVideoForMetadata() }
        }
        header.addView(
            metadataButton,
            LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                rightMargin = dp(6)
            }
        )

        val settingsButton = text("⚙", 16f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(31, 34, 41), 30)
            isClickable = true
            isFocusable = true
            contentDescription = "Settings"
            setOnClickListener { openSettingsDialog() }
        }
        header.addView(
            settingsButton,
            LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                rightMargin = dp(6)
            }
        )

        val openButton = pillButton("＋  OPEN", true).apply {
            setOnClickListener { chooseVideo() }
        }
        header.addView(openButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)))
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        fullscreenChrome.add(header)

        videoFrame = TapDetectFrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            clipToOutline = false
            onVideoDoubleTap = { x, _ -> handleDoubleTap(x) }
            onBrightnessSwipeStart = { readInitialBrightness() }
            onBrightnessSwipe = { level -> applyBrightness(level) }
            onBrightnessSwipeEnd = {
                mainHandler.removeCallbacks(hideCenterHudRunnable)
                mainHandler.postDelayed(hideCenterHudRunnable, 800L)
            }
            onVolumeSwipeStart = { readCurrentVolumeNormalized() }
            onVolumeSwipe = { level -> applyVolumeNormalized(level) }
            onVolumeSwipeEnd = {
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

        // Translucent HUD for Brightness, Volume, Speed, Rotation & Seek feedback
        centerHud = text("☀  50%", 14f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = rounded(Color.argb(115, 15, 17, 22), 24)
            alpha = 0.88f
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

        val viewBadge = text("180° VIEW · LEFT EYE", 10f, Color.WHITE, true).apply {
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = rounded(Color.argb(190, 15, 17, 22), 30)
            elevation = dp(4).toFloat()
            visibility = if (showModeBadgeEnabled) View.VISIBLE else View.GONE
        }
        val badgeParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ).apply {
            setMargins(dp(12), dp(12), 0, 0)
        }
        videoFrame.addView(viewBadge, badgeParams)
        modeBadge = viewBadge

        fpsBadge = text("-- FPS", 10f, Color.rgb(120, 235, 165), true).apply {
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = rounded(Color.argb(190, 15, 17, 22), 30)
            elevation = dp(4).toFloat()
            visibility = if (showFpsEnabled) View.VISIBLE else View.GONE
        }
        val fpsBadgeParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply {
            setMargins(0, dp(12), dp(12), 0)
        }
        videoFrame.addView(fpsBadge, fpsBadgeParams)

        fullscreenButton = text("⛶", 22f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(Color.argb(205, 15, 17, 22), 30)
            elevation = dp(5).toFloat()
            setPadding(dp(10), dp(4), dp(10), dp(4))
            setOnClickListener { toggleFullscreen() }
            contentDescription = "Enter full screen"
        }
        val fullscreenParams = FrameLayout.LayoutParams(dp(46), dp(42), Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(12), dp(12))
        }
        videoFrame.addView(fullscreenButton, fullscreenParams)

        // 1. Fullscreen Top Bar (Back arrow + Video Title)
        fullscreenTopBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(175, 8, 10, 14), Color.TRANSPARENT)
            )
            elevation = dp(4).toFloat()
            visibility = View.GONE
        }
        val exitFullscreenBtn = text("←", 20f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = "Exit full screen"
            setOnClickListener { if (isFullscreen) toggleFullscreen() }
        }
        fullscreenTitleView = text("Now playing", 14f, Color.WHITE, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        fullscreenTopBar.addView(exitFullscreenBtn, LinearLayout.LayoutParams(dp(38), dp(36)).apply {
            rightMargin = dp(8)
        })
        fullscreenTopBar.addView(fullscreenTitleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        videoFrame.addView(
            fullscreenTopBar,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        )

        // 2. Fullscreen Center Transport Row (Rewind ◀◀  |  Large Circular Play/Pause  |  Forward ▶▶)
        fullscreenCenterControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            elevation = dp(6).toFloat()
            visibility = View.GONE
        }
        val centerRewind = text("◀◀", 18f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(Color.argb(105, 15, 17, 22), 26)
            isClickable = true
            isFocusable = true
            contentDescription = "Rewind 10 seconds"
            setOnClickListener { player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L)) }
        }
        overlayPlayButton = text("▶", 24f, Color.rgb(14, 16, 22), true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(122, 146, 255))
            }
            isClickable = true
            isFocusable = true
            contentDescription = "Play or pause"
            setOnClickListener {
                if (player.isPlaying) player.pause() else {
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
                    player.play()
                }
            }
        }
        val centerForward = text("▶▶", 18f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(Color.argb(105, 15, 17, 22), 26)
            isClickable = true
            isFocusable = true
            contentDescription = "Forward 10 seconds"
            setOnClickListener {
                val end = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: Long.MAX_VALUE
                player.seekTo((player.currentPosition + 10_000L).coerceAtMost(end))
            }
        }
        fullscreenCenterControls.addView(centerRewind, LinearLayout.LayoutParams(dp(52), dp(52)).apply {
            rightMargin = dp(26)
        })
        fullscreenCenterControls.addView(overlayPlayButton, LinearLayout.LayoutParams(dp(68), dp(68)))
        fullscreenCenterControls.addView(centerForward, LinearLayout.LayoutParams(dp(52), dp(52)).apply {
            leftMargin = dp(26)
        })
        videoFrame.addView(
            fullscreenCenterControls,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )

        // 3. Fullscreen Bottom Dock (Slider row on top, Speed & Landscape pills below slider)
        fullscreenBottomControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(14), dp(16), dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Color.argb(190, 8, 10, 14), Color.TRANSPARENT)
            )
            elevation = dp(4).toFloat()
            visibility = View.GONE
        }

        overlaySeekBar = CinemaSeekBar(this).apply {
            max = 1000
            onStartScrub = {
                isScrubbing = true
            }
            onProgressScrub = { value ->
                val d = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
                val previewMs = (value.toLong() * d) / 1000L
                fullscreenCurrentTime.text = formatTime(previewMs)
                showCenterHud(formatTime(previewMs))
            }
            onStopScrub = { value ->
                val d = player.duration
                if (d != C.TIME_UNSET && d > 0L) {
                    player.seekTo((value.toLong() * d) / 1000L)
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
        fullscreenCurrentTime = text("00:00", 11.5f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            includeFontPadding = false
        }
        fullscreenDuration = text("00:00", 11.5f, Color.rgb(210, 214, 224), true).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            includeFontPadding = false
        }
        fullscreenSeekRow.addView(fullscreenCurrentTime, LinearLayout.LayoutParams(dp(46), dp(34)))
        fullscreenSeekRow.addView(overlaySeekBar, LinearLayout.LayoutParams(0, dp(34), 1f))
        fullscreenSeekRow.addView(fullscreenDuration, LinearLayout.LayoutParams(dp(46), dp(34)))
        fullscreenBottomControls.addView(
            fullscreenSeekRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34))
        )

        // Bottom pills row below slider: Speed & Landscape
        val bottomPillsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        overlaySpeedButton = text("⚡  1.0x", 12f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(9), dp(18), dp(9))
            background = rounded(Color.argb(145, 28, 32, 42), 22)
            isClickable = true
            isFocusable = true
            setOnClickListener { showSpeedPicker(this) }
        }
        overlayRotateButton = text("↻  Landscape", 12f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(9), dp(18), dp(9))
            background = rounded(Color.argb(145, 28, 32, 42), 22)
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleFullscreenOrientation() }
        }
        bottomPillsRow.addView(overlaySpeedButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            rightMargin = dp(12)
        })
        bottomPillsRow.addView(overlayRotateButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        fullscreenBottomControls.addView(
            bottomPillsRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        videoFrame.addView(
            fullscreenBottomControls,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        )

        placeholder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(24), dp(28), dp(24))
            background = rounded(Color.argb(205, 10, 12, 16), 18)
        }
        val playGlyph = text("▶", 30f, Color.rgb(255, 83, 98), true).apply {
            gravity = Gravity.CENTER
        }
        val placeholderTitle = text("CHOOSE A VIDEO", 16f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(6))
        }
        val placeholderSub = text("Open a file to start watching on your phone", 12f, Color.rgb(174, 179, 188), false).apply {
            gravity = Gravity.CENTER
        }
        placeholder.addView(playGlyph)
        placeholder.addView(placeholderTitle)
        placeholder.addView(placeholderSub)
        placeholder.setOnClickListener { chooseVideo() }
        val placeholderParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ).apply {
            setMargins(dp(32), 0, dp(32), 0)
        }
        videoFrame.addView(placeholder, placeholderParams)

        root.addView(videoFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        titleView = text("No video selected", 13f, Color.WHITE, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        root.addView(titleView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)))
        fullscreenChrome.add(titleView)

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, dp(8))
        }
        flatChip = modeChip("FLAT 2D", VrVideoSurfaceView.Projection.FLAT)
        oneEightyChip = modeChip("180°", VrVideoSurfaceView.Projection.VR180)
        threeSixtyChip = modeChip("360°", VrVideoSurfaceView.Projection.VR360)
        modeRow.addView(flatChip, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(5) })
        modeRow.addView(oneEightyChip, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(5); rightMargin = dp(5) })
        modeRow.addView(threeSixtyChip, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(5) })
        root.addView(modeRow)
        fullscreenChrome.add(modeRow)

        val stereoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        }
        monoChip = stereoChip("MONO", VrVideoSurfaceView.StereoLayout.MONO)
        leftEyeChip = stereoChip("SBS LEFT", VrVideoSurfaceView.StereoLayout.SBS_LEFT)
        rightEyeChip = stereoChip("SBS RIGHT", VrVideoSurfaceView.StereoLayout.SBS_RIGHT)
        stereoRow.addView(monoChip, LinearLayout.LayoutParams(0, dp(34), 1f).apply { rightMargin = dp(4) })
        stereoRow.addView(leftEyeChip, LinearLayout.LayoutParams(0, dp(34), 1f).apply { leftMargin = dp(4); rightMargin = dp(4) })
        stereoRow.addView(rightEyeChip, LinearLayout.LayoutParams(0, dp(34), 1f).apply { leftMargin = dp(4) })
        root.addView(stereoRow)
        fullscreenChrome.add(stereoRow)

        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        currentTimeView = text("00:00", 10f, Color.rgb(184, 189, 198), false)
        durationView = text("00:00", 10f, Color.rgb(184, 189, 198), false)
        seekBar = SeekBar(this).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(255, 79, 94))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(73, 77, 85))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar?) { isScrubbing = true }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val duration = player.duration
                    if (duration != C.TIME_UNSET && duration > 0L) {
                        player.seekTo((this@MainActivity.seekBar.progress / 1000f * duration).toLong())
                    }
                    isScrubbing = false
                }
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
                        currentTimeView.text = formatTime((duration * progress) / 1000L)
                    }
                }
            })
        }
        currentTimeView.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        currentTimeView.includeFontPadding = false
        durationView.gravity = Gravity.CENTER_VERTICAL or Gravity.END
        durationView.includeFontPadding = false
        seekRow.addView(currentTimeView, LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.MATCH_PARENT))
        seekRow.addView(seekBar, LinearLayout.LayoutParams(0, dp(30), 1f))
        seekRow.addView(durationView, LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(seekRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)))
        fullscreenChrome.add(seekRow)

        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(5))
        }
        speedButton = transportButton("1.0x").apply {
            setOnClickListener { showSpeedPicker(this) }
        }
        val backButton = transportButton("↶ 10").apply {
            setOnClickListener { player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L)) }
        }
        playButton = transportButton("▶", true).apply {
            textSize = 20f
            setOnClickListener {
                if (player.isPlaying) player.pause() else {
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
                    player.play()
                }
            }
        }
        val forwardButton = transportButton("10 ↷").apply {
            setOnClickListener {
                val end = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: Long.MAX_VALUE
                player.seekTo((player.currentPosition + 10_000L).coerceAtMost(end))
            }
        }

        transport.addView(speedButton, LinearLayout.LayoutParams(dp(50), dp(46)).apply {
            leftMargin = dp(4); rightMargin = dp(4)
        })
        transport.addView(backButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            leftMargin = dp(4); rightMargin = dp(4)
        })
        transport.addView(playButton, LinearLayout.LayoutParams(dp(62), dp(46)).apply {
            leftMargin = dp(4); rightMargin = dp(4)
        })
        transport.addView(forwardButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            leftMargin = dp(4); rightMargin = dp(4)
        })
        root.addView(transport)
        fullscreenChrome.add(transport)

        val hint = text("DRAG TO LOOK AROUND  •  PINCH TO ZOOM  •  L BRIGHTNESS  •  R VOLUME", 9f, Color.rgb(119, 125, 136), true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.035f
            setPadding(0, dp(6), 0, dp(2))
        }
        root.addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)))
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
                view.setPadding(dp(18), dp(8) + topInset, dp(18), dp(10) + bottomInset)
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
        text(label, 10f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener {
                stereoLayout = layout
                vrSurfaceView.setStereoLayout(layout)
                updateModeChips()
                updateBadgeText()
            }
        }

    private fun modeChip(label: String, projection: VrVideoSurfaceView.Projection): TextView =
        text(label, 12f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
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
            VrVideoSurfaceView.Projection.VR180 -> "180° VIEW"
            VrVideoSurfaceView.Projection.VR360 -> "360° VIEW"
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
        fun style(view: TextView, active: Boolean) {
            view.background = rounded(if (active) Color.rgb(255, 79, 94) else Color.rgb(28, 31, 38), 12)
            view.setTextColor(if (active) Color.WHITE else Color.rgb(188, 193, 202))
        }
        style(flatChip, currentProjection == VrVideoSurfaceView.Projection.FLAT)
        style(oneEightyChip, currentProjection == VrVideoSurfaceView.Projection.VR180)
        style(threeSixtyChip, currentProjection == VrVideoSurfaceView.Projection.VR360)
        style(monoChip, stereoLayout == VrVideoSurfaceView.StereoLayout.MONO)
        style(leftEyeChip, stereoLayout == VrVideoSurfaceView.StereoLayout.SBS_LEFT)
        style(rightEyeChip, stereoLayout == VrVideoSurfaceView.StereoLayout.SBS_RIGHT)
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
        currentVideoName = queryDisplayName(uri).ifBlank { "Selected video" }
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
        activeMediaUri = uri
        ioRetryCount = 0
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
        val params = videoFrame.layoutParams as LinearLayout.LayoutParams
        if (isFullscreen) {
            normalVideoMargins[0] = params.topMargin
            normalVideoMargins[1] = params.bottomMargin
            params.topMargin = 0
            params.bottomMargin = 0
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = 0
            videoFrame.setBackgroundColor(Color.BLACK)
            videoFrame.clipToOutline = false
            params.weight = 1f
            rootLayout.setPadding(0, 0, 0, 0)
            rootLayout.clipToPadding = false
            rootLayout.clipChildren = false
            window.setBackgroundDrawableResource(android.R.color.black)
            overlayRotateButton.text = "↻  Landscape"
            // Shift top badges slightly below fullscreenTopBar
            (modeBadge.layoutParams as FrameLayout.LayoutParams).topMargin = dp(56)
            (fpsBadge.layoutParams as FrameLayout.LayoutParams).topMargin = dp(56)
            enterImmersiveMode()
        } else {
            if (isFullscreenLandscape) {
                isFullscreenLandscape = false
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
            params.topMargin = normalVideoMargins[0]
            params.bottomMargin = normalVideoMargins[1]
            params.height = 0
            params.weight = 1f
            rootLayout.setPadding(
                normalRootPadding[0], normalRootPadding[1],
                normalRootPadding[2], normalRootPadding[3]
            )
            (modeBadge.layoutParams as FrameLayout.LayoutParams).topMargin = dp(12)
            (fpsBadge.layoutParams as FrameLayout.LayoutParams).topMargin = dp(12)
            fullscreenButton.contentDescription = "Enter full screen"
            exitImmersiveMode()
        }
        videoFrame.layoutParams = params
        if (isFullscreen) {
            (videoFrame as TapDetectFrameLayout).apply {
                onVideoSingleTap = { setFullscreenControlsVisible(!fullscreenControlsVisible) }
                shouldIgnoreTap = { x, y ->
                    fun inside(view: View): Boolean = view.visibility == View.VISIBLE &&
                        x >= view.left && x <= view.right && y >= view.top && y <= view.bottom
                    inside(fullscreenTopBar) || inside(fullscreenCenterControls) || inside(fullscreenBottomControls)
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
            fullscreenBottomControls.visibility = View.GONE
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
        fullscreenBottomControls.visibility = visibility
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
    }

    override fun onPause() {
        if (::vrSurfaceView.isInitialized) vrSurfaceView.onPause()
        if (::player.isInitialized) player.pause()
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(progressUpdater)
        mainHandler.removeCallbacks(hideCenterHudRunnable)
        if (::playerView.isInitialized) playerView.player = null
        if (::vrSurfaceView.isInitialized) vrSurfaceView.release()
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    private fun transportButton(label: String, primary: Boolean = false): TextView =
        text(label, if (primary) 16f else 11f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = rounded(if (primary) Color.rgb(255, 79, 94) else Color.rgb(28, 31, 38), 13)
            isClickable = true
            isFocusable = true
        }

    private fun pillButton(label: String, accent: Boolean): TextView =
        text(label, 11f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            background = rounded(if (accent) Color.rgb(255, 79, 94) else Color.rgb(31, 34, 41), 30)
            isClickable = true
            isFocusable = true
        }

    private fun text(label: String, sizeSp: Float, color: Int, bold: Boolean): TextView = TextView(this).apply {
        text = label
        textSize = sizeSp
        setTextColor(color)
        typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        includeFontPadding = false
    }

    private fun rounded(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
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
