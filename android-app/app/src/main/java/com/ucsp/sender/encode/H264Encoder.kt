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
 * blocking the camera pipeline, matching the "degrade, never freeze" philosophy.
 */
class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int = 30,
    private val bitrateBps: Int = 4_000_000,
    private val onEncodedFrame: (accessUnit: ByteArray, isKeyframe: Boolean, presentationTimeUs: Long) -> Unit
) {
    companion object {
        private const val TAG = "H264Encoder"
        private const val GOP_SECONDS = 1
        private const val INPUT_DEQUEUE_TIMEOUT_US = 10_000L
        private const val OUTPUT_DEQUEUE_TIMEOUT_US = 10_000L
    }

    private lateinit var codec: MediaCodec
    private var cachedCodecConfig: ByteArray? = null
    private var outputThread: Thread? = null

    @Volatile
    private var running = false

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, GOP_SECONDS)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
        }

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        running = true
        outputThread = thread(name = "H264EncoderOutput") { drainOutputLoop() }
    }

    /**
     * Copies [cameraImage]'s planes into a free encoder input buffer and queues it.
     * Returns false (frame dropped) if the encoder has no input buffer free right now.
     */
    fun feedFrame(cameraImage: Image, presentationTimeUs: Long): Boolean {
        val index = try {
            codec.dequeueInputBuffer(INPUT_DEQUEUE_TIMEOUT_US)
        } catch (e: IllegalStateException) {
            return false
        }
        if (index < 0) return false

        val inputImage = codec.getInputImage(index)
        if (inputImage == null) {
            codec.queueInputBuffer(index, 0, 0, presentationTimeUs, 0)
            return false
        }

        copyImagePlanes(cameraImage, inputImage)
        val size = width * height * 3 / 2
        codec.queueInputBuffer(index, 0, size, presentationTimeUs, 0)
        return true
    }

    private fun drainOutputLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        while (running) {
            val index = try {
                codec.dequeueOutputBuffer(bufferInfo, OUTPUT_DEQUEUE_TIMEOUT_US)
            } catch (e: IllegalStateException) {
                break
            }
            if (index < 0) continue

            val outputBuffer = codec.getOutputBuffer(index)
            if (outputBuffer != null) handleOutputBuffer(outputBuffer, bufferInfo)
            codec.releaseOutputBuffer(index, false)
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
        val params = Bundle()
        params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
        codec.setParameters(params)
    }

    fun release() {
        running = false
        outputThread?.join(500)
        try {
            codec.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Encoder was not running at release time", e)
        }
        codec.release()
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
