package com.ucsp.sender.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper

/**
 * Polls the connected Wi-Fi's RSSI periodically and reports a 0..4 signal level (5 bars,
 * matching the typical signal-strength icon convention). Used to drive the on-screen
 * signal indicator -- purely informational in Phase 1 (Phase 2's adaptive engine will
 * react to real network stats from the OBS backchannel instead of this local RSSI).
 */
class WifiSignalMonitor(
    context: Context,
    private val onLevelChanged: (level: Int, maxLevel: Int) -> Unit
) {
    companion object {
        private const val POLL_INTERVAL_MS = 2000L
        private const val SIGNAL_LEVELS = 5 // levels 0..4
        private const val NO_SIGNAL_RSSI = -100 // WifiManager has no public MIN_RSSI constant
    }

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val handler = Handler(Looper.getMainLooper())

    private val poller = object : Runnable {
        override fun run() {
            val rssi = try {
                wifiManager.connectionInfo?.rssi ?: NO_SIGNAL_RSSI
            } catch (e: SecurityException) {
                NO_SIGNAL_RSSI
            }
            val level = WifiManager.calculateSignalLevel(rssi, SIGNAL_LEVELS)
            onLevelChanged(level, SIGNAL_LEVELS - 1)
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    fun start() {
        handler.post(poller)
    }

    fun stop() {
        handler.removeCallbacks(poller)
    }
}
