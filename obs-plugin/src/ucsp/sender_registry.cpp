#include "sender_registry.h"

#include <algorithm>
#include <cstdio>
#include <cstdlib>

#include <obs-module.h>
#include <plugin-support.h>

namespace ucsp {

namespace {
// Senders not heard from in this long drop off the "known devices" list (and off any
// "auto" subscriber they were feeding), so a closed phone app doesn't linger forever.
constexpr auto SENDER_TIMEOUT = std::chrono::seconds(5);
} // namespace

std::string format_sockaddr(const sockaddr_in &addr)
{
	char ip_str[INET_ADDRSTRLEN] = {0};
	inet_ntop(AF_INET, &addr.sin_addr, ip_str, sizeof(ip_str));
	char buf[64];
	snprintf(buf, sizeof(buf), "%s:%d", ip_str, ntohs(addr.sin_port));
	return buf;
}

bool parse_sockaddr(const std::string &label, sockaddr_in *out)
{
	auto colon = label.rfind(':');
	if (colon == std::string::npos)
		return false;
	std::string ip = label.substr(0, colon);
	std::string port_str = label.substr(colon + 1);
	int port = atoi(port_str.c_str());
	if (port <= 0 || port > 65535)
		return false;

	sockaddr_in addr{};
	addr.sin_family = AF_INET;
	addr.sin_port = htons(static_cast<uint16_t>(port));
	if (inet_pton(AF_INET, ip.c_str(), &addr.sin_addr) != 1)
		return false;

	*out = addr;
	return true;
}

SenderRegistry &SenderRegistry::instance()
{
	static SenderRegistry registry;
	return registry;
}

bool SenderRegistry::subscribe(uint16_t port, const void *subscriber, PacketCallback callback)
{
	std::lock_guard<std::recursive_mutex> lock(mutex_);

	auto &state = ports_[port];
	if (!state.receiver) {
		state.receiver = std::make_unique<UdpReceiver>();
		bool started = state.receiver->start(port, [this, port](const sockaddr_in &from_addr, const Header &header,
									  const uint8_t *payload, size_t payload_len) {
			on_packet(port, from_addr, header, payload, payload_len);
		});
		if (!started) {
			state.receiver.reset();
			if (state.subscribers.empty())
				ports_.erase(port);
			return false;
		}
	}

	state.subscribers.push_back(Subscriber{subscriber, std::move(callback), std::nullopt});
	return true;
}

void SenderRegistry::unsubscribe(uint16_t port, const void *subscriber)
{
	std::lock_guard<std::recursive_mutex> lock(mutex_);
	auto it = ports_.find(port);
	if (it == ports_.end())
		return;

	auto &subs = it->second.subscribers;
	subs.erase(std::remove_if(subs.begin(), subs.end(), [subscriber](const Subscriber &s) { return s.id == subscriber; }),
		   subs.end());

	if (subs.empty())
		ports_.erase(it);
}

void SenderRegistry::set_filter(uint16_t port, const void *subscriber, std::optional<sockaddr_in> filter_addr)
{
	std::lock_guard<std::recursive_mutex> lock(mutex_);
	auto it = ports_.find(port);
	if (it == ports_.end())
		return;
	for (auto &sub : it->second.subscribers) {
		if (sub.id == subscriber) {
			sub.filter = filter_addr;
			break;
		}
	}
}

SOCKET SenderRegistry::socket_for_port(uint16_t port) const
{
	std::lock_guard<std::recursive_mutex> lock(mutex_);
	auto it = ports_.find(port);
	if (it == ports_.end() || !it->second.receiver)
		return INVALID_SOCKET;
	return it->second.receiver->raw_socket();
}

std::vector<std::string> SenderRegistry::known_sender_labels(uint16_t port) const
{
	std::lock_guard<std::recursive_mutex> lock(mutex_);
	std::vector<std::string> labels;
	auto it = ports_.find(port);
	if (it == ports_.end())
		return labels;

	auto now = std::chrono::steady_clock::now();
	for (const auto &[label, sender] : it->second.known_senders) {
		if (now - sender.last_seen <= SENDER_TIMEOUT)
			labels.push_back(label);
	}
	return labels;
}

void SenderRegistry::on_packet(uint16_t port, const sockaddr_in &from_addr, const Header &header, const uint8_t *payload,
			       size_t payload_len)
{
	std::lock_guard<std::recursive_mutex> lock(mutex_);
	auto it = ports_.find(port);
	if (it == ports_.end())
		return;
	auto &state = it->second;

	std::string label = format_sockaddr(from_addr);
	auto now = std::chrono::steady_clock::now();
	bool is_new_sender = state.known_senders.find(label) == state.known_senders.end();
	state.known_senders[label] = KnownSender{from_addr, now};

	if (is_new_sender)
		obs_log(LOG_INFO, "ucsp: new sender detected on port %d: %s", port, label.c_str());

	// Prune senders we haven't heard from in a while so the "known devices" list (and
	// "auto" mode's single-sender detection) reflects who is actually still streaming.
	for (auto sit = state.known_senders.begin(); sit != state.known_senders.end();) {
		if (now - sit->second.last_seen > SENDER_TIMEOUT)
			sit = state.known_senders.erase(sit);
		else
			++sit;
	}

	bool auto_unambiguous = state.known_senders.size() == 1;

	for (auto &sub : state.subscribers) {
		bool matches;
		if (sub.filter.has_value()) {
			matches = sub.filter->sin_addr.s_addr == from_addr.sin_addr.s_addr &&
				  sub.filter->sin_port == from_addr.sin_port;
		} else {
			matches = auto_unambiguous;
		}
		if (matches)
			sub.callback(from_addr, header, payload, payload_len);
	}
}

} // namespace ucsp
