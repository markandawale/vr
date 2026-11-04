package com.example.panoplay

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import dev.jdtech.mpv.MPVLib

/**
 * Native mpv video output, following mpv-android's SurfaceView lifecycle.
 * Projection and SBS eye selection are done by mpv's GPU shader, not by
 * copying decoded frames through a second OpenGL texture.
 */
class MpvSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    var engine: MPVLib? = null
    var onSurfaceReady: (() -> Unit)? = null
    var onSurfaceLost: (() -> Unit)? = null

    init {
        holder.addCallback(this)
        setBackgroundColor(android.graphics.Color.BLACK)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val mpv = engine ?: return
        try {
            Log.i(TAG, "Attaching native mpv surface")
            mpv.attachSurface(holder.surface)
            mpv.setOptionString("force-window", "yes")
            onSurfaceReady?.invoke()
        } catch (error: Exception) {
            Log.e(TAG, "Unable to attach mpv surface", error)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        try {
            engine?.setPropertyString("android-surface-size", "${width}x${height}")
        } catch (error: Exception) {
            Log.w(TAG, "Unable to update mpv surface size", error)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        onSurfaceLost?.invoke()
        val mpv = engine ?: return
        try {
            // Match mpv-android's order: disable VO before releasing its Surface.
            mpv.setPropertyString("vo", "null")
            mpv.setOptionString("force-window", "no")
            mpv.detachSurface()
            Log.i(TAG, "Detached native mpv surface")
        } catch (error: Exception) {
            Log.w(TAG, "Unable to detach mpv surface cleanly", error)
        }
    }

    companion object {
        private const val TAG = "PanoPlayMpvSurface"
    }
}
