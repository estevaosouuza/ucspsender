package com.ucsp.sender.capture

import android.content.Context
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
 * Binds CameraX's Preview use case directly to the encoder's input Surface, so camera
 * frames are rendered straight into MediaCodec without a CPU YUV copy through
 * ImageAnalysis. Locks the capture FPS range via Camera2Interop so the encoder gets a
 * steady frame rate to work with.
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    private var cameraProvider: ProcessCameraProvider? = null

    @OptIn(ExperimentalCamera2Interop::class)
    fun start(targetSurface: Surface, width: Int, height: Int, fps: Int) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
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
                request.provideSurface(targetSurface, ContextCompat.getMainExecutor(context)) { }
            }

            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        cameraProvider?.unbindAll()
        cameraProvider = null
    }
}
