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
 * Cactus LLM backend for running Gemma on-device.
 *
 * Uses Cactus SDK for local model download and inference.
 */
class CactusLLMEngine private constructor(private val context: Context) : LLMEngine {

    private var cactusLM: CactusLM? = null
    private var isInitialized = false
    private var loadedModelSlug: String? = null
    @Volatile private var lastInitError: String? = null
    private val initMutex = Mutex()
    private val inferenceMutex = Mutex()

    companion object {
        private const val TAG = "CactusLLMEngine"
        // Cactus Kotlin SDK pattern: downloadModel(slug) -> initializeModel(CactusInitParams(model = slug)).
        // Prefer Gemma 3 1B because it is confirmed in the on-device Cactus model registry.
        private val MODEL_CANDIDATES = listOf(
            "gemma3-1b",
            "gemma3-1b-pro",
            "gemma3-270m"
        )
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

        fun getConfiguredModelSlug(): String = MODEL_CANDIDATES.first()
    }

    fun getActiveModelSlug(): String? = loadedModelSlug

    override suspend fun initialize(onProgress: ((String) -> Unit)?): Boolean {
        if (isInitialized && cactusLM?.isLoaded() == true) return true

        return initMutex.withLock {
            if (isInitialized && cactusLM?.isLoaded() == true) return@withLock true

            withContext(Dispatchers.IO) {
                try {
                    Log.i(TAG, "Model self-check: configured slug=${getConfiguredModelSlug()}")
                    CactusContextInitializer.initialize(context)
                    val lm = CactusLM()
                    val attemptErrors = mutableListOf<String>()
                    var availableSlugs: List<String> = emptyList()

                    try {
                        availableSlugs = lm.getModels().map { it.slug }
                        val gemmaSlugs = availableSlugs.filter { it.contains("gemma", ignoreCase = true) }
                        if (gemmaSlugs.isNotEmpty()) {
                            Log.i(TAG, "Model self-check: available Gemma slugs=${gemmaSlugs.joinToString(", ")}")
                        } else {
                            Log.w(TAG, "Model self-check: no Gemma slugs discovered from Cactus model registry")
                        }
                    } catch (discoveryError: Exception) {
                        Log.w(TAG, "Model discovery failed; using static slug candidates", discoveryError)
                    }

                    val downloadCandidates = resolveModelCandidates(MODEL_CANDIDATES, availableSlugs)
                    Log.i(TAG, "Model self-check: resolved download candidates=${downloadCandidates.joinToString(", ")}")

                    for (slug in downloadCandidates) {
                        try {
                            onProgress?.invoke("⏳ Downloading AI model ($slug)…")
                            lm.downloadModel(slug)

                            onProgress?.invoke("⚙️ Loading AI model into memory ($slug)…")
                            lm.initializeModel(
                                CactusInitParams(
                                    model = slug,
                                    contextSize = CONTEXT_SIZE
                                )
                            )

                            cactusLM = lm
                            isInitialized = true
                            loadedModelSlug = slug
                            lastInitError = null
                            onProgress?.invoke("✅ AI model ready ($slug)")
                            return@withContext true
                        } catch (e: Exception) {
                            val errorLine = "$slug failed: ${e.javaClass.simpleName}: ${e.message ?: "unknown error"}"
                            attemptErrors.add(errorLine)
                            Log.e(TAG, "Model initialization attempt failed for slug=$slug", e)
                        }
                    }

                    isInitialized = false
                    cactusLM = null
                    loadedModelSlug = null
                    val gemmaHint = availableSlugs
                        .filter { it.contains("gemma", ignoreCase = true) }
                        .take(6)
                        .joinToString(", ")
                        .ifBlank { "none discovered from registry" }
                    lastInitError = (attemptErrors + "Available Gemma slugs: $gemmaHint").joinToString(" | ")
                    onProgress?.invoke("❌ Failed to load model: $lastInitError")
                    false
                } catch (e: Exception) {
                    Log.e(TAG, "Cactus initialization failed before model load", e)
                    isInitialized = false
                    cactusLM = null
                    loadedModelSlug = null
                    lastInitError = "${e.javaClass.simpleName}: ${e.message ?: "unknown error"}"
                    onProgress?.invoke("❌ Failed to load model: $lastInitError")
                    false
                }
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
                    Log.d(
                        TAG,
                        "Starting native inference (model=${loadedModelSlug ?: "unknown"}, prompt length=${prompt.length} chars)"
                    )
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

    fun getLastInitError(): String? = lastInitError

    private fun resolveModelCandidates(
        configuredCandidates: List<String>,
        discoveredSlugs: List<String>
    ): List<String> {
        if (discoveredSlugs.isEmpty()) return configuredCandidates

        val normalizedDiscovered = discoveredSlugs.associateBy { normalizeSlug(it) }
        val resolved = mutableListOf<String>()

        configuredCandidates.forEach { configured ->
            val normalizedConfigured = normalizeSlug(configured)
            val shortConfigured = normalizeSlug(configured.substringAfterLast("/"))

            // 1) Exact (case-insensitive) match
            normalizedDiscovered[normalizedConfigured]?.let {
                resolved.add(it)
                return@forEach
            }

            // 2) Match by short slug (with or without repo prefix)
            discoveredSlugs.firstOrNull { candidate ->
                val n = normalizeSlug(candidate)
                n == shortConfigured || n.endsWith("/$shortConfigured")
            }?.let {
                resolved.add(it)
                return@forEach
            }
        }

                    // 3) If still empty, pick first discovered Gemma variant.
        if (resolved.isEmpty()) {
            discoveredSlugs.firstOrNull { candidate ->
                val n = normalizeSlug(candidate)
                            n.contains("gemma")
            }?.let { resolved.add(it) }
        }

        return (resolved + configuredCandidates).distinct()
    }

    private fun normalizeSlug(value: String): String = value.trim().lowercase()

    fun unload() {
        cactusLM?.unload()
        cactusLM = null
        isInitialized = false
        loadedModelSlug = null
        lastInitError = null
    }
}
