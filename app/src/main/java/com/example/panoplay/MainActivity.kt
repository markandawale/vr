package com.example.panoplay

import android.content.Context
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.MotionEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.util.Locale

private class TapDetectFrameLayout(context: Context) : FrameLayout(context) {
    var onVideoTap: (() -> Unit)? = null
    var shouldIgnoreTap: ((Float, Float) -> Boolean)? = null
    private var startX = 0f
    private var startY = 0f
    private var startTime = 0L

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Let the actual video surface receive the complete gesture first.
        // This is essential: VR surfaces need the full DOWN/MOVE/UP sequence for panning.
        val handled = super.dispatchTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                startTime = android.os.SystemClock.uptimeMillis()
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - startX
                val dy = event.y - startY
                val elapsed = android.os.SystemClock.uptimeMillis() - startTime
                val slop = 14f * resources.displayMetrics.density
                if (elapsed < 350L && dx * dx + dy * dy < slop * slop &&
                    shouldIgnoreTap?.invoke(event.x, event.y) != true
                ) onVideoTap?.invoke()
            }
            MotionEvent.ACTION_CANCEL -> startTime = 0L
        }
        return handled
    }
}

@OptIn(UnstableApi::class)
class MainActivity : Activity(), VrVideoSurfaceView.SurfaceEvents {
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var vrSurface: VrVideoSurfaceView
    private var attachedSurface: android.view.Surface? = null
    private lateinit var videoFrame: FrameLayout
    private lateinit var placeholder: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var playButton: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var currentTimeView: TextView
    private lateinit var durationView: TextView
    private lateinit var fullscreenButton: TextView
    private lateinit var overlayPlayButton: TextView
    private lateinit var overlaySeekBar: SeekBar
    private lateinit var fullscreenControls: LinearLayout
    private lateinit var fullscreenCurrentTime: TextView
    private lateinit var fullscreenDuration: TextView
    private lateinit var rootLayout: LinearLayout
    private val fullscreenChrome = mutableListOf<View>()
    private var isFullscreen = false
    private var fullscreenControlsVisible = true
    private var normalRootPadding = intArrayOf(0, 0, 0, 0)
    private var normalVideoMargins = intArrayOf(0, 0)

