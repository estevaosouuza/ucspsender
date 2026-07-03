package com.ucsp.sender

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ucsp.sender.databinding.ActivityMainBinding
import com.ucsp.sender.service.StreamingService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isStreaming = false

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

        val intent = Intent(this, StreamingService::class.java).apply {
            putExtra(StreamingService.EXTRA_PC_IP, pcIp)
            putExtra(StreamingService.EXTRA_PC_PORT, pcPort)
        }
        ContextCompat.startForegroundService(this, intent)

        isStreaming = true
        binding.buttonStartStop.setText(R.string.button_stop)
        binding.textStatus.setText(R.string.status_streaming)
    }

    private fun stopStreaming() {
        stopService(Intent(this, StreamingService::class.java))
        isStreaming = false
        binding.buttonStartStop.setText(R.string.button_start)
        binding.textStatus.setText(R.string.status_idle)
    }
}
