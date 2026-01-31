package com.fikfap.downloader

import android.content.Context
import android.os.Environment
import android.util.Log
import io.microshow.rxffmpeg.RxFFmpegInvoke
import io.microshow.rxffmpeg.RxFFmpegSubscriber
import java.io.File

class HLSProcessor(private val context: Context) {
    init {
        RxFFmpegInvoke.getInstance().setDebug(true)
    }

    fun processStream(
        manifestUrl: String, 
        keyHex: String? = null, 
        onProgress: (Int) -> Unit = {}, 
        onComplete: (File?) -> Unit
    ) {
        val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        val fileName = "FikFap_${System.currentTimeMillis()}.mp4"
        val outputFile = File(storageDir, fileName)

        // build args
        val args = mutableListOf<String>()
        
        // RxFFmpeg / FFmpeg Native wrapper quirk: The first argument is often skipped 
        // as if it were the program name. We add "ffmpeg" as a placeholder.
        args.add("ffmpeg")
        
        // Input Options (MUST be before -i)
        // BunnyCDN requires Referer on EVERY segment. -headers is the only flag that 
        // reliably propagates to sub-playlists and segments in this FFmpeg build.
        args.add("-headers")
        args.add("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36\r\nReferer: https://fikfap.com/\r\n")
        
        args.add("-i")
        args.add(manifestUrl)

        // Decryption Key if available (Standard AES-128)
        keyHex?.let {
            args.add("-decryption_key")
            args.add(it)
        }

        args.add("-c")
        args.add("copy")
        args.add("-bsf:a")
        args.add("aac_adtstoasc")
        
        // Robustness for HLS
        args.add("-reconnect")
        args.add("1")
        args.add("-reconnect_at_eof")
        args.add("1")
        args.add("-reconnect_streamed")
        args.add("1")
        args.add("-reconnect_delay_max")
        args.add("10")
        
        args.add("-y")
        args.add(outputFile.absolutePath)

        val command = args.toTypedArray()
        Log.d("HLSProcessor", "Executing RxFFmpeg command in background: ${command.joinToString(" ")}")

        // Wrap in a thread to ensure the UI thread is NEVER blocked by JNI parsing/init
        Thread {
            try {
                RxFFmpegInvoke.getInstance().runCommand(command, object : RxFFmpegSubscriber() {
                    override fun onFinish() {
                        Log.i("HLSProcessor", "Processing successful: ${outputFile.name}")
                        onComplete(outputFile)
                    }

                    override fun onProgress(progress: Int, progressTime: Long) {
                    onProgress(progress)
                }

                    override fun onCancel() {
                        Log.w("HLSProcessor", "Processing canceled")
                        onComplete(null)
                    }

                    override fun onError(message: String?) {
                        Log.e("HLSProcessor", "FFmpeg failed: $message")
                        onComplete(null)
                    }
                })
            } catch (e: Exception) {
                Log.e("HLSProcessor", "Invocation error", e)
                onComplete(null)
            }
        }.start()
    }
}
