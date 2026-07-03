package com.ucsp.sender.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ucsp.sender.R

class StreamingService : Service() {

    companion object {
        const val EXTRA_PC_IP = "pc_ip"
        const val EXTRA_PC_PORT = "pc_port"
        private const val NOTIFICATION_CHANNEL_ID = "ucsp_streaming"
        private const val NOTIFICATION_ID = 1
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pcIp = intent?.getStringExtra(EXTRA_PC_IP).orEmpty()
        val pcPort = intent?.getIntExtra(EXTRA_PC_PORT, 0) ?: 0
        startForeground(NOTIFICATION_ID, buildNotification(pcIp, pcPort))
        return START_STICKY
    }

    private fun buildNotification(pcIp: String, pcPort: Int): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "UCSP Streaming",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Enviando para $pcIp:$pcPort")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
    }
}
