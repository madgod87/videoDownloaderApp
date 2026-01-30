package com.research.mediaanalyzer.logic

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class HlsDownloader(private val context: Context) {
    private val client = OkHttpClient()

    suspend fun downloadHls(playlistUrl: String, onProgress: (Int, Int) -> Unit): File? = withContext(Dispatchers.IO) {
        try {
            // 1. Fetch Playlist
            val request = Request.Builder().url(playlistUrl).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null
            
            // 2. Parse segments (very basic parsing for demo)
            val lines = body.lines()
            val segmentUrls = lines.filter { it.endsWith(".ts") || it.contains(".ts?") }
                .map { line ->
                    if (line.startsWith("http")) line 
                    else playlistUrl.substringBeforeLast("/") + "/" + line
                }

            if (segmentUrls.isEmpty()) return@withContext null

            // 3. Download segments to a temporary file
            val outputFile = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "research_download_${System.currentTimeMillis()}.mp4")
            val fos = FileOutputStream(outputFile)

            segmentUrls.forEachIndexed { index, url ->
                val segmentRequest = Request.Builder().url(url).build()
                client.newCall(segmentRequest).execute().use { segmentResponse ->
                    segmentResponse.body?.byteStream()?.copyTo(fos)
                }
                onProgress(index + 1, segmentUrls.size)
            }
            fos.close()
            
            return@withContext outputFile
        } catch (e: Exception) {
            Log.e("HlsDownloader", "Download failed", e)
            null
        }
    }
}
