package com.verdure.services

import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.cactus.CactusContextInitializer
import com.verdure.core.CactusLLMEngine
import com.verdure.data.NotificationData
import com.verdure.data.NotificationFilter
import com.verdure.data.NotificationSummaryStore
import com.verdure.data.StoredNotification
import com.verdure.data.UserContextManager
import com.verdure.data.VerdurePreferences
import com.verdure.tools.IncentiveTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Background service that monitors notifications and automatically triggers LLM summarization
 * for the top priority notifications.
 *
 * Features:
 * - Monitors VerdureNotificationListener.notifications StateFlow
 * - Always shows top N highest-scoring notifications (even if scores are low)
 * - Triggers LLM to summarize top priorities for widget display
 * - Stores summary in SharedPreferences for widget access
 * - Falls back to truncated raw notification if LLM fails
 */
class NotificationSummarizationService : Service() {
    
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private lateinit var llmEngine: CactusLLMEngine
    private lateinit var notificationFilter: NotificationFilter
    private lateinit var summaryStore: NotificationSummaryStore
    private lateinit var preferences: VerdurePreferences
    private lateinit var incentiveTool: IncentiveTool
    
    private var processingJob: Job? = null
    
    companion object {
        private const val TAG = "NotifSummarizationSvc"
        private const val TOP_PRIORITIES_COUNT = 5  // Show top N highest-scoring notifications
        private const val MIN_SCORE_TO_SHOW = -5  // Include everything (even negative scores)
        private const val RAW_NOTIFICATION_MAX_LENGTH = 100  // Truncation length for fallback
        private const val DEBOUNCE_DELAY_MS = 1000L  // Wait 1 second to batch multiple notifications (reduced for faster updates)
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        try {
            CactusContextInitializer.initialize(this)

            // Initialize LLM engine and tools
            scope.launch {
                Log.d(TAG, "Initializing LLM engine...")
                // Use Singleton instance
                llmEngine = CactusLLMEngine.getInstance(applicationContext)
                val success = llmEngine.initialize()
                
                if (success) {
                    Log.d(TAG, "LLM engine initialized successfully")
                    
                    // Initialize incentive tool for notification matching
                    incentiveTool = IncentiveTool(applicationContext, llmEngine)
                    Log.d(TAG, "Incentive tool initialized")
                } else {
                    Log.e(TAG, "Failed to initialize LLM engine")
                }
            }
            
            // Initialize filter with user context
            val contextManager = UserContextManager.getInstance(applicationContext)
            // Load context synchronously (blocking is OK in onCreate)
            val userContext = runBlocking {
                contextManager.loadContext()
            }
            notificationFilter = NotificationFilter(userContext)
            
            // Initialize summary store
            summaryStore = NotificationSummaryStore(applicationContext)
            
            // Initialize preferences
            preferences = VerdurePreferences.getInstance(applicationContext)
            
            // Start monitoring notifications
            startMonitoring()
            
            Log.d(TAG, "Service initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize service", e)
        }
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null  // Not a bound service
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")
        return START_STICKY  // Restart if killed by system
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
    }
    
    /**
     * Start monitoring notifications from VerdureNotificationListener.
     * Uses debouncing to batch multiple rapid notifications together.
     */
    private fun startMonitoring() {
        scope.launch {
            VerdureNotificationListener.notifications.collect { allNotifications ->
                // Cancel any pending processing job
                processingJob?.cancel()
                
                // Schedule new processing with debounce delay
                processingJob = launch {
                    delay(DEBOUNCE_DELAY_MS)
                    processNotifications(allNotifications)
                }
            }
        }
    }
    
    /**
     * Process notifications: show top N highest-scoring priorities and trigger LLM.
     * Also checks for incentive matches (two-pass processing).
     */
    private suspend fun processNotifications(notifications: List<NotificationData>) {
        if (notifications.isEmpty()) {
            Log.d(TAG, "No notifications available")
            summaryStore.clearSummaries()
            updateWidget()
            return
        }
        
        // PASS 1: Score all notifications and take top N highest-scoring ones
        val topPriorityNotifications = notifications
            .map { it to notificationFilter.scoreNotification(it) }
            .sortedByDescending { (_, score) -> score }
            .take(TOP_PRIORITIES_COUNT)
            .map { (notif, score) -> 
                Log.d(TAG, "Top priority notification (score $score): ${notif.appName} - ${notif.title}")
                notif
            }
        
        // Check if we've already summarized these exact notifications
        val newNotifications = topPriorityNotifications.filter { notif ->
            !summaryStore.hasBeenSummarized(notif.id)
        }
        
        if (newNotifications.isNotEmpty()) {
            Log.d(TAG, "Found ${newNotifications.size} new top priority notifications to summarize")
            // Trigger LLM summarization for widget
            summarizeNotifications(newNotifications)
        } else {
            Log.d(TAG, "All top priority notifications already summarized")
        }
        
        // PASS 2: Check for incentive matches (process ALL notifications, not just top priorities)
        processIncentiveMatches(notifications)
    }
    
