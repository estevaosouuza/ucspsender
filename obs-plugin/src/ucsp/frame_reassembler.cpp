#include "frame_reassembler.h"
#include "fec_decoder.h"

#include <algorithm>
#include <cmath>

#include <obs-module.h>
#include <plugin-support.h>

namespace ucsp {

using namespace std::chrono;

FrameReassembler::FrameReassembler() {}

void FrameReassembler::reset()
{
	for (auto &slot : ring_)
		slot = FrameBuffer{};

	has_last_frame_arrival_ = false;
	last_frame_pts_us_ = 0;
	completed_frame_count_ = 0;

	std::lock_guard<std::mutex> lock(stats_mutex_);
	has_last_frame_id_ = false;
	last_frame_id_received_ = 0;
	jitter_ms_ewma_ = 0.0;
	window_packets_received_ = 0;
	window_has_seq_ = false;
	window_seq_min_ = 0;
	window_seq_max_ = 0;
	window_processing_accum_ms_ = 0.0;
	window_processing_count_ = 0;
}

void FrameReassembler::on_packet(const Header &header, const uint8_t *payload, size_t payload_len)
{
	if (header.packet_type != PACKET_VIDEO_DATA && header.packet_type != PACKET_FEC_PARITY)
		return;

	record_window_packet(header);

	if (header.packet_type == PACKET_VIDEO_DATA)
		handle_video_data(header, payload, payload_len);
	else
		handle_fec_parity(header, payload, payload_len);
}

FrameReassembler::FrameBuffer *FrameReassembler::acquire_slot(const Header &header)
{
	FrameBuffer &slot = ring_[header.frame_id % RING_SIZE];

	if (slot.active && slot.frame_id == header.frame_id)
		return &slot;

	if (slot.active && header.frame_id < slot.frame_id)
		return nullptr; // stale straggler packet for a frame we've already moved past

	if (slot.active && !slot.completed) {
		obs_log(LOG_WARNING, "ucsp: discarding incomplete frame %u (%d/%d packets)", slot.frame_id,
			slot.received_count, slot.total_packets);
		if (on_frame_discarded_)
			on_frame_discarded_();
	}

	slot = FrameBuffer{};
	slot.active = true;
	slot.frame_id = header.frame_id;
	slot.total_packets = header.total_packets;
	slot.is_keyframe = header.is_keyframe();
	slot.pts_us = header.presentation_timestamp_us;
	slot.chunks.resize(static_cast<size_t>(header.total_packets));
	slot.received.assign(static_cast<size_t>(header.total_packets), false);
	slot.first_packet_time = steady_clock::now();

	// First packet ever seen for this frame id: update the continuous jitter/last-frame
	// stats (ucsp-spec.md §4 EstimatedJitterMs is a per-frame-arrival RFC3550-style EWMA).
	auto now = steady_clock::now();
	if (has_last_frame_arrival_) {
		double delta_r_ms = duration<double, std::milli>(now - last_frame_arrival_tp_).count();
		double delta_s_ms = static_cast<double>(header.presentation_timestamp_us - last_frame_pts_us_) / 1000.0;
		double d = std::abs(delta_r_ms - delta_s_ms);
		std::lock_guard<std::mutex> lock(stats_mutex_);
		jitter_ms_ewma_ += (d - jitter_ms_ewma_) / 16.0;
	}
	last_frame_arrival_tp_ = now;
	last_frame_pts_us_ = header.presentation_timestamp_us;
	has_last_frame_arrival_ = true;

	{
		std::lock_guard<std::mutex> lock(stats_mutex_);
		if (!has_last_frame_id_ || header.frame_id > last_frame_id_received_) {
			last_frame_id_received_ = header.frame_id;
			has_last_frame_id_ = true;
		}
	}

	return &slot;
}

void FrameReassembler::handle_video_data(const Header &header, const uint8_t *payload, size_t payload_len)
{
	FrameBuffer *slot = acquire_slot(header);
	if (!slot || slot->completed)
		return;
	if (header.packet_index >= slot->total_packets)
		return;
	if (slot->received[header.packet_index])
		return;

	slot->chunks[header.packet_index].assign(payload, payload + payload_len);
	slot->received[header.packet_index] = true;
	slot->received_count++;

	attempt_recovery(*slot);
	try_complete(*slot);
}

void FrameReassembler::handle_fec_parity(const Header &header, const uint8_t *payload, size_t payload_len)
{
	FrameBuffer *slot = acquire_slot(header);
	if (!slot || slot->completed)
		return;

	FecGroupInfo info;
	info.payload.assign(payload, payload + payload_len);
	info.group_size = header.fec_group_size;
	slot->fec_groups[header.packet_index] = std::move(info);

	attempt_recovery(*slot);
	try_complete(*slot);
}

void FrameReassembler::attempt_recovery(FrameBuffer &slot)
{
	for (auto &entry : slot.fec_groups) {
		int start_index = entry.first;
		FecGroupInfo &group = entry.second;
		int group_size = group.group_size;
		if (group_size <= 0 || start_index + group_size > slot.total_packets)
			continue;

		int missing_count = 0;
		int missing_local_index = -1;
		std::vector<const std::vector<uint8_t> *> present(static_cast<size_t>(group_size), nullptr);
		for (int i = 0; i < group_size; i++) {
			int global_index = start_index + i;
			if (slot.received[static_cast<size_t>(global_index)]) {
				present[static_cast<size_t>(i)] = &slot.chunks[static_cast<size_t>(global_index)];
			} else {
				missing_count++;
				missing_local_index = i;
			}
		}

		if (missing_count != 1)
			continue;

		auto recovered = FecDecoder::recover(group.payload.data(), group.payload.size(), group_size,
						      missing_local_index, present);
		if (recovered.empty())
			continue;

		int global_index = start_index + missing_local_index;
		slot.chunks[static_cast<size_t>(global_index)] = std::move(recovered);
		slot.received[static_cast<size_t>(global_index)] = true;
		slot.received_count++;
	}
}

void FrameReassembler::try_complete(FrameBuffer &slot)
{
	if (slot.completed)
		return;
	if (slot.received_count < slot.total_packets)
		return;

	slot.completed = true;
	completed_frame_count_++;
	if (completed_frame_count_ <= 3 || completed_frame_count_ % 90 == 0) {
		obs_log(LOG_INFO, "ucsp: completed frame %u (%s, %d packets, %llu total frames so far)", slot.frame_id,
			slot.is_keyframe ? "keyframe" : "delta", slot.total_packets,
			static_cast<unsigned long long>(completed_frame_count_));
	}

	size_t total_len = 0;
	for (auto &chunk : slot.chunks)
		total_len += chunk.size();

	std::vector<uint8_t> assembled;
	assembled.reserve(total_len);
	for (auto &chunk : slot.chunks)
		assembled.insert(assembled.end(), chunk.begin(), chunk.end());

	double processing_ms = duration<double, std::milli>(steady_clock::now() - slot.first_packet_time).count();
	record_processing_time_ms(processing_ms);

	if (on_frame_ready_)
		on_frame_ready_(assembled.data(), assembled.size(), slot.is_keyframe, slot.pts_us);

	slot.chunks.clear();
	slot.chunks.shrink_to_fit();
	slot.fec_groups.clear();
}

void FrameReassembler::record_window_packet(const Header &header)
{
	std::lock_guard<std::mutex> lock(stats_mutex_);
	window_packets_received_++;
	if (!window_has_seq_) {
		window_seq_min_ = window_seq_max_ = header.sequence_number;
		window_has_seq_ = true;
	} else {
		window_seq_min_ = std::min(window_seq_min_, header.sequence_number);
		window_seq_max_ = std::max(window_seq_max_, header.sequence_number);
	}
}

void FrameReassembler::record_processing_time_ms(double ms)
{
	std::lock_guard<std::mutex> lock(stats_mutex_);
	window_processing_accum_ms_ += ms;
	window_processing_count_++;
}

BackchannelReportPayload FrameReassembler::snapshot_and_reset_window_stats()
{
	std::lock_guard<std::mutex> lock(stats_mutex_);

	BackchannelReportPayload payload;
	payload.last_frame_id_received = last_frame_id_received_;
	payload.packets_received_window = window_packets_received_;
	payload.packets_expected_window =
		window_has_seq_ ? static_cast<uint16_t>(std::min<uint32_t>(65535, window_seq_max_ - window_seq_min_ + 1)) : 0;
	payload.estimated_jitter_ms_x10 = static_cast<uint16_t>(std::min(65535.0, jitter_ms_ewma_ * 10.0));

	if (payload.packets_expected_window > 0) {
		int loss = 100 - static_cast<int>((100.0 * payload.packets_received_window) / payload.packets_expected_window);
		payload.estimated_packet_loss_percent = static_cast<uint8_t>(std::clamp(loss, 0, 100));
	} else {
		payload.estimated_packet_loss_percent = 0;
	}

	payload.avg_frame_processing_time_ms =
		window_processing_count_ > 0
			? static_cast<uint16_t>(window_processing_accum_ms_ / window_processing_count_)
			: 0;
	payload.flags = 0;

	window_packets_received_ = 0;
	window_has_seq_ = false;
	window_seq_min_ = 0;
	window_seq_max_ = 0;
	window_processing_accum_ms_ = 0.0;
	window_processing_count_ = 0;

	return payload;
}

} // namespace ucsp
