package com.research.mediaanalyzer.vpn

import android.util.Log
import com.research.mediaanalyzer.model.StreamType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class ConnectionFlow(
    val destIp: String,
    val destPort: Int,
    val protocol: String,
    val startTime: Long = System.currentTimeMillis(),
    var lastActiveTime: Long = System.currentTimeMillis(),
    val bytesReceived: AtomicLong = AtomicLong(0),
    val bytesSent: AtomicLong = AtomicLong(0),
    var hostname: String? = null,
    var isMediaCandidate: Boolean = false,
    var classification: StreamType = StreamType.UNSUPPORTED,
    var lastReportedSize: Long = 0,
    var packetCount: Int = 0
)

object VpnTrafficAnalyzer {
    private const val TAG = "VpnTrafficAnalyzer"
    private val flows = ConcurrentHashMap<String, ConnectionFlow>()
    private val dnsMap = ConcurrentHashMap<String, String>() // IP -> Hostname
    
    // ADJUSTED SENSITIVE THRESHOLDS FOR PWA ANALYSIS
    private const val MEDIA_SIZE_THRESHOLD = 15 * 1024 // 15 KB (Threshold to identify a media flow start)
    private const val MEDIA_TIME_THRESHOLD = 3000 // 3 seconds
    private const val PACKET_COUNT_THRESHOLD = 5

    fun trackPacket(destIp: String, destPort: Int, protocol: Int, length: Int, isOutbound: Boolean, payload: ByteArray? = null) {
        val protocolStr = if (protocol == 6) "TCP" else if (protocol == 17) "UDP" else "RAW"
        
        // 1. DNS Observation (Peeking at UDP 53)
        if (protocol == 17 && destPort == 53 && payload != null) {
            processDnsPacket(payload)
        }

        val key = "$destIp:$destPort:$protocolStr"
        val flow = flows.getOrPut(key) { 
            ConnectionFlow(destIp, destPort, protocolStr).apply {
                hostname = dnsMap[destIp]
            }
        }
        
        flow.lastActiveTime = System.currentTimeMillis()
        flow.packetCount++

        if (isOutbound) {
            flow.bytesSent.addAndGet(length.toLong())
        } else {
            flow.bytesReceived.addAndGet(length.toLong())
        }

        analyzeFlow(flow)
    }

    private fun analyzeFlow(flow: ConnectionFlow) {
        val duration = flow.lastActiveTime - flow.startTime
        val totalBytes = flow.bytesReceived.get() + flow.bytesSent.get()

        // Update hostname if discovered via DNS since flow creation
        if (flow.hostname == null) {
            flow.hostname = dnsMap[flow.destIp]
        }

        // RESEARCH DETECTION LOGIC:
        // Group packets into a logical media stream based on persistent high-volume activity
        if (flow.destPort == 443 && totalBytes >= MEDIA_SIZE_THRESHOLD && duration >= MEDIA_TIME_THRESHOLD && flow.packetCount >= PACKET_COUNT_THRESHOLD) {
            if (!flow.isMediaCandidate) {
                flow.isMediaCandidate = true
                flow.classification = StreamType.ADAPTIVE_STREAM // Adaptive is most common for PWA Video (HLS/DASH)
                Log.d(TAG, "MEDIA STREAM DETECTED: ${flow.hostname ?: flow.destIp} ($totalBytes bytes over ${duration}ms)")
            }
        }
    }

    private fun processDnsPacket(payload: ByteArray) {
        // Simple heuristic for demo: look for domain-like strings in DNS responses
        // This is a mapping for the analysis tool to show friendly names.
        try {
            // Placeholder: In a full research implementation, we'd parse the DNS RRs.
        } catch (e: Exception) {}
    }

    fun addDnsMapping(ip: String, host: String) {
        dnsMap[ip] = host
    }

    fun getFlow(destIp: String, destPort: Int, protocolStr: String): ConnectionFlow? {
        return flows["$destIp:$destPort:$protocolStr"]
    }

    fun getFlows(): List<ConnectionFlow> = flows.values.toList()
    
    fun getMediaFlows(): List<ConnectionFlow> = flows.values.filter { it.isMediaCandidate }

    fun clear() {
        flows.clear()
        dnsMap.clear()
    }
}
