package com.example.panoplay

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
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
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan

/**
 * Resolves exact byte length from ContentResolver when ContentDataSource returns C.LENGTH_UNSET (-1),
 * preventing Mp4Extractor from passing regionLength = -1 at random points during playback/seeking.
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

    private lateinit var rootFrame: FrameLayout
    private lateinit var overlayLayer: FrameLayout
    private lateinit var videoFrame: FrameLayout
    private lateinit var stageMask: StageFrameView
    private lateinit var placeholder: LinearLayout
    private lateinit var placeholderTitle: TextView
    private lateinit var placeholderSub: TextView
    private lateinit var placeholderButton: TextView
    private lateinit var centerHud: TextView
    private lateinit var fpsBadge: TextView
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var playButton: ImageView
    private lateinit var seekBar: CinemaSeekBar
    private lateinit var currentTimeView: TextView
    private lateinit var durationView: TextView
    private lateinit var fullscreenButton: ImageView
    private lateinit var modeControl: SegmentedControl
    private lateinit var stereoControl: SegmentedControl
    private lateinit var hintView: TextView

    // Fullscreen Controls
    private lateinit var fullscreenTopBar: LinearLayout
    private lateinit var fullscreenCenterControls: LinearLayout
    private lateinit var fullscreenBottomControls: LinearLayout
    private lateinit var overlayPlayButton: ImageView
    private lateinit var overlaySeekBar: CinemaSeekBar
    private lateinit var fullscreenTitleView: TextView
    private lateinit var fullscreenSubtitleView: TextView
    private lateinit var fullscreenCurrentTime: TextView
    private lateinit var fullscreenDuration: TextView
    private lateinit var rootLayout: LinearLayout
    private lateinit var speedButton: TextView
    private lateinit var overlaySpeedButton: TextView
    private lateinit var overlayRecenterButton: TextView
    private lateinit var overlayMotionButton: TextView
    private lateinit var overlayRotateButton: TextView

    private val brightnessGlyph by lazy { Glyph(Glyph.Kind.BRIGHTNESS, Pano.SUN) }
    private val popoverViews = mutableListOf<View>()
    private var errorDialog: Dialog? = null
    private val fsInsets = intArrayOf(0, 0, 0, 0)

    private val fullscreenChrome = mutableListOf<View>()
    private var isFullscreen = false
    private var fullscreenControlsVisible = true
    private var normalRootPadding = intArrayOf(0, 0, 0, 0)
    private var normalVideoMargins = intArrayOf(0, 0)
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
        Pano.init(this)
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        showFpsEnabled = prefs.getBoolean(KEY_SHOW_FPS, false)
        showModeBadgeEnabled = prefs.getBoolean(KEY_SHOW_MODE_BADGE, false)
        audioOffsetMs = prefs.getInt(KEY_AUDIO_OFFSET_MS, 0).coerceIn(-1000, 1000)

        window.addFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Pano.NIGHT
        window.navigationBarColor = Pano.NIGHT
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
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 50_000,
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ 3_000
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
                updatePlayIcons(isPlaying)
                if (!isPlaying && ::fpsBadge.isInitialized) {
                    fpsBadge.text = "-- FPS"
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    consecutiveErrorCount = 0
                    lastRequestedSeekMs = -1L
                    placeholder.visibility = View.GONE
                    val resolvedName = currentVideoName.ifBlank { "Now playing" }
                    titleView.text = resolvedName
                    if (::fullscreenTitleView.isInitialized) fullscreenTitleView.text = resolvedName
                }
                if (playbackState == Player.STATE_ENDED) {
                    updatePlayIcons(false)
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
                if (activePlaybackUri == null || placeholder.visibility == View.VISIBLE) {
                    showPlaceholder(false)
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

        errorDialog?.dismiss()
        val sheet = createSheet("Playback error", error.errorCodeName, Pano.DANGER)

        val scroll = BoundedScrollView(this, (resources.displayMetrics.heightPixels * 0.42f).toInt()).apply {
            isVerticalScrollBarEnabled = true
            background = surface(Pano.NIGHT, 16f, Pano.HAIRLINE, 1f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val logText = TextView(this).apply {
            text = summary
            textSize = 11f
            setTextColor(Pano.withAlpha(Pano.TEXT, 0.88f))
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        scroll.addView(logText)
        sheet.content.addView(scroll, lp(MATCH, WRAP))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val copy = actionButton("Copy log", false) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("PanoPlay Error", summary))
            Toast.makeText(this@MainActivity, "Error log copied", Toast.LENGTH_SHORT).show()
        }
        val close = actionButton("Close", true) { sheet.dialog.dismiss() }
        buttons.addView(copy, lp(0, dp(52), 1f).apply { rightMargin = dp(10) })
        buttons.addView(close, lp(0, dp(52), 1f))
        sheet.content.addView(buttons, lp(MATCH, WRAP).apply { topMargin = dp(16) })

        errorDialog = sheet.dialog
        showSheet(sheet)
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
        icon.setColorFilter(tintColor, android.graphics.PorterDuff.Mode.SRC_IN)
        val iconSize = dp(17)
        icon.setBounds(0, 0, iconSize, iconSize)
        view.compoundDrawablePadding = dp(7)
        view.setCompoundDrawables(icon, null, null, null)
    }

    private fun setMotionTrackingEnabled(enabled: Boolean) {
        if (enabled && gyroSensor == null) {
            Toast.makeText(this, "Gyroscope sensor not available on this device", Toast.LENGTH_SHORT).show()
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
            stylePill(overlayMotionButton, enabled, Glyph.Kind.MOTION)
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

        showCenterHud("$percent%", 0L, brightnessGlyph)
    }

    private fun formatSpeedLabel(speed: Float): String =
        if (speed == 1.0f || speed == 2.0f) "${speed.toInt()}.0x" else "${speed}x"

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
        val sheet = createSheet("Settings")
        val body = sheet.content

        body.addView(
            toggleRow("Live frame rate", "Show the current frames per second on the video.", showFpsEnabled) { checked ->
                showFpsEnabled = checked
                prefs.edit().putBoolean(KEY_SHOW_FPS, checked).apply()
                applyFpsVisibility()
            },
            lp(MATCH, WRAP)
        )
        body.addView(divider(), lp(MATCH, dp(1)))
        body.addView(
            toggleRow("View badge", "Show the 180\u00B0 or 360\u00B0 view and the eye on the video.", showModeBadgeEnabled) { checked ->
                showModeBadgeEnabled = checked
                prefs.edit().putBoolean(KEY_SHOW_MODE_BADGE, checked).apply()
                applyModeBadgeVisibility()
            },
            lp(MATCH, WRAP)
        )
        body.addView(divider(), lp(MATCH, dp(1)))

        // Audio sync offset: -1000 ms to +1000 ms in 1 ms steps.
        val audioBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, dp(4))
        }
        val audioHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        audioHeader.addView(tv("Audio sync", 15.5f, Pano.TEXT, Pano.medium), lp(0, WRAP, 1f))
        val audioValue = tv(offsetText(audioOffsetMs), 14f, Pano.SUN, Pano.medium).apply {
            fontFeatureSettings = "tnum"
        }
        audioHeader.addView(audioValue, lp(WRAP, WRAP))
        audioBlock.addView(audioHeader, lp(MATCH, WRAP))
        audioBlock.addView(
            tv(
                "Increase if the sound plays before the picture. Decrease if it lags behind.",
                12.5f, Pano.MUTED, Pano.regular
            ).apply { setLineSpacing(0f, 1.1f) },
            lp(MATCH, WRAP).apply { topMargin = dp(4) }
        )

        val slider = CinemaSeekBar(this).apply {
            max = 2000
            centerOrigin = true
            progress = (audioOffsetMs + 1000).coerceIn(0, 2000)
        }

        fun commit(offset: Int) {
            audioOffsetMs = offset
            audioValue.text = offsetText(offset)
        }

        fun persistAndResync(offset: Int) {
            prefs.edit().putInt(KEY_AUDIO_OFFSET_MS, offset).apply()
            if (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING) {
                performSafeSeek(player.currentPosition)
            }
        }

        fun applyExact(target: Int) {
            val clamped = target.coerceIn(-1000, 1000)
            commit(clamped)
            slider.progress = clamped + 1000
            persistAndResync(clamped)
        }

        slider.onProgressScrub = { value -> commit(value - 1000) }
        slider.onStopScrub = { value -> persistAndResync(value - 1000) }
        audioBlock.addView(slider, lp(MATCH, dp(44)).apply {
            leftMargin = -dp(12)
            rightMargin = -dp(12)
            topMargin = dp(6)
        })

        val steps = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chipSpecs = listOf("\u22125 ms" to -5, "\u22121 ms" to -1, "Reset" to 0, "+1 ms" to 1, "+5 ms" to 5)
        chipSpecs.forEachIndexed { index, (label, delta) ->
            val isReset = delta == 0
            val chip = tv(label, 13f, if (isReset) Pano.SUN else Pano.TEXT, Pano.medium).apply {
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                background = ripple(Pano.HIGH, 100f, Pano.HAIRLINE, 1f)
                pressFeedback(this, 0.95f)
                setOnClickListener { applyExact(if (isReset) 0 else audioOffsetMs + delta) }
            }
            steps.addView(chip, lp(0, dp(40), 1f).apply { if (index > 0) leftMargin = dp(6) })
        }
        audioBlock.addView(steps, lp(MATCH, WRAP).apply { topMargin = dp(8) })
        body.addView(audioBlock, lp(MATCH, WRAP))

        body.addView(actionButton("Done", true) { sheet.dialog.dismiss() }, lp(MATCH, dp(54)).apply {
            topMargin = dp(20)
        })
        showSheet(sheet)
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
        val sheet = createSheet("Video details", fileName)

        val scroll = BoundedScrollView(this, (resources.displayMetrics.heightPixels * 0.5f).toInt()).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        var firstSection = true
        var previousWasRow = false
        rows.forEach { (key, value) ->
            if (value == null) {
                val sectionName = key.lowercase(Locale.US).replaceFirstChar { it.uppercase() }
                list.addView(
                    tv(sectionName, 13f, Pano.SUN, Pano.medium),
                    lp(MATCH, WRAP).apply {
                        topMargin = if (firstSection) dp(2) else dp(22)
                        bottomMargin = dp(4)
                    }
                )
                firstSection = false
                previousWasRow = false
            } else {
                if (previousWasRow) list.addView(divider(), lp(MATCH, dp(1)))
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(12), 0, dp(12))
                }
                row.addView(tv(key, 14f, Pano.MUTED, Pano.regular), lp(0, WRAP, 0.44f))
                row.addView(
                    tv(value, 14f, Pano.TEXT, Pano.medium).apply { gravity = Gravity.END },
                    lp(0, WRAP, 0.56f)
                )
                list.addView(row, lp(MATCH, WRAP))
                previousWasRow = true
            }
        }
        scroll.addView(list)
        sheet.content.addView(scroll, lp(MATCH, WRAP))
        sheet.content.addView(actionButton("Close", false) { sheet.dialog.dismiss() }, lp(MATCH, dp(52)).apply {
            topMargin = dp(18)
        })
        showSheet(sheet)
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
        val compact = resources.configuration.screenHeightDp < 700
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Pano.NIGHT)
            clipToPadding = false
            clipChildren = false
            setPadding(dp(20), dp(10), dp(20), dp(12))
        }
        rootLayout = root
        normalRootPadding = intArrayOf(dp(20), dp(10), dp(20), dp(12))

        // ── Header: mark, wordmark, three round actions ──
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val mark = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setImageDrawable(Glyph(Glyph.Kind.LENS, Pano.TEXT, Pano.SUN))
        }
        header.addView(mark, lp(dp(28), dp(28)).apply { rightMargin = dp(10) })
        val brand = tv("panoplay", 22f, Pano.TEXT, Pano.display).apply { letterSpacing = -0.01f }
        header.addView(brand, lp(0, WRAP, 1f))

        val infoButton = iconButton(Glyph.Kind.INFO, "Inspect a video's details").apply {
            setOnClickListener { chooseVideoForMetadata() }
        }
        val settingsButton = iconButton(Glyph.Kind.TUNE, "Settings").apply {
            setOnClickListener { openSettingsDialog() }
        }
        val openButton = iconButton(
            Glyph.Kind.PLUS, "Open a video", Pano.ON_SUN, Pano.SUN, 0, 10,
            Pano.withAlpha(Pano.ON_SUN, 0.18f)
        ).apply {
            setOnClickListener { chooseVideo() }
        }
        header.addView(infoButton, lp(dp(42), dp(42)).apply { rightMargin = dp(8) })
        header.addView(settingsButton, lp(dp(42), dp(42)).apply { rightMargin = dp(8) })
        header.addView(openButton, lp(dp(42), dp(42)))
        root.addView(header, lp(MATCH, dp(56)))
        fullscreenChrome.add(header)

        // ── Stage ──
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
        videoFrame.addView(playerView, FrameLayout.LayoutParams(MATCH, MATCH))

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
        videoFrame.addView(vrSurfaceView, FrameLayout.LayoutParams(MATCH, MATCH))

        // Empty state: the turning sphere, a headline and one clear action.
        placeholder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(14), dp(24), dp(22))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.BLACK, Color.rgb(15, 16, 36))
            )
            isClickable = true
            isFocusable = true
        }
        placeholder.addView(GlobeView(this), lp(MATCH, 0, 1f))
        placeholderTitle = tv("Open a video", 24f, Pano.TEXT, Pano.display).apply { gravity = Gravity.CENTER }
        placeholderSub = tv("Flat, 180\u00B0 and 360\u00B0 videos from your phone.", 14f, Pano.MUTED, Pano.regular).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        placeholderButton = tv("Choose video", 15f, Pano.ON_SUN, Pano.medium).apply {
            gravity = Gravity.CENTER
            setPadding(dp(28), 0, dp(28), 0)
            isClickable = true
            isFocusable = true
            background = ripple(Pano.SUN, 100f, 0, 0f, Pano.withAlpha(Pano.ON_SUN, 0.18f))
            pressFeedback(this, 0.96f)
            setOnClickListener { chooseVideo() }
        }
        placeholder.addView(placeholderTitle, lp(MATCH, WRAP).apply { topMargin = dp(6) })
        placeholder.addView(placeholderSub, lp(MATCH, WRAP).apply { topMargin = dp(6) })
        placeholder.addView(placeholderButton, lp(WRAP, dp(48)).apply { topMargin = dp(18) })
        placeholder.setOnClickListener {
            if (placeholderButton.visibility == View.VISIBLE) chooseVideo()
        }
        videoFrame.addView(placeholder, FrameLayout.LayoutParams(MATCH, MATCH))

        centerHud = tv("", 15f, Pano.TEXT, Pano.medium).apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(11), dp(20), dp(11))
            background = surface(Pano.withAlpha(Pano.NIGHT, 0.80f), 100f, Pano.HAIRLINE, 1f)
            fontFeatureSettings = "tnum"
            elevation = dp(8).toFloat()
            visibility = View.GONE
        }
        videoFrame.addView(
            centerHud,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER)
        )

        modeBadge = tv("", 11.5f, Pano.TEXT, Pano.medium).apply {
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = surface(glassFill(), 100f, Pano.HAIRLINE, 1f)
            elevation = dp(4).toFloat()
            visibility = if (showModeBadgeEnabled) View.VISIBLE else View.GONE
        }
        videoFrame.addView(
            modeBadge,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply {
                setMargins(dp(14), dp(14), 0, 0)
            }
        )

        fpsBadge = tv("-- FPS", 11.5f, Pano.LAGOON, Pano.medium).apply {
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = surface(glassFill(), 100f, Pano.HAIRLINE, 1f)
            fontFeatureSettings = "tnum"
            elevation = dp(4).toFloat()
            visibility = if (showFpsEnabled) View.VISIBLE else View.GONE
        }
        videoFrame.addView(
            fpsBadge,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply {
                setMargins(0, dp(14), dp(14), 0)
            }
        )

        // ── Fullscreen: top bar ──
        fullscreenTopBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(24))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(205, 6, 7, 14), Color.TRANSPARENT)
            )
            elevation = dp(4).toFloat()
            visibility = View.GONE
        }
        val exitFullscreenBtn = iconButton(
            Glyph.Kind.BACK, "Exit full screen", Pano.TEXT, Pano.withAlpha(Pano.NIGHT, 0.62f)
        ).apply {
            setOnClickListener { if (isFullscreen) toggleFullscreen() }
        }
        val titleColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fullscreenTitleView = tv("Now playing", 16f, Pano.TEXT, Pano.display).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        fullscreenSubtitleView = tv("", 12f, Pano.MUTED, Pano.regular)
        titleColumn.addView(fullscreenTitleView, lp(MATCH, WRAP))
        titleColumn.addView(fullscreenSubtitleView, lp(MATCH, WRAP).apply { topMargin = dp(3) })
        fullscreenTopBar.addView(exitFullscreenBtn, lp(dp(42), dp(42)).apply { rightMargin = dp(12) })
        fullscreenTopBar.addView(titleColumn, lp(0, WRAP, 1f))
        videoFrame.addView(
            fullscreenTopBar,
            FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP)
        )

        // ── Fullscreen: centre transport ──
        fullscreenCenterControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            elevation = dp(6).toFloat()
            visibility = View.GONE
        }
        val centerRewind = iconButton(
            Glyph.Kind.REPLAY10, "Back 10 seconds", Pano.TEXT, Pano.withAlpha(Pano.NIGHT, 0.62f), Pano.HAIRLINE, 14
        ).apply {
            setOnClickListener { performSafeSeek((player.currentPosition - 10_000L).coerceAtLeast(0L)) }
        }
        overlayPlayButton = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setImageDrawable(Glyph(Glyph.Kind.PLAY, Pano.ON_SUN))
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = ripple(Pano.SUN, 100f, 0, 0f, Pano.withAlpha(Pano.ON_SUN, 0.18f))
            isClickable = true
            isFocusable = true
            contentDescription = "Play or pause"
            pressFeedback(this, 0.94f)
            setOnClickListener { togglePlayback() }
        }
        val centerForward = iconButton(
            Glyph.Kind.FORWARD10, "Forward 10 seconds", Pano.TEXT, Pano.withAlpha(Pano.NIGHT, 0.62f), Pano.HAIRLINE, 14
        ).apply {
            setOnClickListener {
                val end = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: Long.MAX_VALUE
                performSafeSeek((player.currentPosition + 10_000L).coerceAtMost(end))
            }
        }
        fullscreenCenterControls.addView(centerRewind, lp(dp(60), dp(60)).apply { rightMargin = dp(28) })
        fullscreenCenterControls.addView(overlayPlayButton, lp(dp(84), dp(84)))
        fullscreenCenterControls.addView(centerForward, lp(dp(60), dp(60)).apply { leftMargin = dp(28) })
        videoFrame.addView(
            fullscreenCenterControls,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER)
        )

        // ── Fullscreen: glass dock with seek bar and pills ──
        fullscreenBottomControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(14))
            background = surface(Pano.withAlpha(Pano.NIGHT, 0.82f), 28f, Pano.HAIRLINE, 1f)
            elevation = dp(4).toFloat()
            visibility = View.GONE
        }

        overlaySeekBar = CinemaSeekBar(this).apply {
            max = 1000
            onStartScrub = {
                isScrubbing = true
            }
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
        fullscreenCurrentTime = tv("00:00", 12.5f, Pano.TEXT, Pano.regular).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            fontFeatureSettings = "tnum"
            minWidth = dp(40)
        }
        fullscreenDuration = tv("00:00", 12.5f, Pano.MUTED, Pano.regular).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            fontFeatureSettings = "tnum"
            minWidth = dp(40)
        }
        val fullscreenSeekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fullscreenSeekRow.addView(fullscreenCurrentTime, lp(WRAP, dp(40)))
        fullscreenSeekRow.addView(overlaySeekBar, lp(0, dp(40), 1f))
        fullscreenSeekRow.addView(fullscreenDuration, lp(WRAP, dp(40)))
        fullscreenBottomControls.addView(fullscreenSeekRow, lp(MATCH, dp(40)))

        val bottomPillsScroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, dp(6), 0, 0)
        }
        val bottomPillsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        overlaySpeedButton = glassPill("1.0x", Glyph.Kind.SPEED).apply {
            fontFeatureSettings = "tnum"
            setOnClickListener { showSpeedPicker(this) }
        }
        overlayRecenterButton = glassPill("Recenter", Glyph.Kind.RECENTER).apply {
            setOnClickListener {
                if (currentProjection != VrVideoSurfaceView.Projection.FLAT) {
                    vrSurfaceView.resetView()
                }
            }
        }
        overlayMotionButton = glassPill("Motion", Glyph.Kind.MOTION).apply {
            setOnClickListener { setMotionTrackingEnabled(!isMotionEnabled) }
        }
        overlayRotateButton = glassPill("Landscape", Glyph.Kind.ROTATE).apply {
            setOnClickListener { toggleFullscreenOrientation() }
        }
        val textPills = listOf(overlaySpeedButton, overlayRecenterButton, overlayMotionButton, overlayRotateButton)
        textPills.forEachIndexed { idx, pill ->
            bottomPillsRow.addView(
                pill,
                lp(WRAP, dp(38)).apply { if (idx < textPills.lastIndex) rightMargin = dp(8) }
            )
        }
        bottomPillsScroller.addView(bottomPillsRow, FrameLayout.LayoutParams(WRAP, WRAP))
        fullscreenBottomControls.addView(bottomPillsScroller, lp(MATCH, WRAP))
        videoFrame.addView(
            fullscreenBottomControls,
            FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply {
                setMargins(dp(16), 0, dp(16), dp(14))
            }
        )

        // Rounded corners and hairline edge painted over the stage (portrait only).
        stageMask = StageFrameView(this, dpf(26f), Pano.NIGHT)
        videoFrame.addView(stageMask, FrameLayout.LayoutParams(MATCH, MATCH))

        root.addView(videoFrame, lp(MATCH, 0, 1f).apply { topMargin = dp(2) })

        // ── Dock: title, progress, transport, view controls ──
        val dock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }

        titleView = tv("No video selected", 19f, Pano.TEXT, Pano.display).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        subtitleView = tv("Choose a video to begin", 13f, Pano.MUTED, Pano.regular).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        dock.addView(titleView, lp(MATCH, WRAP).apply { topMargin = dp(if (compact) 12 else 18) })
        dock.addView(subtitleView, lp(MATCH, WRAP).apply { topMargin = dp(5) })

        seekBar = CinemaSeekBar(this).apply {
            max = 1000
            onStartScrub = { isScrubbing = true }
            onProgressScrub = { value ->
                val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: knownTotalDurationMs
                currentTimeView.text = formatTime((duration * value) / 1000L)
            }
            onStopScrub = { value ->
                val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: knownTotalDurationMs
                if (duration > 0L) {
                    performSafeSeek((value / 1000f * duration).toLong())
                }
                isScrubbing = false
            }
        }
        dock.addView(seekBar, lp(MATCH, dp(40)).apply {
            topMargin = dp(6)
            leftMargin = -dp(8)
            rightMargin = -dp(8)
        })

        val timeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        currentTimeView = tv("00:00", 12.5f, Pano.TEXT, Pano.regular).apply { fontFeatureSettings = "tnum" }
        durationView = tv("00:00", 12.5f, Pano.MUTED, Pano.regular).apply {
            gravity = Gravity.END
            fontFeatureSettings = "tnum"
        }
        timeRow.addView(currentTimeView, lp(0, WRAP, 1f))
        timeRow.addView(durationView, lp(0, WRAP, 1f))
        dock.addView(timeRow, lp(MATCH, WRAP))

        val transport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        speedButton = tv("1.0x", 14f, Pano.TEXT, Pano.medium).apply {
            gravity = Gravity.CENTER
            fontFeatureSettings = "tnum"
            background = ripple(Pano.RAISED, 100f, Pano.HAIRLINE, 1f)
            contentDescription = "Playback speed"
            isClickable = true
            isFocusable = true
            pressFeedback(this, 0.93f)
            setOnClickListener { showSpeedPicker(this) }
        }
        val backButton = iconButton(
            Glyph.Kind.REPLAY10, "Back 10 seconds", Pano.TEXT, Color.TRANSPARENT, 0, 10
        ).apply {
            setOnClickListener { performSafeSeek((player.currentPosition - 10_000L).coerceAtLeast(0L)) }
        }
        playButton = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setImageDrawable(Glyph(Glyph.Kind.PLAY, Pano.ON_SUN))
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = ripple(Pano.SUN, 100f, 0, 0f, Pano.withAlpha(Pano.ON_SUN, 0.18f))
            contentDescription = "Play or pause"
            isClickable = true
            isFocusable = true
            pressFeedback(this, 0.94f)
            setOnClickListener { togglePlayback() }
        }
        val forwardButton = iconButton(
            Glyph.Kind.FORWARD10, "Forward 10 seconds", Pano.TEXT, Color.TRANSPARENT, 0, 10
        ).apply {
            setOnClickListener {
                val end = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: Long.MAX_VALUE
                performSafeSeek((player.currentPosition + 10_000L).coerceAtMost(end))
            }
        }
        fullscreenButton = iconButton(Glyph.Kind.FULLSCREEN, "Enter full screen", Pano.TEXT, Pano.RAISED, Pano.HAIRLINE, 12).apply {
            setOnClickListener { toggleFullscreen() }
        }
        transport.addView(speedButton, lp(dp(46), dp(46)).apply { rightMargin = dp(8) })
        transport.addView(backButton, lp(dp(54), dp(54)).apply { rightMargin = dp(8) })
        transport.addView(playButton, lp(dp(70), dp(70)))
        transport.addView(forwardButton, lp(dp(54), dp(54)).apply { leftMargin = dp(8) })
        transport.addView(fullscreenButton, lp(dp(46), dp(46)).apply { leftMargin = dp(8) })
        dock.addView(transport, lp(MATCH, WRAP).apply { topMargin = dp(if (compact) 2 else 6) })

        modeControl = SegmentedControl(
            this,
            listOf(
                SegmentedControl.Item("Flat", Glyph.Kind.FLAT),
                SegmentedControl.Item("180\u00B0", Glyph.Kind.DOME180),
                SegmentedControl.Item("360\u00B0", Glyph.Kind.SPHERE360)
            )
        ).apply {
            contentDescription = "View mode"
            onSelected = { index ->
                setProjection(
                    when (index) {
                        0 -> VrVideoSurfaceView.Projection.FLAT
                        1 -> VrVideoSurfaceView.Projection.VR180
                        else -> VrVideoSurfaceView.Projection.VR360
                    }
                )
            }
        }
        stereoControl = SegmentedControl(
            this,
            listOf(
                SegmentedControl.Item("Mono", Glyph.Kind.MONO),
                SegmentedControl.Item("Left eye", Glyph.Kind.SBS_LEFT),
                SegmentedControl.Item("Right eye", Glyph.Kind.SBS_RIGHT)
            )
        ).apply {
            contentDescription = "Stereo layout"
            onSelected = { index ->
                stereoLayout = when (index) {
                    0 -> VrVideoSurfaceView.StereoLayout.MONO
                    1 -> VrVideoSurfaceView.StereoLayout.SBS_LEFT
                    else -> VrVideoSurfaceView.StereoLayout.SBS_RIGHT
                }
                vrSurfaceView.setStereoLayout(stereoLayout)
                updateModeChips()
                updateBadgeText()
            }
        }
        dock.addView(modeControl, lp(MATCH, dp(50)).apply { topMargin = dp(if (compact) 10 else 16) })
        dock.addView(stereoControl, lp(MATCH, dp(50)).apply { topMargin = dp(8) })

        hintView = tv("", 12f, Pano.FAINT, Pano.regular).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            setLineSpacing(0f, 1.1f)
            visibility = if (compact) View.GONE else View.VISIBLE
        }
        dock.addView(hintView, lp(MATCH, WRAP).apply { topMargin = dp(12) })

        root.addView(dock, lp(MATCH, WRAP))
        fullscreenChrome.add(dock)

        rootFrame = FrameLayout(this).apply { setBackgroundColor(Pano.NIGHT) }
        overlayLayer = FrameLayout(this)
        rootFrame.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
        rootFrame.addView(overlayLayer, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(rootFrame)

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
            fsInsets.fill(0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                insets.displayCutout?.let { cutout ->
                    fsInsets[0] = cutout.safeInsetLeft
                    fsInsets[1] = cutout.safeInsetTop
                    fsInsets[2] = cutout.safeInsetRight
                    fsInsets[3] = cutout.safeInsetBottom
                }
            }
            if (isFullscreen) {
                view.setPadding(0, 0, 0, 0)
                applyFullscreenInsets()
            } else {
                view.setPadding(dp(20), dp(10) + topInset, dp(20), dp(12) + bottomInset)
            }
            insets
        }
        root.requestApplyInsets()
        updateModeChips()
        updateBadgeText()
    }

    private lateinit var modeBadge: TextView

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

    private fun eyeLabel(): String = when (stereoLayout) {
        VrVideoSurfaceView.StereoLayout.MONO -> "Mono"
        VrVideoSurfaceView.StereoLayout.SBS_LEFT -> "Left eye"
        VrVideoSurfaceView.StereoLayout.SBS_RIGHT -> "Right eye"
    }

    private fun modeSummary(): String = when (currentProjection) {
        VrVideoSurfaceView.Projection.FLAT -> "Flat 2D"
        VrVideoSurfaceView.Projection.VR180 -> "180\u00B0 \u00B7 ${eyeLabel()}"
        VrVideoSurfaceView.Projection.VR360 -> "360\u00B0 \u00B7 ${eyeLabel()}"
    }

    private fun updateBadgeText() {
        val projectionLabel = when (currentProjection) {
            VrVideoSurfaceView.Projection.FLAT -> "Flat 2D"
            VrVideoSurfaceView.Projection.VR180 -> "180\u00B0"
            VrVideoSurfaceView.Projection.VR360 -> "360\u00B0"
        }
        val badge = SpannableStringBuilder(projectionLabel)
        if (currentProjection != VrVideoSurfaceView.Projection.FLAT) {
            val start = badge.length
            badge.append("  ").append(eyeLabel())
            badge.setSpan(
                ForegroundColorSpan(Pano.MUTED), start, badge.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        modeBadge.text = badge
        if (::subtitleView.isInitialized) {
            subtitleView.text = if (currentVideoName.isBlank()) "Choose a video to begin" else modeSummary()
        }
        if (::fullscreenSubtitleView.isInitialized) fullscreenSubtitleView.text = modeSummary()
    }

    private fun updateModeChips() {
        modeControl.select(
            when (currentProjection) {
                VrVideoSurfaceView.Projection.FLAT -> 0
                VrVideoSurfaceView.Projection.VR180 -> 1
                VrVideoSurfaceView.Projection.VR360 -> 2
            }
        )
        stereoControl.select(
            when (stereoLayout) {
                VrVideoSurfaceView.StereoLayout.MONO -> 0
                VrVideoSurfaceView.StereoLayout.SBS_LEFT -> 1
                VrVideoSurfaceView.StereoLayout.SBS_RIGHT -> 2
            }
        )
        // Eye selection only matters for 180° and 360°, so dim it for flat video.
        val vr = currentProjection != VrVideoSurfaceView.Projection.FLAT
        stereoControl.isEnabled = vr
        stereoControl.animate().alpha(if (vr) 1f else 0.34f).setDuration(200L).start()
        hintView.text = if (vr) {
            "Drag to look around. Pinch to zoom. Swipe the left edge for brightness."
        } else {
            "Open full screen, then double-tap either side to skip 10 seconds."
        }
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
        showPlaceholder(true)

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
        dismissPopover()
        isFullscreen = !isFullscreen
        fullscreenControlsVisible = true
        fullscreenChrome.forEach { it.visibility = if (isFullscreen) View.GONE else View.VISIBLE }
        stageMask.visibility = if (isFullscreen) View.GONE else View.VISIBLE
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
            overlayRotateButton.text = "Landscape"
            updatePlayIcons(player.isPlaying)
            (modeBadge.layoutParams as FrameLayout.LayoutParams).topMargin = dp(76) + fsInsets[1]
            (fpsBadge.layoutParams as FrameLayout.LayoutParams).topMargin = dp(76) + fsInsets[1]
            enterImmersiveMode()
        } else {
            if (isMotionEnabled) {
                setMotionTrackingEnabled(false)
            }
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
            (modeBadge.layoutParams as FrameLayout.LayoutParams).topMargin = dp(14)
            (fpsBadge.layoutParams as FrameLayout.LayoutParams).topMargin = dp(14)
            fullscreenButton.contentDescription = "Enter full screen"
            exitImmersiveMode()
        }
        videoFrame.layoutParams = params
        applyFullscreenInsets()
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
            listOf(fullscreenTopBar, fullscreenCenterControls, fullscreenBottomControls).forEach {
                it.animate().cancel()
                it.alpha = 1f
                it.visibility = View.GONE
            }
            applyModeBadgeVisibility()
            applyFpsVisibility()
        }
    }

    private fun setFullscreenControlsVisible(visible: Boolean) {
        fullscreenControlsVisible = visible
        fade(fullscreenTopBar, visible)
        fade(fullscreenCenterControls, visible)
        fade(fullscreenBottomControls, visible)
        if (showModeBadgeEnabled) {
            fade(modeBadge, visible)
        } else {
            modeBadge.visibility = View.GONE
        }
        if (showFpsEnabled) {
            fade(fpsBadge, visible)
        } else {
            fpsBadge.visibility = View.GONE
        }
        if (!visible) dismissPopover()
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
        if (popoverViews.isNotEmpty()) {
            dismissPopover()
            return
        }
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
        errorDialog?.dismiss()
        sensorManager.unregisterListener(this)
        mainHandler.removeCallbacks(progressUpdater)
        mainHandler.removeCallbacks(hideCenterHudRunnable)
        if (::playerView.isInitialized) playerView.player = null
        if (::vrSurfaceView.isInitialized) vrSurfaceView.release()
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    // ───────────────────────── Design toolkit ─────────────────────────

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun dpf(value: Float): Float = value * resources.displayMetrics.density

    private fun lp(width: Int, height: Int, weight: Float = 0f): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(width, height, weight)

    private fun tv(text: CharSequence, sizeSp: Float, color: Int, face: Typeface = Pano.medium): TextView =
        TextView(this).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            typeface = face
            includeFontPadding = false
        }

    private fun surface(
        fill: Int,
        radiusDp: Float,
        strokeColor: Int = 0,
        strokeDp: Float = 0f
    ): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dpf(radiusDp)
        if (strokeDp > 0f) setStroke(Math.max(1, dp(strokeDp.toInt())), strokeColor)
    }

    private fun ripple(
        fill: Int,
        radiusDp: Float,
        strokeColor: Int = 0,
        strokeDp: Float = 0f,
        rippleColor: Int = Pano.withAlpha(Color.WHITE, 0.14f)
    ): Drawable {
        val content = surface(fill, radiusDp, strokeColor, strokeDp)
        val mask = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dpf(radiusDp)
        }
        return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
    }

    private fun glassFill(): Int = Pano.withAlpha(Pano.NIGHT, 0.74f)

    /** Gentle press-in feedback; the touch listener never consumes, so clicks still fire. */
    private fun pressFeedback(view: View, scale: Float = 0.92f) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(scale).scaleY(scale).setDuration(80L).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140L).start()
            }
            false
        }
    }

    private fun iconButton(
        kind: Glyph.Kind,
        description: String,
        glyphTint: Int = Pano.TEXT,
        fill: Int = Pano.RAISED,
        strokeColor: Int = Pano.HAIRLINE,
        padDp: Int = 10,
        rippleColor: Int = Pano.withAlpha(Color.WHITE, 0.14f)
    ): ImageView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        setImageDrawable(Glyph(kind, glyphTint))
        setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
        background = ripple(fill, 100f, strokeColor, if (strokeColor != 0) 1f else 0f, rippleColor)
        contentDescription = description
        isClickable = true
        isFocusable = true
        pressFeedback(this)
    }

    private fun actionButton(label: String, primary: Boolean, onClick: () -> Unit): TextView =
        tv(label, 15f, if (primary) Pano.ON_SUN else Pano.TEXT, Pano.medium).apply {
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = if (primary) {
                ripple(Pano.SUN, 100f, 0, 0f, Pano.withAlpha(Pano.ON_SUN, 0.18f))
            } else {
                ripple(Pano.HIGH, 100f, Pano.HAIRLINE, 1f)
            }
            pressFeedback(this, 0.97f)
            setOnClickListener { onClick() }
        }

    private fun stylePill(view: TextView, active: Boolean, kind: Glyph.Kind) {
        val fg = if (active) Pano.ON_SUN else Pano.TEXT
        view.setTextColor(fg)
        view.background = if (active) {
            ripple(Pano.SUN, 100f, 0, 0f, Pano.withAlpha(Pano.ON_SUN, 0.18f))
        } else {
            ripple(Pano.withAlpha(Pano.NIGHT, 0.72f), 100f, Pano.HAIRLINE, 1f)
        }
        setPillLeftIcon(view, Glyph(kind, fg), fg)
    }

    private fun glassPill(label: String, kind: Glyph.Kind): TextView =
        tv(label, 13f, Pano.TEXT, Pano.medium).apply {
            gravity = Gravity.CENTER
            isSingleLine = true
            setPadding(dp(14), 0, dp(16), 0)
            isClickable = true
            isFocusable = true
            stylePill(this, false, kind)
            pressFeedback(this, 0.95f)
        }

    private fun fade(view: View, show: Boolean) {
        view.animate().cancel()
        view.tag = show
        if (show) {
            if (view.visibility != View.VISIBLE) {
                view.alpha = 0f
                view.visibility = View.VISIBLE
            }
            view.animate().alpha(1f).setDuration(180L).start()
        } else {
            if (view.visibility != View.VISIBLE) return
            view.animate().alpha(0f).setDuration(140L).withEndAction {
                if (view.tag == false) {
                    view.visibility = View.GONE
                    view.alpha = 1f
                }
            }.start()
        }
    }

    private fun updatePlayIcons(playing: Boolean) {
        val kind = if (playing) Glyph.Kind.PAUSE else Glyph.Kind.PLAY
        if (::playButton.isInitialized) playButton.setImageDrawable(Glyph(kind, Pano.ON_SUN))
        if (::overlayPlayButton.isInitialized) overlayPlayButton.setImageDrawable(Glyph(kind, Pano.ON_SUN))
    }

    private fun togglePlayback() {
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) performSafeSeek(0L)
            player.play()
        }
    }

    private fun offsetText(ms: Int): String = formatOffsetLabel(ms).replace('-', '\u2212')

    private fun showPlaceholder(loading: Boolean) {
        placeholder.visibility = View.VISIBLE
        if (loading) {
            placeholderTitle.text = "Preparing video"
            placeholderSub.text = currentVideoName.ifBlank { "One moment" }
            placeholderButton.visibility = View.GONE
        } else {
            placeholderTitle.text = "Open a video"
            placeholderSub.text = "Flat, 180\u00B0 and 360\u00B0 videos from your phone."
            placeholderButton.visibility = View.VISIBLE
        }
    }

    // ───────────────────────── Sheets & popover ─────────────────────────

    private class SheetHandle(val dialog: Dialog, val content: LinearLayout)

    private fun createSheet(title: String, subtitle: String? = null, subtitleColor: Int = Pano.MUTED): SheetHandle {
        val dialog = Dialog(this, R.style.PanoSheet)
        val wrapper = FrameLayout(this)
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val r = dpf(28f)
            background = GradientDrawable().apply {
                setColor(Pano.RAISED)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setStroke(dp(1), Pano.HAIRLINE)
            }
            setPadding(dp(22), dp(10), dp(22), dp(24))
        }
        val grab = View(this).apply { background = surface(Pano.withAlpha(Pano.TEXT, 0.22f), 4f) }
        sheet.addView(grab, lp(dp(40), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(18)
        })
        sheet.addView(tv(title, 22f, Pano.TEXT, Pano.display), lp(MATCH, WRAP))
        if (subtitle != null) {
            val sub = tv(subtitle, 13f, subtitleColor, Pano.regular).apply {
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.MIDDLE
            }
            sheet.addView(sub, lp(MATCH, WRAP).apply { topMargin = dp(5) })
        }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sheet.addView(body, lp(MATCH, WRAP).apply { topMargin = dp(14) })

        val sheetWidth = Math.min(resources.displayMetrics.widthPixels, dp(560))
        wrapper.addView(
            sheet,
            FrameLayout.LayoutParams(sheetWidth, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        )
        dialog.setContentView(wrapper)
        dialog.window?.apply {
            setLayout(MATCH, WRAP)
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.62f)
        }
        return SheetHandle(dialog, body)
    }

    private fun showSheet(sheet: SheetHandle) {
        sheet.dialog.show()
        if (isFullscreen) keepImmersive(sheet.dialog)
    }

    @Suppress("DEPRECATION")
    private fun keepImmersive(dialog: Dialog) {
        val w = dialog.window ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            w.insetsController?.hide(WindowInsets.Type.systemBars())
        } else {
            w.decorView.systemUiVisibility = window.decorView.systemUiVisibility
        }
    }

    private fun divider(): View = View(this).apply { setBackgroundColor(Pano.HAIRLINE) }

    private fun toggleRow(title: String, sub: String, initial: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(14))
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(tv(title, 15.5f, Pano.TEXT, Pano.medium), lp(MATCH, WRAP))
        column.addView(
            tv(sub, 12.5f, Pano.MUTED, Pano.regular).apply { setLineSpacing(0f, 1.1f) },
            lp(MATCH, WRAP).apply { topMargin = dp(3) }
        )
        row.addView(column, lp(0, WRAP, 1f).apply { rightMargin = dp(14) })
        val toggle = PanoSwitch(this).apply {
            setOn(initial, false)
            onChanged = onChange
        }
        row.addView(toggle, lp(WRAP, WRAP))
        row.isClickable = true
        row.setOnClickListener { toggle.toggle() }
        return row
    }

    private fun dismissPopover() {
        if (popoverViews.isEmpty() || !::overlayLayer.isInitialized) return
        popoverViews.forEach { overlayLayer.removeView(it) }
        popoverViews.clear()
    }

    private fun applySpeed(speed: Float) {
        currentSpeed = speed
        player.playbackParameters = PlaybackParameters(speed)
        val label = formatSpeedLabel(speed)
        speedButton.text = label
        if (::overlaySpeedButton.isInitialized) overlaySpeedButton.text = label
        showCenterHud(label, 800L)
    }

    private fun showSpeedPicker(anchor: View) {
        if (popoverViews.isNotEmpty()) {
            dismissPopover()
            return
        }
        val scrim = View(this).apply { setOnClickListener { dismissPopover() } }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = surface(Pano.withAlpha(Pano.RAISED, 0.96f), 26f, Pano.HAIRLINE, 1f)
            elevation = dp(12).toFloat()
        }
        speedSteps.forEachIndexed { index, speed ->
            val selected = speed == currentSpeed
            val chip = tv(formatSpeedLabel(speed), 13.5f, if (selected) Pano.ON_SUN else Pano.TEXT, Pano.medium).apply {
                gravity = Gravity.CENTER
                fontFeatureSettings = "tnum"
                isClickable = true
                isFocusable = true
                background = if (selected) {
                    ripple(Pano.SUN, 100f, 0, 0f, Pano.withAlpha(Pano.ON_SUN, 0.18f))
                } else {
                    ripple(Color.TRANSPARENT, 100f)
                }
                setOnClickListener {
                    applySpeed(speed)
                    dismissPopover()
                }
            }
            row.addView(chip, lp(dp(50), dp(40)).apply { if (index > 0) leftMargin = dp(2) })
        }

        overlayLayer.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        row.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popW = row.measuredWidth
        val popH = row.measuredHeight
        val a = IntArray(2)
        val o = IntArray(2)
        anchor.getLocationInWindow(a)
        rootFrame.getLocationInWindow(o)
        val anchorCenterX = a[0] - o[0] + anchor.width / 2
        val anchorTop = a[1] - o[1]
        val left = (anchorCenterX - popW / 2).coerceIn(dp(12), Math.max(dp(12), rootFrame.width - popW - dp(12)))
        var top = anchorTop - popH - dp(10)
        if (top < dp(8)) top = anchorTop + anchor.height + dp(10)
        overlayLayer.addView(row, FrameLayout.LayoutParams(popW, popH).apply {
            gravity = Gravity.TOP or Gravity.START
            leftMargin = left
            topMargin = top
        })
        popoverViews.add(scrim)
        popoverViews.add(row)
        row.alpha = 0f
        row.translationY = dpf(6f)
        row.animate().alpha(1f).translationY(0f).setDuration(160L).start()
    }

    private fun applyFullscreenInsets() {
        if (!::fullscreenTopBar.isInitialized) return
        fullscreenTopBar.setPadding(
            dp(14) + fsInsets[0], dp(10) + fsInsets[1], dp(14) + fsInsets[2], dp(24)
        )
        (fullscreenBottomControls.layoutParams as FrameLayout.LayoutParams).apply {
            leftMargin = dp(16) + fsInsets[0]
            rightMargin = dp(16) + fsInsets[2]
            bottomMargin = dp(14) + fsInsets[3]
        }
        fullscreenBottomControls.requestLayout()
    }

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
