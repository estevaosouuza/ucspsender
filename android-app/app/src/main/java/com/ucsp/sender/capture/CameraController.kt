package com.ucsp.sender.capture

import android.content.Context
import android.media.Image
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

/**
 * Binds CameraX's Preview use case to a visible [PreviewView] (so the operator can see
 * what's being streamed) and an ImageAnalysis use case that hands YUV_420_888 frames to
 * [onFrame] for encoding -- Preview + ImageAnalysis is one of CameraX's guaranteed
 * supported concurrent use-case combinations on every device.
 *
 * targetRotation is pinned to ROTATION_0 for the analysis stream so the encoded video's
 * orientation stays fixed to the camera sensor's native mounting regardless of how the
 * phone is physically rotated -- only the on-screen preview follows the display rotation.
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    companion object {
        private const val TAG = "CameraController"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    @OptIn(ExperimentalCamera2Interop::class)
    fun start(
        previewView: PreviewView,
        width: Int,
        height: Int,
        fps: Int,
        onFatalError: (Throwable) -> Unit = {},
        onFrame: (image: Image, presentationTimeUs: Long) -> Unit
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                val preview = Preview.Builder()
                    .setTargetResolution(Size(width, height))
                    .build()
                preview.setSurfaceProvider(previewView.surfaceProvider)

                val analysisBuilder = ImageAnalysis.Builder()
                    .setTargetResolution(Size(width, height))
                    .setTargetRotation(Surface.ROTATION_0)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)

                Camera2Interop.Extender(analysisBuilder)
                    .setCaptureRequestOption(
                        android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        Range(fps, fps)
                    )

                val analysis = analysisBuilder.build()
                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    try {
                        val image = imageProxy.image
                        if (image != null) {
                            val presentationTimeUs = imageProxy.imageInfo.timestamp / 1000
                            onFrame(image, presentationTimeUs)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Dropping camera frame: analyzer failed", e)
                    } finally {
                        imageProxy.close()
                    }
                }

                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
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
