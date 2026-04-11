package com.verdure.core

import android.content.Context
import android.util.Log
import com.cactus.CactusContextInitializer
import com.cactus.CactusInitParams
import com.cactus.CactusLM
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Cactus embedding engine for on-device semantic vectors.
 *
 * Uses a dedicated CactusLM instance (separate from generation LLM)
 * so ingestion-time embedding can run independently.
 */
class CactusEmbeddingEngine private constructor(private val context: Context) {

    private var cactusEmbeddingLM: CactusLM? = null
    private var isInitialized = false
    private var loadedModelSlug: String? = null
    @Volatile private var embeddingDimension: Int? = null
    @Volatile private var lastInitError: String? = null
    private val initMutex = Mutex()
    private val inferenceMutex = Mutex()

    companion object {
        private const val TAG = "CactusEmbeddingEngine"

        // Verified against Cactus 1.4.3-beta model registry.
        private val MODEL_CANDIDATES = listOf(
            "qwen3-0.6-embed",
            "nomic2-embed-300m"
        )

        private const val CONTEXT_SIZE = 2048

        @Volatile
        private var instance: CactusEmbeddingEngine? = null

        fun getInstance(context: Context): CactusEmbeddingEngine {
            return instance ?: synchronized(this) {
                instance ?: CactusEmbeddingEngine(context.applicationContext).also { instance = it }
            }
        }

        fun getConfiguredModelSlug(): String = MODEL_CANDIDATES.first()
    }

    fun getActiveModelSlug(): String? = loadedModelSlug

    suspend fun initialize(onProgress: ((String) -> Unit)? = null): Boolean {
        if (isInitialized && cactusEmbeddingLM?.isLoaded() == true) return true

        return initMutex.withLock {
            if (isInitialized && cactusEmbeddingLM?.isLoaded() == true) return@withLock true

            withContext(Dispatchers.IO) {
                try {
                    Log.i(TAG, "Embedding self-check: configured slug=${getConfiguredModelSlug()}")
                    CactusContextInitializer.initialize(context)
                    val lm = CactusLM()
                    val attemptErrors = mutableListOf<String>()
                    var availableSlugs: List<String> = emptyList()

                    try {
                        availableSlugs = lm.getModels().map { it.slug }
                        val embeddingSlugs = availableSlugs.filter { it.contains("embed", ignoreCase = true) }
                        if (embeddingSlugs.isNotEmpty()) {
                            Log.i(
                                TAG,
                                "Embedding self-check: available embedding slugs=${embeddingSlugs.joinToString(", ")}"
                            )
                        } else {
                            Log.w(
                                TAG,
                                "Embedding self-check: no embedding slugs discovered from Cactus model registry"
                            )
                        }
                    } catch (discoveryError: Exception) {
                        Log.w(TAG, "Embedding model discovery failed; using static slug candidates", discoveryError)
                    }

                    val downloadCandidates = resolveModelCandidates(MODEL_CANDIDATES, availableSlugs)
                    Log.i(
                        TAG,
                        "Embedding self-check: resolved download candidates=${downloadCandidates.joinToString(", ")}"
                    )

                    for (slug in downloadCandidates) {
                        try {
                            onProgress?.invoke("⏳ Downloading embedding model ($slug)…")
                            lm.downloadModel(slug)

                            onProgress?.invoke("⚙️ Loading embedding model into memory ($slug)…")
                            lm.initializeModel(
                                CactusInitParams(
                                    model = slug,
                                    contextSize = CONTEXT_SIZE
                                )
                            )

                            // Hard failure if chosen model does not emit usable embeddings.
                            val smokeTest = lm.generateEmbedding("embedding warmup")
                            if (smokeTest?.success != true || smokeTest.embeddings.isEmpty()) {
                                throw IllegalStateException(
                                    "Model '$slug' loaded but did not return usable embeddings"
                                )
                            }
                            embeddingDimension = smokeTest.embeddings.size

                            cactusEmbeddingLM = lm
                            isInitialized = true
                            loadedModelSlug = slug
                            lastInitError = null
                            onProgress?.invoke("✅ Embedding model ready ($slug)")
                            return@withContext true
                        } catch (e: Exception) {
                            val errorLine =
                                "$slug failed: ${e.javaClass.simpleName}: ${e.message ?: "unknown error"}"
                            attemptErrors.add(errorLine)
                            Log.e(TAG, "Embedding model init attempt failed for slug=$slug", e)
                        }
                    }

                    isInitialized = false
                    cactusEmbeddingLM = null
                    loadedModelSlug = null
                    embeddingDimension = null
                    val embeddingHint = availableSlugs
                        .filter { it.contains("embed", ignoreCase = true) }
                        .take(8)
                        .joinToString(", ")
                        .ifBlank { "none discovered from registry" }
                    lastInitError = (
                        "Cactus embedding model unavailable or unusable. " +
                            (attemptErrors + "Available embedding slugs: $embeddingHint").joinToString(" | ")
                        )
                    onProgress?.invoke("❌ Failed to load embedding model: $lastInitError")
                    false
                } catch (e: Exception) {
                    Log.e(TAG, "Embedding engine initialization failed before model load", e)
                    isInitialized = false
                    cactusEmbeddingLM = null
                    loadedModelSlug = null
                    embeddingDimension = null
                    lastInitError = "Cactus embedding init failed: ${e.javaClass.simpleName}: ${e.message}"
                    onProgress?.invoke("❌ Failed to load embedding model: $lastInitError")
                    false
                }
            }
        }
    }

    suspend fun embed(text: String): FloatArray {
        val lm = cactusEmbeddingLM
        if (!isInitialized || lm == null || !lm.isLoaded()) {
            throw IllegalStateException(
                "Embedding model is not ready. " +
                    (lastInitError ?: "initialize() has not completed successfully")
            )
        }

        return inferenceMutex.withLock {
            withContext(Dispatchers.IO) {
                val result = lm.generateEmbedding(text)
                    ?: throw IllegalStateException("Embedding generation returned null result")
                if (!result.success || result.embeddings.isEmpty()) {
                    throw IllegalStateException(
                        "Embedding generation failed: ${result.errorMessage ?: "unknown error"}"
                    )
                }
                result.embeddings.map { it.toFloat() }.toFloatArray()
            }
        }
    }

    suspend fun embedBatch(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            texts.map { text -> embed(text) }
        }
    }

    fun isReady(): Boolean = isInitialized && cactusEmbeddingLM?.isLoaded() == true

    fun getLastInitError(): String? = lastInitError

    suspend fun getEmbeddingDimension(): Int {
        embeddingDimension?.let { return it }
        val sample = embed("embedding dimension check")
        embeddingDimension = sample.size
        return sample.size
    }

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

            normalizedDiscovered[normalizedConfigured]?.let {
                resolved.add(it)
                return@forEach
            }

            discoveredSlugs.firstOrNull { candidate ->
                val normalized = normalizeSlug(candidate)
                normalized == shortConfigured || normalized.endsWith("/$shortConfigured")
            }?.let {
                resolved.add(it)
                return@forEach
            }
        }

        if (resolved.isEmpty()) {
            discoveredSlugs.firstOrNull { it.contains("embed", ignoreCase = true) }?.let {
                resolved.add(it)
            }
        }

        return (resolved + configuredCandidates).distinct()
    }

    private fun normalizeSlug(value: String): String = value.trim().lowercase()

    fun unload() {
        cactusEmbeddingLM?.unload()
        cactusEmbeddingLM = null
        isInitialized = false
        loadedModelSlug = null
        embeddingDimension = null
        lastInitError = null
    }
}
