package com.ucsp.sender.capture

import android.content.Context
import android.graphics.Bitmap
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Binds CameraX's Preview use case directly to the encoder's input Surface (the
 * proven-reliable path -- CameraX renders straight into MediaCodec, no CPU copy) as the
 * only full-resolution stream. A second, much lower-resolution ImageAnalysis stream is
 * bound alongside purely to render a small on-screen preview -- an earlier attempt that
 * ran both streams at the *same* (encode) resolution overloaded at least one real
 * device's camera pipeline badly enough to stall both streams, so this one deliberately
 * requests a small fixed size for the analysis stream regardless of encode resolution to
 * keep its marginal cost low.
 *
 * targetRotation is pinned to ROTATION_0 on both streams so the encoded video and the
 * preview share a fixed orientation regardless of how the phone is physically rotated.
 */
class CameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    companion object {
        private const val TAG = "CameraController"
        private val PREVIEW_ANALYSIS_SIZE = Size(320, 240)
        private const val PREVIEW_SAMPLE_STEP = 2 // further downsample within the already-small analysis frame
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    @OptIn(ExperimentalCamera2Interop::class)
    fun start(
        encoderSurface: Surface,
        width: Int,
        height: Int,
        fps: Int,
        onFatalError: (Throwable) -> Unit = {},
        onPreviewFrame: (Bitmap) -> Unit = {}
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

                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(PREVIEW_ANALYSIS_SIZE)
                    .setTargetRotation(Surface.ROTATION_0)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()

                analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    try {
                        val image = imageProxy.image
                        if (image != null) onPreviewFrame(yuvToPreviewBitmap(image))
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to render preview frame", e)
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

    /** Cheap, heavily downsampled YUV_420_888 -> ARGB_8888 conversion, just for a viewfinder-quality preview. */
    private fun yuvToPreviewBitmap(image: Image): Bitmap {
        val outW = image.width / PREVIEW_SAMPLE_STEP
        val outH = image.height / PREVIEW_SAMPLE_STEP
        val pixels = IntArray(outW * outH)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        for (oy in 0 until outH) {
            val srcY = min(oy * PREVIEW_SAMPLE_STEP, image.height - 1)
            val chromaY = srcY / 2
            for (ox in 0 until outW) {
                val srcX = min(ox * PREVIEW_SAMPLE_STEP, image.width - 1)
                val chromaX = srcX / 2

                val yVal = yBuffer.get(srcY * yPlane.rowStride + srcX * yPlane.pixelStride).toInt() and 0xFF
                val uVal = (uBuffer.get(chromaY * uPlane.rowStride + chromaX * uPlane.pixelStride).toInt() and 0xFF) - 128
                val vVal = (vBuffer.get(chromaY * vPlane.rowStride + chromaX * vPlane.pixelStride).toInt() and 0xFF) - 128

                val r = (yVal + 1.402f * vVal).toInt().coerceIn(0, 255)
                val g = (yVal - 0.344136f * uVal - 0.714136f * vVal).toInt().coerceIn(0, 255)
                val b = (yVal + 1.772f * uVal).toInt().coerceIn(0, 255)

                pixels[oy * outW + ox] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        return Bitmap.createBitmap(pixels, outW, outH, Bitmap.Config.ARGB_8888)
    }
}
