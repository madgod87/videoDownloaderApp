package com.fikfap.downloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat

class StreamAnalyzerService : Service() {
    private var webView: WebView? = null

    companion object {
        const val ACTION_START_ANALYSIS = "com.fikfap.START_ANALYSIS"
        const val EXTRA_URL = "extra_url"
        const val EVENT_STREAM_DETECTED = "com.fikfap.STREAM_DETECTED"
        private const val CHANNEL_ID = "analysis_channel"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(3001, createNotification("FikFap v3.0 Analysis Layer"))
        
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36"
            
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                    val message = msg.message()
                    Log.d("FikFap", "JS: $message")
                    
                    if (message.startsWith("FOUND:") || message.startsWith("FETCH:")) {
                        broadcastMessage("[JS] $message")
                        extractStreamUrl(message)
                    }
                    
                    if (message.contains("m3u8") || message.contains("hls")) {
                        extractStreamUrl(message)
                    }
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    broadcastMessage("[WEBVIEW] Loading: $url")
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    broadcastMessage("[WEBVIEW] DOM Loaded - Injecting Scrapper & Spy")
                    injectDeepScraper(view)
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val url = request?.url?.toString() ?: ""
                    
                    // WebSocket Detection (Visual logging as intercepting WS is limited in WebView)
                    if (url.startsWith("ws") || url.startsWith("wss")) {
                        broadcastMessage("[WS] WebSocket link detected: $url")
                    }

                    if ((url.contains(".m3u8") || url.contains(".mpd")) && 
                        (url.contains("playlist") || url.contains("master"))) {
                        
                        // Ignore known ad/chat CDNs
                        if (url.contains("live.mmcdn.com") || url.contains("chaturbate")) return@shouldInterceptRequest super.shouldInterceptRequest(view, request)

                        broadcastMessage("[HLS] Master Detected: $url")
                        broadcastStream(url)
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }
        }
    }

    private fun injectDeepScraper(view: WebView?) {
        view?.evaluateJavascript("""
            (function() {
                console.log('Deep Scraper v3.0 Initialized');
                
                // 1. Selector Scan
                const selectors = [
                    'video source[src*="m3u8"]',
                    '.player-config[data-src]',
                    'window.__NEXT_DATA__',
                    'window.playerData',
                    'video[currentSrc]'
                ];
                
                selectors.forEach(sel => {
                    try {
                        const el = document.querySelector(sel);
                        if (el) console.log('FOUND:', sel, el.src || el.dataset.src || 'EXISTS');
                    } catch(e) {}
                });

                // 2. Window Object Probe
                if (window.__NEXT_DATA__) console.log('FOUND: window.__NEXT_DATA__', JSON.stringify(window.__NEXT_DATA__).substring(0, 500));
                if (window.playerData) console.log('FOUND: window.playerData', JSON.stringify(window.playerData));

                // 3. Network Fetch Spy
                const origFetch = window.fetch;
                window.fetch = function(...args) {
                    const url = (args[0] instanceof Request) ? args[0].url : args[0];
                    console.log('FETCH:', url);
                    return origFetch.apply(this, args);
                };

                // 4. Force Unlock Playback
                const video = document.querySelector('video');
                if (video) {
                    video.play().then(() => console.log('Video playing - streams active'))
                               .catch(e => console.log('Play blocked, waiting for interaction'));
                }
            })();
        """, null)
    }

    private fun extractStreamUrl(log: String) {
        val regex = Regex("(https?://[^\\s\"'<>]+?\\.m3u8[^\\s\"'<>]*)")
        val match = regex.find(log)
        match?.let { 
            broadcastMessage("[HLS] Extracted: ${it.value}")
            broadcastStream(it.value)
        }
    }

    private fun broadcastMessage(msg: String) {
        val intent = Intent(TrafficVpnService.EVENT_BROADCAST).apply {
            putExtra("message", msg)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun broadcastStream(url: String) {
        val intent = Intent(EVENT_STREAM_DETECTED).apply {
            setPackage(packageName)
            putExtra("manifest_url", url)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Stream Analysis Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_ANALYSIS) {
            val url = intent.getStringExtra(EXTRA_URL)
            url?.let { webView?.loadUrl(it) }
        }
        return START_NOT_STICKY
    }

    private fun createNotification(content: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("FikFap v3.0 Downloader")
        .setContentText(content)
        .setSmallIcon(android.R.drawable.ic_menu_search)
        .setOngoing(true)
        .build()

    override fun onDestroy() {
        webView?.destroy()
        super.onDestroy()
    }
}
