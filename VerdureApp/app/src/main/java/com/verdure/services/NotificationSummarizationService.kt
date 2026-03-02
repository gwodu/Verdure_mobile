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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Background service that monitors notifications and automatically triggers LLM summarization
 * for CRITICAL priority notifications (score >= 15).
 *
 * Features:
 * - Monitors VerdureNotificationListener.notifications StateFlow
 * - Filters for CRITICAL priority (score >= 15)
 * - Triggers LLM immediately when critical notification arrives
 * - Stores summary in SharedPreferences for widget access
 * - Falls back to truncated raw notification if LLM fails
 */
class NotificationSummarizationService : Service() {
    
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private lateinit var llmEngine: CactusLLMEngine
    private lateinit var notificationFilter: NotificationFilter
    private lateinit var summaryStore: NotificationSummaryStore
    private lateinit var preferences: VerdurePreferences
    
    private var processingJob: Job? = null
    
    companion object {
        private const val TAG = "NotifSummarizationSvc"
        private const val CRITICAL_THRESHOLD = 15  // Score threshold for immediate LLM processing
        private const val MAX_NOTIFICATIONS_TO_SUMMARIZE = 5  // Top N critical notifications
        private const val RAW_NOTIFICATION_MAX_LENGTH = 100  // Truncation length for fallback
        private const val DEBOUNCE_DELAY_MS = 3000L  // Wait 3 seconds to batch multiple notifications
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        try {
            CactusContextInitializer.initialize(this)

            // Initialize LLM engine
            scope.launch {
                Log.d(TAG, "Initializing LLM engine...")
                // Use Singleton instance
                llmEngine = CactusLLMEngine.getInstance(applicationContext)
                val success = llmEngine.initialize()
                
                if (success) {
                    Log.d(TAG, "LLM engine initialized successfully")
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
     * Process notifications: filter for CRITICAL priority and trigger LLM if needed.
     */
    private suspend fun processNotifications(notifications: List<NotificationData>) {
        if (notifications.isEmpty()) {
            return
        }
        
        // Score and filter for CRITICAL priority (score >= 15)
        val criticalNotifications = notifications
            .map { it to notificationFilter.scoreNotification(it) }
            .filter { (_, score) -> score >= CRITICAL_THRESHOLD }
            .sortedByDescending { (_, score) -> score }
            .take(MAX_NOTIFICATIONS_TO_SUMMARIZE)
            .map { (notif, score) -> 
                Log.d(TAG, "Critical notification (score $score): ${notif.appName} - ${notif.title}")
                notif
            }
        
        if (criticalNotifications.isEmpty()) {
            Log.d(TAG, "No critical notifications to summarize")
            return
        }
        
        // Check if we've already summarized these notifications
        val newNotifications = criticalNotifications.filter { notif ->
            !summaryStore.hasBeenSummarized(notif.id)
        }
        
        if (newNotifications.isEmpty()) {
            Log.d(TAG, "All critical notifications already summarized")
            return
        }
        
        Log.d(TAG, "Found ${newNotifications.size} new critical notifications to summarize")
        
        // Trigger LLM summarization
        summarizeNotifications(newNotifications)
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
You are V, a personal AI assistant. Summarize these critical notifications into ultra-concise, actionable points for a home screen widget.

Critical notifications:
$notifList

Rules:
- Maximum 3 bullet points total (not per notification)
- Each point: 8 words or less
- Focus on ACTION needed, not description
- Use present tense, imperative mood
- Omit app names (user knows context)
- Prioritize time-sensitive items first

Examples:
Input: "Gmail: Interview tomorrow at 9am - Please confirm availability"
Output: • Confirm interview tomorrow 9am

Input: "Slack: Meeting in 15 minutes - Join Zoom link"
Output: • Join meeting in 15 min

Input: "Chase Bank: Security Alert - Verify $500 transaction"
Output: • Verify $500 bank transaction

Now summarize:
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
