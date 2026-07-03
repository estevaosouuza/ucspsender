package com.ucsp.sender.capture

import android.content.Context
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

/**
 * Binds CameraX's Preview use case directly to the encoder's input Surface -- a single
 * camera stream, no on-screen preview. A second concurrent ImageAnalysis stream (added
 * to render a live preview) was found to overload the camera pipeline on at least one
 * real device: both the on-screen preview and the encoded video degraded to a
 * several-seconds-per-frame slideshow. Until a lower-cost way to show a preview is in
 * place (e.g. a much lower-resolution secondary stream), this stays single-stream only.
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
        encoderSurface: Surface,
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

                Camera2Interop.Extender(previewBuilder)
                    .setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        Range(fps, fps)
                    )

                val preview = previewBuilder.build()
                preview.setSurfaceProvider(ContextCompat.getMainExecutor(context)) { request ->
                    request.provideSurface(encoderSurface, ContextCompat.getMainExecutor(context)) { }
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
}
