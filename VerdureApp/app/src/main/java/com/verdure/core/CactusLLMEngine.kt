package com.verdure.core

import android.content.Context
import android.util.Log
import com.cactus.CactusCompletionParams
import com.cactus.CactusContextInitializer
import com.cactus.CactusInitParams
import com.cactus.CactusLM
import com.cactus.CactusModel
import com.cactus.CactusModelManager
import com.cactus.ChatMessage
import com.cactus.InferenceMode
import com.cactus.models.CactusTool
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
    private var toolsProvider: (suspend () -> List<CactusTool>)? = null
    private var toolExecutor: (suspend (String, Map<String, String>) -> String)? = null

    companion object {
        private const val TAG = "CactusLLMEngine"
        // Cactus Kotlin SDK pattern: downloadModel(slug) -> initializeModel(CactusInitParams(model = slug)).
        // Prefer Gemma 4 E2B It first, then fall back to smaller Gemma variants.
        private val MODEL_CANDIDATES = listOf(
            "google/gemma-4-E2B-it",
            "gemma-4-e2b-it",
            "gemma3-1b",
            "gemma3-1b-pro",
            "gemma3-270m"
        )
        private const val CONTEXT_SIZE = 8192
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

    suspend fun getAvailableModels(): List<CactusModel> {
        return try {
            val lm = cactusLM
            if (lm != null) {
                lm.getModels()
            } else {
                CactusLM().getModels()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch available models", e)
            emptyList()
        }
    }

    fun getDownloadedModels(): List<String> {
        return try {
            CactusModelManager.getDownloadedModels()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch downloaded models", e)
            emptyList()
        }
    }

    fun getModelsDirectory(): String {
        return try {
            CactusModelManager.getModelsDirectory()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read models directory", e)
            "(unknown)"
        }
    }

    suspend fun switchModel(
        targetModelSlug: String,
        removePreviousModel: Boolean,
        onProgress: ((String) -> Unit)? = null
    ): Boolean {
        val lm = cactusLM ?: return initialize(onProgress = onProgress) &&
            switchModel(targetModelSlug, removePreviousModel, onProgress)
        val previous = loadedModelSlug

        return try {
            onProgress?.invoke("⏳ Downloading selected model ($targetModelSlug)…")
            lm.downloadModel(targetModelSlug)
            onProgress?.invoke("⚙️ Loading selected model ($targetModelSlug)…")
            lm.initializeModel(
                CactusInitParams(
                    model = targetModelSlug,
                    contextSize = CONTEXT_SIZE
                )
            )

            loadedModelSlug = targetModelSlug
            lastInitError = null
            onProgress?.invoke("✅ Switched to $targetModelSlug")

            if (removePreviousModel && !previous.isNullOrBlank() && previous != targetModelSlug) {
                val deleted = CactusModelManager.deleteModel(previous)
                if (deleted) {
                    onProgress?.invoke("🧹 Removed previous model: $previous")
                } else {
                    onProgress?.invoke("⚠️ Could not remove previous model: $previous")
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed switching model to $targetModelSlug", e)
            lastInitError = "${e.javaClass.simpleName}: ${e.message ?: "unknown error"}"
            onProgress?.invoke("❌ Failed to switch model: $lastInitError")
            false
        }
    }

    fun configureToolCalling(
        toolsProvider: suspend () -> List<CactusTool>,
        toolExecutor: suspend (String, Map<String, String>) -> String
    ) {
        this.toolsProvider = toolsProvider
        this.toolExecutor = toolExecutor
    }

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
                            mode = InferenceMode.LOCAL,
                            tools = resolveToolsForCurrentModel(lm)
                        )
                    )
                    Log.d(TAG, "Native inference completed")

                    if (result?.success == true) {
                        val canUseTools = loadedModelSlug?.let { supportsToolCalling(it, lm) } == true
                        if (
                            canUseTools &&
                            !result.toolCalls.isNullOrEmpty() &&
                            toolsProvider != null &&
                            toolExecutor != null
                        ) {
                            Log.d(TAG, "Tool calls detected (${result.toolCalls?.size}); executing tool loop")
                            runToolCallingLoop(lm, prompt, result.response?.trim().orEmpty(), result.toolCalls)
                        } else {
                            if (!canUseTools && toolsProvider != null) {
                                Log.d(TAG, "Current model does not support native tool-calling; using text-only response")
                            }
                            result.response?.trim().orEmpty()
                        }
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

    private suspend fun runToolCallingLoop(
        lm: CactusLM,
        initialPrompt: String,
        initialResponse: String,
        initialToolCalls: List<com.cactus.ToolCall>?
    ): String {
        val provider = toolsProvider ?: return initialResponse
        val executor = toolExecutor ?: return initialResponse
        val tools = provider()
        if (tools.isEmpty()) return initialResponse

        val messages = mutableListOf(
            ChatMessage(role = "user", content = initialPrompt)
        )

        if (initialResponse.isNotBlank()) {
            messages.add(ChatMessage(role = "assistant", content = initialResponse))
        }

        var pendingCalls = initialToolCalls.orEmpty()
        var finalResponse = initialResponse
        var iterations = 0
        val maxIterations = 4

        while (pendingCalls.isNotEmpty() && iterations < maxIterations) {
            iterations++
            pendingCalls.forEach { call ->
                val result = try {
                    executor(call.name, call.arguments)
                } catch (e: Exception) {
                    "Tool execution failed for ${call.name}: ${e.message}"
                }
                messages.add(
                    ChatMessage(
                        role = "tool",
                        content = """{"name":"${call.name}","result":${escapeForJson(result)}}"""
                    )
                )
            }

            val followup = lm.generateCompletion(
                messages = messages,
                params = CactusCompletionParams(
                    maxTokens = MAX_TOKENS,
                    temperature = TEMPERATURE,
                    topK = TOP_K,
                    mode = InferenceMode.LOCAL,
                    tools = tools
                )
            )

            if (followup?.success != true) break
            finalResponse = followup.response?.trim().orEmpty()
            if (finalResponse.isNotBlank()) {
                messages.add(ChatMessage(role = "assistant", content = finalResponse))
            }
            pendingCalls = followup.toolCalls.orEmpty()
        }

        return finalResponse
    }

    private suspend fun supportsToolCalling(slug: String, lm: CactusLM): Boolean {
        return try {
            lm.getModels().firstOrNull { it.slug.equals(slug, ignoreCase = true) }
                ?.supports_tool_calling
                ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Could not verify tool-calling support for $slug", e)
            false
        }
    }

    private suspend fun resolveToolsForCurrentModel(lm: CactusLM): List<CactusTool> {
        val provider = toolsProvider ?: return emptyList()
        val slug = loadedModelSlug ?: return emptyList()
        return if (supportsToolCalling(slug, lm)) {
            provider()
        } else {
            emptyList()
        }
    }

    fun isCurrentModelToolCapable(): Boolean {
        val slug = loadedModelSlug ?: return false
        val normalized = normalizeSlug(slug)
        return normalized.contains("qwen") || normalized.contains("lfm") || normalized.contains("functiongemma")
    }

    fun getToolCallingSupportSummary(): String {
        val slug = loadedModelSlug ?: return "Model not loaded yet."
        return if (isCurrentModelToolCapable()) {
            "Tool calling enabled for model '$slug'."
        } else {
            "Model '$slug' does not advertise native tool-calling support in Cactus. " +
                "Verdure will keep using intent-routed Kotlin tools."
        }
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

    private fun escapeForJson(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    fun unload() {
        cactusLM?.unload()
        cactusLM = null
        isInitialized = false
        loadedModelSlug = null
        lastInitError = null
    }
}
