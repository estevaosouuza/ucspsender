package com.ucsp.sender

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.AdapterView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ucsp.sender.adaptive.AdaptiveController
import com.ucsp.sender.capture.CameraController
import com.ucsp.sender.databinding.ActivityMainBinding
import com.ucsp.sender.encode.H264Encoder
import com.ucsp.sender.network.BackchannelReport
import com.ucsp.sender.network.UcspBackchannelListener
import com.ucsp.sender.network.UcspPacketizer
import com.ucsp.sender.network.UcspSender
import com.ucsp.sender.network.WifiSignalMonitor
import com.ucsp.sender.service.StreamingService
import com.ucsp.sender.thermal.ThermalMonitor
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val STATE_IS_STREAMING = "is_streaming"

        // Index-matched to R.array.resolution_labels / R.array.fps_labels, ordered
        // lowest-latency-first (lower resolution/fps = less data to encode and send per
        // frame = lower latency on a constrained network, at the cost of image quality).
        private val RESOLUTIONS = listOf(854 to 480, 1280 to 720, 1920 to 1080)
        private val FPS_OPTIONS = listOf(15, 24, 30)
        private const val DEFAULT_RESOLUTION_INDEX = 1 // 720p
        private const val DEFAULT_FPS_INDEX = 2 // 30fps
        private const val BITRATE_BPS = 4_000_000
    }

    private lateinit var binding: ActivityMainBinding
    private var isStreaming = false

    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val packetizer = UcspPacketizer()
    private val adaptiveController = AdaptiveController()

    private var cameraController: CameraController? = null
    private var encoder: H264Encoder? = null
    private var sender: UcspSender? = null
    private var thermalMonitor: ThermalMonitor? = null
    private var wifiSignalMonitor: WifiSignalMonitor? = null

    private val requestCameraPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startStreaming() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.buttonStartStop.setOnClickListener {
            if (isStreaming) stopStreaming() else requestCameraThenStart()
        }

        wifiSignalMonitor = WifiSignalMonitor(this) { level, maxLevel ->
            runOnUiThread { binding.textSignal.text = getString(R.string.signal_format, level, maxLevel) }
        }

        if (savedInstanceState?.getBoolean(STATE_IS_STREAMING) == true) {
            requestCameraThenStart()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_IS_STREAMING, isStreaming)
    }

    override fun onStart() {
        super.onStart()
        wifiSignalMonitor?.start()
    }

    override fun onStop() {
        wifiSignalMonitor?.stop()
        super.onStop()
    }

    private fun requestCameraThenStart() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            startStreaming()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startStreaming() {
        val pcIp = binding.editPcIp.text.toString()
        val pcPort = binding.editPcPort.text.toString().toIntOrNull() ?: return

        val resolutionIndex = binding.spinnerResolution.selectedItemPosition.takeIf { it != AdapterView.INVALID_POSITION }
            ?: DEFAULT_RESOLUTION_INDEX
        val fpsIndex = binding.spinnerFps.selectedItemPosition.takeIf { it != AdapterView.INVALID_POSITION }
            ?: DEFAULT_FPS_INDEX
        val (width, height) = RESOLUTIONS[resolutionIndex]
        val fps = FPS_OPTIONS[fpsIndex]

        val backchannelListener = object : UcspBackchannelListener {
            override fun onReport(report: BackchannelReport) {
                adaptiveController.onBackchannelReport(report)
            }

            override fun onKeyframeRequest() {
                encoder?.requestKeyframe()
            }
        }

        val h264Encoder = H264Encoder(
            width = width,
            height = height,
            fps = fps,
            bitrateBps = BITRATE_BPS,
            onFatalError = { e -> handleFatalError("Falha ao iniciar o encoder de vídeo", e) }
        ) { accessUnit, isKeyframe, presentationTimeUs ->
            val datagrams = packetizer.packetize(accessUnit, isKeyframe, presentationTimeUs)
            networkExecutor.execute { sender?.send(datagrams) }
        }
        encoder = h264Encoder
        val encoderSurface = h264Encoder.createInputSurface()
        if (encoderSurface == null) {
            encoder = null
            return
        }

        networkExecutor.execute {
            try {
                val ucspSender = UcspSender(pcIp, pcPort, backchannelListener)
                sender = ucspSender
                ucspSender.start()
                Log.i(TAG, "UcspSender started, target=$pcIp:$pcPort")
            } catch (e: Exception) {
                handleFatalError("Falha ao iniciar o envio de rede (IP/porta inválidos?)", e)
            }
        }

        val camera = CameraController(this, this)
        cameraController = camera
        camera.start(
            encoderSurface, width, height, fps,
            onFatalError = { e -> handleFatalError("Falha ao iniciar a câmera", e) }
        ) { bitmap ->
            runOnUiThread { binding.previewImage.setImageBitmap(bitmap) }
        }

        thermalMonitor = ThermalMonitor(this).apply { start() }

        ContextCompat.startForegroundService(
            this,
            Intent(this, StreamingService::class.java).apply {
                putExtra(StreamingService.EXTRA_PC_IP, pcIp)
                putExtra(StreamingService.EXTRA_PC_PORT, pcPort)
            }
        )

        isStreaming = true
        binding.buttonStartStop.setText(R.string.button_stop)
        binding.textStatus.setText(R.string.status_streaming)
    }

    private fun stopStreaming() {
        cameraController?.stop()
        cameraController = null
        encoder?.release()
        encoder = null
        sender?.let { s -> networkExecutor.execute { s.stop() } }
        sender = null
        thermalMonitor?.stop()
        thermalMonitor = null

        stopService(Intent(this, StreamingService::class.java))

        isStreaming = false
        binding.buttonStartStop.setText(R.string.button_start)
        binding.textStatus.setText(R.string.status_idle)
    }

    private fun handleFatalError(message: String, e: Throwable) {
        Log.e(TAG, message, e)
        runOnUiThread {
            Toast.makeText(this, "$message: ${e.message}", Toast.LENGTH_LONG).show()
            // Tear down whatever partially started; stopStreaming() is safe to call even
            // if some pieces (camera, sender) never got created.
            stopStreaming()
        }
    }

    override fun onDestroy() {
        if (isStreaming) {
            Log.i(TAG, "Activity destroyed while streaming (e.g. rotation) -- pipeline torn down, will restart in new instance")
            cameraController?.stop()
            encoder?.release()
            sender?.let { s -> networkExecutor.execute { s.stop() } }
            thermalMonitor?.stop()
        }
        networkExecutor.shutdown()
        super.onDestroy()
    }
}
