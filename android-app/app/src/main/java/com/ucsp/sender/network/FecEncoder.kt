package com.ucsp.sender.network

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One XOR parity group, per ucsp-spec.md §3. */
data class FecParityGroup(
    val startIndex: Int,
    val groupSize: Int,
    val payload: ByteArray
)

object FecEncoder {

    /**
     * Splits [chunks] (a frame's payload chunks, each already <= UCSP_MAX_PAYLOAD_SIZE) into
     * groups of up to [groupSize] and returns one parity group per run of >=2 chunks. A
     * trailing group of exactly 1 chunk has nothing to protect and is skipped.
     */
    fun buildParityGroups(chunks: List<ByteArray>, groupSize: Int): List<FecParityGroup> {
        if (groupSize < 2 || chunks.size < 2) return emptyList()
        val groups = mutableListOf<FecParityGroup>()
        var start = 0
        while (start < chunks.size) {
            val end = minOf(start + groupSize, chunks.size)
            val group = chunks.subList(start, end)
            if (group.size >= 2) {
                groups += FecParityGroup(start, group.size, buildGroupParity(group))
            }
            start = end
        }
        return groups
    }

    private fun buildGroupParity(group: List<ByteArray>): ByteArray {
        val lengthTableSize = group.size * 2
        val xored = ByteArray(UCSP_MAX_PAYLOAD_SIZE)
        val tableBytes = ByteArray(lengthTableSize)
        val tableBuffer = ByteBuffer.wrap(tableBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (chunk in group) {
            tableBuffer.putShort(chunk.size.toShort())
            for (i in chunk.indices) {
                xored[i] = (xored[i].toInt() xor chunk[i].toInt()).toByte()
            }
        }
        return tableBytes + xored
    }
}
