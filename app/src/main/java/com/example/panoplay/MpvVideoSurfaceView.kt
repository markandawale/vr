package com.example.panoplay

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.jdtech.mpv.MPVLib

/**
 * Direct SurfaceView output for the experimental libmpv engine.
 * This intentionally avoids the failed LibVLC SurfaceTexture/OpenGL bridge.
 */
class MpvVideoSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private var mpv: MPVLib? = null
    private var surfaceAttached = false
    private var pendingFile: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    var onFileLoaded: (() -> Unit)? = null
    private var eventObserver: MPVLib.EventObserver? = null

    init {
        holder.addCallback(this)
        setBackgroundColor(android.graphics.Color.BLACK)
    }

    fun initialize() {
        if (mpv != null) return
        val instance = MPVLib.create(context.applicationContext)
            ?: throw IllegalStateException("libmpv native initialization failed")
        try {
            instance.setOptionString("config", "no")
            instance.setOptionString("terminal", "no")
            instance.setOptionString("vo", "gpu")
            instance.setOptionString("gpu-context", "android")
            instance.setOptionString("hwdec", "auto-safe")
            instance.setOptionString("audio-display", "no")
            instance.setOptionString("keep-open", "yes")
            instance.setOptionString("osd-level", "0")
            eventObserver = object : MPVLib.EventObserver {
                override fun eventProperty(property: String) {}
                override fun eventProperty(property: String, value: Long) {}
                override fun eventProperty(property: String, value: Double) {}
                override fun eventProperty(property: String, value: Boolean) {}
                override fun eventProperty(property: String, value: String) {}
                override fun endFile(reason: Int, error: Int) {}
                override fun event(eventId: Int) {
                    if (eventId == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED) {
                        mainHandler.post { onFileLoaded?.invoke() }
                    }
                }
            }
            instance.addObserver(eventObserver!!)
            instance.init()
            mpv = instance
            if (holder.surface.isValid) attachSurface(holder)
        } catch (error: Exception) {
            instance.destroy()
            throw error
        }
    }

    fun playFile(path: String) {
        pendingFile = path
        val instance = mpv ?: run {
            initialize()
            mpv
        } ?: return
        if (surfaceAttached) loadPendingFile(instance)
    }

    private fun loadPendingFile(instance: MPVLib) {
        val path = pendingFile ?: return
        pendingFile = null
        instance.command(arrayOf("loadfile", path, "replace"))
    }

    private fun attachSurface(holder: SurfaceHolder) {
        val instance = mpv ?: return
        if (surfaceAttached || !holder.surface.isValid) return
        try {
            instance.attachSurface(holder.surface)
            instance.setOptionString("force-window", "yes")
            surfaceAttached = true
            loadPendingFile(instance)
        } catch (error: Exception) {
            Log.e("PanoPlay", "libmpv surface attach failed", error)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        attachSurface(holder)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        try {
            mpv?.setPropertyString("android-surface-size", "${width}x${height}")
        } catch (error: Exception) {
            Log.w("PanoPlay", "Could not update libmpv surface size", error)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val instance = mpv ?: return
        try {
            instance.setPropertyString("vo", "null")
            instance.setOptionString("force-window", "no")
            instance.detachSurface()
        } catch (error: Exception) {
            Log.w("PanoPlay", "libmpv surface detach failed", error)
        }
        surfaceAttached = false
    }

    fun isPlaying(): Boolean = try {
        mpv?.getPropertyBoolean("pause") == false
    } catch (_: Exception) { false }

    fun positionMs(): Long = try {
        ((mpv?.getPropertyDouble("time-pos") ?: 0.0) * 1000.0).toLong().coerceAtLeast(0L)
    } catch (_: Exception) { 0L }

    fun durationMs(): Long = try {
        ((mpv?.getPropertyDouble("duration") ?: 0.0) * 1000.0).toLong().coerceAtLeast(0L)
    } catch (_: Exception) { 0L }

    fun seekTo(positionMs: Long) {
        mpv?.command(arrayOf("seek", (positionMs.coerceAtLeast(0L) / 1000.0).toString(), "absolute"))
    }

    fun play() {
        mpv?.setPropertyBoolean("pause", false)
    }

    fun pause() {
        mpv?.setPropertyBoolean("pause", true)
    }

    fun release() {
        holder.removeCallback(this)
        val instance = mpv ?: return
        try {
            if (surfaceAttached) instance.detachSurface()
        } catch (_: Exception) {}
        surfaceAttached = false
        eventObserver?.let { instance.removeObserver(it) }
        eventObserver = null
        mpv = null
        instance.destroy()
    }
}
