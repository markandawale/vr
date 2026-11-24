package com.example.panoplay

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * High-framerate, VSync-locked monoscopic viewer for Flat, 180° and 360° VR videos.
 * Uses continuous VSync rendering with main-looper frame gating to eliminate black screens and fast-motion judder.
 */
class VrVideoSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : GLSurfaceView(context, attrs), GLSurfaceView.Renderer {

    enum class Projection { FLAT, VR180, VR360 }
    enum class StereoLayout { MONO, SBS_LEFT, SBS_RIGHT }

    interface SurfaceEvents {
        fun onVideoSurfaceAvailable(surface: Surface)
        fun onVideoSurfaceDestroyed(surface: Surface)
    }

    var surfaceEvents: SurfaceEvents? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderer = VideoRenderer()
    private var previousX = 0f
    private var previousY = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                queueEvent { renderer.zoom(factor) }
                return true
            }
        }
    )

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        setPreserveEGLContextOnPause(true)
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
        setBackgroundColor(0xFF050608.toInt())
        isClickable = true
    }

    fun getVideoSurface(): Surface? = renderer.getOutputSurface()

    fun setProjection(projection: Projection) {
        queueEvent { renderer.setProjection(projection) }
    }

    fun setStereoLayout(layout: StereoLayout) {
        queueEvent { renderer.setStereoLayout(layout) }
    }

    fun setVideoSize(width: Int, height: Int, rotationDegrees: Int = 0, pixelRatio: Float = 1f) {
        if (width <= 0 || height <= 0) return
        queueEvent { renderer.setVideoSize(width, height, rotationDegrees, pixelRatio) }
    }

    fun resetView() {
        queueEvent { renderer.resetView() }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        renderer.onSurfaceCreated()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        renderer.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        renderer.onDrawFrame()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                previousX = event.x
                previousY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - previousX
                    val dy = event.y - previousY
                    val viewW = width.coerceAtLeast(1)
                    queueEvent { renderer.drag(dx, dy, viewW) }
                }
                previousX = event.x
                previousY = event.y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    fun release() {
        queueEvent { renderer.release() }
    }

    private inner class VideoRenderer : SurfaceTexture.OnFrameAvailableListener {
        private var oesTextureId = 0
        @Volatile private var surfaceTexture: SurfaceTexture? = null
        @Volatile private var outputSurface: Surface? = null
        private val frameAvailable = AtomicBoolean(false)

        private var program = 0
        private var positionHandle = 0
        private var texCoordHandle = 0
        private var mvpHandle = 0
        private var textureMatrixHandle = 0
        private var samplerHandle = 0

        private var width = 1
        private var height = 1
        private var videoAspect = 16f / 9f
        private var videoWidth = 0
        private var videoHeight = 0
        private var projection = Projection.VR180
        private var stereoLayout = StereoLayout.SBS_LEFT

        private var targetYaw = 0f
        private var targetPitch = 0f
        private var currentYaw = 0f
        private var currentPitch = 0f
        private var fieldOfView = 80f

        private var vertexBuffer: FloatBuffer? = null
        private var texBuffer: FloatBuffer? = null
        private var vertexCount = 0

        private val projectionMatrix = FloatArray(16)
        private val viewMatrix = FloatArray(16)
        private val mvpMatrix = FloatArray(16)
        private val textureMatrix = FloatArray(16)

        fun getOutputSurface(): Surface? = outputSurface

        fun onSurfaceCreated() {
            GLES20.glClearColor(0.015f, 0.018f, 0.024f, 1f)
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_DITHER)

            program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
            positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
            texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
            mvpHandle = GLES20.glGetUniformLocation(program, "uMvpMatrix")
            textureMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")
            samplerHandle = GLES20.glGetUniformLocation(program, "sTexture")

            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            oesTextureId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val oldSurface = outputSurface
            val oldTexture = surfaceTexture
            if (oldSurface != null) {
                mainHandler.post {
                    surfaceEvents?.onVideoSurfaceDestroyed(oldSurface)
                    oldSurface.release()
                }
            }
            oldTexture?.release()

            frameAvailable.set(false)
            val newSt = SurfaceTexture(oesTextureId).apply {
                // Explicitly pass mainHandler because GLThread has no Looper
                setOnFrameAvailableListener(this@VideoRenderer, mainHandler)
                if (videoWidth > 0 && videoHeight > 0) {
                    setDefaultBufferSize(videoWidth, videoHeight)
                }
            }
            surfaceTexture = newSt
            val newSurface = Surface(newSt)
            outputSurface = newSurface
            mainHandler.post { surfaceEvents?.onVideoSurfaceAvailable(newSurface) }

            Matrix.setIdentityM(textureMatrix, 0)
            rebuildMesh()
        }

        fun onSurfaceChanged(width: Int, height: Int) {
            this.width = width.coerceAtLeast(1)
            this.height = height.coerceAtLeast(1)
            GLES20.glViewport(0, 0, this.width, this.height)
        }

        fun onDrawFrame() {
            val st = surfaceTexture ?: return

            if (frameAvailable.compareAndSet(true, false)) {
                try {
                    st.updateTexImage()
                    st.getTransformMatrix(textureMatrix)
                } catch (_: RuntimeException) {
                    // Surface recreated or producer disconnected
                }
            }

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
            GLES20.glUniform1i(samplerHandle, 0)
            GLES20.glUniformMatrix4fv(textureMatrixHandle, 1, false, textureMatrix, 0)

            if (projection == Projection.FLAT) {
                Matrix.setIdentityM(mvpMatrix, 0)
                val surfaceAspect = width.toFloat() / height.toFloat()
                val sx: Float
                val sy: Float
                if (videoAspect > surfaceAspect) {
                    sx = 1f
                    sy = surfaceAspect / videoAspect
                } else {
                    sx = videoAspect / surfaceAspect
                    sy = 1f
                }
                Matrix.scaleM(mvpMatrix, 0, sx, sy, 1f)
            } else {
                currentYaw += (targetYaw - currentYaw) * 0.35f
                currentPitch += (targetPitch - currentPitch) * 0.35f

                val aspect = width.toFloat() / height.toFloat()
                Matrix.perspectiveM(projectionMatrix, 0, fieldOfView, aspect, 0.05f, 50f)
                val yawRadians = Math.toRadians(currentYaw.toDouble()).toFloat()
                val pitchRadians = Math.toRadians(currentPitch.toDouble()).toFloat()
                val lookX = sin(yawRadians) * cos(pitchRadians)
                val lookY = sin(pitchRadians)
                val lookZ = -cos(yawRadians) * cos(pitchRadians)
                Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, 0f, lookX, lookY, lookZ, 0f, 1f, 0f)
                Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
            }

            GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
            val vertices = vertexBuffer ?: return
            val texCoords = texBuffer ?: return
            vertices.position(0)
            texCoords.position(0)
            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, 0, vertices)
            GLES20.glEnableVertexAttribArray(texCoordHandle)
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoords)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)
            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texCoordHandle)
        }

        override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
            frameAvailable.set(true)
        }

        fun setProjection(next: Projection) {
            if (projection == next && vertexBuffer != null) return
            projection = next
            targetYaw = 0f
            targetPitch = 0f
            currentYaw = 0f
            currentPitch = 0f
            fieldOfView = 80f
            rebuildMesh()
        }

        fun setStereoLayout(next: StereoLayout) {
            if (stereoLayout == next && texBuffer != null) return
            stereoLayout = next
            rebuildMesh()
        }

        fun setVideoSize(width: Int, height: Int, rotationDegrees: Int, pixelRatio: Float) {
            videoWidth = width
            videoHeight = height
            val rotated = rotationDegrees == 90 || rotationDegrees == 270
            val displayWidth = if (rotated) height else width
            val displayHeight = if (rotated) width else height
            val aspect = displayWidth * pixelRatio / displayHeight.toFloat()
            if (aspect > 0f && aspect.isFinite()) videoAspect = aspect
            try {
                surfaceTexture?.setDefaultBufferSize(width, height)
            } catch (_: RuntimeException) {
            }
        }

        fun drag(dx: Float, dy: Float, viewWidth: Int) {
            if (projection == Projection.FLAT) return
            val sensitivity = 165f / viewWidth.coerceAtLeast(1)
            targetYaw += dx * sensitivity
            targetPitch = (targetPitch - dy * sensitivity).coerceIn(-82f, 82f)
            clamp180Bounds()
        }

        fun zoom(scaleFactor: Float) {
            if (projection != Projection.FLAT) {
                fieldOfView = (fieldOfView / scaleFactor).coerceIn(45f, 100f)
                clamp180Bounds()
            }
        }

        private fun clamp180Bounds() {
            if (projection == Projection.VR180) {
                val aspect = width.toFloat() / height.coerceAtLeast(1).toFloat()
                val horizontalHalfFov = Math.toDegrees(
                    atan(tan(Math.toRadians(fieldOfView / 2.0)) * aspect)
                ).toFloat()
                val yawLimit = (90f - horizontalHalfFov - 1.5f).coerceAtLeast(0f)
                targetYaw = targetYaw.coerceIn(-yawLimit, yawLimit)
            }
        }

        fun resetView() {
            targetYaw = 0f
            targetPitch = 0f
            currentYaw = 0f
            currentPitch = 0f
            fieldOfView = 80f
        }

        fun release() {
            outputSurface?.release()
            outputSurface = null
            surfaceTexture?.release()
            surfaceTexture = null
            if (oesTextureId != 0) {
                GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
                oesTextureId = 0
            }
            if (program != 0) {
                GLES20.glDeleteProgram(program)
                program = 0
            }
        }

        private fun rebuildMesh() {
            if (projection == Projection.FLAT) {
                val positions = floatArrayOf(
                    -1f, -1f, 0f,  1f, -1f, 0f, -1f, 1f, 0f,
                    -1f, 1f, 0f,   1f, -1f, 0f,  1f, 1f, 0f
                )
                val u0 = when (stereoLayout) {
                    StereoLayout.MONO, StereoLayout.SBS_LEFT -> 0f
                    StereoLayout.SBS_RIGHT -> 0.5f
                }
                val u1 = when (stereoLayout) {
                    StereoLayout.MONO -> 1f
                    StereoLayout.SBS_LEFT -> 0.5f
                    StereoLayout.SBS_RIGHT -> 1f
                }
                val tex = floatArrayOf(
                    u0, 1f,  u1, 1f,  u0, 0f,
                    u0, 0f,  u1, 1f,  u1, 0f
                )
                setBuffers(positions, tex)
                return
            }

            val rows = 36
            val columns = 72
            val startLongitude = if (projection == Projection.VR180) -PI / 2.0 else -PI
            val endLongitude = if (projection == Projection.VR180) PI / 2.0 else PI
            val totalVertices = rows * columns * 6
            val positions = FloatArray(totalVertices * 3)
            val tex = FloatArray(totalVertices * 2)

            var pIdx = 0
            var tIdx = 0

            fun writeVertex(row: Int, column: Int) {
                val latitude = -PI / 2.0 + (row.toDouble() / rows) * PI
                val longitude = startLongitude + (column.toDouble() / columns) * (endLongitude - startLongitude)
                val cosLat = cos(latitude)
                positions[pIdx++] = (cosLat * sin(longitude)).toFloat()
                positions[pIdx++] = sin(latitude).toFloat()
                positions[pIdx++] = (-cosLat * cos(longitude)).toFloat()

                val sourceU = column.toFloat() / columns
                val u = when (stereoLayout) {
                    StereoLayout.MONO -> sourceU
                    StereoLayout.SBS_LEFT -> sourceU * 0.5f
                    StereoLayout.SBS_RIGHT -> 0.5f + sourceU * 0.5f
                }
                val v = (1.0 - (latitude + PI / 2.0) / PI).toFloat()
                tex[tIdx++] = u
                tex[tIdx++] = v
            }

            for (r in 0 until rows) {
                for (c in 0 until columns) {
                    writeVertex(r, c)
                    writeVertex(r, c + 1)
                    writeVertex(r + 1, c)
                    writeVertex(r + 1, c)
                    writeVertex(r, c + 1)
                    writeVertex(r + 1, c + 1)
                }
            }
            setBuffers(positions, tex)
        }

        private fun setBuffers(positions: FloatArray, texCoords: FloatArray) {
            vertexBuffer = ByteBuffer.allocateDirect(positions.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(positions)
                    position(0)
                }
            texBuffer = ByteBuffer.allocateDirect(texCoords.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(texCoords)
                    position(0)
                }
            vertexCount = positions.size / 3
        }

        private fun createProgram(vertexSource: String, fragmentSource: String): Int {
            val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
            val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
            val linked = GLES20.glCreateProgram()
            GLES20.glAttachShader(linked, vertex)
            GLES20.glAttachShader(linked, fragment)
            GLES20.glLinkProgram(linked)
            val status = IntArray(1)
            GLES20.glGetProgramiv(linked, GLES20.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val message = GLES20.glGetProgramInfoLog(linked)
                GLES20.glDeleteProgram(linked)
                throw IllegalStateException("Unable to link video shader: $message")
            }
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            return linked
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val message = GLES20.glGetShaderInfoLog(shader)
                GLES20.glDeleteShader(shader)
                throw IllegalStateException("Unable to compile video shader: $message")
            }
            return shader
        }
    }

    companion object {
        private const val VERTEX_SHADER = """
            uniform mat4 uMvpMatrix;
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = uMvpMatrix * aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """
        private const val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """
    }
}