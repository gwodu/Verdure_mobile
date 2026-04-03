package com.verdure.core

import android.content.Context
import android.util.Log
import com.cactus.CactusCompletionParams
import com.cactus.CactusContextInitializer
import com.cactus.CactusInitParams
import com.cactus.CactusLM
import com.cactus.ChatMessage
import com.cactus.InferenceMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Cactus LLM backend for running Gemma 4 E2B on-device.
 *
 * Uses Cactus SDK for local model download and inference.
 */
class CactusLLMEngine private constructor(private val context: Context) : LLMEngine {

    private var cactusLM: CactusLM? = null
    private var isInitialized = false
    private val inferenceMutex = Mutex()

    companion object {
        private const val TAG = "CactusLLMEngine"
        private const val MODEL_SLUG = "google/gemma-4-E2B-it"
        private const val CONTEXT_SIZE = 4096
        private const val MAX_TOKENS = 2048
        private const val TEMPERATURE = 0.7
        private const val TOP_K = 64

        @Volatile
        private var instance: CactusLLMEngine? = null

        fun getInstance(context: Context): CactusLLMEngine {
            return instance ?: synchronized(this) {
                instance ?: CactusLLMEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    override suspend fun initialize(): Boolean {
        if (isInitialized && cactusLM?.isLoaded() == true) {
            return true
        }

        return withContext(Dispatchers.IO) {
            try {
                // Ensure Cactus context is initialized
                CactusContextInitializer.initialize(context)

                val lm = CactusLM()

                // Download and initialize the model (throws exception on failure)
                lm.downloadModel(MODEL_SLUG)
                lm.initializeModel(
                    CactusInitParams(
                        model = MODEL_SLUG,
                        contextSize = CONTEXT_SIZE
                    )
                )

                cactusLM = lm
                isInitialized = true
                true
            } catch (e: Exception) {
                e.printStackTrace()
                isInitialized = false
                false
            }
        }
    }

    override suspend fun generateContent(prompt: String): String {
        val lm = cactusLM
        if (!isInitialized || lm == null || !lm.isLoaded()) {
            return "Error: LLM not initialized. Please allow model download and retry."
        }

        return inferenceMutex.withLock {
            withContext(Dispatchers.IO) {
                try {
                    Log.d(TAG, "Starting native inference (prompt length: ${prompt.length} chars)")
                    val result = lm.generateCompletion(
                        messages = listOf(
                            ChatMessage(content = prompt, role = "user")
                        ),
                        params = CactusCompletionParams(
                            maxTokens = MAX_TOKENS,
                            temperature = TEMPERATURE,
                            topK = TOP_K,
                            mode = InferenceMode.LOCAL
                        )
                    )
                    Log.d(TAG, "Native inference completed")

                    if (result?.success == true) {
                        result.response?.trim().orEmpty()
                    } else {
                        "Error: LLM generation failed"
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Native inference error", e)
                    "Error: ${e.message}"
                }
            }
        }
    }

    override fun isReady(): Boolean = isInitialized && cactusLM?.isLoaded() == true

    fun unload() {
        cactusLM?.unload()
        cactusLM = null
        isInitialized = false
    }
}
