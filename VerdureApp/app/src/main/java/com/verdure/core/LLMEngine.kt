package com.verdure.core

/**
 * Interface for any LLM backend (MLC, Gemini, etc.)
 *
 * This abstraction allows Verdure to swap LLM implementations without changing
 * the VerdureAI orchestrator or tools.
 *
 * Current implementation: CactusLLMEngine (Gemma 4 E2B via Cactus SDK)
 * Future: Could support other models (Gemini, Phi-3, etc.)
 */
interface LLMEngine {
    /**
     * Initialize the LLM model
     * Should be called once at app startup
     *
     * @param onProgress Optional callback invoked with human-readable status strings
     *                   (e.g. "Downloading model…", "✅ Ready") during initialization.
     * @return true if initialization successful, false otherwise
     */
    suspend fun initialize(onProgress: ((String) -> Unit)? = null): Boolean

    /**
     * Generate content using the LLM
     * All processing happens on-device - no network required
     *
     * @param prompt The prompt to send to the model
     * @return Generated text response
     */
    suspend fun generateContent(prompt: String): String

    /**
     * Check if the engine is ready to use
     *
     * @return true if initialized and ready, false otherwise
     */
    fun isReady(): Boolean
}