    /**
     * Process notifications for incentive matches (Pass 2)
     * Runs independently of urgency scoring
     */
    private suspend fun processIncentiveMatches(notifications: List<NotificationData>) {
        if (!::incentiveTool.isInitialized) {
            Log.d(TAG, "Incentive tool not initialized, skipping incentive matching")
            return
        }
        
        notifications.forEach { notification ->
            try {
                val result = incentiveTool.execute(
                    mapOf(
                        "action" to "match_notification",
                        "notification" to notification
                    )
                )
                
                // Result is JSON string like {"matched": true/false, "incentive": "...", "summary": "..."}
                if (result.contains("\"matched\": true")) {
                    Log.d(TAG, "Notification matched to incentive: ${notification.appName}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check incentive match for notification ${notification.id}", e)
            }
        }
    }
    
    /**
     * Summarize notifications using LLM, with fallback to raw truncated text.
     */
    private suspend fun summarizeNotifications(notifications: List<NotificationData>) {
        val prompt = buildSummarizationPrompt(notifications)
        
        try {
            // Check if LLM is initialized
            if (!::llmEngine.isInitialized) {
                Log.w(TAG, "LLM not initialized yet - using raw fallback")
                generateRawFallbackSummary(notifications)
                return
            }
            
            Log.d(TAG, "Calling LLM to summarize ${notifications.size} notifications...")
            val summary = llmEngine.generateContent(prompt)
            
            // Store summary
            summaryStore.saveSummary(
                notifications.map { it.id },
                summary,
                System.currentTimeMillis()
            )

            dismissProcessedNotifications(notifications)
            
            Log.d(TAG, "Successfully summarized ${notifications.size} critical notifications")
        } catch (e: Exception) {
            Log.e(TAG, "LLM failed to summarize notifications, using raw fallback", e)
            generateRawFallbackSummary(notifications)
        }
        
        // Trigger widget update
        updateWidget()
    }

    private fun dismissProcessedNotifications(notifications: List<NotificationData>) {
        // Check if auto-dismiss is enabled and background context is enabled
        if (!preferences.autoDismissEnabled || !preferences.dismissAfterBackground) {
            Log.d(TAG, "Auto-dismiss disabled for background context - keeping notifications")
            return
        }
        
        // Filter notifications that should be dismissed based on settings
        val dismissibleNotifications = notifications.filter { notif ->
            // Exclude calendar events if setting is enabled
            // Note: Notification.CATEGORY_EVENT = "event" (Android constant)
            val isCalendarEvent = notif.category == "event"
            val shouldExclude = isCalendarEvent && preferences.excludeCalendarFromDismiss
            
            !shouldExclude && notif.isClearable
        }
        
        if (dismissibleNotifications.isEmpty()) {
            Log.d(TAG, "No notifications eligible for dismissal after filtering")
            return
        }
        
        val dismissedCount = VerdureNotificationListener.dismissViewedNotifications(dismissibleNotifications)
        if (dismissedCount > 0) {
            Log.d(TAG, "Auto-dismissed $dismissedCount summarized notifications (respecting settings)")
        }
    }
    
    /**
     * Generate raw fallback summary when LLM is unavailable or fails.
     */
    private fun generateRawFallbackSummary(notifications: List<NotificationData>) {
        val rawSummary = notifications.joinToString("\n") { notif ->
            val text = notif.text ?: notif.title ?: ""
            val truncated = if (text.length > RAW_NOTIFICATION_MAX_LENGTH) {
                text.substring(0, RAW_NOTIFICATION_MAX_LENGTH) + "..."
            } else {
                text
            }
            "${notif.appName}: $truncated"
        }
        
        summaryStore.saveSummary(
            notifications.map { it.id },
            rawSummary,
            System.currentTimeMillis()
        )
        
        Log.d(TAG, "Saved raw fallback summary for ${notifications.size} notifications")
    }
    
    /**
     * Build LLM prompt for notification summarization.
     * Optimized for widget display: ultra-concise, actionable bullet points.
     */
    private fun buildSummarizationPrompt(notifications: List<NotificationData>): String {
        val notifList = notifications.joinToString("\n") { notif ->
            "${notif.appName}: ${notif.title} - ${notif.text}"
        }
        
        return """
Summarize these top priority notifications for a home screen widget. Be EXTREMELY concise.

Notifications:
$notifList

RULES (CRITICAL):
- Maximum 3 bullet points total
- Each point: 6-8 words maximum
- Start with • (bullet)
- Action-focused only
- NO app names
- NO greetings or extra text

EXAMPLES:
Input: "Gmail: Interview tomorrow 9am - Confirm"
Output: • Confirm interview tomorrow 9am

Input: "Slack: Meeting in 15 min"
Output: • Join meeting in 15 min

Input: "Bank: Verify $500 charge"
Output: • Verify $500 transaction

Output ONLY the bullet points:
        """.trimIndent()
    }
    
    /**
     * Trigger widget update by calling VerdureWidgetProvider.
     */
    private fun updateWidget() {
        try {
            com.verdure.widget.VerdureWidgetProvider.updateAllWidgets(applicationContext)
            Log.d(TAG, "Widget update triggered successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger widget update", e)
        }
    }
}
