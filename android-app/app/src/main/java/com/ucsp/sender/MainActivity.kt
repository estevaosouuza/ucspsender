package com.ucsp.sender

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.widget.AdapterView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.ucsp.sender.adaptive.AdaptiveController
import com.ucsp.sender.capture.CameraController
import com.ucsp.sender.capture.CameraRenderer
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
        private const val STATE_PC_IP = "pc_ip"
        private const val STATE_PC_PORT = "pc_port"
        private const val STATE_RESOLUTION_INDEX = "resolution_index"
        private const val STATE_FPS_INDEX = "fps_index"

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
    private var cameraRenderer: CameraRenderer? = null
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

        binding.previewSurface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = Unit

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                cameraRenderer?.setPreviewSurface(holder.surface, width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                cameraRenderer?.setPreviewSurface(null, 0, 0)
            }
        })

        if (savedInstanceState?.getBoolean(STATE_IS_STREAMING) == true) {
            // Explicitly re-apply the fields we need instead of relying on the standard
            // view-state restore, which runs *after* onCreate() (in onRestoreInstanceState)
            // -- reading binding.editPcIp.text here before that happens would see an empty
            // field, silently sending to loopback instead of the PC. This is exactly what
            // broke streaming across a screen rotation.
            binding.editPcIp.setText(savedInstanceState.getString(STATE_PC_IP, ""))
            binding.editPcPort.setText(savedInstanceState.getInt(STATE_PC_PORT, 0).toString())
            binding.spinnerResolution.setSelection(savedInstanceState.getInt(STATE_RESOLUTION_INDEX, DEFAULT_RESOLUTION_INDEX))
            binding.spinnerFps.setSelection(savedInstanceState.getInt(STATE_FPS_INDEX, DEFAULT_FPS_INDEX))
            requestCameraThenStart()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_IS_STREAMING, isStreaming)
        outState.putString(STATE_PC_IP, binding.editPcIp.text.toString())
        outState.putInt(STATE_PC_PORT, binding.editPcPort.text.toString().toIntOrNull() ?: 0)
        outState.putInt(STATE_RESOLUTION_INDEX, binding.spinnerResolution.selectedItemPosition)
        outState.putInt(STATE_FPS_INDEX, binding.spinnerFps.selectedItemPosition)
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

        val renderer = CameraRenderer()
        cameraRenderer = renderer
        val cameraFacingSurface = renderer.start(encoderSurface, width, height)
        val previewHolder = binding.previewSurface.holder
        val previewFrame = previewHolder.surfaceFrame
        if (previewHolder.surface?.isValid == true && previewFrame.width() > 0 && previewFrame.height() > 0) {
            renderer.setPreviewSurface(previewHolder.surface, previewFrame.width(), previewFrame.height())
        }

        val camera = CameraController(this, this)
        cameraController = camera
        camera.start(
            cameraFacingSurface, width, height, fps,
            onFatalError = { e -> handleFatalError("Falha ao iniciar a câmera", e) }
        )

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
        cameraRenderer?.release()
        cameraRenderer = null
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
            cameraRenderer?.release()
            encoder?.release()
            sender?.let { s -> networkExecutor.execute { s.stop() } }
            thermalMonitor?.stop()
        }
        networkExecutor.shutdown()
        super.onDestroy()
    }
}
