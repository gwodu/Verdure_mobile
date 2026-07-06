package com.verdure.core

import android.content.Context
import android.util.Log
import com.cactus.CactusContextInitializer
import com.cactus.CactusSTT
import com.cactus.CactusTranscriptionParams
import com.cactus.TranscriptionMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * On-device speech-to-text powered by OpenAI Whisper, run through the Cactus SDK.
 *
 * This is the "narrow model" half of Verdure's stack: while [CactusLLMEngine]
 * reasons, this engine does one thing extremely well — turn speech into text —
 * entirely on the phone, with no audio ever leaving the device.
 *
 * Whisper model lives in the Cactus on-device registry. We prefer `whisper-base`
 * (better accuracy, ~150 MB) and fall back to `whisper-tiny` (~75 MB) if base
 * fails to download on a constrained device.
 */
class WhisperSTTEngine private constructor(private val context: Context) : STTEngine {

    private var stt: CactusSTT? = null
    private var isInitialized = false
    private var loadedModelSlug: String? = null
    @Volatile private var lastError: String? = null
    private val initMutex = Mutex()
    private val transcribeMutex = Mutex()

    companion object {
        private const val TAG = "WhisperSTTEngine"
        private const val MIN_AUDIO_BYTES = 32_000 // ~1s of 16kHz mono 16-bit PCM

        // Ordered by preference: accuracy first, then a lighter fallback.
        private val MODEL_CANDIDATES = listOf("whisper-base", "whisper-tiny")

        @Volatile
        private var instance: WhisperSTTEngine? = null

        fun getInstance(context: Context): WhisperSTTEngine {
            return instance ?: synchronized(this) {
                instance ?: WhisperSTTEngine(context.applicationContext).also { instance = it }
            }
        }

        fun getConfiguredModelSlug(): String = MODEL_CANDIDATES.first()
    }

    fun getActiveModelSlug(): String? = loadedModelSlug

    override suspend fun initialize(onProgress: ((String) -> Unit)?): Boolean {
        if (isInitialized && stt != null) return true

        return initMutex.withLock {
            if (isInitialized && stt != null) return@withLock true

            withContext(Dispatchers.IO) {
                try {
                    // Idempotent; MainActivity also calls this at startup.
                    CactusContextInitializer.initialize(context)
                    val engine = CactusSTT()
                    val attemptErrors = mutableListOf<String>()

                    for (slug in MODEL_CANDIDATES) {
                        try {
                            onProgress?.invoke("⏳ Downloading speech model ($slug)…")
                            engine.downloadModel(slug)

                            stt = engine
                            isInitialized = true
                            loadedModelSlug = slug
                            lastError = null
                            onProgress?.invoke("✅ Speech model ready ($slug)")
                            return@withContext true
                        } catch (e: Exception) {
                            val line = "$slug failed: ${e.javaClass.simpleName}: ${e.message ?: "unknown error"}"
                            attemptErrors.add(line)
                            Log.e(TAG, "Speech model init failed for slug=$slug", e)
                        }
                    }

                    isInitialized = false
                    stt = null
                    loadedModelSlug = null
                    lastError = attemptErrors.joinToString(" | ")
                    onProgress?.invoke("❌ Failed to load speech model: $lastError")
                    false
                } catch (e: Exception) {
                    Log.e(TAG, "Cactus STT initialization failed", e)
                    isInitialized = false
                    stt = null
                    loadedModelSlug = null
                    lastError = "${e.javaClass.simpleName}: ${e.message ?: "unknown error"}"
                    onProgress?.invoke("❌ Failed to load speech model: $lastError")
                    false
                }
            }
        }
    }

    override suspend fun transcribe(pcm16: ByteArray): String? {
        val engine = stt
        if (!isInitialized || engine == null) {
            lastError = "Speech model not initialized"
            return null
        }
        if (pcm16.size < MIN_AUDIO_BYTES) {
            lastError = "Recording too short (need ~1s of audio)"
            Log.w(TAG, "Audio buffer ${pcm16.size} bytes below Whisper minimum")
            return null
        }

        return transcribeMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val result = engine.transcribe(
                        audioBuffer = pcm16,
                        params = CactusTranscriptionParams(),
                        mode = TranscriptionMode.LOCAL
                    )
                    if (result?.success == true) {
                        result.text?.trim()?.takeIf { it.isNotEmpty() }
                    } else {
                        lastError = "Transcription returned no text"
                        null
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Transcription error", e)
                    lastError = "${e.javaClass.simpleName}: ${e.message ?: "unknown error"}"
                    null
                }
            }
        }
    }

    // Readiness is keyed off our own post-download flag rather than Cactus's
    // internal isReady(), which may only flip true on first transcription.
    override fun isReady(): Boolean = isInitialized && stt != null

    override fun getLastError(): String? = lastError
}
