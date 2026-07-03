package com.ucsp.sender.capture

import android.content.Context
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

/**
 * Binds a single CameraX Preview use case -- CameraX only ever sees one camera stream,
 * targeting the Surface [CameraRenderer] hands back (a SurfaceTexture-backed GL input).
 * The renderer is what fans that single stream out to both the encoder and the
 * on-screen preview; see [CameraRenderer] for why this is one stream, not two.
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    companion object {
        private const val TAG = "CameraController"
    }

    private var cameraProvider: ProcessCameraProvider? = null

    @OptIn(ExperimentalCamera2Interop::class)
    fun start(
        cameraFacingSurface: Surface,
        width: Int,
        height: Int,
        fps: Int,
        onFatalError: (Throwable) -> Unit = {}
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                val previewBuilder = Preview.Builder()
                    .setTargetResolution(Size(width, height))
                    .setTargetRotation(currentDisplayRotation())

                Camera2Interop.Extender(previewBuilder)
                    .setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        Range(fps, fps)
                    )

                val preview = previewBuilder.build()
                preview.setSurfaceProvider(ContextCompat.getMainExecutor(context)) { request ->
                    request.provideSurface(cameraFacingSurface, ContextCompat.getMainExecutor(context)) { }
                }

                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start camera", e)
                onFatalError(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        cameraProvider?.unbindAll()
        cameraProvider = null
    }

    /**
     * Preview.Builder() has no attached View to infer orientation from here (it renders
     * into CameraRenderer's SurfaceTexture, not a PreviewView), so left unset it falls
     * back to a guess that doesn't reliably match how the phone is actually held --
     * that mismatch is what showed up as an upside-down image. Querying the real
     * current display rotation and passing it explicitly is what a PreviewView would
     * have done internally.
     */
    @Suppress("DEPRECATION")
    private fun currentDisplayRotation(): Int {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return windowManager.defaultDisplay.rotation
    }
}
