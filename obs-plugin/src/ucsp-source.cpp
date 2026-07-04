#include "ucsp-source.h"
#include <plugin-support.h>

#include <mutex>
#include <optional>
#include <string>

#include "ucsp/backchannel_sender.h"
#include "ucsp/frame_reassembler.h"
#include "ucsp/h264_decoder.h"
#include "ucsp/sender_registry.h"

#define UCSP_DEFAULT_PORT 5600

namespace {

struct ucsp_source_data {
	explicit ucsp_source_data(obs_source_t *src)
		: source(src),
		  backchannel_sender([this]() { return ucsp::SenderRegistry::instance().socket_for_port(listen_port); },
				      [this](sockaddr_in *out) {
					      std::lock_guard<std::mutex> lock(target_addr_mutex);
					      if (!has_target_addr)
						      return false;
					      *out = target_addr;
					      return true;
				      },
				      0)
	{
	}

	obs_source_t *source;
	uint16_t listen_port = UCSP_DEFAULT_PORT;
	std::string device_filter; // empty = "auto" (single known sender on the port)
	bool running = false;

	// Address of whichever sender this source is currently accepting packets from
	// (learned from the SenderRegistry dispatch), so BackchannelSender knows who to
	// reply to even when several phones share the same listen port.
	std::mutex target_addr_mutex;
	sockaddr_in target_addr{};
	bool has_target_addr = false;

