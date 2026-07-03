package com.ucsp.sender.network

import java.nio.ByteBuffer

/**
 * Splits one encoded H.264 access unit into UCSP VIDEO_DATA datagrams plus their XOR FEC
 * parity datagrams (ucsp-spec.md §2/§3). One instance owns the per-stream FrameId/
 * SequenceNumber counters, so it must be reused across the whole streaming session rather
 * than recreated per frame.
 */
class UcspPacketizer(
    private val streamId: Int = 0,
    private val fecGroupSize: Int = 5
) {
    private var frameIdCounter = 0L
    private var sequenceNumberCounter = 0L

    fun packetize(accessUnit: ByteArray, isKeyframe: Boolean, presentationTimeUs: Long): List<ByteArray> {
        val frameId = frameIdCounter++
        val chunks = chunk(accessUnit)
        val totalPackets = chunks.size
        val baseFlags = if (isKeyframe) UcspFlags.IS_KEYFRAME else 0
        val datagrams = ArrayList<ByteArray>(totalPackets + totalPackets / fecGroupSize + 1)

        chunks.forEachIndexed { index, payload ->
            val header = UcspHeader(
                packetType = UcspPacketType.VIDEO_DATA,
                streamId = streamId,
                flags = baseFlags,
                frameId = frameId,
                packetIndex = index,
                totalPackets = totalPackets,
                fecGroupSize = 0,
                payloadLength = payload.size,
                presentationTimestampUs = presentationTimeUs,
                sequenceNumber = sequenceNumberCounter++
            )
            datagrams += buildDatagram(header, payload)
        }

        FecEncoder.buildParityGroups(chunks, fecGroupSize).forEach { group ->
            val header = UcspHeader(
                packetType = UcspPacketType.FEC_PARITY,
                streamId = streamId,
                flags = baseFlags or UcspFlags.IS_FEC_PACKET,
                frameId = frameId,
                packetIndex = group.startIndex,
                totalPackets = totalPackets,
                fecGroupSize = group.groupSize,
                payloadLength = group.payload.size,
                presentationTimestampUs = presentationTimeUs,
                sequenceNumber = sequenceNumberCounter++
            )
            datagrams += buildDatagram(header, group.payload)
        }

        return datagrams
    }

    private fun chunk(accessUnit: ByteArray): List<ByteArray> {
        if (accessUnit.isEmpty()) return emptyList()
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < accessUnit.size) {
            val end = minOf(offset + UCSP_MAX_PAYLOAD_SIZE, accessUnit.size)
            chunks += accessUnit.copyOfRange(offset, end)
            offset = end
        }
        return chunks
    }

    private fun buildDatagram(header: UcspHeader, payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(UCSP_HEADER_SIZE + payload.size)
        header.writeTo(buffer)
        buffer.put(payload)
        return buffer.array()
    }
}
