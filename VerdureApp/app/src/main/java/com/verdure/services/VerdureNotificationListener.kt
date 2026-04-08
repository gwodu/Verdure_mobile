package com.verdure.services

import android.app.Notification
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.verdure.data.NotificationData
import com.verdure.data.NotificationFilter
import com.verdure.data.NotificationRepository
import com.verdure.data.NotificationSummaryStore
import com.verdure.data.UserContextManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * NotificationListenerService that captures all notifications posted to the device.
 * Provides access to notifications via a StateFlow for reactive UI updates.
 * 
 * Now persists ALL notifications to Room database for 24-hour retention.
 */
class VerdureNotificationListener : NotificationListenerService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var notificationRepository: NotificationRepository
    private lateinit var notificationFilter: NotificationFilter
    private lateinit var ingestionPipeline: IngestionPipeline
    
    companion object {
        private const val TAG = "VerdureNotifListener"

        // StateFlow to hold the list of notifications (for backwards compatibility)
        private val _notifications = MutableStateFlow<List<NotificationData>>(emptyList())
        val notifications: StateFlow<List<NotificationData>> = _notifications.asStateFlow()

        // Track notification count
        private var nextId = 0

        // Active listener instance required to dismiss system notifications.
        @Volatile
        private var listenerInstance: VerdureNotificationListener? = null

        /**
         * Dismiss notifications that Verdure already processed.
         * Only clearable notifications are removed; ongoing/system items are skipped.
         * Marks notifications as dismissed in Room database before clearing from tray.
         */
        fun dismissViewedNotifications(notifications: List<NotificationData>): Int {
            val listener = listenerInstance
            if (listener == null) {
                Log.w(TAG, "Cannot dismiss notifications: listener not connected")
                return 0
            }

            val dismissibleNotifications = notifications.filter {
                it.isClearable && it.systemKey.isNotBlank()
            }

            if (dismissibleNotifications.isEmpty()) {
                return 0
            }

            var dismissedCount = 0
            val dismissedKeys = mutableListOf<String>()
            
            dismissibleNotifications.forEach { notification ->
                try {
                    // Cancel from system tray
                    listener.cancelNotification(notification.systemKey)
                    removeFromCacheBySystemKey(notification.systemKey)
                    dismissedKeys.add(notification.systemKey)
                    dismissedCount++
                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "Failed to dismiss ${notification.appName} (${notification.systemKey})",
                        e
                    )
                }
            }

            // Mark as dismissed in Room (persists for 24h even though cleared from tray)
            if (dismissedKeys.isNotEmpty() && listener::notificationRepository.isInitialized) {
                listener.serviceScope.launch {
                    listener.notificationRepository.markMultipleDismissed(dismissedKeys)
                }
            }

            Log.d(
                TAG,
                "Dismissed $dismissedCount/${dismissibleNotifications.size} viewed notifications"
            )
            return dismissedCount
        }

        private fun removeFromCacheBySystemKey(systemKey: String) {
            _notifications.value = _notifications.value.filterNot { it.systemKey == systemKey }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        listenerInstance = this
        Log.d(TAG, "Notification Listener Connected")

        // Initialize repository and filter
        notificationRepository = NotificationRepository.getInstance(applicationContext)
        val contextManager = UserContextManager.getInstance(applicationContext)
        val userContext = runBlocking {
            contextManager.loadContext()
        }
        notificationFilter = NotificationFilter(userContext)
        ingestionPipeline = IngestionPipeline.getInstance(applicationContext)

        // Clean up old notifications on startup
        serviceScope.launch {
            notificationRepository.cleanupOldNotifications()
        }

        // Load existing notifications when service connects
        loadExistingNotifications()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (listenerInstance === this) {
            listenerInstance = null
        }
        Log.d(TAG, "Notification Listener Disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)

        val notificationData = extractNotificationData(sbn)
        if (notificationData != null) {
            Log.d(TAG, "New notification: ${notificationData.appName} - ${notificationData.title}")

            // Add to the beginning of the list (most recent first)
            val currentList = _notifications.value.toMutableList()
            currentList.removeAll { it.systemKey == notificationData.systemKey }
            currentList.add(0, notificationData)
            _notifications.value = currentList
            
            // Persist to Room database with priority score
            if (::notificationRepository.isInitialized && ::notificationFilter.isInitialized) {
                serviceScope.launch {
                    val score = notificationFilter.scoreNotification(notificationData)
                    val stored = notificationRepository.storeNotificationAndGet(notificationData, score)
                    if (stored != null && ::ingestionPipeline.isInitialized) {
                        ingestionPipeline.enqueue(stored)
                    } else {
                        Log.w(TAG, "Skipping ingestion; stored row unavailable")
                    }
                }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
        
        val packageName = sbn.packageName
        val postTime = sbn.postTime
        
        // Find the notification ID that matches this removal
        var removedNotifId: Int? = null
        
        // Get current list
        val currentList = _notifications.value.toMutableList()
        val iterator = currentList.iterator()
        while (iterator.hasNext()) {
            val notif = iterator.next()
            if (notif.packageName == packageName && notif.timestamp == postTime) {
                removedNotifId = notif.id
                iterator.remove()
            }
        }
        
        if (removedNotifId != null) {
            Log.d(TAG, "Notification removed from state: $packageName")
            
            // Clear summary BEFORE updating StateFlow to avoid race condition
            // This ensures NotificationSummarizationService sees cleared tracking when it processes
            val summaryCleared = checkAndClearStaleSummary(removedNotifId)
            
            // Now update StateFlow (triggers service re-evaluation)
            _notifications.value = currentList
            Log.d(TAG, "StateFlow updated: ${currentList.size} notifications remaining")
            
            // If summary was cleared but no StateFlow change triggers service,
            // manually update widget to show cleared state
            if (summaryCleared && currentList.isEmpty()) {
                com.verdure.widget.VerdureWidgetProvider.updateAllWidgets(applicationContext)
            }
        } else {
            Log.d(TAG, "Notification removed (not in state): $packageName")
        }
    }
    
    /**
     * Check if the removed notification was in the widget summary and clear if needed.
     * Returns true if summary was cleared.
     */
    private fun checkAndClearStaleSummary(removedNotifId: Int): Boolean {
        return try {
            val summaryStore = NotificationSummaryStore(applicationContext)
            val summary = summaryStore.getLatestSummary()
            
            if (summary != null && summary.notificationIds.contains(removedNotifId)) {
                Log.d(TAG, "Removed notification was in widget summary - clearing for re-evaluation")
                summaryStore.clearSummaries()
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check/clear summary after notification removal", e)
            false
        }
    }

    /**
     * Load existing notifications when the service first connects.
     */
    private fun loadExistingNotifications() {
        try {
            val activeNotifications = activeNotifications ?: return
            val notificationList = mutableListOf<NotificationData>()

            for (sbn in activeNotifications) {
                val data = extractNotificationData(sbn)
                if (data != null) {
                    notificationList.add(data)
                }
            }

            // Sort by timestamp, most recent first
            notificationList.sortByDescending { it.timestamp }
            _notifications.value = notificationList

            Log.d(TAG, "Loaded ${notificationList.size} existing notifications")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading existing notifications", e)
        }
    }

    /**
     * Extract relevant data from a StatusBarNotification.
     * Handles multiple notification styles (BigText, Inbox, Messaging)
     * to capture full content from apps like WhatsApp, Telegram, etc.
     */
    private fun extractNotificationData(sbn: StatusBarNotification): NotificationData? {
        try {
            val notification = sbn.notification ?: return null
            val extras = notification.extras ?: return null

            // Get app name from package manager
            val appName = try {
                val pm = packageManager
                val appInfo = pm.getApplicationInfo(sbn.packageName, 0)
                pm.getApplicationLabel(appInfo).toString()
            } catch (e: PackageManager.NameNotFoundException) {
                sbn.packageName
            }

            // Extract title and text with fallbacks for rich notification styles
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()

            val text = extractBestText(extras)

            // Skip if both title and text are null/empty
            if (title.isNullOrBlank() && text.isNullOrBlank()) {
                return null
            }

            // Log contentIntent availability for debugging
            val contentIntent = notification.contentIntent
            if (contentIntent == null) {
                Log.d(TAG, "No contentIntent for notification from $appName")
            } else {
                Log.d(TAG, "ContentIntent available for notification from $appName")
            }

            // Extract metadata for enhanced scoring
            val hasActions = notification.actions?.isNotEmpty() == true
            val hasImage = extras.containsKey(Notification.EXTRA_PICTURE) ||
                          notification.getLargeIcon() != null
            val isOngoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
            
            // Get notification importance (replaces deprecated priority field)
            val importance = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val notificationManager = getSystemService(NotificationManager::class.java)
                notification.channelId?.let { channelId ->
                    notificationManager?.getNotificationChannel(channelId)?.importance
                } ?: NotificationManager.IMPORTANCE_DEFAULT
            } else {
                @Suppress("DEPRECATION")
                notification.priority
            }

            return NotificationData(
                id = nextId++,
                systemKey = sbn.key,
                packageName = sbn.packageName,
                appName = appName,
                title = title,
                text = text,
                timestamp = sbn.postTime,
                isClearable = sbn.isClearable,
                category = notification.category,
                priority = importance,
                contentIntent = contentIntent,

                // Metadata for enhanced scoring
                hasActions = hasActions,
                hasImage = hasImage,
                isOngoing = isOngoing
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting notification data", e)
            return null
        }
    }

    /**
     * Extract the best available text from notification extras.
     * WhatsApp, Telegram, and other messaging apps use different styles:
     * - EXTRA_BIG_TEXT: BigTextStyle (expanded single message)
     * - EXTRA_TEXT_LINES: InboxStyle (multiple messages summary)
     * - EXTRA_TEXT: Standard short text
     */
    private fun extractBestText(extras: android.os.Bundle): String? {
        // Priority 1: BigTextStyle - contains the full expanded text
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        if (!bigText.isNullOrBlank()) {
            return bigText
        }

        // Priority 2: InboxStyle text lines - multiple messages grouped together
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (textLines != null && textLines.isNotEmpty()) {
            return textLines.mapNotNull { it?.toString() }
                .filter { it.isNotBlank() }
                .joinToString(" | ")
        }

        // Priority 3: Standard text field
        return extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    }
}
