#include "backchannel_sender.h"

#include <cstring>

#include <obs-module.h>
#include <plugin-support.h>

namespace ucsp {

namespace {
constexpr auto REPORT_INTERVAL = std::chrono::milliseconds(50);
constexpr auto KEYFRAME_REQUEST_MIN_INTERVAL = std::chrono::milliseconds(300);
} // namespace

BackchannelSender::BackchannelSender(UdpReceiver &receiver, uint8_t stream_id) : receiver_(receiver), stream_id_(stream_id) {}

BackchannelSender::~BackchannelSender()
{
	stop();
}

void BackchannelSender::start()
{
	if (running_)
		return;
	running_ = true;
	thread_ = std::thread(&BackchannelSender::timer_loop, this);
}

void BackchannelSender::stop()
{
	if (!running_)
		return;
	running_ = false;
	if (thread_.joinable())
		thread_.join();
}

void BackchannelSender::timer_loop()
{
	while (running_) {
		std::this_thread::sleep_for(REPORT_INTERVAL);
		if (!running_)
			break;
		send_report();
	}
}

void BackchannelSender::send_report()
{
	if (!stats_provider_)
		return;

	BackchannelReportPayload stats = stats_provider_();
	uint8_t payload[BackchannelReportPayload::SIZE];
	stats.write(payload);
	send_packet(PACKET_BACKCHANNEL_REPORT, payload, sizeof(payload));
}

void BackchannelSender::request_keyframe_now()
{
	{
		std::lock_guard<std::mutex> lock(keyframe_request_mutex_);
		auto now = std::chrono::steady_clock::now();
		if (has_last_keyframe_request_ && now - last_keyframe_request_tp_ < KEYFRAME_REQUEST_MIN_INTERVAL)
			return;
		last_keyframe_request_tp_ = now;
		has_last_keyframe_request_ = true;
	}
	send_packet(PACKET_KEYFRAME_REQUEST, nullptr, 0);
}

void BackchannelSender::send_packet(uint8_t packet_type, const uint8_t *payload, size_t payload_len)
{
	sockaddr_in target{};
	if (!receiver_.sender_address(&target))
		return; // haven't heard from the phone yet, nothing to reply to

	Header header;
	header.packet_type = packet_type;
	header.stream_id = stream_id_;
	header.payload_length = static_cast<uint16_t>(payload_len);
	header.sequence_number = sequence_counter_++;

	std::vector<uint8_t> datagram(HEADER_SIZE + payload_len);
	header.write(datagram.data());
	if (payload_len > 0)
		memcpy(datagram.data() + HEADER_SIZE, payload, payload_len);

	sendto(receiver_.raw_socket(), reinterpret_cast<const char *>(datagram.data()), static_cast<int>(datagram.size()), 0,
	       reinterpret_cast<sockaddr *>(&target), sizeof(target));
}

} // namespace ucsp
