package com.ucsp.sender.network

import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Single UDP socket used both to send VIDEO_DATA/FEC_PARITY datagrams to the PC and to
 * receive its backchannel replies (ucsp-spec.md §4/§5). Must be started/stopped from a
 * background thread — never the main/UI thread.
 */
class UcspSender(
    private val remoteHost: String,
    private val remotePort: Int,
    private val backchannelListener: UcspBackchannelListener
) {
    companion object {
        private const val TAG = "UcspSender"
        private const val RECEIVE_TIMEOUT_MS = 200
        private const val RECEIVE_BUFFER_SIZE = 2048
    }

    private val socket = DatagramSocket()
    private val remoteAddress: InetAddress = InetAddress.getByName(remoteHost)

    @Volatile
    private var running = false
    private var receiveThread: Thread? = null
    private var sentDatagramCount = 0L

    fun start() {
        if (running) return
        running = true
        socket.soTimeout = RECEIVE_TIMEOUT_MS
        receiveThread = Thread(::receiveLoop, "UcspBackchannelRx").also { it.start() }
        Log.i(TAG, "UcspSender socket bound, local port=${socket.localPort}, target=$remoteAddress:$remotePort")
    }

    fun send(datagrams: List<ByteArray>) {
        for (datagram in datagrams) {
            try {
                socket.send(DatagramPacket(datagram, datagram.size, remoteAddress, remotePort))
                sentDatagramCount++
                if (sentDatagramCount <= 3 || sentDatagramCount % 150 == 0L) {
                    Log.i(TAG, "Sent $sentDatagramCount datagrams so far to $remoteAddress:$remotePort")
                }
            } catch (e: IOException) {
                Log.w(TAG, "Failed to send UCSP datagram", e)
            }
        }
    }

    fun stop() {
        running = false
        receiveThread?.join(500)
        socket.close()
    }

    private fun receiveLoop() {
        val buffer = ByteArray(RECEIVE_BUFFER_SIZE)
        while (running) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                UcspBackchannelParser.dispatch(packet.data, packet.length, backchannelListener)
            } catch (_: SocketTimeoutException) {
                // expected: lets the loop re-check `running` without blocking forever
            } catch (e: IOException) {
                if (running) Log.w(TAG, "UDP receive error", e)
            }
        }
    }
}
