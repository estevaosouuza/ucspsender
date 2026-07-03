package com.ucsp.sender.network

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decoded payload of a BACKCHANNEL_REPORT packet, per ucsp-spec.md §4. */
data class BackchannelReport(
    val lastFrameIdReceived: Long,
    val packetsExpectedWindow: Int,
    val packetsReceivedWindow: Int,
    val estimatedJitterMs: Double,
    val estimatedPacketLossPercent: Int,
    val avgFrameProcessingTimeMs: Int
)

interface UcspBackchannelListener {
    fun onReport(report: BackchannelReport)
    fun onKeyframeRequest()
}

/** Parses inbound datagrams received on the sender's own UDP socket (OBS -> phone direction). */
object UcspBackchannelParser {

    fun dispatch(datagram: ByteArray, length: Int, listener: UcspBackchannelListener) {
        if (length < UCSP_HEADER_SIZE) return
        val buffer = ByteBuffer.wrap(datagram, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        val header = UcspHeader.parse(buffer)
        when (header.packetType) {
            UcspPacketType.BACKCHANNEL_REPORT -> parseReport(buffer)?.let(listener::onReport)
            UcspPacketType.KEYFRAME_REQUEST -> listener.onKeyframeRequest()
        }
    }

    private fun parseReport(buffer: ByteBuffer): BackchannelReport? {
        if (buffer.remaining() < 14) return null
        val lastFrameIdReceived = buffer.int.toLong() and 0xFFFFFFFFL
        val packetsExpectedWindow = buffer.short.toInt() and 0xFFFF
        val packetsReceivedWindow = buffer.short.toInt() and 0xFFFF
        val jitterFixedPoint = buffer.short.toInt() and 0xFFFF
        val lossPercent = buffer.get().toInt() and 0xFF
        val avgProcessingMs = buffer.short.toInt() and 0xFFFF
        return BackchannelReport(
            lastFrameIdReceived = lastFrameIdReceived,
            packetsExpectedWindow = packetsExpectedWindow,
            packetsReceivedWindow = packetsReceivedWindow,
            estimatedJitterMs = jitterFixedPoint / 10.0,
            estimatedPacketLossPercent = lossPercent,
            avgFrameProcessingTimeMs = avgProcessingMs
        )
    }
}
