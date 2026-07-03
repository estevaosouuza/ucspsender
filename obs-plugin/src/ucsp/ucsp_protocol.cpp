#include "ucsp_protocol.h"

#include <cstring>

namespace ucsp {

namespace {

void put_u16le(uint8_t *buf, uint16_t v)
{
	buf[0] = static_cast<uint8_t>(v & 0xFF);
	buf[1] = static_cast<uint8_t>((v >> 8) & 0xFF);
}

void put_u32le(uint8_t *buf, uint32_t v)
{
	buf[0] = static_cast<uint8_t>(v & 0xFF);
	buf[1] = static_cast<uint8_t>((v >> 8) & 0xFF);
	buf[2] = static_cast<uint8_t>((v >> 16) & 0xFF);
	buf[3] = static_cast<uint8_t>((v >> 24) & 0xFF);
}

void put_u64le(uint8_t *buf, uint64_t v)
{
	for (int i = 0; i < 8; i++)
		buf[i] = static_cast<uint8_t>((v >> (8 * i)) & 0xFF);
}

uint16_t get_u16le(const uint8_t *buf)
{
	return static_cast<uint16_t>(buf[0]) | (static_cast<uint16_t>(buf[1]) << 8);
}

uint32_t get_u32le(const uint8_t *buf)
{
	return static_cast<uint32_t>(buf[0]) | (static_cast<uint32_t>(buf[1]) << 8) |
	       (static_cast<uint32_t>(buf[2]) << 16) | (static_cast<uint32_t>(buf[3]) << 24);
}

uint64_t get_u64le(const uint8_t *buf)
{
	uint64_t v = 0;
	for (int i = 0; i < 8; i++)
		v |= static_cast<uint64_t>(buf[i]) << (8 * i);
	return v;
}

} // namespace

void Header::write(uint8_t *buf) const
{
	buf[0] = version;
	buf[1] = packet_type;
	buf[2] = stream_id;
	buf[3] = flags;
	put_u32le(buf + 4, frame_id);
	put_u16le(buf + 8, packet_index);
	put_u16le(buf + 10, total_packets);
	buf[12] = fec_group_size;
	buf[13] = codec;
	put_u16le(buf + 14, payload_length);
	put_u64le(buf + 16, presentation_timestamp_us);
	put_u32le(buf + 24, sequence_number);
	put_u32le(buf + 28, reserved);
}

Header Header::parse(const uint8_t *buf)
{
	Header h;
	h.version = buf[0];
	h.packet_type = buf[1];
	h.stream_id = buf[2];
	h.flags = buf[3];
	h.frame_id = get_u32le(buf + 4);
	h.packet_index = get_u16le(buf + 8);
	h.total_packets = get_u16le(buf + 10);
	h.fec_group_size = buf[12];
	h.codec = buf[13];
	h.payload_length = get_u16le(buf + 14);
	h.presentation_timestamp_us = get_u64le(buf + 16);
	h.sequence_number = get_u32le(buf + 24);
	h.reserved = get_u32le(buf + 28);
	return h;
}

void BackchannelReportPayload::write(uint8_t *buf) const
{
	put_u32le(buf, last_frame_id_received);
	put_u16le(buf + 4, packets_expected_window);
	put_u16le(buf + 6, packets_received_window);
	put_u16le(buf + 8, estimated_jitter_ms_x10);
	buf[10] = estimated_packet_loss_percent;
	put_u16le(buf + 11, avg_frame_processing_time_ms);
	buf[13] = flags;
}

BackchannelReportPayload BackchannelReportPayload::parse(const uint8_t *buf)
{
	BackchannelReportPayload p;
	p.last_frame_id_received = get_u32le(buf);
	p.packets_expected_window = get_u16le(buf + 4);
	p.packets_received_window = get_u16le(buf + 6);
	p.estimated_jitter_ms_x10 = get_u16le(buf + 8);
	p.estimated_packet_loss_percent = buf[10];
	p.avg_frame_processing_time_ms = get_u16le(buf + 11);
	p.flags = buf[13];
	return p;
}

} // namespace ucsp
