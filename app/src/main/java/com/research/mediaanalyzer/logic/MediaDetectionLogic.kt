package com.research.mediaanalyzer.logic

import android.net.Uri
import com.research.mediaanalyzer.model.MediaStream
import com.research.mediaanalyzer.model.StreamType

object MediaDetectionLogic {

    fun analyzeUrl(url: String): MediaStream? {
        val uri = Uri.parse(url)
        val path = uri.path?.lowercase() ?: ""
        val host = uri.host ?: ""

        // DRM Detection (Highest Priority)
        if (url.contains("widevine") || url.contains("playready") || 
            url.contains("license") || url.contains("proxy/drm")) {
            return MediaStream(url, StreamType.DRM_PROTECTED, domain = host, drmInfo = "Detected DRM License Request")
        }

        // Adaptive Streams
        if (path.endsWith(".m3u8") || path.endsWith(".mpd") || url.contains("m3u8")) {
            return MediaStream(url, StreamType.ADAPTIVE_STREAM, format = if (path.endsWith(".m3u8")) "HLS" else "DASH", domain = host)
        }

        // Direct Media
        if (path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".m4v") || path.endsWith(".m4s")) {
            return MediaStream(url, StreamType.DIRECT_MEDIA, format = path.substringAfterLast("."), domain = host)
        }

        return null
    }

    fun isMediaSegment(url: String): Boolean {
        val path = url.lowercase()
        return path.endsWith(".ts") || path.endsWith(".m4s") || path.contains("segment")
    }
}
