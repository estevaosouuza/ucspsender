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
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

/**
 * Binds CameraX's Preview use case directly to the encoder's input Surface (the
 * proven-reliable path -- CameraX renders straight into MediaCodec, no CPU copy) as the
 * primary, full-resolution stream. A second, much lower-resolution Preview is bound
 * alongside for the on-screen [PreviewView] -- an earlier attempt ran both streams at
 * the *same* (encode) resolution and overloaded at least one real device's camera
 * pipeline badly enough to stall both, so this one deliberately requests a small fixed
 * size for the display stream regardless of encode resolution to keep its marginal cost
 * low.
 *
 * Using a second real Preview (rendered by PreviewView itself, GPU-composited) instead
 * of manually converting ImageAnalysis frames to a Bitmap is what gives an actual smooth
 * video preview instead of a hand-drawn frame sequence -- PreviewView also handles
 * orientation for its own display automatically, so it doesn't touch the encoder
 * stream's fixed, unrotated orientation at all.
 *
 * targetRotation is left at CameraX's default (effectively unrotated, since nothing here
 * is tied to a display/View) on the encoder stream, so the encoded video stays a fixed,
 * stable orientation/aspect regardless of how the phone is physically held -- matches
 * this app's use case (a camera mounted or held for a steady 16:9 feed to OBS).
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    companion object {
        private const val TAG = "CameraController"
        private val PREVIEW_DISPLAY_SIZE = Size(320, 240)
    }

    private var cameraProvider: ProcessCameraProvider? = null

    @OptIn(ExperimentalCamera2Interop::class)
    fun start(
        previewView: PreviewView,
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

                val encoderPreviewBuilder = Preview.Builder()
                    .setTargetResolution(Size(width, height))

                Camera2Interop.Extender(encoderPreviewBuilder)
                    .setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        Range(fps, fps)
                    )

                val encoderPreview = encoderPreviewBuilder.build()
                encoderPreview.setSurfaceProvider(ContextCompat.getMainExecutor(context)) { request ->
                    request.provideSurface(encoderSurface, ContextCompat.getMainExecutor(context)) { }
                }

                val displayPreview = Preview.Builder()
                    .setTargetResolution(PREVIEW_DISPLAY_SIZE)
                    .build()
                displayPreview.setSurfaceProvider(previewView.surfaceProvider)

                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, encoderPreview, displayPreview)
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
