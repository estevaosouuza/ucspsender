package com.ucsp.sender.network

import java.nio.ByteBuffer
import java.nio.ByteOrder

object UcspPacketType {
    const val VIDEO_DATA = 0
    const val FEC_PARITY = 1
    const val BACKCHANNEL_REPORT = 2
    const val KEYFRAME_REQUEST = 3
    const val HELLO = 4
    const val HELLO_ACK = 5
}

object UcspFlags {
    const val IS_KEYFRAME = 0x01
    const val IS_FEC_PACKET = 0x02
}

object UcspCodec {
    const val H264 = 0
}

const val UCSP_VERSION = 1
const val UCSP_HEADER_SIZE = 32
const val UCSP_MAX_PAYLOAD_SIZE = 1368

/**
 * Wire-format mirror of docs/protocol/ucsp-spec.md §1. FrameId/SequenceNumber are kept as
 * Long (signed) on the Kotlin side purely to have headroom above 2^31 without overflow;
 * only the low 32 bits are ever written to/read from the wire.
 */
data class UcspHeader(
    val version: Int = UCSP_VERSION,
    val packetType: Int,
    val streamId: Int = 0,
    val flags: Int = 0,
    val frameId: Long,
    val packetIndex: Int,
    val totalPackets: Int,
    val fecGroupSize: Int = 0,
    val codec: Int = UcspCodec.H264,
    val payloadLength: Int,
    val presentationTimestampUs: Long,
    val sequenceNumber: Long,
    val reserved: Int = 0
) {
    val isKeyframe: Boolean get() = (flags and UcspFlags.IS_KEYFRAME) != 0
    val isFecPacket: Boolean get() = (flags and UcspFlags.IS_FEC_PACKET) != 0

    fun writeTo(buffer: ByteBuffer) {
        val order = buffer.order()
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(version.toByte())
        buffer.put(packetType.toByte())
        buffer.put(streamId.toByte())
        buffer.put(flags.toByte())
        buffer.putInt(frameId.toInt())
        buffer.putShort(packetIndex.toShort())
        buffer.putShort(totalPackets.toShort())
        buffer.put(fecGroupSize.toByte())
        buffer.put(codec.toByte())
        buffer.putShort(payloadLength.toShort())
        buffer.putLong(presentationTimestampUs)
        buffer.putInt(sequenceNumber.toInt())
        buffer.putInt(reserved)
        buffer.order(order)
    }

    fun toByteArray(): ByteArray {
        val buffer = ByteBuffer.allocate(UCSP_HEADER_SIZE)
        writeTo(buffer)
        return buffer.array()
    }

    companion object {
        fun parse(buffer: ByteBuffer): UcspHeader {
            val order = buffer.order()
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            val version = buffer.get().toInt() and 0xFF
            val packetType = buffer.get().toInt() and 0xFF
            val streamId = buffer.get().toInt() and 0xFF
            val flags = buffer.get().toInt() and 0xFF
            val frameId = (buffer.int.toLong() and 0xFFFFFFFFL)
            val packetIndex = buffer.short.toInt() and 0xFFFF
            val totalPackets = buffer.short.toInt() and 0xFFFF
            val fecGroupSize = buffer.get().toInt() and 0xFF
            val codec = buffer.get().toInt() and 0xFF
            val payloadLength = buffer.short.toInt() and 0xFFFF
            val pts = buffer.long
            val sequenceNumber = (buffer.int.toLong() and 0xFFFFFFFFL)
            val reserved = buffer.int
            buffer.order(order)
            return UcspHeader(
                version = version,
                packetType = packetType,
                streamId = streamId,
                flags = flags,
                frameId = frameId,
                packetIndex = packetIndex,
                totalPackets = totalPackets,
                fecGroupSize = fecGroupSize,
                codec = codec,
                payloadLength = payloadLength,
                presentationTimestampUs = pts,
                sequenceNumber = sequenceNumber,
                reserved = reserved
            )
        }
    }
}
