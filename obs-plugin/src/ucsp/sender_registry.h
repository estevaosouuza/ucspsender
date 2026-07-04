#pragma once

#include <winsock2.h>
#include <ws2tcpip.h>

#include <chrono>
#include <cstdint>
#include <functional>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

#include "ucsp_protocol.h"
#include "udp_receiver.h"

namespace ucsp {

// Windows only allows one bind() per UDP port, so several ucsp_source instances that all
// want to listen on the SAME port (several phones all pointed at one PC:port, one OBS
// scene per phone) have to share a single socket. This registry owns that shared socket
// per port, tracks which sender addresses have recently sent to it (for the "which
// devices are connected" properties dropdown), and dispatches each packet only to the
// subscriber(s) whose selected device address matches -- so streams from different phones
// never get mixed into the same frame reassembler.
//
// Ports used by only one source (the common single-camera case) behave exactly as before:
// with a single known sender, "auto" (no explicit selection) forwards everything from it.
class SenderRegistry {
public:
	static SenderRegistry &instance();

	SenderRegistry(const SenderRegistry &) = delete;
	SenderRegistry &operator=(const SenderRegistry &) = delete;

	using PacketCallback = std::function<void(const sockaddr_in &from_addr, const Header &header, const uint8_t *payload,
						   size_t payload_len)>;

	// Registers `subscriber` (any stable, unique pointer -- the caller's ucsp_source
	// instance is used) to receive packets arriving on `port`. Creates the shared
	// UdpReceiver for that port on the first subscriber; returns false if the socket
	// couldn't be bound (e.g. the port is in use by something outside this plugin).
	bool subscribe(uint16_t port, const void *subscriber, PacketCallback callback);

	// Removes `subscriber` from `port`; tears down the shared socket once nobody is left
	// listening on it.
	void unsubscribe(uint16_t port, const void *subscriber);

	// Empty optional = "auto": forward to this subscriber only while exactly one sender
	// is currently known on the port (unambiguous single-camera case). Once 2+ senders
	// are seen, auto subscribers receive nothing until a specific device is chosen.
	void set_filter(uint16_t port, const void *subscriber, std::optional<sockaddr_in> filter_addr);

	// Raw socket shared by every subscriber of `port`, for BackchannelSender to send
	// replies from. INVALID_SOCKET if nothing is currently subscribed to that port.
	SOCKET socket_for_port(uint16_t port) const;

	// "ip:port" labels of senders seen on `port` in the last few seconds, for populating
	// the source properties device dropdown.
	std::vector<std::string> known_sender_labels(uint16_t port) const;

private:
	SenderRegistry() = default;

	struct Subscriber {
		const void *id;
		PacketCallback callback;
		std::optional<sockaddr_in> filter;
	};

	struct KnownSender {
		sockaddr_in addr;
		std::chrono::steady_clock::time_point last_seen;
	};

	struct PortState {
		std::unique_ptr<UdpReceiver> receiver;
		std::vector<Subscriber> subscribers;
		std::map<std::string, KnownSender> known_senders; // key = "ip:port"
	};

	void on_packet(uint16_t port, const sockaddr_in &from_addr, const Header &header, const uint8_t *payload,
		       size_t payload_len);

	// Recursive: on_packet() dispatches to subscriber callbacks while holding this lock,
	// and a callback can legitimately re-enter the registry on the same thread (e.g. a
	// discarded-frame handler requesting a keyframe, which needs socket_for_port()). A
	// plain std::mutex would be undefined behavior in that case.
	mutable std::recursive_mutex mutex_;
	std::map<uint16_t, PortState> ports_;
};

// "ip:port" <-> sockaddr_in helpers shared with the properties dropdown / settings storage.
std::string format_sockaddr(const sockaddr_in &addr);
bool parse_sockaddr(const std::string &label, sockaddr_in *out);

} // namespace ucsp