	ucsp::FrameReassembler reassembler;
	ucsp::H264Decoder decoder;
	ucsp::BackchannelSender backchannel_sender;
};

void apply_device_filter(ucsp_source_data *ctx)
{
	std::optional<sockaddr_in> filter;
	if (!ctx->device_filter.empty()) {
		sockaddr_in addr{};
		if (ucsp::parse_sockaddr(ctx->device_filter, &addr))
			filter = addr;
	}
	ucsp::SenderRegistry::instance().set_filter(ctx->listen_port, ctx, filter);
}

void push_frame_to_obs(obs_source_t *source, const AVFrame *frame, uint64_t pts_us)
{
	obs_source_frame2 obs_frame = {};
	obs_frame.format = VIDEO_FORMAT_I420;
	obs_frame.width = static_cast<uint32_t>(frame->width);
	obs_frame.height = static_cast<uint32_t>(frame->height);
	obs_frame.timestamp = pts_us * 1000ULL;
	obs_frame.range = VIDEO_RANGE_DEFAULT;

	// obs_source_output_video2 copies color_matrix/color_range_* through verbatim (it
	// does not compute a default), so they must be filled in here or the shader color
	// conversion collapses to a degenerate all-zero matrix.
	video_format_get_parameters_for_format(VIDEO_CS_DEFAULT, obs_frame.range, obs_frame.format, obs_frame.color_matrix,
						obs_frame.color_range_min, obs_frame.color_range_max);

	for (int i = 0; i < 3; i++) {
		obs_frame.data[i] = frame->data[i];
		obs_frame.linesize[i] = static_cast<uint32_t>(frame->linesize[i]);
	}
	obs_source_output_video2(source, &obs_frame);
}

void start_receiving(ucsp_source_data *ctx)
{
	// The sender's FrameId/SequenceNumber counters restart from zero on every new
	// streaming session (see UcspPacketizer on the Android side), so any state left over
	// from a previous session would make every packet from a fresh session look like a
	// stale straggler and get silently dropped forever. Always start from a clean slate.
	ctx->reassembler.reset();
	{
		std::lock_guard<std::mutex> lock(ctx->target_addr_mutex);
		ctx->has_target_addr = false;
	}

	bool subscribed = ucsp::SenderRegistry::instance().subscribe(
		ctx->listen_port, ctx,
		[ctx](const sockaddr_in &from_addr, const ucsp::Header &header, const uint8_t *payload, size_t payload_len) {
			{
				std::lock_guard<std::mutex> lock(ctx->target_addr_mutex);
				ctx->target_addr = from_addr;
				ctx->has_target_addr = true;
			}
			ctx->reassembler.on_packet(header, payload, payload_len);
		});

	if (subscribed) {
		apply_device_filter(ctx);
		ctx->backchannel_sender.start();
		ctx->running = true;
		obs_log(LOG_INFO, "ucsp_source: listening on UDP port %d", ctx->listen_port);
	} else {
		ctx->running = false;
		obs_log(LOG_ERROR, "ucsp_source: failed to start UDP receiver on port %d", ctx->listen_port);
	}
}

void stop_receiving(ucsp_source_data *ctx)
{
	if (!ctx->running)
		return;
	ctx->backchannel_sender.stop();
	ucsp::SenderRegistry::instance().unsubscribe(ctx->listen_port, ctx);
	ctx->running = false;
}

const char *ucsp_source_get_name(void *)
{
	return obs_module_text("UcspSourceName");
}

void ucsp_source_update(void *data, obs_data_t *settings)
{
	auto *ctx = static_cast<ucsp_source_data *>(data);
	uint16_t new_port = static_cast<uint16_t>(obs_data_get_int(settings, "listen_port"));
	std::string new_device_filter = obs_data_get_string(settings, "device_filter");
	if (new_device_filter == "auto")
		new_device_filter.clear();

	bool port_changed = new_port != ctx->listen_port;
	bool filter_changed = new_device_filter != ctx->device_filter;
	if (!port_changed && !filter_changed)
		return;

	if (port_changed) {
		bool was_running = ctx->running;
		if (was_running)
			stop_receiving(ctx);
		ctx->listen_port = new_port;
		ctx->device_filter = new_device_filter;
		if (was_running)
			start_receiving(ctx);
	} else {
		ctx->device_filter = new_device_filter;
		apply_device_filter(ctx);
	}
}

void *ucsp_source_create(obs_data_t *settings, obs_source_t *source)
{
	auto *ctx = new ucsp_source_data(source);
	ctx->listen_port = static_cast<uint16_t>(obs_data_get_int(settings, "listen_port"));
	std::string initial_device_filter = obs_data_get_string(settings, "device_filter");
	if (initial_device_filter != "auto")
		ctx->device_filter = initial_device_filter;

	if (!ctx->decoder.init())
		obs_log(LOG_ERROR, "ucsp_source: failed to initialize H.264 decoder");

	ctx->reassembler.set_frame_ready_callback(
		[ctx](const uint8_t *frame_data, size_t len, bool /*is_keyframe*/, uint64_t pts_us) {
			ctx->decoder.decode(frame_data, len, pts_us);
		});
	ctx->reassembler.set_frame_discarded_callback([ctx]() { ctx->backchannel_sender.request_keyframe_now(); });

	ctx->decoder.set_frame_callback(
		[ctx](const AVFrame *frame, uint64_t pts_us) { push_frame_to_obs(ctx->source, frame, pts_us); });
	ctx->decoder.set_decode_error_callback([ctx]() { ctx->backchannel_sender.request_keyframe_now(); });

	ctx->backchannel_sender.set_stats_provider([ctx]() { return ctx->reassembler.snapshot_and_reset_window_stats(); });

	start_receiving(ctx);
	return ctx;
}

void ucsp_source_destroy(void *data)
{
	auto *ctx = static_cast<ucsp_source_data *>(data);
	stop_receiving(ctx);
	ctx->decoder.shutdown();
	obs_log(LOG_INFO, "ucsp_source destroyed");
	delete ctx;
}

void ucsp_source_defaults(obs_data_t *settings)
{
	obs_data_set_default_int(settings, "listen_port", UCSP_DEFAULT_PORT);
	obs_data_set_default_string(settings, "device_filter", "auto");
}

// Manual fallback for whatever isn't covered by the automatic recovery paths (keyframe
// auto-request on decode failure, reassembler reset on every start_receiving) -- fully
// tears down and rebinds the UDP socket and reassembler state without needing to remove
// the source or restart OBS.
bool ucsp_source_reset_clicked(obs_properties_t *, obs_property_t *, void *data)
{
	auto *ctx = static_cast<ucsp_source_data *>(data);
	obs_log(LOG_INFO, "ucsp_source: manual reset requested");
	stop_receiving(ctx);
	start_receiving(ctx);
	return false;
}

// Rebuilds the "device" dropdown from whoever is currently sending to this source's port,
// without needing to close and reopen the properties dialog.
bool ucsp_source_refresh_devices_clicked(obs_properties_t *props, obs_property_t *, void *data)
{
	auto *ctx = static_cast<ucsp_source_data *>(data);
	obs_property_t *list = obs_properties_get(props, "device_filter");
	if (!list)
		return false;

	obs_property_list_clear(list);
	obs_property_list_add_string(list, obs_module_text("DeviceAuto"), "auto");
	for (const auto &label : ucsp::SenderRegistry::instance().known_sender_labels(ctx->listen_port))
		obs_property_list_add_string(list, label.c_str(), label.c_str());
	return true;
}

obs_properties_t *ucsp_source_properties(void *data)
{
	auto *ctx = static_cast<ucsp_source_data *>(data);
	obs_properties_t *props = obs_properties_create();
	obs_properties_add_int(props, "listen_port", obs_module_text("ListenPort"), 1024, 65535, 1);

	// Lets several phones share one listen port -- one OBS source/scene per phone,
	// picked here by sender IP -- instead of requiring a separate port per phone.
	obs_property_t *device_list = obs_properties_add_list(props, "device_filter", obs_module_text("Device"),
							       OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_STRING);
	obs_property_list_add_string(device_list, obs_module_text("DeviceAuto"), "auto");
	if (ctx) {
		for (const auto &label : ucsp::SenderRegistry::instance().known_sender_labels(ctx->listen_port))
			obs_property_list_add_string(device_list, label.c_str(), label.c_str());
	}

	obs_properties_add_button(props, "refresh_devices", obs_module_text("RefreshDevices"),
				   ucsp_source_refresh_devices_clicked);
	obs_properties_add_button(props, "reset_connection", obs_module_text("ResetConnection"), ucsp_source_reset_clicked);
	return props;
}

} // namespace

struct obs_source_info ucsp_source_info = []() {
	struct obs_source_info info = {};
	info.id = "ucsp_source";
	info.type = OBS_SOURCE_TYPE_INPUT;
	info.output_flags = OBS_SOURCE_ASYNC_VIDEO;
	info.get_name = ucsp_source_get_name;
	info.create = ucsp_source_create;
	info.destroy = ucsp_source_destroy;
	info.get_defaults = ucsp_source_defaults;
	info.get_properties = ucsp_source_properties;
	info.update = ucsp_source_update;
	return info;
}();
