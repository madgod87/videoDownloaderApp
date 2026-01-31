package com.fikfap.downloader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.fikfap.downloader.databinding.ActivityMainBinding
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.core.content.FileProvider
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import android.util.Log

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    
    private val vpnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) startAnalysis()
    }

    private val streamReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                StreamAnalyzerService.EVENT_STREAM_DETECTED -> {
                    val manifestUrl = intent.getStringExtra("manifest_url") ?: return
                    appendToLog("[DETECTION] Media Node Identified: ${manifestUrl.take(40)}...")
                    processHls(manifestUrl)
                }
                TrafficVpnService.EVENT_BROADCAST -> {
                    val msg = intent.getStringExtra("message") ?: ""
                    appendToLog(msg)
                }
                DownloadService.EVENT_PROGRESS -> {
                    val progress = intent.getIntExtra("progress", 0)
                    updateDownloadUi(progress)
                }
                DownloadService.EVENT_COMPLETE -> {
                    val path = intent.getStringExtra("file_path")
                    handleDownloadComplete(path)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupUI()
        appendToLog("FIKFAP DOWNLOADER v4.0 ONLINE")
    }

    private fun setupUI() {
        binding.analyzeButton.setOnClickListener {
            val url = binding.urlInput.text.toString()
            if (url.isNotEmpty()) {
                val vpnIntent = VpnService.prepare(this)
                if (vpnIntent != null) vpnLauncher.launch(vpnIntent) else startAnalysis()
            }
        }

        binding.libraryButton.setOnClickListener {
            startActivity(Intent(this, LibraryActivity::class.java))
        }

        binding.stopButton.setOnClickListener { stopAll() }
        binding.exportButton.setOnClickListener { exportLogs() }
        binding.shareButton.setOnClickListener { shareLogs() }
    }

    private fun startAnalysis() {
        val url = binding.urlInput.text.toString()
        appendToLog("[SYSTEM] Analyzing Quantum Traffic: $url")
        binding.statusText.text = "Status: Monitoring..."
        
        startForegroundServiceIfNecessary(Intent(this, TrafficVpnService::class.java))
        val analyzerIntent = Intent(this, StreamAnalyzerService::class.java).apply {
            action = StreamAnalyzerService.ACTION_START_ANALYSIS
            putExtra(StreamAnalyzerService.EXTRA_URL, url)
        }
        startForegroundServiceIfNecessary(analyzerIntent)
    }

    private fun updateDownloadUi(progress: Int) {
        binding.downloadProgressCard.visibility = View.VISIBLE
        binding.downloadProgressBar.isIndeterminate = false
        binding.downloadProgressBar.progress = progress
        binding.statusText.text = "SECURED: $progress%"
    }

    private fun handleDownloadComplete(path: String?) {
        isHlsProcessing.set(false)
        binding.downloadProgressCard.visibility = View.GONE
        if (path != null) {
            val file = File(path)
            appendToLog("[COMPLETE] Artifact Secured: ${file.name} ✓")
            binding.statusText.text = "Status: READY (Download Complete)"
            Toast.makeText(this, "Artifact added to Library", Toast.LENGTH_SHORT).show()
            
            // AUTO TURN OFF VPN
            stopVpn()
        } else {
            appendToLog("[ERROR] Quantum Link Severed (FFmpeg Fail)")
            binding.statusText.text = "Status: FAILED"
        }
    }

    private fun stopVpn() {
        startService(Intent(this, TrafficVpnService::class.java).apply { action = TrafficVpnService.ACTION_STOP })
        appendToLog("[SYSTEM] VPN Tunnel Collapsed. Security maintained.")
    }

    private fun exportLogs() {
        try {
            val logDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            val fileName = "FikFap_Log_${System.currentTimeMillis()}.txt"
            val file = File(logDir, fileName)
            FileOutputStream(file).use { 
                it.write(binding.logText.text.toString().toByteArray())
            }
            Toast.makeText(this, "Logs saved to Downloads folder", Toast.LENGTH_LONG).show()
            appendToLog("[SYSTEM] Logs exported to: ${file.name}")
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareLogs() {
        try {
            val logDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            val fileName = "FikFap_ShareLog_${System.currentTimeMillis()}.txt"
            val file = File(logDir, fileName)
            FileOutputStream(file).use { 
                it.write(binding.logText.text.toString().toByteArray())
            }
            
            val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share Analysis Log"))
            appendToLog("[SYSTEM] Sharing session log...")
        } catch (e: Exception) {
            Toast.makeText(this, "Share failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private val isHlsProcessing = AtomicBoolean(false)

    private fun processHls(url: String) {
        if (isHlsProcessing.get()) return
        
        // Filter
        if (!url.contains("m3u8") && !url.contains("mpd")) return
        if (url.contains("live.mmcdn.com")) return 

        isHlsProcessing.set(true)
        binding.statusText.text = "Status: Link Established..."
        binding.downloadProgressCard.visibility = View.VISIBLE
        binding.downloadProgressBar.isIndeterminate = true
        
        // STOP ANALYZER immediately to focus bandwidth on download
        stopService(Intent(this, StreamAnalyzerService::class.java))
        
        // Delegate to DownloadService for call-stability
        val downloadIntent = Intent(this, DownloadService::class.java).apply {
            action = DownloadService.ACTION_START_DOWNLOAD
            putExtra(DownloadService.EXTRA_URL, url)
        }
        startForegroundServiceIfNecessary(downloadIntent)
    }

    private fun startForegroundServiceIfNecessary(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopAll() {
        stopVpn()
        stopService(Intent(this, StreamAnalyzerService::class.java))
        stopService(Intent(this, DownloadService::class.java))
        binding.statusText.text = "Status: READY"
        appendToLog("[SYSTEM] Operation Aborted.")
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(StreamAnalyzerService.EVENT_STREAM_DETECTED)
            addAction(TrafficVpnService.EVENT_BROADCAST)
            addAction(DownloadService.EVENT_PROGRESS)
            addAction(DownloadService.EVENT_COMPLETE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(streamReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(streamReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(streamReceiver)
    }

    private fun appendToLog(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        runOnUiThread {
            binding.logText.append("[$time] $msg\n")
            binding.logScrollView.post { binding.logScrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