    private var isScrubbing = false
    private var sourceAspectRatio = 16f / 9f
    private var currentProjection = VrVideoSurfaceView.Projection.VR180
    private var isLandscape = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val progressUpdater = object : Runnable {
        override fun run() {
            if (::player.isInitialized) {
                val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
                val position = player.currentPosition.coerceAtLeast(0L)
                if (!isScrubbing) {
                    seekBar.max = 1000
                    seekBar.progress = if (duration > 0L) ((position * 1000L) / duration).toInt() else 0
                    currentTimeView.text = formatTime(position)
                }
                durationView.text = formatTime(duration)
                if (::overlaySeekBar.isInitialized) {
                    if (!isScrubbing) overlaySeekBar.progress = if (duration > 0L) ((position * 1000L) / duration).toInt() else 0
                    fullscreenCurrentTime.text = formatTime(position)
                    fullscreenDuration.text = formatTime(duration)
                }
            }
            mainHandler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(9, 10, 13)
        window.navigationBarColor = Color.rgb(9, 10, 13)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        player = ExoPlayer.Builder(this).build()
        buildInterface()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playButton.text = if (isPlaying) "Ⅱ" else "▶"
                if (::overlayPlayButton.isInitialized) overlayPlayButton.text = if (isPlaying) "Ⅱ" else "▶"
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    placeholder.visibility = View.GONE
                    titleView.text = currentVideoName.ifBlank { "Now playing" }
                }
                if (playbackState == Player.STATE_ENDED) {
                    playButton.text = "▶"
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val rotated = videoSize.unappliedRotationDegrees == 90 || videoSize.unappliedRotationDegrees == 270
                val displayWidth = if (rotated) videoSize.height else videoSize.width
                val displayHeight = if (rotated) videoSize.width else videoSize.height
                sourceAspectRatio = displayWidth * videoSize.pixelWidthHeightRatio / displayHeight.coerceAtLeast(1)
                vrSurface.setVideoSize(videoSize.width, videoSize.height, videoSize.unappliedRotationDegrees, videoSize.pixelWidthHeightRatio)
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Toast.makeText(this@MainActivity, "Can't play this video: ${error.errorCodeName}", Toast.LENGTH_LONG).show()
                placeholder.visibility = View.VISIBLE
            }
        })
        mainHandler.post(progressUpdater)
    }

    private var currentVideoName: String = ""

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
        val openButton = pillButton("＋  OPEN VIDEO", true).apply {
            setOnClickListener { chooseVideo() }
        }
        header.addView(openButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        fullscreenChrome.add(header)

        videoFrame = TapDetectFrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            clipToOutline = false
        }
        // Flat playback uses Media3's standard SurfaceView output. This avoids
        // relying on the custom OpenGL external-texture path for ordinary video.
        playerView = LayoutInflater.from(this).inflate(R.layout.video_player, videoFrame, false) as PlayerView
        playerView.player = player
        videoFrame.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Experimental spherical path: custom GL renderer; Flat 2D remains on Media3 SurfaceView.
        vrSurface = VrVideoSurfaceView(this).apply { surfaceEvents = this@MainActivity; visibility = View.GONE }
        videoFrame.addView(vrSurface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val viewBadge = text("FLAT 2D", 10f, Color.WHITE, true).apply {
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = rounded(Color.argb(190, 15, 17, 22), 30)
            elevation = dp(4).toFloat()
        }
        val badgeParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
            setMargins(dp(12), dp(12), 0, 0)
        }
        videoFrame.addView(viewBadge, badgeParams)
        modeBadge = viewBadge

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

        fullscreenControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            background = null
            elevation = 0f
        }
        val rewind = transportButton("↶ 10").apply {
            setOnClickListener { player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L)) }
        }
        overlayPlayButton = transportButton("▶", true).apply {
            setOnClickListener {
                if (player.isPlaying) player.pause() else {
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
                    player.play()
                }
            }
        }
        val forward = transportButton("10 ↷").apply {
            setOnClickListener {
                val end = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: Long.MAX_VALUE
                player.seekTo((player.currentPosition + 10_000L).coerceAtMost(end))
            }
        }
        overlaySeekBar = SeekBar(this).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(255, 79, 94))
            thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar?) { isScrubbing = true }
                override fun onStopTrackingTouch(bar: SeekBar?) {
                    val d = player.duration
                    if (d != C.TIME_UNSET && d > 0L) player.seekTo(progress * d / 1000L)
                    isScrubbing = false
                }
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {}
            })
        }
        val fullscreenSeekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fullscreenCurrentTime = text("00:00", 10f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            includeFontPadding = false
        }
        fullscreenDuration = text("00:00", 10f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        fullscreenSeekRow.addView(fullscreenCurrentTime, LinearLayout.LayoutParams(dp(42), dp(30)))
        fullscreenSeekRow.addView(overlaySeekBar, LinearLayout.LayoutParams(0, dp(30), 1f))
        // Keep the duration label vertically centred with the seek-bar track;
        // Gravity.END here also implies TOP and was pushing it above the row.
        fullscreenSeekRow.addView(fullscreenDuration, LinearLayout.LayoutParams(dp(42), dp(30)))
        fullscreenControls.orientation = LinearLayout.VERTICAL
        fullscreenControls.gravity = Gravity.CENTER_HORIZONTAL
        fullscreenControls.addView(fullscreenSeekRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)))

        val fullscreenTransport = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf(rewind, overlayPlayButton, forward).forEachIndexed { index, control ->
            control.background = rounded(Color.argb(85, 12, 14, 18), 22)
            control.textSize = if (index == 1) 17f else 11f
            fullscreenTransport.addView(control, LinearLayout.LayoutParams(dp(if (index == 1) 42 else 46), dp(34)).apply {
                if (index != 0) leftMargin = dp(8)
            })
        }
        fullscreenControls.addView(fullscreenTransport, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)))
        fullscreenControls.visibility = View.GONE
        videoFrame.addView(fullscreenControls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(dp(14), 0, dp(14), dp(12))
        })

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
        val placeholderParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            setMargins(dp(32), 0, dp(32), 0)
        }
        videoFrame.addView(placeholder, placeholderParams)

        root.addView(videoFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = 0
            bottomMargin = 0
        })

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
        val backButton = transportButton("↶ 10").apply { setOnClickListener { player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L)) } }
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
        // Give both skip controls identical widths and symmetric spacing so their
        // "10" labels sit optically centred on both sides of the play button.
        transport.addView(backButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            leftMargin = dp(6); rightMargin = dp(6)
        })
        transport.addView(playButton, LinearLayout.LayoutParams(dp(62), dp(46)).apply {
            leftMargin = dp(6); rightMargin = dp(6)
        })
        transport.addView(forwardButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            leftMargin = dp(6); rightMargin = dp(6)
        })
        root.addView(transport)
        fullscreenChrome.add(transport)

        val hint = text("DRAG TO LOOK AROUND  •  PINCH TO ZOOM  •  MONO / 180° / 360° SBS", 9f, Color.rgb(119, 125, 136), true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.045f
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
                // Do not reapply the normal 18dp horizontal gutters when Android
                // dispatches fresh insets after immersive mode is entered.
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
    private var stereoLayout = VrVideoSurfaceView.StereoLayout.SBS_LEFT

    private fun stereoChip(label: String, layout: VrVideoSurfaceView.StereoLayout): TextView =
        text(label, 10f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener {
                stereoLayout = layout
                updateModeChips()
                setProjection(currentProjection)
                if (currentProjection != VrVideoSurfaceView.Projection.FLAT) {
                    val projectionLabel = if (currentProjection == VrVideoSurfaceView.Projection.VR180) "180° VIEW" else "360° VIEW"
                    val eyeLabel = when (layout) {
                        VrVideoSurfaceView.StereoLayout.MONO -> "MONO"
                        VrVideoSurfaceView.StereoLayout.SBS_LEFT -> "LEFT EYE"
                        VrVideoSurfaceView.StereoLayout.SBS_RIGHT -> "RIGHT EYE"
                    }
                    modeBadge.text = "$projectionLabel · $eyeLabel"
                }
            }
        }

    private fun modeChip(label: String, projection: VrVideoSurfaceView.Projection): TextView = text(label, 12f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER
        setOnClickListener { setProjection(projection) }
    }

    private fun setProjection(projection: VrVideoSurfaceView.Projection) {
        currentProjection = projection
        if (projection == VrVideoSurfaceView.Projection.FLAT) {
            attachedSurface?.let { if (it.isValid) player.clearVideoSurface(it) }
            playerView.player = player; playerView.visibility = View.VISIBLE; vrSurface.visibility = View.GONE
        } else {
            playerView.player = null; player.clearVideoSurface(); playerView.visibility = View.GONE
            vrSurface.setProjection(projection); vrSurface.setStereoLayout(stereoLayout); vrSurface.resetView()
            vrSurface.visibility = View.VISIBLE; vrSurface.requestRender()
            attachedSurface?.let { if (it.isValid) player.setVideoSurface(it) }
        }
        val projectionLabel = when (projection) {
            VrVideoSurfaceView.Projection.FLAT -> "FLAT 2D"
            VrVideoSurfaceView.Projection.VR180 -> "180° VIEW"
            VrVideoSurfaceView.Projection.VR360 -> "360° VIEW"
        }
        modeBadge.text = if (projection != VrVideoSurfaceView.Projection.FLAT &&
            stereoLayout == VrVideoSurfaceView.StereoLayout.SBS_LEFT) {
            "$projectionLabel · LEFT EYE"
        } else projectionLabel
        updateModeChips()
    }

    override fun onVideoSurfaceAvailable(surface: android.view.Surface) {
        if (attachedSurface !== surface) { attachedSurface?.let { player.clearVideoSurface(it); it.release() }; attachedSurface = surface }
        if (currentProjection != VrVideoSurfaceView.Projection.FLAT && surface.isValid) player.setVideoSurface(surface)
    }
    override fun onVideoSurfaceDestroyed(surface: android.view.Surface) {
        if (attachedSurface === surface) { player.clearVideoSurface(surface); attachedSurface = null }
        surface.release()
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
        if (requestCode != REQUEST_VIDEO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some document providers do not offer persistable permissions.
        }
        currentVideoName = queryDisplayName(uri).ifBlank { "Selected video" }
        // Every newly opened video starts in the requested default viewing mode.
        stereoLayout = VrVideoSurfaceView.StereoLayout.SBS_LEFT
        updateModeChips()
        setProjection(VrVideoSurfaceView.Projection.VR180)
        titleView.text = currentVideoName
        placeholder.visibility = View.VISIBLE
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

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        fullscreenControlsVisible = true
        fullscreenChrome.forEach { it.visibility = if (isFullscreen) View.GONE else View.VISIBLE }
        fullscreenControls.visibility = if (isFullscreen) View.VISIBLE else View.GONE
        // The fullscreen-entry button is only available in normal player mode.
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
            enterImmersiveMode()
        } else {
            params.topMargin = normalVideoMargins[0]
            params.bottomMargin = normalVideoMargins[1]
            params.height = 0
            params.weight = 1f
            rootLayout.setPadding(
                normalRootPadding[0], normalRootPadding[1],
                normalRootPadding[2], normalRootPadding[3]
            )
            fullscreenButton.contentDescription = "Enter full screen"
            exitImmersiveMode()
        }
        videoFrame.layoutParams = params
        if (isFullscreen) {
            (videoFrame as TapDetectFrameLayout).apply {
                onVideoTap = { setFullscreenControlsVisible(!fullscreenControlsVisible) }
                shouldIgnoreTap = { x, y ->
                    fun inside(view: View): Boolean = view.visibility == View.VISIBLE &&
                        x >= view.left && x <= view.right && y >= view.top && y <= view.bottom
                    inside(fullscreenControls)
                }
            }
            setFullscreenControlsVisible(true)
        } else {
            (videoFrame as TapDetectFrameLayout).apply {
                onVideoTap = null
                shouldIgnoreTap = null
            }
            // The translucent overlay controller belongs exclusively to fullscreen.
            // Keep the normal-screen controls and fullscreen-entry button available.
            fullscreenControlsVisible = false
            fullscreenControls.visibility = View.GONE
            fullscreenButton.visibility = View.VISIBLE
            modeBadge.visibility = View.VISIBLE
        }
    }

    private fun setFullscreenControlsVisible(visible: Boolean) {
        fullscreenControlsVisible = visible
        val visibility = if (visible) View.VISIBLE else View.INVISIBLE
        fullscreenControls.visibility = visibility
        modeBadge.visibility = visibility
    }

    @Suppress("DEPRECATION")
    private fun enterImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ normally keeps the app content inside the stable system-bar
            // insets even after the bars are hidden. Disable decor fitting so the
            // video surface can extend behind those insets to the physical display edges.
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
        if (::vrSurface.isInitialized) vrSurface.onResume()
    }

    override fun onPause() {
        if (::vrSurface.isInitialized) vrSurface.onPause()
        if (::player.isInitialized) player.pause()
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(progressUpdater)
        if (::playerView.isInitialized) playerView.player = null
        if (attachedSurface != null) { player.clearVideoSurface(attachedSurface!!); attachedSurface?.release(); attachedSurface = null }
        if (::vrSurface.isInitialized) vrSurface.surfaceEvents = null
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    private fun transportButton(label: String, primary: Boolean = false): TextView = text(label, if (primary) 16f else 11f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER
        background = rounded(if (primary) Color.rgb(255, 79, 94) else Color.rgb(28, 31, 38), 13)
        isClickable = true
        isFocusable = true
    }

    private fun pillButton(label: String, accent: Boolean): TextView = text(label, 11f, Color.WHITE, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(15), 0, dp(15), 0)
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
    }
}
