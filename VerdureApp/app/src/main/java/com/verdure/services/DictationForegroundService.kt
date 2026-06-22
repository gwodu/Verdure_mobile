package com.verdure.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import com.verdure.core.WhisperSTTEngine
import com.verdure.voice.AudioRecorder
import com.verdure.voice.DictationCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that holds the microphone while the user dictates.
 *
 * Why a separate service: on Android 14 an app may only record audio while it
 * has a running foreground service typed `microphone`. The accessibility
 * service can't legally hold the mic on its own, so it delegates capture here.
 *
 * Lifecycle is driven by intents from [VoiceInputAccessibilityService]:
 *   ACTION_START → go foreground, begin recording
 *   ACTION_STOP  → stop recording, transcribe with Whisper, deliver text, exit
 */
class DictationForegroundService : Service() {

    private val recorder = AudioRecorder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        private const val TAG = "DictationFgService"
        private const val CHANNEL_ID = "verdure_dictation"
        private const val NOTIFICATION_ID = 4711

        const val ACTION_START = "com.verdure.dictation.START"
        const val ACTION_STOP = "com.verdure.dictation.STOP"

        fun start(context: Context) {
            val intent = Intent(context, DictationForegroundService::class.java)
                .setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DictationForegroundService::class.java)
                .setAction(ACTION_STOP)
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> beginRecording()
            ACTION_STOP -> finishAndTranscribe()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun beginRecording() {
        // On Android 14 a background-started microphone FGS can be rejected.
        // Surface that instead of failing silently.
        try {
            goForeground()
        } catch (e: Exception) {
            Log.e(TAG, "Foreground start failed", e)
            toast("Can't start mic: ${e.javaClass.simpleName}. Enable 'Display over other apps' in Voice setup.")
            DictationCoordinator.setState(DictationCoordinator.State.ERROR, "Mic blocked")
            stopSelf()
            return
        }

        val started = recorder.start()
        if (!started) {
            Log.e(TAG, "Failed to start recorder")
            toast("Microphone unavailable — check the mic permission")
            DictationCoordinator.setState(DictationCoordinator.State.ERROR, "Microphone unavailable")
            shutdown()
            return
        }
        toast("🎙️ Listening… tap the mic again to insert")
        DictationCoordinator.setState(DictationCoordinator.State.RECORDING)
    }

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    private fun finishAndTranscribe() {
        DictationCoordinator.setState(DictationCoordinator.State.TRANSCRIBING)

        scope.launch {
            // Stop recording off the main thread (joins the capture thread).
            val audio = withContext(Dispatchers.IO) { recorder.stop() }
            val seconds = audio.size / (AudioRecorder.SAMPLE_RATE * 2.0)
            Log.i(TAG, "Captured ${audio.size} bytes (~${"%.1f".format(seconds)}s)")

            if (audio.size < 32_000) {
                toast("Didn't catch any audio — hold a bit longer (got ${"%.1f".format(seconds)}s)")
                DictationCoordinator.setState(DictationCoordinator.State.ERROR, "Too short")
                shutdown()
                return@launch
            }

            val engine = WhisperSTTEngine.getInstance(applicationContext)
            if (!engine.isReady()) {
                toast("Loading Whisper model (first use)…")
                val ok = engine.initialize()
                if (!ok) {
                    toast("Speech model not ready: ${engine.getLastError() ?: "download failed"}")
                    DictationCoordinator.setState(DictationCoordinator.State.ERROR, "Model not ready")
                    shutdown()
                    return@launch
                }
            }

            val text = engine.transcribe(audio)
            if (text.isNullOrBlank()) {
                val err = engine.getLastError() ?: "No speech detected"
                Log.w(TAG, "Empty transcription: $err")
                toast("No text from speech: $err")
                DictationCoordinator.setState(DictationCoordinator.State.ERROR, err)
            } else {
                Log.i(TAG, "Transcribed: $text")
                // Visible proof the STT half worked, independent of injection.
                toast("✍️ $text")
                DictationCoordinator.deliverTranscription(text)
            }
            shutdown()
        }
    }

    private fun shutdown() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice typing",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            nm.createNotificationChannel(channel)
        }

        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Verdure is listening")
            .setContentText("Tap the mic again to insert text")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        recorder.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
