package com.research.mediaanalyzer.model

enum class StreamType {
    DIRECT_MEDIA,
    ADAPTIVE_STREAM,
    DRM_PROTECTED,
    UNSUPPORTED
}

data class MediaStream(
    val url: String,
    val type: StreamType,
    val format: String? = null,
    val domain: String? = null,
    val estimatedSize: Long? = null,
    val drmInfo: String? = null
)

data class TrafficLog(
    val timestamp: Long,
    val domain: String,
    val path: String?,
    val size: Long,
    val type: String
)
