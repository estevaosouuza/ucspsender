#include "udp_receiver.h"

#include <obs-module.h>
#include <plugin-support.h>

#pragma comment(lib, "ws2_32.lib")

namespace ucsp {

namespace {

std::atomic<int> g_wsa_refcount{0};

bool wsa_acquire()
{
	if (g_wsa_refcount.fetch_add(1) == 0) {
		WSADATA wsaData;
		if (WSAStartup(MAKEWORD(2, 2), &wsaData) != 0) {
			g_wsa_refcount.fetch_sub(1);
			return false;
		}
	}
	return true;
}

void wsa_release()
{
	if (g_wsa_refcount.fetch_sub(1) == 1)
		WSACleanup();
}

} // namespace

UdpReceiver::UdpReceiver() {}

UdpReceiver::~UdpReceiver()
{
	stop();
}

bool UdpReceiver::start(uint16_t listen_port, PacketCallback callback)
{
	if (running_)
		return false;
	if (!wsa_acquire())
		return false;

	socket_ = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
	if (socket_ == INVALID_SOCKET) {
		obs_log(LOG_ERROR, "ucsp: failed to create UDP socket");
		wsa_release();
		return false;
	}

	// Bounded timeout so the receive loop can periodically check `running_` instead of
	// blocking forever in recvfrom().
	DWORD timeout_ms = 200;
	setsockopt(socket_, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char *>(&timeout_ms), sizeof(timeout_ms));

	sockaddr_in bind_addr{};
	bind_addr.sin_family = AF_INET;
	bind_addr.sin_addr.s_addr = INADDR_ANY;
	bind_addr.sin_port = htons(listen_port);

	if (bind(socket_, reinterpret_cast<sockaddr *>(&bind_addr), sizeof(bind_addr)) == SOCKET_ERROR) {
		obs_log(LOG_ERROR, "ucsp: failed to bind UDP socket on port %d", listen_port);
		closesocket(socket_);
		socket_ = INVALID_SOCKET;
		wsa_release();
		return false;
	}

	callback_ = std::move(callback);
	running_ = true;
	thread_ = std::thread(&UdpReceiver::receive_loop, this);
	return true;
}

void UdpReceiver::stop()
{
	if (!running_)
		return;
	running_ = false;
	if (thread_.joinable())
		thread_.join();
	if (socket_ != INVALID_SOCKET) {
		closesocket(socket_);
		socket_ = INVALID_SOCKET;
	}
	wsa_release();
}

bool UdpReceiver::sender_address(sockaddr_in *out) const
{
	std::lock_guard<std::mutex> lock(addr_mutex_);
	if (!has_sender_addr_)
		return false;
	*out = sender_addr_;
	return true;
}

void UdpReceiver::receive_loop()
{
	std::vector<uint8_t> buffer(2048);

	while (running_) {
		sockaddr_in from_addr{};
		int from_len = sizeof(from_addr);

		int received = recvfrom(socket_, reinterpret_cast<char *>(buffer.data()), static_cast<int>(buffer.size()), 0,
					 reinterpret_cast<sockaddr *>(&from_addr), &from_len);

		if (received == SOCKET_ERROR) {
			int err = WSAGetLastError();
			if (err == WSAETIMEDOUT)
				continue;
			if (running_)
				obs_log(LOG_WARNING, "ucsp: recvfrom error %d", err);
			continue;
		}

		if (static_cast<size_t>(received) < HEADER_SIZE)
			continue;

		{
			std::lock_guard<std::mutex> lock(addr_mutex_);
			sender_addr_ = from_addr;
			has_sender_addr_ = true;
		}

		Header header = Header::parse(buffer.data());
		size_t payload_len = static_cast<size_t>(received) - HEADER_SIZE;
		if (callback_)
			callback_(header, buffer.data() + HEADER_SIZE, payload_len);
	}
}

} // namespace ucsp
