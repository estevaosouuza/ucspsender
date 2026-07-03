package com.ucsp.sender.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.ucsp.sender.R
import com.ucsp.sender.adaptive.AdaptiveController
import com.ucsp.sender.capture.CameraController
import com.ucsp.sender.encode.H264Encoder
import com.ucsp.sender.network.BackchannelReport
import com.ucsp.sender.network.UcspBackchannelListener
import com.ucsp.sender.network.UcspPacketizer
import com.ucsp.sender.network.UcspSender
import com.ucsp.sender.thermal.ThermalMonitor
import java.util.concurrent.Executors

class StreamingService : LifecycleService() {

    companion object {
        const val EXTRA_PC_IP = "pc_ip"
        const val EXTRA_PC_PORT = "pc_port"
        private const val NOTIFICATION_CHANNEL_ID = "ucsp_streaming"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "StreamingService"

        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val VIDEO_FPS = 30
        private const val VIDEO_BITRATE_BPS = 4_000_000
    }

    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val packetizer = UcspPacketizer()
    private val adaptiveController = AdaptiveController()

    private var cameraController: CameraController? = null
    private var encoder: H264Encoder? = null
    private var sender: UcspSender? = null
    private var thermalMonitor: ThermalMonitor? = null

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val pcIp = intent?.getStringExtra(EXTRA_PC_IP).orEmpty()
        val pcPort = intent?.getIntExtra(EXTRA_PC_PORT, 0) ?: 0
        startForeground(NOTIFICATION_ID, buildNotification(pcIp, pcPort))
        startPipeline(pcIp, pcPort)
        return START_STICKY
    }

    private fun startPipeline(pcIp: String, pcPort: Int) {
        if (pcIp.isEmpty() || pcPort == 0) {
            Log.e(TAG, "Missing PC IP/port, not starting pipeline")
            return
        }

        val backchannelListener = object : UcspBackchannelListener {
            override fun onReport(report: BackchannelReport) {
                adaptiveController.onBackchannelReport(report)
            }

            override fun onKeyframeRequest() {
                encoder?.requestKeyframe()
            }
        }

        val h264Encoder = H264Encoder(
            width = VIDEO_WIDTH,
            height = VIDEO_HEIGHT,
            fps = VIDEO_FPS,
            bitrateBps = VIDEO_BITRATE_BPS
        ) { accessUnit, isKeyframe, presentationTimeUs ->
            val datagrams = packetizer.packetize(accessUnit, isKeyframe, presentationTimeUs)
            networkExecutor.execute { sender?.send(datagrams) }
        }
        encoder = h264Encoder

        networkExecutor.execute {
            val ucspSender = UcspSender(pcIp, pcPort, backchannelListener)
            sender = ucspSender
            ucspSender.start()
        }

        val surface = h264Encoder.createInputSurface()

        val camera = CameraController(this, this)
        cameraController = camera
        camera.start(surface, VIDEO_WIDTH, VIDEO_HEIGHT, VIDEO_FPS)

        thermalMonitor = ThermalMonitor(this).apply { start() }
    }

    override fun onDestroy() {
        cameraController?.stop()
        encoder?.release()
        sender?.let { s -> networkExecutor.execute { s.stop() } }
        thermalMonitor?.stop()
        networkExecutor.shutdown()
        super.onDestroy()
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
