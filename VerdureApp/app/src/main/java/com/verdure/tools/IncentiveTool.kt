package com.verdure.tools

import android.content.Context
import android.util.Log
import com.verdure.core.LLMEngine
import com.verdure.data.Incentive
import com.verdure.data.IncentiveMatch
import com.verdure.data.IncentiveStore
import com.verdure.data.NotificationData
import com.cactus.models.ToolParameter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Tool for managing incentives (goals/tasks) and matching notifications to them
 */
class IncentiveTool(
    private val context: Context,
    private val llmEngine: LLMEngine
) : Tool {
    
    override val name = "incentive_tracker"
    override val description = "Track goals/tasks and match notifications to them"
    override val argumentSchema: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            type = "string",
            description = "Action: create, match_notification, list, get_matches, delete, toggle",
            required = true
        ),
        "description" to ToolParameter(
            type = "string",
            description = "Natural language incentive description (for create action)"
        ),
        "incentive_id" to ToolParameter(
            type = "string",
            description = "Incentive identifier (for get_matches/delete/toggle actions)"
        )
    )
    
    private val incentiveStore = IncentiveStore(context)
    private val json = Json { ignoreUnknownKeys = true }
    
    companion object {
        private const val TAG = "IncentiveTool"
    }
    
    @Serializable
    private data class IncentiveCreationResponse(
        val name: String,
        val summary: String,
        val keywords: List<String>
    )
    
    @Serializable
    private data class IncentiveMatchResponse(
        val matched: Boolean,
        val incentive_id: String? = null,
        val action_summary: String? = null,
        val confidence: String? = null
    )
    
    override suspend fun execute(params: Map<String, Any>): String {
        val action = params["action"] as? String ?: return "Error: No action specified"
        
        return when (action) {
            "create" -> createIncentive(params)
            "match_notification" -> matchNotification(params)
            "list" -> listIncentives()
            "get_matches" -> getMatches(params)
            "delete" -> deleteIncentive(params)
            "toggle" -> toggleIncentive(params)
            else -> "Error: Unknown action: $action"
        }
    }
    
    /**
     * Create a new incentive from user's natural language description
     * Uses LLM to extract name, summary, and keywords
     */
    private suspend fun createIncentive(params: Map<String, Any>): String {
        val userDescription = params["description"] as? String 
            ?: return "Error: No description provided"
        
        Log.d(TAG, "Creating incentive from: $userDescription")
        
        // Use LLM to extract structured info
        val prompt = buildIncentiveCreationPrompt(userDescription)
        val llmResponse = llmEngine.generateContent(prompt)
        
        // Parse LLM response
        val parsed = parseIncentiveCreation(llmResponse)
        if (parsed == null) {
            return "I couldn't understand your incentive. Try describing it more clearly."
        }
        
        // Create and save incentive
        val incentive = incentiveStore.createIncentive(
            name = parsed.name,
            userDescription = userDescription,
            aiSummary = parsed.summary,
            keywords = parsed.keywords
        )
        
        Log.d(TAG, "Created incentive: ${incentive.name} with ${parsed.keywords.size} keywords")
        
        return """Created incentive: ${incentive.name}

I'll watch for notifications related to: ${parsed.keywords.joinToString(", ")}

You can view tracked notifications in the Incentives section."""
    }
    
    /**
     * Match a notification to active incentives
     * Returns JSON with match results
     */
    private suspend fun matchNotification(params: Map<String, Any>): String {
        @Suppress("UNCHECKED_CAST")
        val notificationData = params["notification"] as? NotificationData
            ?: return """{"matched": false}"""
        
        val activeIncentives = incentiveStore.getActiveIncentives()
        if (activeIncentives.isEmpty()) {
            return """{"matched": false}"""
        }
        
        // Use LLM to check if notification matches any incentive
        val prompt = buildNotificationMatchPrompt(notificationData, activeIncentives)
        val llmResponse = llmEngine.generateContent(prompt)
        
        // Parse match result
        val matchResult = parseMatchResult(llmResponse)
        if (matchResult == null || !matchResult.matched) {
            return """{"matched": false}"""
        }
        
        // Save match
        val match = IncentiveMatch(
            incentiveId = matchResult.incentive_id ?: return """{"matched": false}""",
            notificationId = notificationData.id,
            notificationAppName = notificationData.appName,
            notificationTitle = notificationData.title,
            notificationText = notificationData.text,
            notificationTimestamp = notificationData.timestamp,
            actionSummary = matchResult.action_summary ?: "New notification",
            matchedAt = System.currentTimeMillis()
        )
        
        incentiveStore.saveMatch(match)
        
        val incentiveName = incentiveStore.getIncentive(match.incentiveId)?.name ?: "Unknown"
        Log.d(TAG, "Matched notification to incentive: $incentiveName")
        
        return """{"matched": true, "incentive": "$incentiveName", "summary": "${match.actionSummary}"}"""
    }
    
    /**
     * List all incentives
     */
    private fun listIncentives(): String {
        val incentives = incentiveStore.getAllIncentives()
        if (incentives.isEmpty()) {
            return "You haven't created any incentives yet."
        }
        
        return buildString {
            appendLine("Your tracked goals:\n")
            incentives.forEach { incentive ->
                val status = if (incentive.isActive) "Active" else "Paused"
                val matchCount = incentiveStore.getMatches(incentive.id).size
                appendLine("• ${incentive.name} ($status)")
                appendLine("  $matchCount notification(s) tracked")
                appendLine()
            }
        }
    }
    
    /**
     * Get matches for a specific incentive
     */
    private fun getMatches(params: Map<String, Any>): String {
        val incentiveId = params["incentive_id"] as? String 
            ?: return "Error: No incentive ID provided"
        
        val incentive = incentiveStore.getIncentive(incentiveId)
            ?: return "Incentive not found"
        
        val matches = incentiveStore.getMatches(incentiveId)
        if (matches.isEmpty()) {
            return "No notifications tracked yet for: ${incentive.name}"
        }
        
        return buildString {
            appendLine("${incentive.name} - ${matches.size} notification(s):\n")
            matches.take(10).forEach { match ->
                appendLine("• ${match.actionSummary}")
                appendLine("  ${match.notificationAppName} - ${formatTime(match.notificationTimestamp)}")
                appendLine()
            }
        }
    }
    
    /**
     * Delete an incentive
     */
    private fun deleteIncentive(params: Map<String, Any>): String {
        val incentiveId = params["incentive_id"] as? String 
            ?: return "Error: No incentive ID provided"
        
        val incentive = incentiveStore.getIncentive(incentiveId)
        if (incentive != null) {
            incentiveStore.deleteIncentive(incentiveId)
            return "Deleted: ${incentive.name}"
        }
        return "Incentive not found"
    }
    
    /**
     * Toggle incentive active state
     */
    private fun toggleIncentive(params: Map<String, Any>): String {
        val incentiveId = params["incentive_id"] as? String 
            ?: return "Error: No incentive ID provided"
        
        val incentive = incentiveStore.getIncentive(incentiveId)
        if (incentive != null) {
            incentiveStore.toggleIncentiveActive(incentiveId)
            val newState = if (incentive.isActive) "Paused" else "Activated"
            return "$newState: ${incentive.name}"
        }
        return "Incentive not found"
    }
    
    // ----- Prompt Building -----
    
    /**
     * Build prompt to extract structured info from user's incentive description
     */
    private fun buildIncentiveCreationPrompt(userDescription: String): String {
        return """
You are V, a personal AI assistant. Extract structured information from this goal/task description.

User description: "$userDescription"

Extract:
1. Short name (2-5 words)
2. Detailed summary (what to track, why it matters)
3. Keywords to match (5-10 important words/phrases)

Respond with ONLY valid JSON:
{
  "name": "[short name]",
  "summary": "[detailed summary]",
  "keywords": ["keyword1", "keyword2", "keyword3"]
}

Examples:

Input: "I want to track graduate school applications. I need to know about deadlines, acceptances, rejections, recommendation letters, all emails from universities."
Output: {
  "name": "Graduate School Applications",
  "summary": "Track all communications about grad school applications including deadlines, decisions, and recommendation letters from universities",
  "keywords": ["graduate school", "grad school", "university", "application", "deadline", "acceptance", "rejection", "recommendation", "admission", "phd", "masters"]
}

Input: "Track my job search - interviews, offers, rejections, salary negotiations"
Output: {
  "name": "Job Search",
  "summary": "Monitor job search progress including interview invitations, offers, rejections, and salary discussions",
  "keywords": ["job", "interview", "offer", "position", "career", "employment", "salary", "hiring", "recruiter", "application"]
}

Now extract from the user's description:
        """.trimIndent()
    }
    
    /**
     * Build prompt to check if notification matches any incentive
     */
    private fun buildNotificationMatchPrompt(
        notification: NotificationData,
        incentives: List<Incentive>
    ): String {
        val incentivesList = incentives.joinToString("\n") { incentive ->
            "ID: ${incentive.id}\nName: ${incentive.name}\nSummary: ${incentive.aiSummary}\nKeywords: ${incentive.keywords.joinToString(", ")}"
        }
        
        return """
Check if this notification matches any of the user's tracked goals.

Notification:
App: ${notification.appName}
Title: ${notification.title ?: "N/A"}
Text: ${notification.text ?: "N/A"}

Tracked goals:
$incentivesList

Respond with ONLY valid JSON:
{
  "matched": true/false,
  "incentive_id": "[ID if matched, null otherwise]",
  "action_summary": "[Brief 4-6 word summary if matched]",
  "confidence": "high/medium/low"
}

Rules:
- Match if notification content clearly relates to the goal's keywords or summary
- Don't match generic notifications (social media likes, etc.)
- Action summary should be brief and specific (e.g., "Stanford application deadline Friday")

Examples:

Notification: "Gmail: Stanford PhD Program - Application Deadline Approaching"
Goals: [Graduate School Applications with keywords: grad school, university, application, deadline...]
Output: {
  "matched": true,
  "incentive_id": "abc123",
  "action_summary": "Stanford PhD deadline approaching",
  "confidence": "high"
}

Notification: "Instagram: New follower"
Goals: [Graduate School Applications...]
Output: {
  "matched": false
}

Now analyze:
        """.trimIndent()
    }
    
    // ----- Response Parsing -----
    
    private fun parseIncentiveCreation(llmResponse: String): IncentiveCreationResponse? {
        return try {
            val jsonStart = llmResponse.indexOf('{')
            val jsonEnd = llmResponse.lastIndexOf('}') + 1
            
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val jsonString = llmResponse.substring(jsonStart, jsonEnd)
                json.decodeFromString<IncentiveCreationResponse>(jsonString)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse incentive creation response", e)
            null
        }
    }
    
    private fun parseMatchResult(llmResponse: String): IncentiveMatchResponse? {
        return try {
            val jsonStart = llmResponse.indexOf('{')
            val jsonEnd = llmResponse.lastIndexOf('}') + 1
            
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val jsonString = llmResponse.substring(jsonStart, jsonEnd)
                json.decodeFromString<IncentiveMatchResponse>(jsonString)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse match result", e)
            null
        }
    }
    
    private fun formatTime(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        
        return when {
            diff < 60_000 -> "just now"
            diff < 3600_000 -> "${diff / 60_000}m ago"
            diff < 86400_000 -> "${diff / 3600_000}h ago"
            diff < 604800_000 -> "${diff / 86400_000}d ago"
            else -> {
                val formatter = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
                formatter.format(java.util.Date(timestamp))
            }
        }
    }
}
