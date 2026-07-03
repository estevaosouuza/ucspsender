#include "h264_decoder.h"

#include <obs-module.h>
#include <plugin-support.h>

namespace ucsp {

H264Decoder::H264Decoder() {}

H264Decoder::~H264Decoder()
{
	shutdown();
}

bool H264Decoder::init()
{
	const AVCodec *codec = avcodec_find_decoder(AV_CODEC_ID_H264);
	if (!codec) {
		obs_log(LOG_ERROR, "ucsp: H.264 decoder not found in libavcodec");
		return false;
	}

	codec_ctx_ = avcodec_alloc_context3(codec);
	if (!codec_ctx_) {
		obs_log(LOG_ERROR, "ucsp: failed to allocate AVCodecContext");
		return false;
	}

	if (avcodec_open2(codec_ctx_, codec, nullptr) < 0) {
		obs_log(LOG_ERROR, "ucsp: failed to open H.264 decoder");
		avcodec_free_context(&codec_ctx_);
		return false;
	}

	packet_ = av_packet_alloc();
	frame_ = av_frame_alloc();
	return packet_ != nullptr && frame_ != nullptr;
}

void H264Decoder::shutdown()
{
	if (frame_)
		av_frame_free(&frame_);
	if (packet_)
		av_packet_free(&packet_);
	if (codec_ctx_)
		avcodec_free_context(&codec_ctx_);
}

void H264Decoder::decode(const uint8_t *annex_b_data, size_t len, uint64_t pts_us)
{
	if (!codec_ctx_)
		return;

	av_packet_unref(packet_);
	packet_->data = const_cast<uint8_t *>(annex_b_data);
	packet_->size = static_cast<int>(len);
	packet_->pts = static_cast<int64_t>(pts_us);

	int ret = avcodec_send_packet(codec_ctx_, packet_);
	if (ret < 0) {
		obs_log(LOG_WARNING, "ucsp: avcodec_send_packet failed (%d)", ret);
		return;
	}

	while (ret >= 0) {
		ret = avcodec_receive_frame(codec_ctx_, frame_);
		if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF)
			break;
		if (ret < 0) {
			obs_log(LOG_WARNING, "ucsp: avcodec_receive_frame failed (%d)", ret);
			break;
		}

		if (on_frame_)
			on_frame_(frame_, static_cast<uint64_t>(frame_->pts));
		av_frame_unref(frame_);
	}
}

} // namespace ucsp
