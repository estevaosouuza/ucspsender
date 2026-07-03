#pragma once

#include <cstdint>
#include <functional>

extern "C" {
#include <libavcodec/avcodec.h>
}

namespace ucsp {

// Software H.264 decode via libavcodec (FFmpeg, vcpkg). Outputs land in AVFrame's native
// AV_PIX_FMT_YUV420P planes, which map 1:1 onto OBS's VIDEO_FORMAT_I420 -- no swscale
// conversion needed for Phase 1. Hardware decode (D3D11VA/DXVA2) is a Phase 3 upgrade of
// this same class's internals.
class H264Decoder {
public:
	// Callback receives the still-valid decoded AVFrame (borrowed: do not free/unref
	// it, and don't retain the pointer past the callback's return) plus the UCSP
	// presentation timestamp (microseconds) that produced it.
	using DecodedFrameCallback = std::function<void(const AVFrame *frame, uint64_t pts_us)>;

	H264Decoder();
	~H264Decoder();

	H264Decoder(const H264Decoder &) = delete;
	H264Decoder &operator=(const H264Decoder &) = delete;

	bool init();
	void shutdown();

	void set_frame_callback(DecodedFrameCallback cb) { on_frame_ = std::move(cb); }

	// annex_b_data must stay valid for the duration of this call.
	void decode(const uint8_t *annex_b_data, size_t len, uint64_t pts_us);

private:
	AVCodecContext *codec_ctx_ = nullptr;
	AVPacket *packet_ = nullptr;
	AVFrame *frame_ = nullptr;
	DecodedFrameCallback on_frame_;
};

} // namespace ucsp
