package com.ucsp.sender.capture

import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.ucsp.sender.capture.gl.EglCore
import com.ucsp.sender.capture.gl.OesTextureProgram

/**
 * Renders a single camera stream to two destinations at once (the encoder's input
 * Surface and an optional on-screen preview Surface) via a shared OpenGL texture,
 * instead of asking CameraX for two concurrent camera streams -- which overloaded this
 * project's test device badly enough to reduce both streams to a multi-second-per-frame
 * crawl. CameraX only ever sees one Preview use case, targeting the [Surface] returned
 * by [cameraInputSurface]; this class is the only consumer of the camera frames it
 * carries, fanning each one out to the encoder and (if attached) the preview surface on
 * a dedicated GL thread.
 */
class CameraRenderer {
    companion object {
        private const val TAG = "CameraRenderer"
    }

    private val renderThread = HandlerThread("CameraGlRender").apply { start() }
    private val renderHandler = Handler(renderThread.looper)

    private val eglCore = EglCore()
    private val program = OesTextureProgram()
    private val transformMatrix = FloatArray(16)

    private lateinit var cameraSurfaceTexture: SurfaceTexture
    private lateinit var cameraInputSurfaceValue: Surface

    private var encoderSurface: Surface? = null
    private var encoderEglSurface: EGLSurface? = null
    private var encoderWidth = 0
    private var encoderHeight = 0

    private var previewSurface: Surface? = null
    private var previewEglSurface: EGLSurface? = null
    private var previewWidth = 0
    private var previewHeight = 0

    @Volatile
    private var released = false

    /**
     * Must be called (and its result awaited) before binding CameraX's Preview use case,
     * since Preview needs a real Surface to render into immediately.
     */
    fun start(encoderSurface: Surface, encoderWidth: Int, encoderHeight: Int): Surface {
        val resultHolder = arrayOfNulls<Surface>(1)
        val latch = java.util.concurrent.CountDownLatch(1)

        renderHandler.post {
            try {
                eglCore.start()
                // A tiny 1x1 pbuffer-less current context via the encoder surface itself,
                // just so texture/program creation below has a current GL context.
                encoderEglSurface = eglCore.createWindowSurface(encoderSurface)
                eglCore.makeCurrent(encoderEglSurface!!)
                program.create()

                cameraSurfaceTexture = SurfaceTexture(program.textureId)
                cameraSurfaceTexture.setDefaultBufferSize(encoderWidth, encoderHeight)
                cameraInputSurfaceValue = Surface(cameraSurfaceTexture)

                this.encoderSurface = encoderSurface
                this.encoderWidth = encoderWidth
                this.encoderHeight = encoderHeight

                cameraSurfaceTexture.setOnFrameAvailableListener({ renderHandler.post { drawFrame() } }, renderHandler)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize GL renderer", e)
            } finally {
                resultHolder[0] = if (::cameraInputSurfaceValue.isInitialized) cameraInputSurfaceValue else null
                latch.countDown()
            }
        }

        latch.await()
        return resultHolder[0] ?: error("CameraRenderer failed to initialize")
    }

    /** Attaches or replaces the on-screen preview surface (e.g. once a TextureView becomes available). */
    fun setPreviewSurface(surface: Surface?, width: Int, height: Int) {
        renderHandler.post {
            previewEglSurface?.let { eglCore.releaseSurface(it) }
            previewEglSurface = null
            previewSurface = surface
            previewWidth = width
            previewHeight = height
            if (surface != null) {
                try {
                    previewEglSurface = eglCore.createWindowSurface(surface)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to create preview EGL surface", e)
                }
            }
        }
    }

    private fun drawFrame() {
        if (released) return
        try {
            eglCore.makeCurrent(encoderEglSurface!!)
            cameraSurfaceTexture.updateTexImage()
            cameraSurfaceTexture.getTransformMatrix(transformMatrix)

            eglCore.makeCurrent(encoderEglSurface!!)
            program.draw(transformMatrix, encoderWidth, encoderHeight)
            eglCore.swapBuffers(encoderEglSurface!!)

            val previewEgl = previewEglSurface
            if (previewEgl != null) {
                eglCore.makeCurrent(previewEgl)
                program.draw(transformMatrix, previewWidth, previewHeight)
                eglCore.swapBuffers(previewEgl)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to draw camera frame", e)
        }
    }

    fun release() {
        released = true
        val latch = java.util.concurrent.CountDownLatch(1)
        renderHandler.post {
            try {
                if (::cameraSurfaceTexture.isInitialized) cameraSurfaceTexture.release()
                if (::cameraInputSurfaceValue.isInitialized) cameraInputSurfaceValue.release()
                program.release()
                previewEglSurface?.let { eglCore.releaseSurface(it) }
                encoderEglSurface?.let { eglCore.releaseSurface(it) }
                eglCore.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing GL renderer", e)
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        renderThread.quitSafely()
    }
}
