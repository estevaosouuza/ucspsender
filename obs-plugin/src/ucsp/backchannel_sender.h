#pragma once

#include <winsock2.h>
#include <ws2tcpip.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <functional>
#include <mutex>
#include <thread>

#include "ucsp_protocol.h"

namespace ucsp {

// Sends BACKCHANNEL_REPORT packets back to the phone roughly every 50ms (ucsp-spec.md
// §4) and KEYFRAME_REQUEST packets on demand (§5, §7). Sends from a caller-supplied socket
// (shared with a SenderRegistry-owned UdpReceiver rather than owning its own, since the
// phone's single-socket UcspSender listens for replies on the exact socket it sent from,
// and several ucsp_source instances may share one physical port/socket) to a
// caller-supplied target address (the specific device this source is currently showing).
class BackchannelSender {
public:
	using StatsProvider = std::function<BackchannelReportPayload()>;
	using SocketProvider = std::function<SOCKET()>;
	using TargetAddressProvider = std::function<bool(sockaddr_in *out)>;

	BackchannelSender(SocketProvider socket_provider, TargetAddressProvider target_provider, uint8_t stream_id = 0);
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

	SocketProvider socket_provider_;
	TargetAddressProvider target_provider_;
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
