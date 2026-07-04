package com.ucsp.sender.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val socket = DatagramSocket()
    private val remoteAddress: InetAddress = InetAddress.getByName(remoteHost)

    @Volatile
    private var running = false
    private var receiveThread: Thread? = null
    private var sentDatagramCount = 0L
    private var wifiNetworkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        trackWifiNetwork()
    }

    // Many networks used with this app have no internet access (a LAN-only PC+phone
    // link, a repeater, a 2.4GHz-only band, etc.). Android may still treat mobile data as
    // the "default" network in that case, so a plain unbound socket can silently send
    // through cellular instead of Wi-Fi -- everything looks connected but nothing ever
    // reaches the PC. Binding the socket to whichever network currently reports a Wi-Fi
    // transport makes traffic follow that network's own routing table regardless of
    // internet validation status.
    //
    // A one-time bind isn't enough: switching Wi-Fi networks mid-stream (different SSID,
    // or even toggling Wi-Fi off/on) destroys the Network the socket was bound to, and a
    // socket bound to a dead network silently stops delivering. Watching for Wi-Fi network
    // changes and re-binding on every onAvailable() keeps the stream alive across a live
    // network switch instead of requiring the user to stop/start streaming again.
    private fun trackWifiNetwork() {
        val cm = connectivityManager ?: return
        // NetworkRequest.Builder() requires NET_CAPABILITY_INTERNET by default, which some
        // LAN-only setups (a dedicated router with no uplink) never satisfy even though
        // the Wi-Fi network itself works fine for local traffic -- drop that requirement so
        // this matches any Wi-Fi network regardless of internet/validation status.
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                try {
                    network.bindSocket(socket)
                    Log.i(TAG, "Socket bound to Wi-Fi network (available/changed)")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to bind socket to Wi-Fi network, using default routing", e)
                }
            }
        }
        try {
            cm.registerNetworkCallback(request, callback)
            wifiNetworkCallback = callback
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register Wi-Fi network callback, using default routing", e)
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
        wifiNetworkCallback?.let { callback ->
            try {
                connectivityManager?.unregisterNetworkCallback(callback)
            } catch (e: IllegalArgumentException) {
                // already unregistered (e.g. the callback never successfully registered)
            }
        }
        wifiNetworkCallback = null
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
