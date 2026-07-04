#pragma once

#include <winsock2.h>
#include <ws2tcpip.h>

#include <atomic>
#include <cstdint>
#include <functional>
#include <mutex>
#include <thread>
#include <vector>

#include "ucsp_protocol.h"

namespace ucsp {

// Binds a UDP socket on listen_port and dispatches every valid UCSP datagram to a
// callback on a dedicated receive thread. Also remembers the most recent sender address
// so BackchannelSender knows where to reply (ucsp-spec.md §6: no handshake in Phase 1 --
// the phone's address is learned from its first VIDEO_DATA packet).
class UdpReceiver {
public:
	using PacketCallback =
		std::function<void(const sockaddr_in &from_addr, const Header &header, const uint8_t *payload, size_t payload_len)>;

	UdpReceiver();
	~UdpReceiver();

	UdpReceiver(const UdpReceiver &) = delete;
	UdpReceiver &operator=(const UdpReceiver &) = delete;

	bool start(uint16_t listen_port, PacketCallback callback);
	void stop();

	SOCKET raw_socket() const { return socket_; }

private:
	void receive_loop();

	SOCKET socket_ = INVALID_SOCKET;
	std::thread thread_;
	std::atomic<bool> running_{false};
	PacketCallback callback_;

	mutable std::mutex addr_mutex_;
	sockaddr_in sender_addr_{};
	bool has_sender_addr_ = false;
};

} // namespace ucsp
