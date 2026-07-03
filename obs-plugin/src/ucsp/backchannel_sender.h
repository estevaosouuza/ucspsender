#pragma once

#include <atomic>
#include <chrono>
#include <cstdint>
#include <functional>
#include <mutex>
#include <thread>

#include "ucsp_protocol.h"
#include "udp_receiver.h"

namespace ucsp {

// Sends BACKCHANNEL_REPORT packets back to the phone roughly every 50ms (ucsp-spec.md
// §4) and KEYFRAME_REQUEST packets on demand (§5, §7). Reuses UdpReceiver's socket and
// its learned sender address rather than opening a second socket, since the phone's
// single-socket UcspSender listens for replies on the exact socket it sent from.
class BackchannelSender {
public:
	using StatsProvider = std::function<BackchannelReportPayload()>;

	explicit BackchannelSender(UdpReceiver &receiver, uint8_t stream_id = 0);
	~BackchannelSender();

	BackchannelSender(const BackchannelSender &) = delete;
	BackchannelSender &operator=(const BackchannelSender &) = delete;

	void set_stats_provider(StatsProvider provider) { stats_provider_ = std::move(provider); }

	void start();
	void stop();

	// Fires an out-of-band KEYFRAME_REQUEST immediately (not on the ~50ms cadence),
	// rate-limited so a burst of unrecoverable frames doesn't flood the phone.
	void request_keyframe_now();

private:
	void timer_loop();
	void send_report();
	void send_packet(uint8_t packet_type, const uint8_t *payload, size_t payload_len);

	UdpReceiver &receiver_;
	uint8_t stream_id_;
	std::thread thread_;
	std::atomic<bool> running_{false};
	StatsProvider stats_provider_;
	uint32_t sequence_counter_ = 0;

	std::mutex keyframe_request_mutex_;
	std::chrono::steady_clock::time_point last_keyframe_request_tp_{};
	bool has_last_keyframe_request_ = false;
};

} // namespace ucsp
