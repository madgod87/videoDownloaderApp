package com.research.mediaanalyzer.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.research.mediaanalyzer.databinding.ActivityMainBinding
import com.research.mediaanalyzer.logic.DirectMediaDownloader
import com.research.mediaanalyzer.logic.HlsDownloader
import com.research.mediaanalyzer.logic.MediaDetectionLogic
import com.research.mediaanalyzer.model.MediaStream
import com.research.mediaanalyzer.model.StreamType
import com.research.mediaanalyzer.service.AnalyzerForegroundService
import com.research.mediaanalyzer.vpn.TrafficVpnService
import com.research.mediaanalyzer.vpn.VpnTrafficAnalyzer
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var detectedStream: MediaStream? = null
    private lateinit var libraryAdapter: LibraryAdapter
    private val downloadedFiles = mutableListOf<File>()
    
    // Modern ActivityResultLauncher for VPN Permission
    private val vpnRequestLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val url = binding.urlInput.text.toString()
            startAnalysis(url)
        }
    }

    // UNIFIED LOGGING & MEDIA DETECTION RECEIVER
    private val vpnEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val type = intent?.getStringExtra("type") ?: return
            
            when (type) {
                "LIFECYCLE" -> {
                    val message = intent.getStringExtra("message") ?: ""
                    appendToLog(message)
                }
                "VPN-CONN" -> {
                    val host = intent.getStringExtra("host") ?: ""
                    val port = intent.getIntExtra("port", 0)
                    val proto = intent.getStringExtra("proto") ?: ""
                    val bytes = intent.getLongExtra("bytes", 0)
                    val formattedBytes = when {
                        bytes > 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
                        bytes > 1024 -> String.format("%.2f KB", bytes / 1024.0)
                        else -> "$bytes B"
                    }
                    // Log connection metadata as requested
                    appendToLog("[VPN-CONN] $host:$port ($proto) -> $formattedBytes")
                }
                "MEDIA" -> {
                    val host = intent.getStringExtra("host") ?: ""
                    val classification = intent.getStringExtra("classification") ?: "UNKNOWN"
                    val bytes = intent.getLongExtra("bytes", 0)
                    
                    val stream = MediaStream(
                        url = "http://$host", // Proxy URL for logic
                        type = StreamType.valueOf(classification),
                        domain = host,
                        format = "Inferred from Traffic"
                    )
                    
                    appendToLog("[MEDIA] CDN detected: $host (${bytes / 1024} KB) - $classification")
                    onMediaDetected(stream)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        setupWebView()
        setupLibrary()
        VpnTrafficAnalyzer.clear()
        
        appendToLog("System initialized. Waiting for URL...")
    }

    private fun setupLibrary() {
        libraryAdapter = LibraryAdapter(downloadedFiles,
            onPlay = { file -> playVideo(file) },
            onFolder = { file -> openFolder(file) }
        )
        binding.libraryRecyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.libraryRecyclerView.adapter = libraryAdapter
        refreshLibrary()
    }

    private fun refreshLibrary() {
        val dir = getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
        val files = dir?.listFiles()?.toList()?.sortedByDescending { it.lastModified() } ?: emptyList()
        downloadedFiles.clear()
        downloadedFiles.addAll(files)
        libraryAdapter.updateFiles(downloadedFiles)
        binding.noDownloadsText.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(TrafficVpnService.EVENT_BROADCAST)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(vpnEventReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(vpnEventReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(vpnEventReceiver)
    }

    private fun setupUI() {
        binding.analyzeButton.setOnClickListener {
            val url = binding.urlInput.text.toString()
            if (url.isNotEmpty()) {
                prepareAndStartAnalysis(url)
            } else {
                Toast.makeText(this, "Please enter a URL", Toast.LENGTH_SHORT).show()
            }
        }

        binding.exportButton.setOnClickListener { exportLogs() }
        binding.shareButton.setOnClickListener { shareLogs() }
        binding.stopButton.setOnClickListener {
            stopAnalysis()
            appendToLog("Analysis stopped by user")
        }
        binding.libraryButton.setOnClickListener {
            refreshLibrary()
            binding.libraryCard.visibility = View.VISIBLE
        }
        binding.closeLibraryButton.setOnClickListener {
            binding.libraryCard.visibility = View.GONE
        }
    }

    private fun setupWebView() {
        val webView = binding.hiddenWebView
        webView.settings.javaScriptEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.domStorageEnabled = true
        
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                runOnUiThread { appendToLog("WEBVIEW: Loading $url") }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                runOnUiThread { appendToLog("Load finished") }
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                request?.url?.toString()?.let { url ->
                    logWebTraffic(url)
                }
                return super.shouldInterceptRequest(view, request)
            }
        }
    }

    private fun logWebTraffic(url: String) {
        val domain = Uri.parse(url).host ?: "unknown"
        runOnUiThread {
            appendToLog("[XHR/DOC] $domain")
        }
    }

    private fun prepareAndStartAnalysis(url: String) {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnRequestLauncher.launch(intent)
        } else {
            startAnalysis(url)
        }
    }

    private fun startAnalysis(url: String) {
        binding.statusText.text = "Status: Analyzing..."
        appendToLog("Starting analysis of $url")
        appendToLog("[SYSTEM] Initializing VPN Analysis Layer...")
        
        // 1. Start Foreground Service
        AnalyzerForegroundService.start(this)
        
        // 2. Start VPN Service (PRIMARY DATA SOURCE)
        val vpnIntent = Intent(this, TrafficVpnService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(vpnIntent)
        } else {
            startService(vpnIntent)
        }
        
        // 3. Load URL AFTER VPN starts to ensure capture
        binding.hiddenWebView.postDelayed({
            binding.hiddenWebView.loadUrl(url)
        }, 1000)
    }

    private fun onMediaDetected(stream: MediaStream) {
        if (detectedStream != null) return // One detection per session

        detectedStream = stream
        runOnUiThread {
            binding.statusText.text = "Status: ${stream.type.name} Detected"
            
            if (stream.type == StreamType.DRM_PROTECTED) {
                showDrmAlert(stream)
            } else {
                showDownloadConfirmation(stream)
            }
        }
    }

    private fun showDrmAlert(stream: MediaStream) {
        AlertDialog.Builder(this)
            .setTitle("DRM Protected")
            .setMessage("DRM-protected stream detected at ${stream.domain}. Download not possible.")
            .setPositiveButton("OK") { _, _ -> stopAnalysis() }
            .show()
    }

    private fun showDownloadConfirmation(stream: MediaStream) {
        AlertDialog.Builder(this)
            .setTitle("Media Detected")
            .setMessage("Media stream found via VPN analysis at ${stream.domain}.\nType: ${stream.type.name}\n\nDo you want to attempt acquisition?")
            .setPositiveButton("Download") { _, _ -> startAcquisition(stream) }
            .setNegativeButton("Cancel") { _, _ -> stopAnalysis() }
            .show()
    }

    private fun startAcquisition(stream: MediaStream) {
        binding.statusText.text = "Status: Downloading..."
        binding.downloadProgressCard.visibility = View.VISIBLE
        binding.downloadTitle.text = "Acquisition: ${stream.domain}"
        binding.downloadProgressBar.isIndeterminate = true
        appendToLog("Acquisition started for ${stream.domain}")

        lifecycleScope.launch {
            try {
                val file = if (stream.type == StreamType.ADAPTIVE_STREAM) {
                    HlsDownloader(this@MainActivity).downloadHls(stream.url) { current, total ->
                        runOnUiThread {
                            binding.downloadProgressBar.isIndeterminate = false
                            binding.downloadProgressBar.max = total
                            binding.downloadProgressBar.progress = current
                            binding.downloadStatus.text = "Acquired $current of $total units"
                            appendToLog("Progress: $current/$total segments")
                        }
                    }
                } else {
                    DirectMediaDownloader(this@MainActivity).downloadDirect(stream.url)
                }
                onDownloadComplete(file)
            } catch (e: Exception) {
                runOnUiThread {
                    binding.downloadProgressCard.visibility = View.GONE
                    Toast.makeText(this@MainActivity, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    appendToLog("ERROR: ${e.message}")
                    stopAnalysis()
                }
            }
        }
    }

    private fun onDownloadComplete(file: File?) {
        runOnUiThread {
            binding.downloadProgressCard.visibility = View.GONE
            if (file != null && file.exists()) {
                binding.statusText.text = "Status: Completed"
                appendToLog("DOWNLOAD COMPLETE: ${file.absolutePath}")
                Toast.makeText(this, "Acquisition Complete: ${file.name}", Toast.LENGTH_LONG).show()
                refreshLibrary()
            } else {
                binding.statusText.text = "Status: Failed"
                appendToLog("Acquisition failed or no data captured.")
                Toast.makeText(this, "Acquisition failed", Toast.LENGTH_SHORT).show()
            }
            stopAnalysis()
        }
    }

    private fun playVideo(file: File) {
        val uri = FileProvider.getUriForFile(this, "com.research.mediaanalyzer.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Open Video"))
    }

    private fun openFolder(file: File) {
        Toast.makeText(this, "Stored in: ${file.parent}", Toast.LENGTH_LONG).show()
    }

    private fun stopAnalysis() {
        binding.statusText.text = "Status: Idle"
        startService(Intent(this, TrafficVpnService::class.java).apply { action = TrafficVpnService.ACTION_STOP })
        AnalyzerForegroundService.stop(this)
        binding.hiddenWebView.stopLoading()
        detectedStream = null
    }

    private fun appendToLog(text: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        runOnUiThread {
            binding.logText.append("[$time] $text\n")
            binding.logScrollView.post { binding.logScrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun exportLogs() {
        val content = binding.logText.text.toString()
        if (content.isEmpty()) return
        try {
            val fileName = "analyzer_log_${System.currentTimeMillis()}.txt"
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, fileName)
            file.writeText(content)
            Toast.makeText(this, "Exported to Downloads", Toast.LENGTH_LONG).show()
            appendToLog("LOG EXPORTED: ${file.absolutePath}")
        } catch (e: Exception) {
            val file = File(getExternalFilesDir(null), "fallback_log.txt")
            file.writeText(content)
            Toast.makeText(this, "Saved to Internal", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareLogs() {
        val content = binding.logText.text.toString()
        if (content.isEmpty()) return
        try {
            val file = File(getExternalFilesDir(null), "log_share.txt")
            file.writeText(content)
            val uri = FileProvider.getUriForFile(this, "com.research.mediaanalyzer.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share Logs"))
        } catch (e: Exception) {}
    }
}
