package com.verdure.services

import android.content.Context
import android.util.Log
import com.verdure.core.CactusEmbeddingEngine
import com.verdure.core.CactusLLMEngine
import com.verdure.data.EntityMentionEntity
import com.verdure.data.NotificationData
import com.verdure.data.NotificationRepository
import com.verdure.data.VectorIndex
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Ambient AI ingestion pipeline:
 * extract -> embed -> dual-store persist.
 *
 * Triggered asynchronously after Room notification insert.
 */
class IngestionPipeline private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repo = NotificationRepository.getInstance(context)
    private val generationEngine = CactusLLMEngine.getInstance(context)
    private val embeddingEngine = CactusEmbeddingEngine.getInstance(context)
    private val vectorIndex by lazy { VectorIndex(context) }
    private val processing = AtomicBoolean(false)
    private val pendingNotifications = ConcurrentLinkedQueue<NotificationData>()
    private val drainMutex = Mutex()

    companion object {
        private const val TAG = "IngestionPipeline"
        private const val DEBOUNCE_MS = 800L
        private const val DEFAULT_MODEL_VERSION = "qwen3-0.6-embed"

        @Volatile
        private var instance: IngestionPipeline? = null

        fun getInstance(context: Context): IngestionPipeline {
            return instance ?: synchronized(this) {
                instance ?: IngestionPipeline(context.applicationContext).also { instance = it }
            }
        }
    }

    @Serializable
    private data class ExtractionJson(
        val summary: String = "",
        val entities: List<String> = emptyList()
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun enqueue(notificationData: NotificationData) {
        pendingNotifications.add(notificationData)
        triggerProcessing()
    }

    fun warmup() {
        scope.launch {
            try {
                vectorIndex.ensureReady()
                Log.d(TAG, "Ingestion warmup complete")
            } catch (e: Exception) {
                Log.e(TAG, "Ingestion warmup failed", e)
            }
        }
    }

    private fun triggerProcessing() {
        scope.launch {
            if (processing.get()) return@launch
            processing.set(true)
            try {
                delay(DEBOUNCE_MS)
                drainQueue()
            } finally {
                processing.set(false)
                if (pendingNotifications.isNotEmpty()) {
                    triggerProcessing()
                }
            }
        }
    }

    private suspend fun drainQueue() {
        drainMutex.withLock {
            vectorIndex.ensureReady()
            if (!embeddingEngine.isReady()) {
                Log.d(TAG, "Embedding model not ready - queue retained for later drain")
                if (!embeddingEngine.initialize()) {
                    Log.e(TAG, "Embedding engine init failed, cannot drain queue yet")
                    return
                }
            }

            while (true) {
                val next = pendingNotifications.poll() ?: break
                try {
                    ingestSingle(next)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed ingest for notification systemKey=${next.systemKey}", e)
                }
            }
        }
    }

    private suspend fun ingestSingle(notificationData: NotificationData) {
        Log.d(TAG, "Ingestion start id=${notificationData.id} app=${notificationData.appName}")

        val extraction = extractSummaryAndEntities(notificationData)
        if (extraction.summary.isBlank()) {
            Log.w(TAG, "Extraction produced empty summary, skipping id=${notificationData.id}")
            return
        }

        val stored = repo.findBySystemKey(notificationData.systemKey)
        if (stored == null) {
            Log.w(TAG, "Notification not found in Room yet, re-queueing systemKey=${notificationData.systemKey}")
            pendingNotifications.add(notificationData)
            return
        }

        val sourceNotificationId = stored.id

        val embeddingRowId = repo.storeEmbeddingRow(
            sourceNotificationId = sourceNotificationId,
            summaryText = extraction.summary,
            modelVersion = embeddingEngine.getActiveModelSlug() ?: DEFAULT_MODEL_VERSION
        )
        if (embeddingRowId <= 0L) {
            Log.e(TAG, "Failed to persist embedding row for notificationId=$sourceNotificationId")
            return
        }

        val vector = embeddingEngine.embed(extraction.summary)
        vectorIndex.insert(embeddingRowId, vector)

        val mentions = extraction.entities.map { raw ->
            val normalized = raw.trim()
            val type = inferEntityType(normalized)
            EntityMentionEntity(
                notificationId = sourceNotificationId,
                type = type,
                value = normalized
            )
        }.filter { it.value.isNotBlank() }
        repo.storeEntityMentions(sourceNotificationId, mentions)

        Log.d(
            TAG,
            "Ingestion complete notificationId=$sourceNotificationId embeddingId=$embeddingRowId entities=${mentions.size}"
        )
    }

    private suspend fun extractSummaryAndEntities(notification: NotificationData): ExtractionJson {
        val prompt = """
Extract entities (people, orgs, dates, amounts, actions) and a one-sentence summary from this notification.
Return JSON only with this exact shape:
{"summary":"...","entities":["..."]}

Notification:
app=${notification.appName}
title=${notification.title ?: ""}
text=${notification.text ?: ""}
category=${notification.category ?: ""}
        """.trimIndent()

        val raw = generationEngine.generateContent(prompt)
        return try {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}') + 1
            if (start >= 0 && end > start) {
                json.decodeFromString<ExtractionJson>(raw.substring(start, end))
            } else {
                ExtractionJson(
                    summary = fallbackSummary(notification),
                    entities = emptyList()
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Extraction parse failed, using fallback summary", e)
            ExtractionJson(
                summary = fallbackSummary(notification),
                entities = emptyList()
            )
        }
    }

    private fun fallbackSummary(notification: NotificationData): String {
        val title = notification.title?.trim().orEmpty()
        val text = notification.text?.trim().orEmpty()
        return listOf(title, text)
            .filter { it.isNotBlank() }
            .joinToString(" - ")
            .take(240)
    }

    private fun inferEntityType(raw: String): String {
        val lower = raw.lowercase()
        return when {
            lower.contains(Regex("\\d{1,2}[:/]\\d{1,2}")) -> "date"
            lower.contains(Regex("\\$\\d+")) -> "amount"
            lower.contains(" inc") || lower.contains(" llc") || lower.contains(" corp") -> "org"
            lower.contains("call ") || lower.contains("submit ") || lower.contains("pay ") -> "action"
            else -> "entity"
        }
    }
}
