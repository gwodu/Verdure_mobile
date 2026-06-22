package com.verdure.core

/**
 * Interface for any on-device speech-to-text backend.
 *
 * Mirrors [LLMEngine]: Verdure is not just a model switcher, it runs several
 * *specialized* on-device models, each tuned for one task. STTEngine is the
 * narrow "ears" model (OpenAI Whisper) the way LLMEngine is the "reasoning"
 * model. Keeping it behind an interface lets us swap Whisper for another STT
 * backend without touching the dictation pipeline.
 *
 * Current implementation: [WhisperSTTEngine] (OpenAI Whisper via Cactus SDK).
 */
interface STTEngine {
    /**
     * Download + load the speech model. Safe to call repeatedly; subsequent
     * calls are no-ops once ready.
     *
     * @param onProgress Optional human-readable status callback
     *                   (e.g. "Downloading speech model…", "✅ Ready").
     * @return true if the model is ready to transcribe.
     */
    suspend fun initialize(onProgress: ((String) -> Unit)? = null): Boolean

    /**
     * Transcribe raw microphone audio fully on-device.
     *
     * @param pcm16 16-bit PCM, 16 kHz, mono audio bytes (little-endian).
     *              Cactus/Whisper require at least ~1 second (32,000 bytes).
     * @return Transcribed text, or null if transcription failed / produced nothing.
     */
    suspend fun transcribe(pcm16: ByteArray): String?

    /** @return true if the model is loaded and ready. */
    fun isReady(): Boolean

    /** Last initialization/transcription error, for surfacing in the UI. */
    fun getLastError(): String?
}
