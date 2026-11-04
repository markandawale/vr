package com.example.panoplay

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
import androidx.media3.exoplayer.video.spherical.SphericalGLSurfaceView
import androidx.media3.ui.PlayerView
import java.util.Locale

@OptIn(UnstableApi::class)
class MainActivity : Activity() {
    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private lateinit var sphericalPlayerView: PlayerView
    private var sphericalView: SphericalGLSurfaceView? = null
    private lateinit var videoFrame: FrameLayout
    private lateinit var placeholder: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var playButton: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var currentTimeView: TextView
    private lateinit var durationView: TextView
    private lateinit var rotateButton: TextView

    private var isScrubbing = false
    private var sourceAspectRatio = 16f / 9f
    private var currentProjection = VrVideoSurfaceView.Projection.FLAT
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

            override fun onRenderedFirstFrame() {
                if (currentProjection != VrVideoSurfaceView.Projection.FLAT) {
                    val projection = currentProjection
                    val layout = stereoLayout
                    queueSphericalProjection(projection, layout)
                    // SurfaceTexture delivery and the spherical GL draw happen on
                    // different threads. Re-apply after the first metadata frame has
                    // been consumed so its default mesh cannot replace the chosen one.
                    mainHandler.postDelayed({
                        if (currentProjection == projection && stereoLayout == layout) {
                            queueSphericalProjection(projection, layout)
                        }
                    }, 300L)
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                val rotated = videoSize.unappliedRotationDegrees == 90 || videoSize.unappliedRotationDegrees == 270
                val displayWidth = if (rotated) videoSize.height else videoSize.width
                val displayHeight = if (rotated) videoSize.width else videoSize.height
                sourceAspectRatio = displayWidth * videoSize.pixelWidthHeightRatio / displayHeight.coerceAtLeast(1)
                // 360° SBS equirectangular files are normally around 4:1 overall.
                // 2:1 is usually mono 360°, so let users choose SBS manually for 180° sources.
                val detectedStereo = if (sourceAspectRatio >= 3.5f) {
                    VrVideoSurfaceView.StereoLayout.SBS_LEFT
                } else {
                    VrVideoSurfaceView.StereoLayout.MONO
                }
                if (stereoLayout != detectedStereo) {
                    stereoLayout = detectedStereo
                    updateModeChips()
                    if (currentProjection != VrVideoSurfaceView.Projection.FLAT) setProjection(currentProjection)
                }
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

        videoFrame = FrameLayout(this).apply {
            background = rounded(Color.rgb(18, 20, 25), 22)
            clipToOutline = true
        }
        // Flat playback uses Media3's standard SurfaceView output. This avoids
        // relying on the custom OpenGL external-texture path for ordinary video.
        playerView = LayoutInflater.from(this).inflate(R.layout.video_player, videoFrame, false) as PlayerView
        playerView.player = player
        videoFrame.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Use Media3's maintained spherical renderer for 180°/360° playback.
        // It owns the video SurfaceTexture and handles SBS eye cropping.
        sphericalPlayerView = LayoutInflater.from(this).inflate(
            R.layout.spherical_video_player, videoFrame, false
        ) as PlayerView
        sphericalView = sphericalPlayerView.videoSurfaceView as? SphericalGLSurfaceView
        sphericalView?.setUseSensorRotation(false)
        sphericalPlayerView.visibility = View.GONE
        videoFrame.addView(
            sphericalPlayerView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

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
            topMargin = dp(14)
            bottomMargin = dp(14)
        })

        titleView = text("No video selected", 13f, Color.WHITE, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        root.addView(titleView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24)))

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
        seekRow.addView(currentTimeView, LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.WRAP_CONTENT))
        seekRow.addView(seekBar, LinearLayout.LayoutParams(0, dp(30), 1f))
        seekRow.addView(durationView, LinearLayout.LayoutParams(dp(42), ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
        root.addView(seekRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)))

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
        rotateButton = transportButton("ROTATE").apply { setOnClickListener { toggleOrientation() } }
        transport.addView(backButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(6) })
        transport.addView(playButton, LinearLayout.LayoutParams(dp(62), dp(46)).apply { leftMargin = dp(6); rightMargin = dp(6) })
        transport.addView(forwardButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(6); rightMargin = dp(6) })
        transport.addView(rotateButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply { leftMargin = dp(6) })
        root.addView(transport)

        val hint = text("DRAG TO LOOK AROUND  •  PINCH TO ZOOM  •  MONO / 180° / 360° SBS", 9f, Color.rgb(119, 125, 136), true).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.045f
            setPadding(0, dp(6), 0, dp(2))
        }
        root.addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)))

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
            view.setPadding(dp(18), dp(8) + topInset, dp(18), dp(10) + bottomInset)
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
    private var stereoLayout = VrVideoSurfaceView.StereoLayout.MONO

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
            // Keep the working Media3 flat playback path.
            playerView.visibility = View.VISIBLE
            PlayerView.switchTargetView(player, sphericalPlayerView, playerView)
            sphericalPlayerView.visibility = View.GONE
        } else {
            // ExoPlayer's own spherical surface manages GL lifecycle and video output.
            // Keep its built-in stereo mode MONO: our projection mesh below crops the
            // exact source eye, which also supports SBS RIGHT without double-cropping.
            sphericalView?.setDefaultStereoMode(C.STEREO_MODE_MONO)
            sphericalView?.setUseSensorRotation(false)
            sphericalPlayerView.visibility = View.VISIBLE
            PlayerView.switchTargetView(player, playerView, sphericalPlayerView)
            playerView.visibility = View.GONE
            queueSphericalProjection(projection, stereoLayout)
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

    /**
     * Overrides Media3's default full-sphere mesh on its GL thread. Media3 1.11.1
     * keeps Projection/ProjectionRenderer package-private, so reflection is used
     * only for mesh creation; decoder surfaces, frame delivery, touch input and
     * GL lifecycle remain owned by Media3's SphericalGLSurfaceView.
     */
    private fun queueSphericalProjection(
        projection: VrVideoSurfaceView.Projection,
        layout: VrVideoSurfaceView.StereoLayout
    ) {
        val view = sphericalView ?: return
        if (projection == VrVideoSurfaceView.Projection.FLAT) return
        view.queueEvent {
            try {
                val projectionName = "androidx.media3.exoplayer.video.spherical.Projection"
                val projectionClass = Class.forName(projectionName)
                val meshClassName = "$projectionName\$Mesh"
                val subMeshClassName = "$projectionName\$SubMesh"
                val intType = Int::class.javaPrimitiveType!!
                val floatType = Float::class.javaPrimitiveType!!

                val factory = projectionClass.getDeclaredMethod(
                    "createEquirectangular",
                    floatType, intType, intType, floatType, floatType, intType
                ).apply { isAccessible = true }
                val horizontalFov = if (projection == VrVideoSurfaceView.Projection.VR180) 180f else 360f
                val baseProjection = factory.invoke(
                    null, 50f, 36, 72, 180f, horizontalFov, C.STEREO_MODE_MONO
                )
                val sourceMeshField = projectionClass.getField("leftMesh").apply { isAccessible = true }
                val sourceMesh = sourceMeshField.get(baseProjection)
                val meshClass = Class.forName(meshClassName)
                val subMeshClass = Class.forName(subMeshClassName)
                val getSubMesh = meshClass.getMethod("getSubMesh", intType).apply { isAccessible = true }
                val sourceSubMesh = getSubMesh.invoke(sourceMesh, 0)

                val textureCoordsField = subMeshClass.getField("textureCoords").apply { isAccessible = true }
                val textureCoords = (textureCoordsField.get(sourceSubMesh) as FloatArray).clone()
                val cropStart = when (layout) {
                    VrVideoSurfaceView.StereoLayout.MONO,
                    VrVideoSurfaceView.StereoLayout.SBS_LEFT -> 0f
                    VrVideoSurfaceView.StereoLayout.SBS_RIGHT -> 0.5f
                }
                val cropWidth = if (layout == VrVideoSurfaceView.StereoLayout.MONO) 1f else 0.5f
                for (i in textureCoords.indices step 2) {
                    textureCoords[i] = cropStart + textureCoords[i] * cropWidth
                }

                val textureIdField = subMeshClass.getField("textureId").apply { isAccessible = true }
                val verticesField = subMeshClass.getField("vertices").apply { isAccessible = true }
                val modeField = subMeshClass.getField("mode").apply { isAccessible = true }
                val subMeshConstructor = subMeshClass.getConstructor(
                    intType, FloatArray::class.java, FloatArray::class.java, intType
                ).apply { isAccessible = true }
                val selectedSubMesh = subMeshConstructor.newInstance(
                    textureIdField.getInt(sourceSubMesh),
                    verticesField.get(sourceSubMesh) as FloatArray,
                    textureCoords,
                    modeField.getInt(sourceSubMesh)
                )
                val subMeshArray = java.lang.reflect.Array.newInstance(subMeshClass, 1)
                java.lang.reflect.Array.set(subMeshArray, 0, selectedSubMesh)
                val meshConstructor = meshClass.getConstructor(subMeshArray.javaClass).apply { isAccessible = true }
                val selectedMesh = meshConstructor.newInstance(subMeshArray)
                val projectionConstructor = projectionClass.getConstructor(meshClass, intType).apply { isAccessible = true }
                val selectedProjection = projectionConstructor.newInstance(selectedMesh, C.STEREO_MODE_MONO)

                val sceneField = view.javaClass.getDeclaredField("scene").apply { isAccessible = true }
                val scene = sceneField.get(view)
                val rendererField = scene.javaClass.getDeclaredField("projectionRenderer").apply { isAccessible = true }
                val projectionRenderer = rendererField.get(scene)
                val setProjection = projectionRenderer.javaClass
                    .getDeclaredMethod("setProjection", projectionClass).apply { isAccessible = true }
                setProjection.invoke(projectionRenderer, selectedProjection)
            } catch (error: Exception) {
                android.util.Log.e("PanoPlay", "Could not apply spherical projection", error)
                mainHandler.post {
                    Toast.makeText(
                        this@MainActivity,
                        "Panorama setup failed: ${error.javaClass.simpleName}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
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
        if (requestCode != REQUEST_VIDEO || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some document providers do not offer persistable permissions.
        }
        currentVideoName = queryDisplayName(uri).ifBlank { "Selected video" }
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

    private fun toggleOrientation() {
        isLandscape = !isLandscape
        requestedOrientation = if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        rotateButton.text = if (isLandscape) "PORTRAIT" else "ROTATE"
    }

    override fun onResume() {
        super.onResume()
        if (::sphericalPlayerView.isInitialized) sphericalPlayerView.onResume()
    }

    override fun onPause() {
        if (::sphericalPlayerView.isInitialized) sphericalPlayerView.onPause()
        if (::player.isInitialized) player.pause()
        super.onPause()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(progressUpdater)
        if (::playerView.isInitialized) playerView.player = null
        if (::sphericalPlayerView.isInitialized) sphericalPlayerView.player = null
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
