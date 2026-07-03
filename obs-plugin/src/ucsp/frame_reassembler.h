#pragma once

#include <array>
#include <chrono>
#include <cstdint>
#include <functional>
#include <map>
#include <mutex>
#include <vector>

#include "ucsp_protocol.h"

namespace ucsp {

// Reassembles VIDEO_DATA + FEC_PARITY packets into complete Annex-B access units, per
// ucsp-spec.md §2/§3/§7. Frames are tracked in a small fixed ring keyed by FrameId; an
// older, still-incomplete frame is discarded immediately (never blocked on) as soon as a
// newer FrameId needs its ring slot, which is what implements the "zero freeze, degrade
// instead" philosophy on the receive side.
//
// on_packet() must only be called from a single thread (the UdpReceiver's receive
// thread) -- the ring buffer itself is not synchronized. Only snapshot_and_reset_window_stats()
// is safe to call from a different thread (the BackchannelSender's timer thread).
class FrameReassembler {
public:
	using FrameReadyCallback = std::function<void(const uint8_t *data, size_t len, bool is_keyframe, uint64_t pts_us)>;
	using FrameDiscardedCallback = std::function<void()>;

	FrameReassembler();

	void set_frame_ready_callback(FrameReadyCallback cb) { on_frame_ready_ = std::move(cb); }
	void set_frame_discarded_callback(FrameDiscardedCallback cb) { on_frame_discarded_ = std::move(cb); }

	void on_packet(const Header &header, const uint8_t *payload, size_t payload_len);

	// Thread-safe. Returns the report for the window since the last call and resets the
	// window-scoped counters (packets/processing time); continuous stats (last frame id,
	// jitter EWMA) are left untouched.
	BackchannelReportPayload snapshot_and_reset_window_stats();

private:
	static constexpr size_t RING_SIZE = 4;

	struct FecGroupInfo {
		std::vector<uint8_t> payload;
		int group_size = 0;
	};

	struct FrameBuffer {
		bool active = false;
		bool completed = false;
		uint32_t frame_id = 0;
		int total_packets = 0;
		bool is_keyframe = false;
		uint64_t pts_us = 0;
		std::vector<std::vector<uint8_t>> chunks;
		std::vector<bool> received;
		int received_count = 0;
		std::chrono::steady_clock::time_point first_packet_time;
		std::map<int, FecGroupInfo> fec_groups; // keyed by group start index
	};

	std::array<FrameBuffer, RING_SIZE> ring_{};

	FrameBuffer *acquire_slot(const Header &header);
	void handle_video_data(const Header &header, const uint8_t *payload, size_t payload_len);
	void handle_fec_parity(const Header &header, const uint8_t *payload, size_t payload_len);
	void attempt_recovery(FrameBuffer &slot);
	void try_complete(FrameBuffer &slot);
	void record_window_packet(const Header &header);
	void record_processing_time_ms(double ms);

	FrameReadyCallback on_frame_ready_;
	FrameDiscardedCallback on_frame_discarded_;

	// Receive-thread-only (never read elsewhere), used for the per-frame jitter estimate.
	bool has_last_frame_arrival_ = false;
	std::chrono::steady_clock::time_point last_frame_arrival_tp_;
	uint64_t last_frame_pts_us_ = 0;
	uint64_t completed_frame_count_ = 0;

	// Shared with the backchannel timer thread; guarded by stats_mutex_.
	std::mutex stats_mutex_;
	bool has_last_frame_id_ = false;
	uint32_t last_frame_id_received_ = 0;
	double jitter_ms_ewma_ = 0.0;
	uint16_t window_packets_received_ = 0;
	bool window_has_seq_ = false;
	uint32_t window_seq_min_ = 0;
	uint32_t window_seq_max_ = 0;
	double window_processing_accum_ms_ = 0.0;
	uint32_t window_processing_count_ = 0;
};

} // namespace ucsp
