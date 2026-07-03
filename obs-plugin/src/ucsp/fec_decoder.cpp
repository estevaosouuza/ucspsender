#include "fec_decoder.h"
#include "ucsp_protocol.h"

#include <cstring>

namespace ucsp {

namespace {

uint16_t read_u16le(const uint8_t *buf)
{
	return static_cast<uint16_t>(buf[0]) | (static_cast<uint16_t>(buf[1]) << 8);
}

} // namespace

std::vector<uint8_t> FecDecoder::recover(const uint8_t *parity_payload, size_t parity_payload_len, int group_size,
					  int missing_index, const std::vector<const std::vector<uint8_t> *> &present_chunks)
{
	if (missing_index < 0 || missing_index >= group_size)
		return {};
	if (present_chunks.size() != static_cast<size_t>(group_size))
		return {};

	const size_t length_table_size = static_cast<size_t>(group_size) * 2;
	if (parity_payload_len < length_table_size + MAX_PAYLOAD_SIZE)
		return {};

	const uint16_t missing_len = read_u16le(parity_payload + missing_index * 2);
	if (missing_len > MAX_PAYLOAD_SIZE)
		return {};

	uint8_t recovered[MAX_PAYLOAD_SIZE];
	memcpy(recovered, parity_payload + length_table_size, MAX_PAYLOAD_SIZE);

	for (int i = 0; i < group_size; i++) {
		if (i == missing_index)
			continue;
		const std::vector<uint8_t> *chunk = present_chunks[static_cast<size_t>(i)];
		if (!chunk)
			return {}; // more than one chunk missing in this group: unrecoverable
		for (size_t b = 0; b < chunk->size(); b++)
			recovered[b] ^= (*chunk)[b];
	}

	return std::vector<uint8_t>(recovered, recovered + missing_len);
}

} // namespace ucsp
