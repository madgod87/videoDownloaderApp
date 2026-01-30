package com.research.mediaanalyzer.vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class TrafficVpnService : VpnService(), Runnable {
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnThread: Thread? = null
    private val isRunning = AtomicBoolean(false)

    companion object {
        private const val TAG = "TrafficVpnService"
        const val ACTION_STOP = "com.research.mediaanalyzer.STOP_VPN"
        const val EVENT_BROADCAST = "com.research.mediaanalyzer.VPN_EVENT"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn()
            return START_NOT_STICKY
        }
        
        logToActivity("[VPN] Service started")
        
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            startForeground(2002, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(2002, createNotification())
        }
        
        if (vpnThread == null) {
            logToActivity("[VPN] Service initialized")
            isRunning.set(true)
            vpnThread = Thread(this, "TrafficVpnThread")
            vpnThread?.start()
        }
        return START_STICKY
    }

    private fun createNotification(): android.app.Notification {
        val channelId = "vpn_channel"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(channelId, "VPN Analysis", android.app.NotificationManager.IMPORTANCE_LOW)
            getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(channel)
        }
        return androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setContentTitle("VPN Analysis Active")
            .setContentText("Monitoring research traffic flows...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    private fun logToActivity(message: String) {
        Log.d(TAG, message)
        val intent = Intent(EVENT_BROADCAST).apply {
            setPackage(packageName)
            putExtra("type", "LIFECYCLE")
            putExtra("message", message)
        }
        sendBroadcast(intent)
    }

    private fun stopVpn() {
        isRunning.set(false)
        vpnInterface?.close()
        vpnInterface = null
        vpnThread = null
        stopSelf()
    }

    override fun run() {
        try {
            setupVpn()
            val pfd = vpnInterface ?: run {
                logToActivity("[VPN-ERROR] Failed to establish TUN interface. Is another VPN running?")
                return
            }
            logToActivity("[VPN] Interface established")
            
            val inputStream = FileInputStream(pfd.fileDescriptor)
            val buffer = ByteBuffer.allocate(65535)

            logToActivity("[VPN] Packet loop started")

            while (isRunning.get()) {
                val length = inputStream.read(buffer.array())
                if (length > 0) {
                    analyzePacket(buffer, length)
                }
                Thread.sleep(5)
            }
        } catch (e: Exception) {
            Log.e(TAG, "VPN Loop error", e)
        } finally {
            stopVpn()
        }
    }

    private fun setupVpn() {
        try {
            logToActivity("[VPN] Starting builder...")
            val builder = Builder()
            builder.setSession("MediaAnalyzerVpn")
            builder.addAddress("10.0.0.2", 24)
            
            // Route everything to observe traffic
            builder.addRoute("0.0.0.0", 0)
            builder.addDnsServer("8.8.8.8")
            
            // Critical for Android 14: System must know what this VPN is for
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            logToActivity("[VPN] Calling establish()...")
            vpnInterface = builder.establish()
            
            if (vpnInterface != null) {
                logToActivity("[VPN] Interface established successfully")
            } else {
                logToActivity("[VPN-ERROR] establish() returned null")
            }
        } catch (e: Exception) {
            logToActivity("[VPN-ERROR] Setup failed: ${e.message}")
            Log.e(TAG, "VPN Setup Error", e)
        }
    }

    private fun analyzePacket(buffer: ByteBuffer, length: Int) {
        if (length < 20) return

        // IPv4 Header
        buffer.position(0)
        val version = (buffer.get().toInt() shr 4) and 0x0F
        if (version != 4) return // IPv4 ONLY for this tool

        // Protocol at offset 9
        buffer.position(9)
        val protocol = buffer.get().toInt() and 0xFF

        // Destination IP at offset 16
        val destIpBytes = ByteArray(4)
        buffer.position(16)
        buffer.get(destIpBytes)
        val destIp = java.net.InetAddress.getByAddress(destIpBytes).hostAddress

        // Extract Port (if TCP/UDP)
        var destPort = -1
        if (protocol == 6 || protocol == 17) {
            buffer.position(20) // Start of Transport Header (assuming no IP options)
            buffer.getShort() // Skip source port
            destPort = buffer.getShort().toInt() and 0xFFFF
        }

        if (destIp != null && destPort != -1) {
            val protocolStr = if (protocol == 6) "TCP" else if (protocol == 17) "UDP" else "RAW"
            
            // Extract payload for deeper analysis (DNS peeking)
            val payload = ByteArray(length)
            val pos = buffer.position()
            buffer.position(0)
            buffer.get(payload)
            buffer.position(pos)

            VpnTrafficAnalyzer.trackPacket(destIp, destPort, protocol, length, true, payload)
            
            val flow = VpnTrafficAnalyzer.getFlow(destIp, destPort, protocolStr)
            
            // Throttled Metadata Logging: Broadcast cumulative flow size (every 10 packets)
            if (length % 10 == 0 && flow != null) {
                broadcastConnMetadata(flow.hostname ?: destIp, destPort, protocol, flow.bytesReceived.get() + flow.bytesSent.get())
            }
            
            // Check for media detection milestones
            if (flow != null && flow.isMediaCandidate && flow.lastReportedSize == 0L) {
                flow.lastReportedSize = flow.bytesReceived.get() + flow.bytesSent.get()
                broadcastMediaDetected(flow)
            }
        }
    }

    private fun broadcastConnMetadata(host: String, port: Int, protocol: Int, bytes: Long) {
        val intent = Intent(EVENT_BROADCAST).apply {
            setPackage(packageName)
            putExtra("type", "VPN-CONN")
            putExtra("host", host)
            putExtra("port", port)
            putExtra("proto", if (protocol == 6) "TCP" else "UDP")
            putExtra("bytes", bytes.toLong())
        }
        sendBroadcast(intent)
    }

    private fun broadcastMediaDetected(flow: ConnectionFlow) {
        val intent = Intent(EVENT_BROADCAST).apply {
            setPackage(packageName)
            putExtra("type", "MEDIA")
            putExtra("host", flow.hostname ?: flow.destIp)
            putExtra("classification", flow.classification.name)
            putExtra("bytes", flow.bytesReceived.get() + flow.bytesSent.get())
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
