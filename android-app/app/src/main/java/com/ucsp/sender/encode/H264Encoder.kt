package com.ucsp.sender.encode

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import kotlin.concurrent.thread

/**
 * H.264 Baseline encoder fed frame-by-frame from camera YUV buffers (buffer-input mode,
 * not Surface-input) so the same camera session can also drive a visible on-screen
 * preview. Baseline profile inherently forbids B-slices, so "no B-frames" falls out of
 * the profile choice rather than needing a separate flag. GOP is kept short (~1s) so the
 * stream recovers quickly after a keyframe request or an unrecoverable frame drop.
 *
 * The one-time SPS/PPS buffer (BUFFER_FLAG_CODEC_CONFIG) is cached and re-prepended to
 * every subsequent keyframe's NAL, per ucsp-spec.md §2, so any IDR sent over UCSP is
 * independently decodable.
 *
 * Runs in MediaCodec's classic synchronous mode: [feedFrame] is called directly from the
 * camera's analyzer thread and dequeues an input buffer with a short timeout -- if the
 * encoder has no free buffer yet (falling behind), the frame is dropped instead of
 * blocking the camera pipeline, matching the "degrade, never freeze" philosophy. Any
 * unexpected error is reported via [onFatalError] instead of crashing the app, since
 * hardware encoder quirks vary a lot across devices.
 */
class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int = 30,
    private val bitrateBps: Int = 4_000_000,
    private val onFatalError: (Throwable) -> Unit = {},
    private val onEncodedFrame: (accessUnit: ByteArray, isKeyframe: Boolean, presentationTimeUs: Long) -> Unit
) {
    companion object {
        private const val TAG = "H264Encoder"
        private const val GOP_SECONDS = 1
        private const val INPUT_DEQUEUE_TIMEOUT_US = 10_000L
        private const val OUTPUT_DEQUEUE_TIMEOUT_US = 10_000L
    }

    private var codec: MediaCodec? = null
    private var cachedCodecConfig: ByteArray? = null
    private var outputThread: Thread? = null

    @Volatile
    private var running = false

    /** Returns true if the encoder started successfully; false (with [onFatalError] fired) otherwise. */
    fun start(): Boolean {
        return try {
            val newCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val colorFormat = selectSupportedColorFormat(newCodec)

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, GOP_SECONDS)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            }

            newCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            newCodec.start()
            codec = newCodec
            running = true
            outputThread = thread(name = "H264EncoderOutput") { drainOutputLoop() }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start H.264 encoder", e)
            onFatalError(e)
            false
        }
    }

    /**
     * Not every device's hardware encoder accepts COLOR_FormatYUV420Flexible directly in
     * configure(), even though MediaCodec.getInputImage() is documented to work with it --
     * this varies by vendor. Prefer Flexible when the encoder actually advertises it, else
     * fall back to a concrete planar/semiplanar format it does advertise.
     */
    private fun selectSupportedColorFormat(mediaCodec: MediaCodec): Int {
        val supported = try {
            mediaCodec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).colorFormats
        } catch (e: Exception) {
            intArrayOf()
        }
        val preferenceOrder = intArrayOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        )
        for (candidate in preferenceOrder) {
            if (supported.contains(candidate)) return candidate
        }
        return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
    }

    /**
     * Copies [cameraImage]'s planes into a free encoder input buffer and queues it.
     * Returns false (frame dropped) if the encoder isn't running, has no input buffer
     * free right now, or the copy/queue fails for any reason.
     */
    fun feedFrame(cameraImage: Image, presentationTimeUs: Long): Boolean {
        val mediaCodec = codec ?: return false
        return try {
            val index = mediaCodec.dequeueInputBuffer(INPUT_DEQUEUE_TIMEOUT_US)
            if (index < 0) return false

            val inputImage = mediaCodec.getInputImage(index)
            if (inputImage == null) {
                mediaCodec.queueInputBuffer(index, 0, 0, presentationTimeUs, 0)
                return false
            }

            copyImagePlanes(cameraImage, inputImage)
            val size = width * height * 3 / 2
            mediaCodec.queueInputBuffer(index, 0, size, presentationTimeUs, 0)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Dropping frame: failed to feed encoder", e)
            false
        }
    }

    private fun drainOutputLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        val mediaCodec = codec ?: return
        while (running) {
            val index = try {
                mediaCodec.dequeueOutputBuffer(bufferInfo, OUTPUT_DEQUEUE_TIMEOUT_US)
            } catch (e: IllegalStateException) {
                break
            }
            if (index < 0) continue

            try {
                val outputBuffer = mediaCodec.getOutputBuffer(index)
                if (outputBuffer != null) handleOutputBuffer(outputBuffer, bufferInfo)
                mediaCodec.releaseOutputBuffer(index, false)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to handle encoder output buffer", e)
            }
        }
    }

    private fun handleOutputBuffer(buffer: java.nio.ByteBuffer, info: MediaCodec.BufferInfo) {
        val data = ByteArray(info.size)
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        buffer.get(data)

        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            cachedCodecConfig = data
            return
        }

        val isKeyframe = info.flags and MediaCodec.BUFFER_FLAG_SYNC_FRAME != 0
        val codecConfig = cachedCodecConfig
        val accessUnit = if (isKeyframe && codecConfig != null) codecConfig + data else data

        onEncodedFrame(accessUnit, isKeyframe, info.presentationTimeUs)
    }

    fun requestKeyframe() {
        try {
            val params = Bundle()
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            codec?.setParameters(params)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request keyframe", e)
        }
    }

    fun release() {
        running = false
        outputThread?.join(500)
        val mediaCodec = codec ?: return
        try {
            mediaCodec.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Encoder was not running at release time", e)
        }
        mediaCodec.release()
        codec = null
    }

    private fun copyImagePlanes(src: Image, dst: Image) {
        copyPlane(src.planes[0], dst.planes[0], src.width, src.height)
        copyPlane(src.planes[1], dst.planes[1], src.width / 2, src.height / 2)
        copyPlane(src.planes[2], dst.planes[2], src.width / 2, src.height / 2)
    }

    /**
     * Copies one YUV_420_888 plane, honoring row/pixel strides on both sides -- camera
     * vendors and encoders disagree on whether chroma planes are fully planar
     * (pixelStride=1) or semiplanar/interleaved (pixelStride=2), so a flat buffer copy
     * would corrupt the image on many devices.
     */
    private fun copyPlane(src: Image.Plane, dst: Image.Plane, width: Int, height: Int) {
        val srcBuffer = src.buffer
        val dstBuffer = dst.buffer
        val srcRowStride = src.rowStride
        val srcPixelStride = src.pixelStride
        val dstRowStride = dst.rowStride
        val dstPixelStride = dst.pixelStride

        if (srcPixelStride == dstPixelStride) {
            val rowBytes = (width - 1) * srcPixelStride + 1
            for (row in 0 until height) {
                srcBuffer.position(row * srcRowStride)
                srcBuffer.limit(minOf(srcBuffer.position() + rowBytes, srcBuffer.capacity()))
                dstBuffer.position(row * dstRowStride)
                dstBuffer.put(srcBuffer)
            }
            srcBuffer.limit(srcBuffer.capacity())
        } else {
            for (row in 0 until height) {
                for (col in 0 until width) {
                    dstBuffer.put(row * dstRowStride + col * dstPixelStride, srcBuffer.get(row * srcRowStride + col * srcPixelStride))
                }
            }
        }
    }
}
