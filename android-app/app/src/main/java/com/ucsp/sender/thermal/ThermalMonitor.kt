package com.ucsp.sender.thermal

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * Phase 1: only logs thermal status transitions. Phase 2 wires this into
 * AdaptiveController so the encoder steps down resolution/bitrate before hardware
 * throttling causes encode-latency spikes (Thermal API needs API 29+).
 */
class ThermalMonitor(private val context: Context) {

    companion object {
        private const val TAG = "ThermalMonitor"
    }

    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val newListener = PowerManager.OnThermalStatusChangedListener { status ->
            Log.i(TAG, "Thermal status changed: $status")
        }
        listener = newListener
        powerManager.addThermalStatusListener(newListener)
    }

    fun stop() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        listener?.let { powerManager.removeThermalStatusListener(it) }
        listener = null
    }
}
