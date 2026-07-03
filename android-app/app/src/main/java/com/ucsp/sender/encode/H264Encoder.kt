package com.ucsp.sender.encode

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface

/**
 * H.264 Baseline encoder fed by a Surface (CameraX renders straight into it -- no CPU YUV
 * copy). This is the original, proven-reliable input path: MediaCodec's Surface-input
 * mode. Baseline profile inherently forbids B-slices, so "no B-frames" falls out of the
 * profile choice rather than needing a separate flag. GOP is kept short (~1s) so the
 * stream recovers quickly after a keyframe request or an unrecoverable frame drop.
 *
 * The one-time SPS/PPS buffer (BUFFER_FLAG_CODEC_CONFIG) is cached and re-prepended to
 * every subsequent keyframe's NAL, per ucsp-spec.md §2, so any IDR sent over UCSP is
 * independently decodable.
 *
 * Any startup failure is reported via [onFatalError] instead of throwing, since hardware
 * encoder support varies across devices.
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
    }

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var cachedCodecConfig: ByteArray? = null

    /** Returns the encoder's input Surface for CameraX to render into, or null on failure. */
    fun createInputSurface(): Surface? {
        return try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, GOP_SECONDS)
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
            }

            val newCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            newCodec.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    handleOutputBuffer(codec, index, info)
                }

                override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                    Log.e(TAG, "MediaCodec encoder error", e)
                }

                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
            })
            newCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = newCodec.createInputSurface()
            newCodec.start()

            codec = newCodec
            inputSurface = surface
            surface
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start H.264 encoder", e)
            onFatalError(e)
            null
        }
    }

    private fun handleOutputBuffer(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        val outputBuffer = codec.getOutputBuffer(index)
        if (outputBuffer == null) {
            codec.releaseOutputBuffer(index, false)
            return
        }

        val data = ByteArray(info.size)
        outputBuffer.position(info.offset)
        outputBuffer.limit(info.offset + info.size)
        outputBuffer.get(data)

        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
            cachedCodecConfig = data
            codec.releaseOutputBuffer(index, false)
            return
        }

        val isKeyframe = info.flags and MediaCodec.BUFFER_FLAG_SYNC_FRAME != 0
        val codecConfig = cachedCodecConfig
        val accessUnit = if (isKeyframe && codecConfig != null) codecConfig + data else data

        onEncodedFrame(accessUnit, isKeyframe, info.presentationTimeUs)
        codec.releaseOutputBuffer(index, false)
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
        val mediaCodec = codec ?: return
        try {
            mediaCodec.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Encoder was not running at release time", e)
        }
        mediaCodec.release()
        inputSurface?.release()
        inputSurface = null
        codec = null
    }
}
