package com.fikfap.downloader

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class DownloadService : Service() {

    companion object {
        private const val CHANNEL_ID = "download_channel"
        private const val NOTIFICATION_ID = 101
        
        const val ACTION_START_DOWNLOAD = "START_DOWNLOAD"
        const val EXTRA_URL = "extra_url"
        const val EXTRA_FILE_PATH = "extra_file_path"
        
        const val EVENT_PROGRESS = "com.fikfap.downloader.DOWNLOAD_PROGRESS"
        const val EVENT_COMPLETE = "com.fikfap.downloader.DOWNLOAD_COMPLETE"
    }

    private lateinit var hlsProcessor: HLSProcessor
    private val notificationBuilder by lazy {
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("FikFap Downloader")
            .setContentText("Initializing download...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setProgress(100, 0, true)
    }

    override fun onCreate() {
        super.onCreate()
        hlsProcessor = HLSProcessor(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_DOWNLOAD) {
            val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
            
            startForeground(NOTIFICATION_ID, notificationBuilder.build())
            
            hlsProcessor.processStream(url, onProgress = { progress ->
                updateNotification(progress)
                broadcastProgress(progress)
            }) { file ->
                broadcastComplete(file)
                stopForeground(true)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun updateNotification(progress: Int) {
        notificationBuilder.setContentText("Downloading: $progress%")
            .setProgress(100, progress, false)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())
    }

    private fun broadcastProgress(progress: Int) {
        val intent = Intent(EVENT_PROGRESS).apply {
            putExtra("progress", progress)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun broadcastComplete(file: File?) {
        val intent = Intent(EVENT_COMPLETE).apply {
            putExtra("file_path", file?.absolutePath)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Media Downloads",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
