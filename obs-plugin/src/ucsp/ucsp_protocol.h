#pragma once

#include <cstddef>
#include <cstdint>

// C++ mirror of docs/protocol/ucsp-spec.md. Implemented independently of the Kotlin
// sender (android-app/.../network/UcspHeader.kt) -- only the wire bytes need to agree.
// Fields are parsed/written byte-by-byte in ucsp_protocol.cpp; never reinterpret_cast a
// raw buffer as `Header*`, since compiler struct padding is not guaranteed to match the
// wire layout.
namespace ucsp {

constexpr uint8_t VERSION = 1;
constexpr size_t HEADER_SIZE = 32;
constexpr size_t MAX_PAYLOAD_SIZE = 1368;

enum PacketType : uint8_t {
	PACKET_VIDEO_DATA = 0,
	PACKET_FEC_PARITY = 1,
	PACKET_BACKCHANNEL_REPORT = 2,
	PACKET_KEYFRAME_REQUEST = 3,
	PACKET_HELLO = 4,
	PACKET_HELLO_ACK = 5,
};

enum Flags : uint8_t {
	FLAG_IS_KEYFRAME = 0x01,
	FLAG_IS_FEC_PACKET = 0x02,
};

enum Codec : uint8_t {
	CODEC_H264 = 0,
};

struct Header {
	uint8_t version = VERSION;
	uint8_t packet_type = 0;
	uint8_t stream_id = 0;
	uint8_t flags = 0;
	uint32_t frame_id = 0;
	uint16_t packet_index = 0;
	uint16_t total_packets = 0;
	uint8_t fec_group_size = 0;
	uint8_t codec = CODEC_H264;
	uint16_t payload_length = 0;
	uint64_t presentation_timestamp_us = 0;
	uint32_t sequence_number = 0;
	uint32_t reserved = 0;

	bool is_keyframe() const { return (flags & FLAG_IS_KEYFRAME) != 0; }
	bool is_fec_packet() const { return (flags & FLAG_IS_FEC_PACKET) != 0; }

	// Writes HEADER_SIZE bytes to buf.
	void write(uint8_t *buf) const;

	// Reads HEADER_SIZE bytes from buf. Caller must ensure buf has at least HEADER_SIZE
	// bytes available.
	static Header parse(const uint8_t *buf);
};

// Payload layout of a BACKCHANNEL_REPORT packet (sent OBS -> phone).
struct BackchannelReportPayload {
	uint32_t last_frame_id_received = 0;
	uint16_t packets_expected_window = 0;
	uint16_t packets_received_window = 0;
	uint16_t estimated_jitter_ms_x10 = 0;
	uint8_t estimated_packet_loss_percent = 0;
	uint16_t avg_frame_processing_time_ms = 0;
	uint8_t flags = 0;

	static constexpr size_t SIZE = 14;

	void write(uint8_t *buf) const;
	static BackchannelReportPayload parse(const uint8_t *buf);
};

} // namespace ucsp
