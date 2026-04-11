package com.verdure.tools

import android.content.Context
import android.util.Log
import com.verdure.core.CactusEmbeddingEngine
import com.verdure.data.NotificationFilter
import com.verdure.data.NotificationRepository
import com.verdure.data.StoredNotification
import com.verdure.data.UserContextManager
import com.verdure.data.VectorIndex
import com.verdure.data.toNotificationDataForScoring
import com.cactus.models.ToolParameter
import kotlin.math.exp

/**
 * Semantic retrieval over notification memory:
 * 1) embed query
 * 2) vector top-k search
 * 3) join back to stored notifications
 * 4) hybrid re-rank (cosine + recency + heuristic)
 */
class SemanticRetrievalTool(
    private val context: Context,
    private val contextManager: UserContextManager
) : Tool {

    companion object {
        private const val TAG = "SemanticRetrievalTool"

        // Tunable weights for hybrid ranking.
        private const val WEIGHT_COSINE = 0.5
        private const val WEIGHT_RECENCY = 0.3
        private const val WEIGHT_HEURISTIC = 0.2

        private const val DEFAULT_TOP_K = 10
    }

    override val name: String = "semantic_retrieval"
    override val description: String =
        "Retrieves relevant notifications using embeddings plus recency and heuristic re-ranking"
    override val argumentSchema: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            type = "string",
            description = "Action to run: retrieve",
            required = false
        ),
        "query" to ToolParameter(
            type = "string",
            description = "Natural language user query",
            required = true
        ),
        "k" to ToolParameter(
            type = "number",
            description = "Number of notifications to return",
            required = false
        )
    )

    private val embeddingEngine by lazy { CactusEmbeddingEngine.getInstance(context) }
    private val vectorIndex by lazy { VectorIndex(context) }
    private val repository by lazy { NotificationRepository.getInstance(context) }

    override suspend fun execute(params: Map<String, Any>): String {
        val action = params["action"] as? String ?: "retrieve"
        if (action != "retrieve") {
            return "Unsupported semantic retrieval action: $action"
        }
        val query = params["query"] as? String
            ?: return "Semantic retrieval requires a query"
        val topK = (params["k"] as? Int ?: DEFAULT_TOP_K).coerceIn(1, 50)

        Log.d(TAG, "Retrieval start query='$query' k=$topK")

        if (!embeddingEngine.isReady()) {
            return "Embeddings not ready"
        }
        val queryVector = try {
            embeddingEngine.embed(query)
        } catch (e: Exception) {
            Log.e(TAG, "Failed generating query embedding", e)
            return "Embeddings not ready"
        }
        vectorIndex.setVectorDimension(queryVector.size)
        vectorIndex.ensureReady()

        val vectorResults = try {
            vectorIndex.searchTopK(queryVector, topK)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Vector search dimension mismatch; falling back gracefully", e)
            return "No semantically relevant notifications found."
        }
        if (vectorResults.isEmpty()) {
            Log.d(TAG, "Retrieval completed with 0 vector matches")
            return "No semantically relevant notifications found."
        }

        val embeddings = repository.getEmbeddingsByIds(vectorResults.map { it.first })
        if (embeddings.isEmpty()) {
            return "No semantically relevant notifications found."
        }

        val notificationIds = embeddings.map { it.sourceNotificationId }.distinct()
        val notifications = repository.getNotificationsByIds(notificationIds)
        if (notifications.isEmpty()) {
            return "No semantically relevant notifications found."
        }

        val userContext = contextManager.loadContext()
        val filter = NotificationFilter(userContext)
        val embeddingById = embeddings.associateBy { it.id }
        val byNotificationId = notifications.associateBy { it.id }
        val now = System.currentTimeMillis()

        val reranked = vectorResults.mapNotNull { (embeddingId, cosineDistance) ->
            val embedding = embeddingById[embeddingId] ?: return@mapNotNull null
            val notif = byNotificationId[embedding.sourceNotificationId] ?: return@mapNotNull null
            val cosineSimilarity = (1.0 - cosineDistance.toDouble()).coerceIn(0.0, 1.0)
            val recencyScore = recencyScore(notif.timestamp, now)
            val heuristicScore = heuristicScore(notif, filter)
            val finalScore =
                (WEIGHT_COSINE * cosineSimilarity) +
                    (WEIGHT_RECENCY * recencyScore) +
                    (WEIGHT_HEURISTIC * heuristicScore)
            RankedResult(
                notification = notif,
                finalScore = finalScore,
                cosineDistance = cosineDistance,
                cosineSimilarity = cosineSimilarity
            )
        }
            .sortedByDescending { it.finalScore }
            .take(topK)

        Log.d(TAG, "Retrieval complete results=${reranked.size}")
        return formatNotificationsForContext(reranked.map { it.notification })
    }

    private fun recencyScore(timestamp: Long, now: Long): Double {
        val hours = ((now - timestamp).coerceAtLeast(0L)).toDouble() / 3_600_000.0
        // Exponential decay over hours.
        return exp(-hours / 24.0).coerceIn(0.0, 1.0)
    }

    private fun heuristicScore(notification: StoredNotification, filter: NotificationFilter): Double {
        val raw = filter.scoreNotification(notification.toNotificationDataForScoring())
        val normalized = (raw + 5.0) / 29.0 // [-5, 24] -> [0, 1]
        return normalized.coerceIn(0.0, 1.0)
    }

    private fun formatNotificationsForContext(notifications: List<StoredNotification>): String {
        if (notifications.isEmpty()) return "No semantically relevant notifications found."
        return notifications.mapIndexed { index, notif ->
            val title = sanitizeText(notif.title ?: "(no title)")
            val text = sanitizeText(notif.text ?: "(no text)")
            val timeDesc = notif.getFormattedTime()
            val dismissedTag = if (notif.isDismissed) " [dismissed]" else ""
            "${index + 1}. [id=${notif.id}] ${notif.appName}: $title - $text ($timeDesc)$dismissedTag"
        }.joinToString("\n")
    }

    private fun sanitizeText(text: String): String {
        return text
            .replace("\u0000", "")
            .replace(Regex("[\\p{C}&&[^\\n\\r\\t]]"), "")
            .take(220)
    }

    private data class RankedResult(
        val notification: StoredNotification,
        val finalScore: Double,
        val cosineDistance: Float,
        val cosineSimilarity: Double
    )
}
