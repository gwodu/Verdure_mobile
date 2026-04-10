package com.verdure.tools

import android.content.Context
import android.util.Log
import com.verdure.core.LLMEngine
import com.verdure.data.NotificationData
import com.verdure.data.NotificationFilter
import com.verdure.data.NotificationRepository
import com.verdure.data.StoredNotification
import com.verdure.data.UserContextManager
import com.verdure.data.VerdurePreferences
import com.verdure.services.VerdureNotificationListener

/**
 * Tool for analyzing and prioritizing notifications
 *
 * Architecture:
 * 1. All notifications stored in Room database (24h retention)
 * 2. Heuristic filter scores notifications on arrival
 * 3. LLM analyzes priority notifications from Room (not just in-memory)
 * 4. Auto-dismissal respects user toggle settings
 *
 * This validates the hybrid system: heuristic does heavy lifting, LLM provides synthesis
 */
class NotificationTool(
    private val context: Context,
    private val llmEngine: LLMEngine,
    private val contextManager: UserContextManager
) : Tool {
    private val repository: NotificationRepository by lazy {
        NotificationRepository.getInstance(context)
    }
    
    private val preferences: VerdurePreferences by lazy {
        VerdurePreferences.getInstance(context)
    }
    
    companion object {
        private const val TAG = "NotificationTool"
        private const val DEFAULT_CLEAR_AFTER_VIEW = true
    }

    override val name: String = "notification_filter"
    override val description: String = "Analyzes and prioritizes notifications based on importance and urgency"

    override suspend fun execute(params: Map<String, Any>): String {
        val action = params["action"] as? String
        val clearAfterView = params["clear_after_view"] as? Boolean ?: DEFAULT_CLEAR_AFTER_VIEW

        // If action is "get_all", return recent notifications (no score threshold)
        if (action == "get_all") {
            val limit = params["limit"] as? Int ?: 8
            val notifications = repository.getRecentNotifications(limit)
            if (notifications.isEmpty()) {
                return "No recent notifications right now."
            }
            val formatted = formatNotificationsForContext(notifications)
            maybeDismissViewedNotifications(
                notifications, 
                clearAfterView,
                VerdurePreferences.DismissalContext.CHAT
            )
            return formatted
        }

        // If action is "search", filter by keywords
        if (action == "search") {
            val keywords = params["keywords"] as? List<*>
            val keywordStrings = keywords?.mapNotNull { it as? String } ?: emptyList()
            val limit = params["limit"] as? Int ?: 10

            val notifications = searchNotifications(keywordStrings, limit)
            if (notifications.isEmpty()) {
                return "No notifications found matching: ${keywordStrings.joinToString(", ")}"
            }
            val formatted = formatNotificationsForContext(notifications)
            maybeDismissViewedNotifications(
                notifications, 
                clearAfterView,
                VerdurePreferences.DismissalContext.CHAT
            )
            return formatted
        }

        // Handle "get_priority" action (for compatibility with VerdureAI)
        if (action == "get_priority") {
            val limit = params["limit"] as? Int ?: 8
            val notifications = getPriorityNotifications(limit)
            if (notifications.isEmpty()) {
                return "No priority notifications right now."
            }
            val formatted = formatNotificationsForContext(notifications)
            maybeDismissViewedNotifications(
                notifications, 
                clearAfterView,
                VerdurePreferences.DismissalContext.CHAT
            )
            return formatted
        }

        // Otherwise, use LLM to analyze (original behavior)
        val userQuery = params["query"] as? String ?: "What's important?"

        // Get priority-filtered notifications from Room (last 24h)
        val notifications = getPriorityNotifications()

        if (notifications.isEmpty()) {
            return "You have no priority notifications right now."
        }

        // Format notifications for LLM analysis
        val notificationList = formatNotificationsForContext(notifications)

        val prompt = """
You are a notification prioritization assistant. Analyze these notifications and categorize them:

Notifications:
$notificationList

User asked: "$userQuery"

Categorize into:
🔴 URGENT (needs immediate attention)
🟡 IMPORTANT (check soon)
⚪ CAN WAIT (low priority)
🚫 IGNORE (spam/unimportant)

Provide a brief, clear summary.
        """.trimIndent()

        val response = llmEngine.generateContent(prompt)
        maybeDismissViewedNotifications(
            notifications, 
            clearAfterView,
            VerdurePreferences.DismissalContext.CHAT
        )
        return response
    }

    private fun maybeDismissViewedNotifications(
        notifications: List<StoredNotification>,
        clearAfterView: Boolean,
        dismissalContext: VerdurePreferences.DismissalContext
    ) {
        if (!clearAfterView || notifications.isEmpty()) {
            return
        }

        // Filter notifications based on settings (respects toggle + calendar exclusion)
        val dismissibleNotifications = notifications.filter { notification ->
            preferences.shouldDismissNotification(notification, dismissalContext)
        }

        if (dismissibleNotifications.isEmpty()) {
            Log.d(TAG, "No notifications eligible for dismissal (toggle/settings)")
            return
        }

        // Convert to NotificationData for compatibility with dismissal API
        val notificationDataList = dismissibleNotifications.map { it.toNotificationData() }
        
        val dismissedCount = VerdureNotificationListener.dismissViewedNotifications(notificationDataList)
        if (dismissedCount > 0) {
            Log.d(TAG, "Dismissed $dismissedCount viewed notifications after processing (context: $dismissalContext)")
        }
    }

    /**
     * Format notifications for context/analysis
     */
    private fun formatNotificationsForContext(notifications: List<StoredNotification>): String {
        return notifications.mapIndexed { index, notif ->
            // Sanitize text to avoid special characters that might crash LLM
            val title = sanitizeText(notif.title ?: "(no title)")
            val text = sanitizeText(notif.text ?: "(no text)")
            val timeDesc = notif.getFormattedTime()
            val dismissedTag = if (notif.isDismissed) " [dismissed]" else ""
            "${index + 1}. ${notif.appName}: $title - $text ($timeDesc)$dismissedTag"
        }.joinToString("\n")
    }

    /**
     * Sanitize text to remove potentially problematic characters
     */
    private fun sanitizeText(text: String): String {
        return text
            .replace("\u0000", "") // Remove null bytes
            .replace(Regex("[\\p{C}&&[^\\n\\r\\t]]"), "") // Remove other control characters except newlines/tabs
            .take(200) // Limit length per field
    }

    /**
     * Get priority notifications from Room database (last 24 hours).
     *
     * Flow:
     * 1. Query Room database for notifications (includes dismissed ones)
     * 2. Filter by priority score (>= 2)
     * 3. Return top N by score (already calculated and stored)
     *
     * This enables querying dismissed notifications - they're gone from
     * the system tray but still searchable in Room for 24h.
     */
    private suspend fun getPriorityNotifications(limit: Int = 8): List<StoredNotification> {
        return repository.getPriorityNotifications(minScore = 2, limit = limit)
    }

    /**
     * Search notifications by keywords from Room database (last 24h).
     *
     * Flow:
     * 1. Query Room with SQL LIKE for fast text search
     * 2. Results sorted by priority score (high score = more relevant)
     * 3. Return matching notifications (up to limit)
     *
     * This enables "find notifications about X" queries even for dismissed items.
     */
    private suspend fun searchNotifications(
        keywords: List<String>, 
        limit: Int = 10
    ): List<StoredNotification> {
        if (keywords.isEmpty()) {
            // No keywords? Just return recent notifications
            return repository.getRecentNotifications(limit)
        }

        // Search each keyword and combine results (deduped by systemKey)
        val allMatches = keywords.flatMap { keyword ->
            repository.searchNotifications(keyword, limit)
        }.distinctBy { it.systemKey }
        
        // Sort by priority score and return top N
        return allMatches
            .sortedByDescending { it.priorityScore }
            .take(limit)
    }
}
