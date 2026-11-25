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
import android.media.AudioAttributes as NativeAudioAttributes
import android.media.AudioFormat as NativeAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
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
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import java.io.IOException
import java.nio.ByteBuffer
import java.util.ArrayList
import java.util.IdentityHashMap
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * Android Native MediaExtractor + MediaCodec playback engine for MP4 files
 * with broken/negative sample sizes that stall ExoPlayer's Mp4Extractor.
 */
private class NativeStagefrightEngine(
    private val context: Context,
    private val onReady: (durationMs: Long) -> Unit,
    private val onEnded: () -> Unit,
    private val onError: (Throwable) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var videoExtractor: MediaExtractor? = null
    private var audioExtractor: MediaExtractor? = null
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null

    @Volatile private var isReleased = false
    @Volatile private var isPlayingState = false
    @Volatile private var isPausedByUser = false
    @Volatile private var durationUs = 0L
    @Volatile private var currentPositionUs = 0L
    @Volatile private var speedFactor = 1.0f
    @Volatile private var pendingSeekUs = -1L

    private var currentUri: Uri? = null
    private var currentSurface: Surface? = null
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    fun isPlaying(): Boolean = isPlayingState && !isReleased
    fun getCurrentPositionMs(): Long = (currentPositionUs / 1000L).coerceAtLeast(0L)
    fun getDurationMs(): Long = (durationUs / 1000L).coerceAtLeast(0L)

    fun setPlaybackSpeed(speed: Float) {
        speedFactor = speed.coerceIn(0.25f, 4.0f)
    }

    fun start(uri: Uri, surface: Surface?, startPositionMs: Long) {
        release()
        if (surface == null || !surface.isValid) {
            onError(IOException("Target video surface is not available"))
            return
        }
        isReleased = false
        currentUri = uri
        currentSurface = surface
        currentPositionUs = (startPositionMs.coerceAtLeast(0L)) * 1000L
        isPausedByUser = false
        isPlayingState = true

        videoThread = Thread({
            runVideoLoop(uri, surface, currentPositionUs)
        }, "PanoPlay-NativeVideo").apply {
            isDaemon = true
            start()
        }
    }

    fun play() {
        isPausedByUser = false
        isPlayingState = true
        try {
            audioTrack?.play()
        } catch (_: IllegalStateException) {
        }
    }

    fun pause() {
        isPausedByUser = true
        isPlayingState = false
        try {
            audioTrack?.pause()
        } catch (_: IllegalStateException) {
        }
    }

    fun seekTo(positionMs: Long) {
        val targetUs = positionMs.coerceAtLeast(0L) * 1000L
        currentPositionUs = targetUs
        pendingSeekUs = targetUs
        if (!isPausedByUser) {
            isPlayingState = true
        }
    }

    fun release() {
        isReleased = true
        isPlayingState = false
        pendingSeekUs = -1L

        try {
            videoThread?.interrupt()
            audioThread?.interrupt()
        } catch (_: Exception) {
        }
        videoThread = null
        audioThread = null

        safeReleaseCodecAndExtractor(videoCodec, videoExtractor)
        videoCodec = null
        videoExtractor = null

        safeReleaseCodecAndExtractor(audioCodec, audioExtractor)
        audioCodec = null
        audioExtractor = null

        try {
            audioTrack?.stop()
        } catch (_: Exception) {
        }
        try {
            audioTrack?.release()
        } catch (_: Exception) {
        }
        audioTrack = null
    }

    private fun safeReleaseCodecAndExtractor(codec: MediaCodec?, extractor: MediaExtractor?) {
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        try {
            extractor?.release()
        } catch (_: Exception) {
        }
    }

    private fun runVideoLoop(uri: Uri, surface: Surface, initialPosUs: Long) {
        val vExtractor = MediaExtractor()
        var vCodec: MediaCodec? = null
        var aExtractor: MediaExtractor? = null
        var aCodec: MediaCodec? = null
        var aTrack: AudioTrack? = null

        try {
            vExtractor.setDataSource(context, uri, null)
            var vTrackIndex = -1
            var vFormat: MediaFormat? = null
            var aTrackIndex = -1
            var aFormat: MediaFormat? = null

            for (i in 0 until vExtractor.trackCount) {
                val fmt = vExtractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (vTrackIndex < 0 && mime.startsWith("video/")) {
                    vTrackIndex = i
                    vFormat = fmt
                    if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                        durationUs = maxOf(durationUs, fmt.getLong(MediaFormat.KEY_DURATION))
                    }
                } else if (aTrackIndex < 0 && mime.startsWith("audio/")) {
                    aTrackIndex = i
                    aFormat = fmt
                    if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                        durationUs = maxOf(durationUs, fmt.getLong(MediaFormat.KEY_DURATION))
                    }
                }
            }

            if (vTrackIndex < 0 || vFormat == null) {
                throw IOException("No video track found in media")
            }

            videoExtractor = vExtractor
            vExtractor.selectTrack(vTrackIndex)
            if (initialPosUs > 0L) {
                vExtractor.seekTo(initialPosUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            }

            val vMime = vFormat.getString(MediaFormat.KEY_MIME).orEmpty()
            val decoder = MediaCodec.createDecoderByType(vMime)
            vCodec = decoder
            videoCodec = decoder
            decoder.configure(vFormat, surface, null, 0)
            decoder.start()

            if (aTrackIndex >= 0 && aFormat != null) {
                val audioEx = MediaExtractor().apply {
                    setDataSource(context, uri, null)
                    selectTrack(aTrackIndex)
                    if (initialPosUs > 0L) {
                        seekTo(initialPosUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    }
                }
                audioExtractor = audioEx
                val aMime = aFormat.getString(MediaFormat.KEY_MIME).orEmpty()
                val adec = MediaCodec.createDecoderByType(aMime).apply {
                    configure(aFormat, null, null, 0)
                    start()
                }
                aCodec = adec
                audioCodec = adec

                val sampleRate = if (aFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    aFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                } else {
                    48000
                }
                val channelCount = if (aFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    aFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else {
                    2
                }
                val channelMask = if (channelCount == 1) {
                    NativeAudioFormat.CHANNEL_OUT_MONO
                } else {
                    NativeAudioFormat.CHANNEL_OUT_STEREO
                }
                val minBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    channelMask,
                    NativeAudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(4096)

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        NativeAudioAttributes.Builder()
                            .setUsage(NativeAudioAttributes.USAGE_MEDIA)
                            .setContentType(NativeAudioAttributes.CONTENT_TYPE_MOVIE)
                            .build()
                    )
                    .setAudioFormat(
                        NativeAudioFormat.Builder()
                            .setEncoding(NativeAudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelMask)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 2)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
                aTrack = track
                audioTrack = track
                track.play()

                audioThread = Thread({
                    runAudioLoop(audioEx, adec, track)
                }, "PanoPlay-NativeAudio").apply {
                    isDaemon = true
                    start()
                }
            }

            mainHandler.post {
                if (!isReleased) onReady((durationUs / 1000L).coerceAtLeast(0L))
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var playbackStartWallMs = SystemClock.elapsedRealtime()
            var playbackStartMediaUs = initialPosUs

            while (!isReleased) {
                if (isPausedByUser) {
                    Thread.sleep(40L)
                    playbackStartWallMs = SystemClock.elapsedRealtime()
                    playbackStartMediaUs = currentPositionUs
                    continue
                }

                val seekTarget = pendingSeekUs
                if (seekTarget >= 0L) {
                    pendingSeekUs = -1L
                    try {
                        vExtractor.seekTo(seekTarget, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        decoder.flush()
                        inputDone = false
                        currentPositionUs = seekTarget
                        playbackStartWallMs = SystemClock.elapsedRealtime()
                        playbackStartMediaUs = seekTarget
                    } catch (_: Exception) {
                    }
                }

                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(10_000L)
                    if (inIndex >= 0) {
                        val inBuf: ByteBuffer? = decoder.getInputBuffer(inIndex)
                        val sampleSize = if (inBuf != null) {
                            try {
                                vExtractor.readSampleData(inBuf, 0)
                            } catch (_: Exception) {
                                -1
                            }
                        } else {
                            -1
                        }

                        if (sampleSize < 0) {
                            // Check if extractor simply hit a broken sample header or actual end of stream
                            val nextTimeUs = vExtractor.sampleTime
                            if (nextTimeUs >= 0L && nextTimeUs < durationUs - 500_000L) {
                                decoder.queueInputBuffer(inIndex, 0, 0, currentPositionUs, 0)
                                vExtractor.advance()
                            } else {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            }
                        } else {
                            val ptsUs = vExtractor.sampleTime.coerceAtLeast(0L)
                            val flags = if ((vExtractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_KEY_FRAME) != 0) {
                                MediaCodec.BUFFER_FLAG_KEY_FRAME
                            } else {
                                0
                            }
                            decoder.queueInputBuffer(inIndex, 0, sampleSize, ptsUs, flags)
                            vExtractor.advance()
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000L)
                if (outIndex >= 0) {
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        decoder.releaseOutputBuffer(outIndex, false)
                        break
                    }
                    val ptsUs = bufferInfo.presentationTimeUs.coerceAtLeast(0L)
                    currentPositionUs = ptsUs

                    val elapsedWallUs = ((SystemClock.elapsedRealtime() - playbackStartWallMs) * 1000f * speedFactor).toLong()
                    val expectedMediaUs = playbackStartMediaUs + elapsedWallUs
                    val sleepUs = ptsUs - expectedMediaUs
                    if (sleepUs > 2_000L) {
                        val sleepMs = (sleepUs / 1000L).coerceAtMost(60L)
                        Thread.sleep(sleepMs)
                    } else if (sleepUs < -250_000L) {
                        // Sync anchor after seek/stall
                        playbackStartWallMs = SystemClock.elapsedRealtime()
                        playbackStartMediaUs = ptsUs
                    }

                    decoder.releaseOutputBuffer(outIndex, bufferInfo.size > 0)
                }
            }

            if (!isReleased) {
                isPlayingState = false
                mainHandler.post {
                    if (!isReleased) onEnded()
                }
            }
        } catch (t: Throwable) {
            if (!isReleased && vThreadNotInterrupted(t)) {
                isPlayingState = false
                mainHandler.post {
                    if (!isReleased) onError(t)
                }
            }
        } finally {
            safeReleaseCodecAndExtractor(aCodec, audioEx = aExtractor)
            try {
                aTrack?.stop()
            } catch (_: Exception) {
            }
            try {
                aTrack?.release()
            } catch (_: Exception) {
            }
            safeReleaseCodecAndExtractor(vCodec, vExtractor)
        }
    }

    private fun runAudioLoop(extractor: MediaExtractor, codec: MediaCodec, track: AudioTrack) {
        val info = MediaCodec.BufferInfo()
        var done = false
        try {
            while (!isReleased) {
                if (isPausedByUser) {
                    Thread.sleep(40L)
                    continue
                }
                val seekTarget = pendingSeekUs
                if (seekTarget >= 0L) {
                    try {
                        extractor.seekTo(seekTarget, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                        codec.flush()
                        track.flush()
                        track.play()
                        done = false
                    } catch (_: Exception) {
                    }
                }

                if (!done) {
                    val idx = codec.dequeueInputBuffer(10_000L)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)
                        val size = if (buf != null) {
                            try {
                                extractor.readSampleData(buf, 0)
                            } catch (_: Exception) {
                                -1
                            }
                        } else {
                            -1
                        }
                        if (size < 0) {
                            val nextUs = extractor.sampleTime
                            if (nextUs >= 0L && nextUs < durationUs - 500_000L) {
                                codec.queueInputBuffer(idx, 0, 0, currentPositionUs, 0)
                                extractor.advance()
                            } else {
                                codec.queueInputBuffer(idx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                done = true
                            }
                        } else {
                            val pts = extractor.sampleTime.coerceAtLeast(0L)
                            codec.queueInputBuffer(idx, 0, size, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000L)
                if (outIdx >= 0) {
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        codec.releaseOutputBuffer(outIdx, false)
                        break
                    }
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0) {
                        val pcm = ByteArray(info.size)
                        outBuf.position(info.offset)
                        outBuf.get(pcm, 0, info.size)
                        track.write(pcm, 0, info.size)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun vThreadNotInterrupted(t: Throwable): Boolean =
        t !is InterruptedException && videoThread?.isInterrupted != true
}

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

/**
 * Exact Material vector icons matching reference screenshot
 * for Speedometer, Recenter Crosshair, and Motion Compass pills.
 */
private class PillIconDrawable(
    private val iconType: Type,
    private val tintColor: Int
) : Drawable() {

    enum class Type { SPEED, RECENTER, MOTION }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tintColor
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tintColor
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private val oval = RectF()

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
                strokePaint.strokeWidth = s * 0.11f
                val r = s * 0.30f
                canvas.drawCircle(cx, cy, r, strokePaint)
                canvas.drawCircle(cx, cy, s * 0.11f, fillPaint)

                val tickStart = r + s * 0.02f
                val tickEnd = s * 0.46f
                canvas.drawLine(cx, cy - tickStart, cx, cy - tickEnd, strokePaint)
                canvas.drawLine(cx, cy + tickStart, cx, cy + tickEnd, strokePaint)
                canvas.drawLine(cx - tickStart, cy, cx - tickEnd, cy, strokePaint)
                canvas.drawLine(cx + tickStart, cy, cx + tickEnd, cy, strokePaint)
            }
            Type.MOTION -> {
                val r = s * 0.42f
                path.reset()
                path.fillType = Path.FillType.EVEN_ODD
                path.addCircle(cx, cy, r, Path.Direction.CW)

                val tipDist = r * 0.64f
                val sideDist = r * 0.23f
                val diag = 0.7071f
                val neX = cx + tipDist * diag
                val neY = cy - tipDist * diag
                val swX = cx - tipDist * diag
                val swY = cy + tipDist * diag
                val nwX = cx - sideDist * diag
                val nwY = cy - sideDist * diag
                val seX = cx + sideDist * diag
                val seY = cy + sideDist * diag

                path.moveTo(neX, neY)
                path.lineTo(seX, seY)
                path.lineTo(swX, swY)
                path.lineTo(nwX, nwY)
                path.close()
                canvas.drawPath(path, fillPaint)

                canvas.drawCircle(cx, cy, r * 0.11f, fillPaint)
            }
            Type.SPEED -> {
                val gaugeCy = cy + s * 0.16f
                val outerR = s * 0.40f
                strokePaint.strokeWidth = s * 0.10f
                oval.set(cx - outerR, gaugeCy - outerR, cx + outerR, gaugeCy + outerR)
                canvas.drawArc(oval, 185f, 170f, false, strokePaint)

                path.reset()
                path.fillType = Path.FillType.EVEN_ODD
                val innerR = s * 0.25f
                oval.set(cx - innerR, gaugeCy - innerR, cx + innerR, gaugeCy + innerR)
                path.addArc(oval, 180f, 180f)
                path.lineTo(cx + innerR, gaugeCy + s * 0.06f)
                path.lineTo(cx - innerR, gaugeCy + s * 0.06f)
                path.close()
                canvas.drawPath(path, fillPaint)

                strokePaint.strokeWidth = s * 0.11f
                canvas.drawLine(
                    cx - s * 0.03f,
                    gaugeCy - s * 0.02f,
                    cx + s * 0.23f,
                    gaugeCy - s * 0.28f,
                    strokePaint
                )
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
 * Exact Material / Google Files vector icons for Play, Pause, Rewind, and Forward.
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
                val halfH = h * 0.145f
                val leftX = cx - w * 0.095f
                val rightX = cx + w * 0.155f
                path.reset()
                path.moveTo(leftX, cy - halfH)
                path.lineTo(rightX, cy)
                path.lineTo(leftX, cy + halfH)
                path.close()
                canvas.drawPath(path, paint)
            }
            Type.PAUSE -> {
                val barW = w * 0.056f
                val halfH = h * 0.14f
                val gap = w * 0.044f

                rect.set(cx - gap - barW, cy - halfH, cx - gap, cy + halfH)
                canvas.drawRect(rect, paint)

                rect.set(cx + gap, cy - halfH, cx + gap + barW, cy + halfH)
                canvas.drawRect(rect, paint)
            }
            Type.REWIND -> {
                val halfH = h * 0.34f
                val triW = halfH * 1.32f
                val overlap = triW * 0.22f
                val totalW = triW * 2f - overlap
                val startX = cx - totalW * 0.5f

                val lTipX = startX
                val lBaseX = lTipX + triW
                path.reset()
                path.moveTo(lTipX, cy)
                path.lineTo(lBaseX, cy - halfH)
                path.lineTo(lBaseX, cy + halfH)
                path.close()
                canvas.drawPath(path, paint)

                val rTipX = lBaseX - overlap
                val rBaseX = rTipX + triW
                path.reset()
                path.moveTo(rTipX, cy)
                path.lineTo(rBaseX, cy - halfH)
                path.lineTo(rBaseX, cy + halfH)
                path.close()
                canvas.drawPath(path, paint)
            }
            Type.FORWARD -> {
                val halfH = h * 0.34f
                val triW = halfH * 1.32f
                val overlap = triW * 0.22f
                val totalW = triW * 2f - overlap
                val startX = cx - totalW * 0.5f

                val lBaseX = startX
                val lTipX = lBaseX + triW
                path.reset()
                path.moveTo(lBaseX, cy - halfH)
                path.lineTo(lTipX, cy)
                path.lineTo(lBaseX, cy + halfH)
                path.close()
                canvas.drawPath(path, paint)

                val rBaseX = lTipX - overlap
                val rTipX = rBaseX + triW
                path.reset()
                path.moveTo(rBaseX, cy - halfH)
                path.lineTo(rTipX, cy)
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
        color = Color.rgb(122, 146, 255)
        style = Paint.Style.FILL
    }
    private val thumbGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 122, 146, 255)
        style = Paint.Style.FILL
    }
    private val thumbCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(122, 146, 255)
        style = Paint.Style.FILL
    }
    private val trackRect = RectF()
    private val fillRect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val sidePad = 6.5f * density
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
            canvas.drawCircle(thumbX, centerY, 13f * density, thumbGlowPaint)
        }
        val thumbRadius = (if (isDragging) 8.5f else 6.5f) * density
        canvas.drawCircle(thumbX, centerY, thumbRadius, thumbCorePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val sidePad = 6.5f * density
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
                    } else if (startX > width * 0.85f) {
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

    private var nativeEngine: NativeStagefrightEngine? = null
    private var usingNativeEngine = false

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

    // Fullscreen Controls
    private lateinit var fullscreenTopBar: LinearLayout
    private lateinit var fullscreenCenterControls: LinearLayout
    private lateinit var fullscreenBottomControls: LinearLayout
    private lateinit var overlayPlayButton: ImageView
    private lateinit var overlaySeekBar: CinemaSeekBar
    private lateinit var fullscreenTitleView: TextView
    private lateinit var fullscreenCurrentTime: TextView
    private lateinit var fullscreenDuration: TextView
    private lateinit var rootLayout: LinearLayout
    private lateinit var speedButton: TextView
    private lateinit var overlaySpeedButton: TextView
    private lateinit var overlayRecenterButton: TextView
    private lateinit var overlayMotionButton: TextView
    private lateinit var overlayRotateButton: TextView

    private val playVector by lazy { TransportIconDrawable(TransportIconDrawable.Type.PLAY, Color.rgb(14, 16, 22)) }
    private val pauseVector by lazy { TransportIconDrawable(TransportIconDrawable.Type.PAUSE, Color.rgb(14, 16, 22)) }

    private val fullscreenChrome = mutableListOf<View>()
    private var isFullscreen = false
    private var fullscreenControlsVisible = true
    private var normalRootPadding = intArrayOf(0, 0, 0, 0)
    private var normalVideoMargins = intArrayOf(0, 0)
    private var currentBrightness = -1f
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

    private val audioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private fun isCurrentlyPlaying(): Boolean =
        if (usingNativeEngine) nativeEngine?.isPlaying() == true else player.isPlaying

    private fun getActivePositionMs(): Long =
        if (usingNativeEngine) (nativeEngine?.getCurrentPositionMs() ?: 0L)
        else player.currentPosition.coerceAtLeast(0L)

    private fun getActiveDurationMs(): Long {
        val raw = if (usingNativeEngine) (nativeEngine?.getDurationMs() ?: 0L)
        else player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
        if (raw > 0L) knownTotalDurationMs = raw
        return if (raw > 0L) raw else knownTotalDurationMs
    }

    private fun syncPlayIcons(playing: Boolean) {
        playButton.text = if (playing) "Ⅱ" else "▶"
        if (::overlayPlayButton.isInitialized) {
            overlayPlayButton.setImageDrawable(if (playing) pauseVector else playVector)
        }
        if (!playing && ::fpsBadge.isInitialized) {
            fpsBadge.text = "-- FPS"
        }
    }

    private fun togglePlayPause() {
        if (usingNativeEngine) {
            val ne = nativeEngine ?: return
            if (ne.isPlaying()) {
                ne.pause()
                syncPlayIcons(false)
            } else {
                ne.play()
                syncPlayIcons(true)
            }
            return
        }
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_ENDED) performSafeSeek(0L)
            player.play()
        }
    }

    private val progressUpdater = object : Runnable {
        override fun run() {
            if (::player.isInitialized) {
                val duration = getActiveDurationMs()
                val position = getActivePositionMs()
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
        val playing = isCurrentlyPlaying()
        if (!playing) {
            fpsBadge.text = "-- FPS"
            return
        }
        val now = SystemClock.uptimeMillis()
        var liveFps = if (currentProjection != VrVideoSurfaceView.Projection.FLAT && ::vrSurfaceView.isInitialized) {
            vrSurfaceView.getMeasuredVideoFps()
        } else {
            0
        }

        if (!usingNativeEngine) {
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
            if (liveFps <= 0) {
                val formatFps = player.videoFormat?.frameRate ?: -1f
                if (formatFps > 0f) {
                    liveFps = (formatFps + 0.5f).toInt()
                }
            }
        }

        fpsBadge.text = if (liveFps > 0) "$liveFps FPS" else "-- FPS"
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

        buildInterface()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!usingNativeEngine) {
                    syncPlayIcons(isPlaying)
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
                if (playbackState == Player.STATE_ENDED && !usingNativeEngine) {
                    syncPlayIcons(false)
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

                val exactPos = if (lastRequestedSeekMs >= 0L) {
                    lastRequestedSeekMs
                } else {
                    player.currentPosition.coerceAtLeast(0L)
                }

                // Switch to Android Native MediaExtractor + MediaCodec engine at exact position (0ms skip)
                if (uri != null && consecutiveErrorCount < 2) {
                    consecutiveErrorCount++
                    switchToNativeEngine(uri, exactPos)
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

    private fun switchToNativeEngine(uri: Uri, startPositionMs: Long) {
        val targetSurface = if (currentProjection == VrVideoSurfaceView.Projection.FLAT) {
            playerView.videoSurfaceView?.let { v ->
                when (v) {
                    is SurfaceView -> v.holder.surface
                    else -> null
                }
            }
        } else {
            vrOutputSurface ?: vrSurfaceView.getVideoSurface()
        }

        try {
            player.stop()
            player.clearMediaItems()
        } catch (_: Exception) {
        }

        nativeEngine?.release()
        usingNativeEngine = true
        val engine = NativeStagefrightEngine(
            context = this,
            onReady = { durMs ->
                if (durMs > 0L) knownTotalDurationMs = durMs
                placeholder.visibility = View.GONE
                val resolvedName = currentVideoName.ifBlank { "Now playing" }
                titleView.text = resolvedName
                if (::fullscreenTitleView.isInitialized) fullscreenTitleView.text = resolvedName
                syncPlayIcons(true)
            },
            onEnded = {
                syncPlayIcons(false)
            },
            onError = { throwable ->
                usingNativeEngine = false
                val pbEx = PlaybackException(
                    throwable.message ?: "Native MediaExtractor playback failed",
                    throwable,
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                )
                showFullErrorDialog(pbEx)
            }
        )
        engine.setPlaybackSpeed(currentSpeed)
        nativeEngine = engine
        engine.start(uri, targetSurface, startPositionMs)
    }

    /**
     * Recursively prints EVERY frame of the exception and all nested causes
     * without Android's "... N more" line truncation.
     */
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
            appendLine("Timestamp: ${formatTime(getActivePositionMs())}")
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
            setPadding(dp(18), dp(16), dp(18), dp(14))
            background = rounded(Color.rgb(20, 23, 29), 18)
        }

        val title = text("PLAYBACK ERROR LOG", 14f, Color.rgb(255, 79, 94), true).apply {
            letterSpacing = 0.06f
            setPadding(0, 0, 0, dp(10))
        }
        outer.addView(title)

        val scrollView = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            background = rounded(Color.rgb(13, 15, 20), 12)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val logText = TextView(this).apply {
            text = summary
            textSize = 10.5f
            setTextColor(Color.rgb(230, 234, 242))
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        scrollView.addView(logText)
        val maxScrollHeight = (resources.displayMetrics.heightPixels * 0.52f).toInt()
        outer.addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxScrollHeight))

        val dialog = AlertDialog.Builder(this)
            .setView(outer)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val copyBtn = pillButton("COPY ERROR", false).apply {
            setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("PanoPlay Error", summary))
                Toast.makeText(this@MainActivity, "Full error log copied", Toast.LENGTH_SHORT).show()
            }
        }
        val closeBtn = pillButton("CLOSE", true).apply {
            setOnClickListener { dialog.dismiss() }
        }
        btnRow.addView(copyBtn, LinearLayout.LayoutParams(0, dp(40), 1f).apply { rightMargin = dp(8) })
        btnRow.addView(closeBtn, LinearLayout.LayoutParams(0, dp(40), 1f))
        outer.addView(
            btnRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            }
        )

        dialog.show()
    }

    private fun performSafeSeek(targetMs: Long) {
        val clamped = targetMs.coerceAtLeast(0L)
        lastRequestedSeekMs = clamped
        consecutiveErrorCount = 0

        if (usingNativeEngine) {
            nativeEngine?.seekTo(clamped)
            return
        }

        val state = player.playbackState
        if (state == Player.STATE_IDLE || state == Player.STATE_ENDED || player.playerError != null) {
            val uri = activePlaybackUri ?: return
            mainHandler.postDelayed({
                if (::player.isInitialized && activePlaybackUri == uri) {
                    rebindActiveSurface()
                    player.stop()
                    player.clearMediaItems()
                    player.setMediaItem(MediaItem.fromUri(uri))
                    player.prepare()
                    if (clamped > 0L) {
                        player.seekTo(clamped)
                    }
                    player.playWhenReady = true
                }
            }, 100L)
        } else {
            player.seekTo(clamped)
            if (!player.isPlaying) {
                player.playWhenReady = true
            }
        }
    }

    private fun setPillLeftIcon(view: TextView, icon: PillIconDrawable, tintColor: Int) {
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
            val textColor = if (enabled) Color.rgb(14, 16, 22) else Color.WHITE
            overlayMotionButton.background = rounded(
                if (enabled) Color.rgb(122, 146, 255) else Color.argb(145, 28, 32, 42),
                22
            )
            overlayMotionButton.setTextColor(textColor)
            setPillLeftIcon(
                overlayMotionButton,
                PillIconDrawable(PillIconDrawable.Type.MOTION, textColor),
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
            if (usingNativeEngine) {
                nativeEngine?.setPlaybackSpeed(selected)
            } else {
                player.playbackParameters = PlaybackParameters(selected)
            }
            val label = formatSpeedLabel(selected)
            speedButton.text = label
            if (::overlaySpeedButton.isInitialized) overlaySpeedButton.text = label
            showCenterHud(label, 800L)
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
            performSafeSeek((getActivePositionMs() - 10_000L).coerceAtLeast(0L))
            showCenterHud("-10s", 750L)
        } else if (x > w * 0.72f) {
            val end = getActiveDurationMs().takeIf { it > 0L } ?: Long.MAX_VALUE
            performSafeSeek((getActivePositionMs() + 10_000L).coerceAtMost(end))
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
            setPadding(dp(22), dp(18), dp(22), dp(16))
            background = rounded(Color.rgb(20, 23, 29), 18)
        }

        val dialogTitle = text("PLAYER SETTINGS", 14f, Color.WHITE, true).apply {
            letterSpacing = 0.06f
            setPadding(0, 0, 0, dp(14))
        }
        container.addView(dialogTitle)

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
                    performSafeSeek(getActivePositionMs())
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
            performSafeSeek(getActivePositionMs())
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

        // 1. Fullscreen Top Bar
        fullscreenTopBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(10), dp(10), dp(10))
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
        fullscreenTitleView = text("Now playing", 13.5f, Color.WHITE, true).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }
        fullscreenTopBar.addView(exitFullscreenBtn, LinearLayout.LayoutParams(dp(34), dp(36)).apply {
            rightMargin = dp(2)
        })
        fullscreenTopBar.addView(fullscreenTitleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        videoFrame.addView(
            fullscreenTopBar,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
        )

        // 2. Fullscreen Center Transport Row
        fullscreenCenterControls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            elevation = dp(6).toFloat()
            visibility = View.GONE
        }
        val centerRewind = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setImageDrawable(TransportIconDrawable(TransportIconDrawable.Type.REWIND, Color.WHITE))
            isClickable = true
            isFocusable = true
            contentDescription = "Rewind 10 seconds"
            setOnClickListener { performSafeSeek((getActivePositionMs() - 10_000L).coerceAtLeast(0L)) }
        }
        overlayPlayButton = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setImageDrawable(playVector)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(122, 146, 255))
            }
            isClickable = true
            isFocusable = true
            contentDescription = "Play or pause"
            setOnClickListener { togglePlayPause() }
        }
        val centerForward = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setImageDrawable(TransportIconDrawable(TransportIconDrawable.Type.FORWARD, Color.WHITE))
            isClickable = true
            isFocusable = true
            contentDescription = "Forward 10 seconds"
            setOnClickListener {
                val end = getActiveDurationMs().takeIf { it > 0L } ?: Long.MAX_VALUE
                performSafeSeek((getActivePositionMs() + 10_000L).coerceAtMost(end))
            }
        }
        fullscreenCenterControls.addView(centerRewind, LinearLayout.LayoutParams(dp(26), dp(22)).apply {
            rightMargin = dp(28)
        })
        fullscreenCenterControls.addView(overlayPlayButton, LinearLayout.LayoutParams(dp(64), dp(64)))
        fullscreenCenterControls.addView(centerForward, LinearLayout.LayoutParams(dp(26), dp(22)).apply {
            leftMargin = dp(28)
        })
        videoFrame.addView(
            fullscreenCenterControls,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )

        // 3. Fullscreen Bottom Dock
        fullscreenBottomControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(12), dp(14), dp(12), dp(18))
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
                val d = getActiveDurationMs()
                val previewMs = (value.toLong() * d) / 1000L
                fullscreenCurrentTime.text = formatTime(previewMs)
                showCenterHud(formatTime(previewMs))
            }
            onStopScrub = { value ->
                val d = getActiveDurationMs()
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
        fullscreenCurrentTime = text("00:00", 11.5f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            includeFontPadding = false
        }
        fullscreenDuration = text("00:00", 11.5f, Color.rgb(210, 214, 224), true).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            includeFontPadding = false
        }
        fullscreenSeekRow.addView(fullscreenCurrentTime, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply {
            rightMargin = dp(5)
        })
        fullscreenSeekRow.addView(overlaySeekBar, LinearLayout.LayoutParams(0, dp(34), 1f))
        fullscreenSeekRow.addView(fullscreenDuration, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply {
            leftMargin = dp(5)
        })
        fullscreenBottomControls.addView(
            fullscreenSeekRow,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34))
        )

        val bottomPillsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
        }
        overlaySpeedButton = text("1.0x", 11.5f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(13), dp(8), dp(14), dp(8))
            background = rounded(Color.argb(145, 28, 32, 42), 22)
            isClickable = true
            isFocusable = true
            setPillLeftIcon(this, PillIconDrawable(PillIconDrawable.Type.SPEED, Color.WHITE), Color.WHITE)
            setOnClickListener { showSpeedPicker(this) }
        }
        overlayRecenterButton = text("Recenter", 11.5f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(13), dp(8), dp(14), dp(8))
            background = rounded(Color.argb(145, 28, 32, 42), 22)
            isClickable = true
            isFocusable = true
            setPillLeftIcon(this, PillIconDrawable(PillIconDrawable.Type.RECENTER, Color.WHITE), Color.WHITE)
            setOnClickListener {
                if (currentProjection != VrVideoSurfaceView.Projection.FLAT) {
                    vrSurfaceView.resetView()
                }
            }
        }
        overlayMotionButton = text("Motion", 11.5f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(13), dp(8), dp(14), dp(8))
            background = rounded(Color.argb(145, 28, 32, 42), 22)
            isClickable = true
            isFocusable = true
            setPillLeftIcon(this, PillIconDrawable(PillIconDrawable.Type.MOTION, Color.WHITE), Color.WHITE)
            setOnClickListener { setMotionTrackingEnabled(!isMotionEnabled) }
        }
        overlayRotateButton = text("Landscape", 11.5f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            background = rounded(Color.argb(145, 28, 32, 42), 22)
            isClickable = true
            isFocusable = true
            setOnClickListener { toggleFullscreenOrientation() }
        }

        val bottomPills = listOf(overlaySpeedButton, overlayRecenterButton, overlayMotionButton, overlayRotateButton)
        bottomPills.forEachIndexed { idx, pill ->
            bottomPillsRow.addView(
                pill,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (idx < bottomPills.lastIndex) rightMargin = dp(6)
                }
            )
        }
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
                    val duration = getActiveDurationMs()
                    if (duration > 0L) {
                        performSafeSeek((this@MainActivity.seekBar.progress / 1000f * duration).toLong())
                    }
                    isScrubbing = false
                }
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val duration = getActiveDurationMs()
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
        val backButton = transportButton("-10s").apply {
            setOnClickListener { performSafeSeek((getActivePositionMs() - 10_000L).coerceAtLeast(0L)) }
        }
        playButton = transportButton("▶", true).apply {
            textSize = 20f
            setOnClickListener { togglePlayPause() }
        }
        val forwardButton = transportButton("+10s").apply {
            setOnClickListener {
                val end = getActiveDurationMs().takeIf { it > 0L } ?: Long.MAX_VALUE
                performSafeSeek((getActivePositionMs() + 10_000L).coerceAtMost(end))
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
            if (!usingNativeEngine) {
                playerView.player = player
            }
        } else {
            playerView.player = null
            playerView.visibility = View.GONE
            vrSurfaceView.visibility = View.VISIBLE
            if (!usingNativeEngine) {
                val activeSurface = vrOutputSurface ?: vrSurfaceView.getVideoSurface()
                if (activeSurface != null && activeSurface.isValid) {
                    player.setVideoSurface(activeSurface)
                }
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
        nativeEngine?.release()
        nativeEngine = null
        usingNativeEngine = false
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
            overlayPlayButton.setImageDrawable(if (isCurrentlyPlaying()) pauseVector else playVector)
            (modeBadge.layoutParams as FrameLayout.LayoutParams).topMargin = dp(56)
            (fpsBadge.layoutParams as FrameLayout.LayoutParams).topMargin = dp(56)
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
        if (isMotionEnabled && gyroSensor != null) {
            lastGyroTimestampNs = 0L
            sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onPause() {
        sensorManager.unregisterListener(this)
        if (usingNativeEngine) {
            nativeEngine?.pause()
            syncPlayIcons(false)
        } else {
            if (::player.isInitialized) player.pause()
        }
        if (::vrSurfaceView.isInitialized) vrSurfaceView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        mainHandler.removeCallbacks(progressUpdater)
        mainHandler.removeCallbacks(hideCenterHudRunnable)
        nativeEngine?.release()
        nativeEngine = null
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
