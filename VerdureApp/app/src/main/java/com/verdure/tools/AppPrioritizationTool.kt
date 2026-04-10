package com.verdure.tools

import com.cactus.models.ToolParameter
import android.util.Log
import com.verdure.data.InstalledAppsManager
import com.verdure.data.UserContextManager

/**
 * Tool for managing app prioritization
 *
 * Handles:
 * - Moving apps to top of priority order (from chat: "prioritize Discord")
 * - Validating app names against installed apps
 * - Syncing chat-based changes with drag-and-drop UI
 *
 * Architecture:
 * - Both chat (VerdureAI) and UI (AppPriorityActivity) use same data source (UserContext)
 * - This tool provides the logic layer between chat intent and data updates
 */
class AppPrioritizationTool(
    private val contextManager: UserContextManager,
    private val appsManager: InstalledAppsManager
) : Tool {

    companion object {
        private const val TAG = "AppPrioritizationTool"
    }

    override val name: String = "app_prioritization"
    override val description: String = "Manages user's app priority ordering for notification scoring"
    override val argumentSchema: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            type = "string",
            description = "Action: prioritize_app, deprioritize_app, or get_current_order",
            required = true
        ),
        "app_name" to ToolParameter(
            type = "string",
            description = "Human-readable app name for prioritize/deprioritize actions",
            required = false
        )
    )

    override suspend fun execute(params: Map<String, Any>): String {
        val action = params["action"] as? String ?: return "No action specified"

        return when (action) {
            "prioritize_app" -> {
                val appName = params["app_name"] as? String
                    ?: return "No app name specified"
                prioritizeApp(appName)
            }
            "deprioritize_app" -> {
                val appName = params["app_name"] as? String
                    ?: return "No app name specified"
                deprioritizeApp(appName)
            }
            "get_current_order" -> {
                getCurrentOrder()
            }
            else -> "Unknown action: $action"
        }
    }

    /**
     * Move an app to the top of the priority order
     * Used when user says "prioritize Discord"
     */
    private suspend fun prioritizeApp(appName: String): String {
        // Find the app's package name
        val packageName = appsManager.findAppByName(appName)
            ?: return "I couldn't find an app named \"$appName\" on your device. Make sure it's installed."

        val actualAppName = appsManager.getAppName(packageName) ?: appName

        // Move to position 0 (highest priority)
        contextManager.moveAppPriority(packageName, toIndex = 0)

        Log.d(TAG, "Moved $actualAppName to top priority (package: $packageName)")
        return "✓ $actualAppName is now your top priority app"
    }

    /**
     * Move an app to the bottom of the priority order
     * Used when user says "deprioritize Instagram"
     */
    private suspend fun deprioritizeApp(appName: String): String {
        val packageName = appsManager.findAppByName(appName)
            ?: return "I couldn't find an app named \"$appName\" on your device."

        val actualAppName = appsManager.getAppName(packageName) ?: appName
        val context = contextManager.loadContext()
        val currentOrder = context.priorityRules.customAppOrder

        // Move to last position
        val lastIndex = maxOf(currentOrder.size - 1, 0)
        contextManager.moveAppPriority(packageName, toIndex = lastIndex)

        Log.d(TAG, "Moved $actualAppName to lowest priority (package: $packageName)")
        return "✓ $actualAppName deprioritized"
    }

    /**
     * Get current app priority ordering
     */
    private suspend fun getCurrentOrder(): String {
        val context = contextManager.loadContext()
        val customOrder = context.priorityRules.customAppOrder

        if (customOrder.isEmpty()) {
            return "No custom app order set yet. Use the settings menu to drag apps into order."
        }

        // Convert package names to readable app names
        val readableOrder = customOrder.mapNotNull { packageName ->
            appsManager.getAppName(packageName)
        }

        if (readableOrder.isEmpty()) {
            return "Your custom app order is empty."
        }

        return buildString {
            appendLine("Your app priority order:")
            readableOrder.take(10).forEachIndexed { index, appName ->
                appendLine("${index + 1}. $appName")
            }
            if (readableOrder.size > 10) {
                appendLine("... and ${readableOrder.size - 10} more")
            }
        }
    }
}
