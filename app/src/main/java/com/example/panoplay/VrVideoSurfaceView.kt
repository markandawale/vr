package com.example.panoplay

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.os.Handler
import android.os.Looper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** A monoscopic, phone-screen viewer for flat, 180-degree and 360-degree video. */
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
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            queueEvent { renderer.zoom(detector.scaleFactor) }
            requestRender()
            return true
        }
    })

    init {
        setEGLContextClientVersion(2)
        setPreserveEGLContextOnPause(true)
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
        setBackgroundColor(0xFF050608.toInt())
        isClickable = true
    }

    fun setProjection(projection: Projection) {
        queueEvent { renderer.setProjection(projection) }
        requestRender()
    }

    fun setStereoLayout(layout: StereoLayout) {
        queueEvent { renderer.setStereoLayout(layout) }
        requestRender()
    }

    fun setVideoSize(width: Int, height: Int, rotationDegrees: Int = 0, pixelRatio: Float = 1f) {
        if (width <= 0 || height <= 0) return
        queueEvent { renderer.setVideoSize(width, height, rotationDegrees, pixelRatio) }
        requestRender()
    }

    fun resetView() {
        queueEvent { renderer.resetView() }
        requestRender()
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
                    queueEvent { renderer.drag(dx, dy, width.coerceAtLeast(1)) }
                    requestRender()
                }
                previousX = event.x
                previousY = event.y
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    override fun onDetachedFromWindow() {
        // The renderer owns the SurfaceTexture. The player is released by the Activity.
        super.onDetachedFromWindow()
    }

    private inner class VideoRenderer : SurfaceTexture.OnFrameAvailableListener {
        private var oesTextureId = 0
        private var surfaceTexture: SurfaceTexture? = null
        private var outputSurface: Surface? = null
        private var program = 0
        private var positionHandle = 0
        private var texCoordHandle = 0
        private var mvpHandle = 0
        private var textureMatrixHandle = 0
        private var samplerHandle = 0
        private var width = 1
        private var height = 1
        private var videoAspect = 16f / 9f
        private var projection = Projection.FLAT
        private var stereoLayout = StereoLayout.MONO
        private var yaw = 0f
        private var pitch = 0f
        private var fieldOfView = 78f
        private var vertexBuffer: FloatBuffer? = null
        private var texBuffer: FloatBuffer? = null
        private var vertexCount = 0
        private val projectionMatrix = FloatArray(16)
        private val viewMatrix = FloatArray(16)
        private val mvpMatrix = FloatArray(16)
        private val textureMatrix = FloatArray(16)
        private val identityMatrix = FloatArray(16)

        fun onSurfaceCreated() {
            GLES20.glClearColor(0.015f, 0.018f, 0.024f, 1f)
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
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
                mainHandler.post { surfaceEvents?.onVideoSurfaceDestroyed(oldSurface) }
            }
            oldTexture?.release()
            surfaceTexture = SurfaceTexture(oesTextureId).also {
                it.setOnFrameAvailableListener(this)
            }
            outputSurface = Surface(surfaceTexture)
            val created = outputSurface!!
            mainHandler.post { surfaceEvents?.onVideoSurfaceAvailable(created) }
            Matrix.setIdentityM(identityMatrix, 0)
            Matrix.setIdentityM(textureMatrix, 0)
            rebuildMesh()
        }

        fun onSurfaceChanged(width: Int, height: Int) {
            this.width = width.coerceAtLeast(1)
            this.height = height.coerceAtLeast(1)
            GLES20.glViewport(0, 0, this.width, this.height)
        }

        fun onDrawFrame() {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val st = surfaceTexture ?: return
            // Consume the latest decoded frame on every GL draw. Some device/driver
            // combinations coalesce SurfaceTexture callbacks; continuous rendering
            // prevents the viewer from remaining on its initial black frame.
            try {
                st.updateTexImage()
                st.getTransformMatrix(textureMatrix)
            } catch (_: RuntimeException) {
                // No frame is queued yet, or the producer is being recreated.
                // Keep drawing the last valid frame / clear color and try next frame.
            }
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
                val aspect = width.toFloat() / height.toFloat()
                Matrix.perspectiveM(projectionMatrix, 0, fieldOfView, aspect, 0.05f, 50f)
                val yawRadians = Math.toRadians(yaw.toDouble()).toFloat()
                val pitchRadians = Math.toRadians(pitch.toDouble()).toFloat()
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
            this@VrVideoSurfaceView.requestRender()
        }

        fun setProjection(next: Projection) {
            projection = next
            yaw = 0f
            pitch = 0f
            fieldOfView = 78f
            rebuildMesh()
        }

        fun setStereoLayout(next: StereoLayout) {
            stereoLayout = next
            rebuildMesh()
        }

        fun setVideoSize(width: Int, height: Int, rotationDegrees: Int, pixelRatio: Float) {
            val rotated = rotationDegrees == 90 || rotationDegrees == 270
            val displayWidth = if (rotated) height else width
            val displayHeight = if (rotated) width else height
            val aspect = displayWidth * pixelRatio / displayHeight.toFloat()
            if (aspect > 0f && aspect.isFinite()) videoAspect = aspect
            try { surfaceTexture?.setDefaultBufferSize(width, height) } catch (_: RuntimeException) { }
        }

        fun drag(dx: Float, dy: Float, viewWidth: Int) {
            if (projection == Projection.FLAT) return
            val sensitivity = 180f / viewWidth.coerceAtLeast(1)
            yaw += dx * sensitivity
            pitch = (pitch - dy * sensitivity).coerceIn(-82f, 82f)
            if (projection == Projection.VR180) {
                val aspect = width.toFloat() / height.coerceAtLeast(1).toFloat()
                val horizontalHalfFov = Math.toDegrees(kotlin.math.atan(kotlin.math.tan(Math.toRadians(fieldOfView / 2.0)) * aspect)).toFloat()
                val yawLimit = (90f - horizontalHalfFov - 2f).coerceAtLeast(0f)
                yaw = yaw.coerceIn(-yawLimit, yawLimit)
            }
        }

        fun zoom(scaleFactor: Float) {
            if (projection != Projection.FLAT) {
                fieldOfView = (fieldOfView / scaleFactor).coerceIn(45f, 100f)
                if (projection == Projection.VR180) {
                    val aspect = width.toFloat() / height.coerceAtLeast(1).toFloat()
                    val horizontalHalfFov = Math.toDegrees(kotlin.math.atan(kotlin.math.tan(Math.toRadians(fieldOfView / 2.0)) * aspect)).toFloat()
                    val yawLimit = (90f - horizontalHalfFov - 2f).coerceAtLeast(0f)
                    yaw = yaw.coerceIn(-yawLimit, yawLimit)
                }
            }
        }

        fun resetView() {
            yaw = 0f
            pitch = 0f
            fieldOfView = 78f
        }

        private fun rebuildMesh() {
            if (projection == Projection.FLAT) {
                val positions = floatArrayOf(
                    -1f, -1f, 0f,  1f, -1f, 0f, -1f, 1f, 0f,
                    -1f, 1f, 0f,   1f, -1f, 0f,  1f, 1f, 0f
                )
                val tex = floatArrayOf(
                    0f, 1f,  1f, 1f,  0f, 0f,
                    0f, 0f,  1f, 1f,  1f, 0f
                )
                setBuffers(positions, tex)
                return
            }
            val rows = 48
            val columns = 96
            val startLongitude = if (projection == Projection.VR180) -PI / 2.0 else -PI
            val endLongitude = if (projection == Projection.VR180) PI / 2.0 else PI
            val positions = ArrayList<Float>(rows * columns * 18)
            val tex = ArrayList<Float>(rows * columns * 12)

            fun point(row: Int, column: Int): Pair<FloatArray, FloatArray> {
                val latitude = -PI / 2.0 + (row.toDouble() / rows) * PI
                val longitude = startLongitude + (column.toDouble() / columns) * (endLongitude - startLongitude)
                val cosLat = cos(latitude)
                val x = (cosLat * sin(longitude)).toFloat()
                val y = sin(latitude).toFloat()
                val z = (-cosLat * cos(longitude)).toFloat()
                val sourceU = column.toFloat() / columns
                // SBS panoramas place the two eye images in horizontal halves.
                val u = when (stereoLayout) {
                    StereoLayout.MONO -> sourceU
                    StereoLayout.SBS_LEFT -> sourceU * 0.5f
                    StereoLayout.SBS_RIGHT -> 0.5f + sourceU * 0.5f
                }
                val v = (1.0 - (latitude + PI / 2.0) / PI).toFloat()
                return Pair(floatArrayOf(x, y, z), floatArrayOf(u, v))
            }

            for (r in 0 until rows) {
                for (c in 0 until columns) {
                    val p00 = point(r, c)
                    val p10 = point(r, c + 1)
                    val p01 = point(r + 1, c)
                    val p11 = point(r + 1, c + 1)
                    // Two triangles per grid cell. Culling is disabled because the camera is inside the sphere.
                    appendVertex(positions, tex, p00)
                    appendVertex(positions, tex, p10)
                    appendVertex(positions, tex, p01)
                    appendVertex(positions, tex, p01)
                    appendVertex(positions, tex, p10)
                    appendVertex(positions, tex, p11)
                }
            }
            setBuffers(positions.toFloatArray(), tex.toFloatArray())
        }

        private fun appendVertex(positionList: MutableList<Float>, texList: MutableList<Float>, point: Pair<FloatArray, FloatArray>) {
            point.first.forEach { positionList.add(it) }
            point.second.forEach { texList.add(it) }
        }

        private fun setBuffers(positions: FloatArray, texCoords: FloatArray) {
            vertexBuffer = ByteBuffer.allocateDirect(positions.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(positions)
                position(0)
            }
            texBuffer = ByteBuffer.allocateDirect(texCoords.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
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
