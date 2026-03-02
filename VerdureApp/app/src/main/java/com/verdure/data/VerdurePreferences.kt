package com.verdure.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Centralized settings management for Verdure.
 * Stores user preferences in SharedPreferences.
 */
class VerdurePreferences(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "verdure_settings",
        Context.MODE_PRIVATE
    )
    
    companion object {
        private const val KEY_AUTO_DISMISS_ENABLED = "auto_dismiss_enabled"
        private const val KEY_EXCLUDE_CALENDAR_FROM_DISMISS = "exclude_calendar_from_dismiss"
        private const val KEY_DISMISS_AFTER_CHAT = "dismiss_after_chat"
        private const val KEY_DISMISS_AFTER_BACKGROUND = "dismiss_after_background"
        
        // Defaults
        private const val DEFAULT_AUTO_DISMISS = true
        private const val DEFAULT_EXCLUDE_CALENDAR = true
        private const val DEFAULT_DISMISS_AFTER_CHAT = true
        private const val DEFAULT_DISMISS_AFTER_BACKGROUND = true
        
        @Volatile
        private var INSTANCE: VerdurePreferences? = null
        
        fun getInstance(context: Context): VerdurePreferences {
            return INSTANCE ?: synchronized(this) {
                val instance = VerdurePreferences(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
    
    /**
     * Master toggle for auto-dismissal feature.
     * When OFF, notifications are never dismissed (only stored in Room).
     */
    var autoDismissEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DISMISS_ENABLED, DEFAULT_AUTO_DISMISS)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_DISMISS_ENABLED, value).apply()
        }
    
    /**
     * Whether to exclude calendar invites from auto-dismissal.
     * Calendar events (category = EVENT) are never dismissed when true.
     */
    var excludeCalendarFromDismiss: Boolean
        get() = prefs.getBoolean(KEY_EXCLUDE_CALENDAR_FROM_DISMISS, DEFAULT_EXCLUDE_CALENDAR)
        set(value) {
            prefs.edit().putBoolean(KEY_EXCLUDE_CALENDAR_FROM_DISMISS, value).apply()
        }
    
    /**
     * Whether to dismiss notifications after chat interaction.
     * If false, chat queries don't trigger dismissal (but still store in Room).
     */
    var dismissAfterChat: Boolean
        get() = prefs.getBoolean(KEY_DISMISS_AFTER_CHAT, DEFAULT_DISMISS_AFTER_CHAT)
        set(value) {
            prefs.edit().putBoolean(KEY_DISMISS_AFTER_CHAT, value).apply()
        }
    
    /**
     * Whether to dismiss notifications after background processing.
     * If false, background summarization doesn't trigger dismissal.
     */
    var dismissAfterBackground: Boolean
        get() = prefs.getBoolean(KEY_DISMISS_AFTER_BACKGROUND, DEFAULT_DISMISS_AFTER_BACKGROUND)
        set(value) {
            prefs.edit().putBoolean(KEY_DISMISS_AFTER_BACKGROUND, value).apply()
        }
    
    /**
     * Check if a notification should be dismissed based on settings.
     * 
     * @param notification The notification to check
     * @param context Where dismissal is happening (chat or background)
     * @return true if should dismiss, false otherwise
     */
    fun shouldDismissNotification(
        notification: StoredNotification,
        context: DismissalContext
    ): Boolean {
        // Master toggle off = never dismiss
        if (!autoDismissEnabled) {
            return false
        }
        
        // Check context-specific toggle
        val contextEnabled = when (context) {
            DismissalContext.CHAT -> dismissAfterChat
            DismissalContext.BACKGROUND -> dismissAfterBackground
        }
        
        if (!contextEnabled) {
            return false
        }
        
        // Exclude calendar invites if enabled
        // Note: Notification.CATEGORY_EVENT = "event" (Android constant)
        if (excludeCalendarFromDismiss && notification.category == "event") {
            return false
        }
        
        // Only dismiss clearable notifications
        if (!notification.isClearable) {
            return false
        }
        
        return true
    }
    
    /**
     * Context where dismissal is being triggered.
     */
    enum class DismissalContext {
        CHAT,        // User asked about notifications in chat
        BACKGROUND   // Background service processed critical notifications
    }
}
