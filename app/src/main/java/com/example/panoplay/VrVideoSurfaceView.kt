package com.example.panoplay

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

class VrVideoSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    enum class Projection { FLAT, VR180, VR360 }
    enum class StereoLayout { MONO, SBS_LEFT, SBS_RIGHT }

    interface SurfaceEvents {
        fun onVideoSurfaceAvailable(surface: Surface)
        fun onVideoSurfaceDestroyed(surface: Surface)
    }

    var surfaceEvents: SurfaceEvents? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var glThread: HandlerThread? = null
    private var glHandler: Handler? = null
    private val renderEngine = EglRenderEngine()

    private var previousX = 0f
    private var previousY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private var wasPinching = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                wasPinching = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                wasPinching = true
                val factor = detector.scaleFactor
                if (factor.isFinite() && factor > 0.2f && factor < 5.0f) {
                    postGl { renderEngine.zoom(factor) }
                }
                return true
            }
        }
    )

    init {
        holder.addCallback(this)
        isClickable = true
    }

    private fun ensureGlThread(): Handler {
        var handler = glHandler
        if (handler == null || glThread?.isAlive != true) {
            val thread = HandlerThread("PanoPlay-GL-VSync", android.os.Process.THREAD_PRIORITY_DISPLAY).apply {
                start()
            }
            glThread = thread
            handler = Handler(thread.looper)
            glHandler = handler
        }
        return handler
    }

    private fun postGl(block: () -> Unit) {
        ensureGlThread().post(block)
    }

    fun getVideoSurface(): Surface? = renderEngine.getDecoderSurface()

    fun getMeasuredVideoFps(): Int = renderEngine.getCurrentFps()

    fun setProjection(projection: Projection) {
        postGl { renderEngine.setProjection(projection) }
    }

    fun setStereoLayout(layout: StereoLayout) {
        postGl { renderEngine.setStereoLayout(layout) }
    }

    fun setVideoSize(width: Int, height: Int, rotationDegrees: Int = 0, pixelRatio: Float = 1f) {
        if (width <= 0 || height <= 0) return
        postGl { renderEngine.setVideoSize(width, height, rotationDegrees, pixelRatio) }
    }

    fun resetView() {
        postGl { renderEngine.resetView() }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val displaySurface = holder.surface
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                displaySurface.setFrameRate(
                    60f,
                    Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                    Surface.CHANGE_FRAME_RATE_ALWAYS
                )
            } catch (_: Throwable) {
            }
        }
        postGl {
            renderEngine.initEglAndSurface(displaySurface)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        postGl {
            renderEngine.onViewportChanged(width, height)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        postGl {
            renderEngine.destroyEglAndSurface()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                wasPinching = false
                activePointerId = event.getPointerId(0)
                previousX = event.x
                previousY = event.y
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Second finger touched down: lock out single-finger panning for this gesture
                wasPinching = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // Never pan if pinching occurred or more than 1 finger is on screen
                if (wasPinching || scaleDetector.isInProgress || event.pointerCount > 1) {
                    return true
                }
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex != -1) {
                    val x = event.getX(pointerIndex)
                    val y = event.getY(pointerIndex)
                    val dx = x - previousX
                    val dy = y - previousY
                    previousX = x
                    previousY = y
                    val viewW = width.coerceAtLeast(1)
                    postGl { renderEngine.drag(dx, dy, viewW) }
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Sync remaining finger's position so lifting one finger never causes a jump
                val liftedIndex = event.actionIndex
                val liftedId = event.getPointerId(liftedIndex)
                val newIndex = if (liftedIndex == 0) 1 else 0
                if (newIndex < event.pointerCount) {
                    activePointerId = event.getPointerId(newIndex)
                    previousX = event.getX(newIndex)
                    previousY = event.getY(newIndex)
                } else if (liftedId == activePointerId) {
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activePointerId = MotionEvent.INVALID_POINTER_ID
                wasPinching = false
                return true
            }
        }
        return true
    }

    fun onResume() {
        postGl { renderEngine.setActive(true) }
    }

    fun onPause() {
        postGl { renderEngine.setActive(false) }
    }

    fun release() {
        postGl {
            renderEngine.destroyEglAndSurface()
        }
        glThread?.quitSafely()
        glThread = null
        glHandler = null
    }

    private inner class EglRenderEngine : Choreographer.FrameCallback, SurfaceTexture.OnFrameAvailableListener {
        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var eglConfig: EGLConfig? = null

        private var oesTextureId = 0
        @Volatile private var decoderSurfaceTexture: SurfaceTexture? = null
        @Volatile private var decoderSurface: Surface? = null

        private val hasNewVideoFrame = AtomicBoolean(false)
        private val frameCounter = AtomicInteger(0)
        @Volatile private var measuredFps = 0
        private var fpsWindowStartMs = 0L

        private var isRunning = false
        private var isActive = true

        private var program = 0
        private var positionHandle = 0
        private var texCoordHandle = 0
        private var mvpHandle = 0
        private var textureMatrixHandle = 0
        private var samplerHandle = 0

        private var viewportWidth = 1
        private var viewportHeight = 1
        private var videoAspect = 16f / 9f
        private var videoWidth = 0
        private var videoHeight = 0

        private var projection = Projection.VR180
        private var stereoLayout = StereoLayout.SBS_LEFT

        private var targetYaw = 0f
        private var targetPitch = 0f
        private var currentYaw = 0f
        private var currentPitch = 0f
        private var fieldOfView = DEFAULT_FOV

        private var vertexBuffer: FloatBuffer? = null
        private var texBuffer: FloatBuffer? = null
        private var vertexCount = 0

        private val projectionMatrix = FloatArray(16)
        private val viewMatrix = FloatArray(16)
        private val mvpMatrix = FloatArray(16)
        private val textureMatrix = FloatArray(16)

        private var firstFrameDrawn = false

        fun getDecoderSurface(): Surface? = decoderSurface

        fun getCurrentFps(): Int {
            val now = SystemClock.uptimeMillis()
            if (fpsWindowStartMs > 0L && (now - fpsWindowStartMs) > 1500L) {
                measuredFps = 0
            }
            return measuredFps
        }

        fun setActive(active: Boolean) {
            isActive = active
            if (active && isRunning && eglSurface != EGL14.EGL_NO_SURFACE) {
                Choreographer.getInstance().removeFrameCallback(this)
                Choreographer.getInstance().postFrameCallback(this)
            }
        }

        fun initEglAndSurface(displaySurface: Surface) {
            destroyEglAndSurface()

            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            EGL14.eglInitialize(eglDisplay, version, 0, version, 1)

            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 0,
                EGL14.EGL_STENCIL_SIZE, 0,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
            eglConfig = configs[0]

            val contextAttribs = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            )
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)

            val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, displaySurface, surfaceAttribs, 0)

            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            EGL14.eglSwapInterval(eglDisplay, 1)

            setupGlPipeline()

            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            oesTextureId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            hasNewVideoFrame.set(false)
            firstFrameDrawn = false
            frameCounter.set(0)
            measuredFps = 0
            fpsWindowStartMs = SystemClock.uptimeMillis()

            val handler = glHandler ?: mainHandler
            val st = SurfaceTexture(oesTextureId).apply {
                setOnFrameAvailableListener(this@EglRenderEngine, handler)
                if (videoWidth > 0 && videoHeight > 0) {
                    setDefaultBufferSize(videoWidth, videoHeight)
                }
            }
            decoderSurfaceTexture = st
            val decSurf = Surface(st)
            decoderSurface = decSurf

            Matrix.setIdentityM(textureMatrix, 0)
            rebuildMesh()

            isRunning = true
            mainHandler.post {
                surfaceEvents?.onVideoSurfaceAvailable(decSurf)
            }

            Choreographer.getInstance().removeFrameCallback(this)
            Choreographer.getInstance().postFrameCallback(this)
        }

        fun onViewportChanged(width: Int, height: Int) {
            viewportWidth = width.coerceAtLeast(1)
            viewportHeight = height.coerceAtLeast(1)
            if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
                firstFrameDrawn = false
            }
        }

        override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
            hasNewVideoFrame.set(true)
            val count = frameCounter.incrementAndGet()
            val now = SystemClock.uptimeMillis()
            if (fpsWindowStartMs == 0L) {
                fpsWindowStartMs = now
            } else {
                val elapsed = now - fpsWindowStartMs
                if (elapsed >= 1000L) {
                    measuredFps = ((count * 1000f) / elapsed + 0.5f).toInt()
                    frameCounter.set(0)
                    fpsWindowStartMs = now
                }
            }
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (!isRunning || !isActive) return
            Choreographer.getInstance().postFrameCallback(this)

            if (eglDisplay == EGL14.EGL_NO_DISPLAY || eglSurface == EGL14.EGL_NO_SURFACE) return
            val st = decoderSurfaceTexture ?: return

            var updatedFrame = false
            if (hasNewVideoFrame.compareAndSet(true, false)) {
                try {
                    st.updateTexImage()
                    st.getTransformMatrix(textureMatrix)
                    updatedFrame = true
                    firstFrameDrawn = true
                } catch (_: RuntimeException) {
                }
            }

            val cameraMoving = abs(targetYaw - currentYaw) > 0.002f ||
                abs(targetPitch - currentPitch) > 0.002f

            if (!updatedFrame && !cameraMoving && firstFrameDrawn) {
                return
            }

            drawScene()
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }

        private fun drawScene() {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
            GLES20.glUniform1i(samplerHandle, 0)
            GLES20.glUniformMatrix4fv(textureMatrixHandle, 1, false, textureMatrix, 0)

            if (projection == Projection.FLAT) {
                Matrix.setIdentityM(mvpMatrix, 0)
                val surfaceAspect = viewportWidth.toFloat() / viewportHeight.toFloat()
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
                currentYaw += (targetYaw - currentYaw) * 0.18f
                currentPitch += (targetPitch - currentPitch) * 0.18f

                val aspect = viewportWidth.toFloat() / viewportHeight.toFloat()
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

        fun destroyEglAndSurface() {
            isRunning = false
            Choreographer.getInstance().removeFrameCallback(this)

            val oldSurf = decoderSurface
            decoderSurface = null
            if (oldSurf != null) {
                mainHandler.post {
                    surfaceEvents?.onVideoSurfaceDestroyed(oldSurf)
                    oldSurf.release()
                }
            }
            decoderSurfaceTexture?.release()
            decoderSurfaceTexture = null

            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (oesTextureId != 0) {
                    GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
                    oesTextureId = 0
                }
                if (program != 0) {
                    GLES20.glDeleteProgram(program)
                    program = 0
                }
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    eglSurface = EGL14.EGL_NO_SURFACE
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext)
                    eglContext = EGL14.EGL_NO_CONTEXT
                }
                EGL14.eglTerminate(eglDisplay)
                eglDisplay = EGL14.EGL_NO_DISPLAY
            }
        }

        fun setProjection(next: Projection) {
            if (projection == next && vertexBuffer != null) return
            projection = next
            targetYaw = 0f
            targetPitch = 0f
            currentYaw = 0f
            currentPitch = 0f
            fieldOfView = DEFAULT_FOV
            firstFrameDrawn = false
            rebuildMesh()
        }

        fun setStereoLayout(next: StereoLayout) {
            if (stereoLayout == next && texBuffer != null) return
            stereoLayout = next
            firstFrameDrawn = false
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
                decoderSurfaceTexture?.setDefaultBufferSize(width, height)
            } catch (_: RuntimeException) {
            }
        }

        fun drag(dx: Float, dy: Float, viewWidth: Int) {
            if (projection == Projection.FLAT) return
            val zoomScale = (fieldOfView / DEFAULT_FOV).coerceIn(0.45f, 1.25f)
            val sensitivity = (95f / viewWidth.coerceAtLeast(1)) * zoomScale
            targetYaw -= dx * sensitivity
            targetPitch = (targetPitch + dy * sensitivity).coerceIn(-82f, 82f)
            clamp180Bounds()
        }

        fun zoom(scaleFactor: Float) {
            if (projection != Projection.FLAT) {
                fieldOfView = (fieldOfView / scaleFactor).coerceIn(45f, 108f)
                clamp180Bounds()
                firstFrameDrawn = false
            }
        }

        private fun clamp180Bounds() {
            if (projection == Projection.VR180) {
                val aspect = viewportWidth.toFloat() / viewportHeight.coerceAtLeast(1).toFloat()
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
            fieldOfView = DEFAULT_FOV
            firstFrameDrawn = false
        }

        private fun setupGlPipeline() {
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
        }

        private fun rebuildMesh() {
            val u0 = when (stereoLayout) {
                StereoLayout.SBS_RIGHT -> 0.5f
                else -> 0f
            }
            val u1 = when (stereoLayout) {
                StereoLayout.SBS_LEFT -> 0.5f
                else -> 1f
            }

            if (projection == Projection.FLAT) {
                val positions = floatArrayOf(
                    -1f, -1f, 0f,  1f, -1f, 0f, -1f, 1f, 0f,
                    -1f, 1f, 0f,   1f, -1f, 0f,  1f, 1f, 0f
                )
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
                val v = ((latitude + PI / 2.0) / PI).toFloat()
                tex[tIdx++] = u0 + sourceU * (u1 - u0)
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
        private const val DEFAULT_FOV = 92f
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
