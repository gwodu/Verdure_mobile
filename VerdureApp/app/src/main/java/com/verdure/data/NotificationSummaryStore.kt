package com.verdure.data

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persistent storage for notification summaries.
 * Stores summaries in SharedPreferences with 24-hour expiration.
 */
class NotificationSummaryStore(context: Context) {
    private val prefs = context.getSharedPreferences("notification_summaries", Context.MODE_PRIVATE)
    
    companion object {
        private const val TAG = "NotificationSummaryStore"
        private const val SUMMARY_EXPIRATION_MS = 24 * 60 * 60 * 1000L  // 24 hours
        private const val KEY_LATEST_SUMMARY = "latest_summary"
        private const val KEY_SUMMARIZED_IDS = "summarized_ids"
    }
    
    @Serializable
    data class Summary(
        val notificationIds: List<Int>,
        val text: String,
        val timestamp: Long,
        val maxScore: Int = 0
    )
    
    /**
     * Save a new summary with automatic cleanup of expired summaries.
     */
    fun saveSummary(notificationIds: List<Int>, text: String, timestamp: Long, maxScore: Int = 0) {
        // Clean up expired summaries before saving new one
        cleanupExpiredSummaries()
        
        val summary = Summary(notificationIds, text, timestamp, maxScore)
        
        try {
            prefs.edit()
                .putString(KEY_LATEST_SUMMARY, Json.encodeToString(summary))
                .putStringSet(KEY_SUMMARIZED_IDS, notificationIds.map { it.toString() }.toSet())
                .apply()
            
            Log.d(TAG, "Saved summary for ${notificationIds.size} notifications")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save summary", e)
        }
    }
    
    /**
     * Get the latest summary if it exists and hasn't expired.
     * Returns null if no summary exists or if it has expired.
     */
    fun getLatestSummary(): Summary? {
        val json = prefs.getString(KEY_LATEST_SUMMARY, null) ?: return null
        
        return try {
            val summary = Json.decodeFromString<Summary>(json)
            
            // Check if expired
            val age = System.currentTimeMillis() - summary.timestamp
            if (age > SUMMARY_EXPIRATION_MS) {
                Log.d(TAG, "Summary expired (age: ${age / 1000 / 60} minutes)")
                clearSummaries()
                null
            } else {
                summary
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode summary", e)
            null
        }
    }
    
    /**
     * Check if a notification has already been summarized.
     */
    fun hasBeenSummarized(notificationId: Int): Boolean {
        val summarizedIds = prefs.getStringSet(KEY_SUMMARIZED_IDS, emptySet()) ?: emptySet()
        return summarizedIds.contains(notificationId.toString())
    }
    
    /**
     * Clean up expired summaries.
     */
    fun cleanupExpiredSummaries() {
        val summary = getLatestSummary()  // This will auto-clear if expired
        if (summary == null) {
            Log.d(TAG, "No valid summary to keep, cleared expired data")
        }
    }
    
    /**
     * Clear all summaries and tracked IDs.
     */
    fun clearSummaries() {
        prefs.edit()
            .remove(KEY_LATEST_SUMMARY)
            .remove(KEY_SUMMARIZED_IDS)
            .apply()
        
        Log.d(TAG, "Cleared all summaries")
    }
}
