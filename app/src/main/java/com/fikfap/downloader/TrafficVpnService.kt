package com.fikfap.downloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.experimental.and

class TrafficVpnService : VpnService(), Runnable {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var thread: Thread? = null
    private val isRunning = AtomicBoolean(false)
    private val hostTrafficMap = mutableMapOf<String, Long>()

    companion object {
        const val ACTION_STOP = "com.fikfap.STOP_VPN"
        const val EVENT_BROADCAST = "com.fikfap.VPN_EVENT"
        private const val CHANNEL_ID = "vpn_channel"
        
        private val FIKFAP_CDN_IPS = listOf(
            "57.144.142.0",           // FikFap Backend
            "185.196.164.0",          // BunnyCDN Node (B-CDN)
            "169.150.218.0",          // BunnyCDN Node
            "84.17.47.0",             // BunnyCDN Node
            "157.240.1.0"             // FB Edge
        )
        
        private val CDN_MASKS = listOf(24, 24, 24, 24, 24)
        
        private val MANIFEST_REGEX = Regex("https?://[^\\s\"'<>]+?\\.(m3u8|mpd|bin)[^\\s\"'<>]*")
        private val BASE64_MANIFEST_REGEX = Regex("data:application/x-mpegURL;base64,([^\"']+)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        
        if (thread == null) {
            createNotificationChannel()
            hostTrafficMap.clear()
            isRunning.set(true)
            thread = Thread(this, "TrafficVpnThread").apply { start() }
            startForeground(3002, createNotification())
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "VPN Traffic Analysis",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification() = androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("FikFap Network Analyzer v3.0")
        .setContentText("QUIC/HTTP3 Deep Inspection Active")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setOngoing(true)
        .build()

    override fun run() {
        try {
            val builder = Builder()
                .setSession("FikFapAnalyzer")
                .addAddress("10.0.0.1", 24)
                .addDnsServer("8.8.8.8")
                .setMtu(1500)
            
            // SELECTIVE ROUTING: Only intercept CDN traffic to avoid "Black Hole" effect
            // This allows WebView IPC and system services to continue working
            FIKFAP_CDN_IPS.forEachIndexed { index, ip ->
                try {
                    builder.addRoute(ip, CDN_MASKS[index])
                } catch (e: Exception) {
                    Log.e("TrafficVpnService", "Failed to add route: $ip/${CDN_MASKS[index]}")
                }
            }

            // Exclude our own app and common browser/system packages to ensure WebView stability
            try {
                builder.addDisallowedApplication(packageName)
                builder.addDisallowedApplication("com.android.chrome")
                builder.addDisallowedApplication("com.google.android.webview")
                builder.addDisallowedApplication("com.android.webview")
                builder.addDisallowedApplication("com.google.android.gms")
                builder.addDisallowedApplication("com.android.vending")
                builder.addDisallowedApplication("com.google.android.gsf") // Google Services Framework
                builder.addDisallowedApplication("com.google.android.ims") // Carrier Services
            } catch (e: Exception) {}

            vpnInterface = builder.establish()

            if (vpnInterface == null) return

            val inputStream = FileInputStream(vpnInterface?.fileDescriptor)
            val buffer = ByteBuffer.allocate(32768)

            while (isRunning.get()) {
                val length = inputStream.read(buffer.array())
                if (length > 0) {
                    analyzePacket(buffer.array(), length)
                } else if (length == 0) {
                    Thread.sleep(10)
                }
            }
        } catch (e: Exception) {
            Log.e("TrafficVpnService", "VPN Error", e)
        } finally {
            stopVpn()
        }
    }

    private fun analyzePacket(packet: ByteArray, length: Int) {
        if (length < 20) return
        val protocol = packet[9].toInt() and 0xFF
        val ihl = (packet[0].toInt() and 0x0F) * 4
        
        val srcIp = getIpAddress(packet, 12)
        val dstIp = getIpAddress(packet, 16)
        val remoteHost = if (srcIp.startsWith("10.0.")) dstIp else srcIp

        // Payload extraction
        if (length > ihl) {
            val payload = packet.copyOfRange(ihl, length)
            
            // HTTP/QUIC/WebSocket payload analysis
            if (protocol == 17) { // UDP
                val srcPort = ((packet[ihl].toInt() and 0xFF) shl 8) or (packet[ihl + 1].toInt() and 0xFF)
                val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
                
                if (srcPort == 443 || dstPort == 443) {
                    decodeQuicPacket(payload)?.let { url ->
                        broadcastToUi("[QUIC] Decoded stream -> $url")
                        saveManifest(url, "quic_extracted.m3u8")
                    }
                }
            }

            // Aggressive manifest hunting in any packet
            huntManifests(payload).forEach { manifest ->
                broadcastToUi("[VPN] Hunted Manifest: $manifest")
                saveManifest(manifest, "hunted_${System.currentTimeMillis()}.m3u8")
            }
        }

        // Volume tracking for known hosts
        if (FIKFAP_CDN_IPS.any { remoteHost.startsWith(it) }) {
            val currentVolume = hostTrafficMap.getOrDefault(remoteHost, 0L) + length
            hostTrafficMap[remoteHost] = currentVolume
            if (currentVolume % 51200 < length.toLong()) { // Every 50KB
                broadcastToUi("[VPN-CONN] Priority Host $remoteHost -> ${formatSize(currentVolume)}")
            }
        }
    }

    private fun decodeQuicPacket(udpPayload: ByteArray): String? {
        // RFC 9000: QUIC Long Header starts with 0xC0 (bit 7 and 6 set)
        if (udpPayload.isNotEmpty() && (udpPayload[0].toInt() and 0xC0) == 0xC0) {
            val text = String(udpPayload, Charsets.US_ASCII)
            // Look for stream identifiers or HLS patterns in the initial frames
            if (text.contains("m3u8") || text.contains("hls")) {
                return MANIFEST_REGEX.find(text)?.value
            }
        }
        return null
    }

    private fun huntManifests(data: ByteArray): List<String> {
        val text = String(data, Charsets.UTF_8)
        val results = mutableListOf<String>()
        
        // Standard Regex
        MANIFEST_REGEX.findAll(text).forEach { results.add(it.value) }
        
        // Base64 Regex
        BASE64_MANIFEST_REGEX.findAll(text).forEach { match ->
            try {
                val decoded = String(Base64.decode(match.groupValues[1], Base64.DEFAULT))
                if (decoded.contains("m3u8") || decoded.contains("#EXTM3U")) {
                    results.add(decoded)
                }
            } catch (e: Exception) {}
        }
        
        return results
    }

    private fun saveManifest(url: String, name: String) {
        try {
            val data = if (url.startsWith("http")) url else "Captured content from VPN: $url"
            val dir = File("/data/local/tmp/manifests/")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, name)
            FileOutputStream(file).use { it.write(data.toByteArray()) }
            broadcastToUi("[MEDIA] SAVED: /manifests/$name")
        } catch (e: Exception) {
            val fallbackFile = File(getExternalFilesDir("manifests"), name)
            fallbackFile.writeText(url)
            broadcastToUi("[MEDIA] SAVED (Fallback): $name")
        }
    }

    private fun getIpAddress(packet: ByteArray, offset: Int): String {
        return "${packet[offset].toInt() and 0xFF}.${packet[offset+1].toInt() and 0xFF}.${packet[offset+2].toInt() and 0xFF}.${packet[offset+3].toInt() and 0xFF}"
    }

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1048576) return String.format("%.2f KB", bytes / 1024.0)
        return String.format("%.2f MB", bytes / 1048576.0)
    }

    private fun broadcastToUi(msg: String) {
        val intent = Intent(EVENT_BROADCAST).apply {
            putExtra("message", msg)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun stopVpn() {
        isRunning.set(false)
        try { vpnInterface?.close() } catch (e: Exception) {}
        vpnInterface = null
        thread = null
        stopSelf()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
