package com.verdure.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.verdure.R
import com.verdure.data.NotificationSummaryStore
import com.verdure.ui.MainActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Verdure Widget Provider
 * 
 * Displays LLM-summarized top priority notifications on the home screen.
 * Updates automatically when new notifications arrive via NotificationSummarizationService.
 */
class VerdureWidgetProvider : AppWidgetProvider() {
    
    companion object {
        private const val TAG = "VerdureWidget"
        private const val ACTION_REFRESH = "com.verdure.widget.ACTION_REFRESH"
        
        /**
         * Manually trigger widget update from external components
         */
        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val widgetIds = appWidgetManager.getAppWidgetIds(
                ComponentName(context, VerdureWidgetProvider::class.java)
            )
            
            if (widgetIds.isNotEmpty()) {
                val provider = VerdureWidgetProvider()
                provider.onUpdate(context, appWidgetManager, widgetIds)
                Log.d(TAG, "Manually updated ${widgetIds.size} widget(s)")
            }
        }
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        
        if (intent.action == ACTION_REFRESH) {
            Log.d(TAG, "Refresh action received")
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val widgetIds = appWidgetManager.getAppWidgetIds(
                ComponentName(context, VerdureWidgetProvider::class.java)
            )
            onUpdate(context, appWidgetManager, widgetIds)
        }
    }
    
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        Log.d(TAG, "onUpdate called for ${appWidgetIds.size} widget(s)")
        
        // Update each widget instance
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }
    
    override fun onEnabled(context: Context) {
        Log.d(TAG, "First widget added")
        
        // Ensure NotificationSummarizationService is running
        try {
            val serviceIntent = Intent(context, com.verdure.services.NotificationSummarizationService::class.java)
            context.startService(serviceIntent)
            Log.d(TAG, "Started NotificationSummarizationService")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start NotificationSummarizationService", e)
        }
        
        // Trigger immediate update when widget is first added
        updateAllWidgets(context)
    }
    
    override fun onDisabled(context: Context) {
        Log.d(TAG, "Last widget removed")
    }
    
    /**
     * Update a single widget instance
     */
    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val summaryStore = NotificationSummaryStore(context)
        val summary = summaryStore.getLatestSummary()
        
        val views = RemoteViews(context.packageName, R.layout.widget_layout)
        
        if (summary != null) {
            // Active state: Display summary
            views.setTextViewText(R.id.widget_summary, summary.text)
            
            // Format timestamp
            val timeStr = formatTimestamp(summary.timestamp)
            views.setTextViewText(R.id.widget_timestamp, "Updated $timeStr")
            
            Log.d(TAG, "Widget updated with summary (${summary.notificationIds.size} notifications)")
        } else {
            // Empty state: No notifications available
            views.setTextViewText(R.id.widget_summary, "No notifications yet\n\nCheck back soon")
            views.setTextViewText(R.id.widget_timestamp, "")
            
            Log.d(TAG, "Widget updated with empty state")
        }
        
        // Add click handling to open MainActivity
        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent)
        
        // Add refresh button click handling
        val refreshIntent = Intent(context, VerdureWidgetProvider::class.java).apply {
            action = ACTION_REFRESH
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
        }
        val refreshPendingIntent = PendingIntent.getBroadcast(
            context,
            appWidgetId,
            refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent)
        
        // Update the widget
        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
    
    /**
     * Format timestamp to human-readable string
     */
    private fun formatTimestamp(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        
        return when {
            diff < 60_000 -> "just now"
            diff < 3600_000 -> "${diff / 60_000}m ago"
            diff < 86400_000 -> {
                val dateFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
                dateFormat.format(Date(timestamp))
            }
            else -> {
                val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                dateFormat.format(Date(timestamp))
            }
        }
    }
}
