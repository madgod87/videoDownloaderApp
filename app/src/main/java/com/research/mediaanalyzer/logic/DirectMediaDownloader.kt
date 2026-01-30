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

class DirectMediaDownloader(private val context: Context) {
    private val client = OkHttpClient()

    suspend fun downloadDirect(url: String): File? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) return@withContext null
            
            val fileName = "research_video_${System.currentTimeMillis()}.mp4"
            val outputFile = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), fileName)
            
            response.body?.byteStream()?.use { input ->
                FileOutputStream(outputFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            return@withContext outputFile
        } catch (e: Exception) {
            Log.e("DirectDownloader", "Download failed", e)
            null
        }
    }
}
