#pragma once

#include <cstdint>
#include <vector>

// XOR (Pro-MPEG COP3-style) parity recovery, per ucsp-spec.md §3. Stateless: the caller
// (FrameReassembler) owns the per-frame chunk buffers and only calls this when exactly
// one chunk in a FEC group is missing.
namespace ucsp {

class FecDecoder {
public:
	// parity_payload is the FEC_PARITY packet's payload: a group_size*uint16 length
	// table followed by the XOR of the group's chunks (each zero-padded to
	// MAX_PAYLOAD_SIZE). present_chunks must have exactly group_size entries, one
	// nullptr at missing_index and the group's real (unpadded) chunk bytes elsewhere.
	// Returns the recovered chunk trimmed to its true length, or an empty vector if
	// recovery isn't possible (bad input, or more than one chunk actually missing).
	static std::vector<uint8_t> recover(const uint8_t *parity_payload, size_t parity_payload_len, int group_size,
					     int missing_index, const std::vector<const std::vector<uint8_t> *> &present_chunks);
};

} // namespace ucsp
