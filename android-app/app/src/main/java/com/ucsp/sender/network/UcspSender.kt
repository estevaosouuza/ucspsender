package com.ucsp.sender.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
    context: Context,
    private val remoteHost: String,
    private val remotePort: Int,
    private val backchannelListener: UcspBackchannelListener
) {
    companion object {
        private const val TAG = "UcspSender"
        private const val RECEIVE_TIMEOUT_MS = 200
        private const val RECEIVE_BUFFER_SIZE = 2048
    }

    private val appContext = context.applicationContext
    private val socket = DatagramSocket()
    private val remoteAddress: InetAddress = InetAddress.getByName(remoteHost)

    @Volatile
    private var running = false
    private var receiveThread: Thread? = null
    private var sentDatagramCount = 0L

    init {
        bindToWifiNetworkIfAvailable()
    }

    // Many networks used with this app have no internet access (a LAN-only PC+phone
    // link, a repeater, a 2.4GHz-only band, etc.). Android may still treat mobile data as
    // the "default" network in that case, so a plain unbound socket can silently send
    // through cellular instead of Wi-Fi -- everything looks connected but nothing ever
    // reaches the PC. Binding the socket to whichever network currently reports a Wi-Fi
    // transport makes traffic follow that network's own routing table regardless of
    // internet validation status.
    private fun bindToWifiNetworkIfAvailable() {
        try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val wifiNetwork = cm.allNetworks.firstOrNull { network ->
                cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
            if (wifiNetwork == null) {
                Log.w(TAG, "No active Wi-Fi network found to bind to, using default routing")
                return
            }
            wifiNetwork.bindSocket(socket)
            Log.i(TAG, "Socket explicitly bound to the active Wi-Fi network")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to bind socket to Wi-Fi network, using default routing", e)
        }
    }

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
