package com.verdure.core

import android.content.Context
import android.util.Log
import com.verdure.core.HybridRouter.Route
import com.verdure.data.UserContextManager
import com.verdure.services.CalendarReader
import com.verdure.data.PriorityChanges
import com.verdure.tools.Tool
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Central AI orchestrator for Verdure
 *
 * Architecture: Two-pass intent detection system
 *
 * PASS 1: Intent Classification
 * - Minimal prompt: Just classify the intent
 * - Returns: {"intent": "...", "confidence": "..."}
 * - Fast, focused, reliable
 *
 * PASS 2: Intent-Specific Processing
 * - notification_query → Fetch notifications + synthesize
 * - notification_rerank → Extract priority changes + apply
 * - chat → Generate natural response
 *
 * Benefits:
 * - Cleaner separation of concerns
 * - Smaller JSON payloads (more reliable)
 * - Intent classification separate from extraction
 * - Easier to debug and improve
 */
class VerdureAI(
    private val context: Context,
    private val llmEngine: LLMEngine,
    private val contextManager: UserContextManager
) {

    companion object {
        private const val TAG = "VerdureAI"
        private const val CALENDAR_EVENT_LIMIT = 5
    }

    // Registry of all available tools
    private val tools = mutableMapOf<String, Tool>()
    private var recentConversationTurns: List<String> = emptyList()
    private val router by lazy { HybridRouter(context) }
    private val calendarReader by lazy { CalendarReader(context) }

    // JSON parser for structured output
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Register a tool to be available for use
     */
    fun registerTool(tool: Tool) {
        tools[tool.name] = tool
        Log.d(TAG, "Registered tool: ${tool.name} - ${tool.description}")
    }

    /**
     * Update recent chat turns so the LLM keeps short-term conversational context
     * across app restarts and multi-message flows.
     */
    fun setRecentConversationTurns(turns: List<String>) {
        recentConversationTurns = turns.takeLast(8)
    }

    /**
     * Process a user request with two-pass system
     *
     * Pass 1: Classify intent (notification_query, notification_rerank, chat)
     * Pass 2: Handle based on intent with appropriate prompt
     */
    suspend fun processRequest(userMessage: String): String {
        // PASS 1: Intent Classification
        val intentPrompt = buildIntentClassificationPrompt(userMessage)
        val intentJson = llmEngine.generateContent(intentPrompt)
        val intent = parseIntent(intentJson)

        Log.d(TAG, "Detected intent: $intent")

        // PASS 2: Intent-Specific Processing
        return when (intent) {
            "notification_query" -> handleNotificationQuery(userMessage)
            "notification_rerank" -> handleNotificationRerank(userMessage)
            "app_prioritization" -> handleAppPrioritization(userMessage)
            "chat" -> handleChat(userMessage)
            else -> {
                Log.w(TAG, "Unknown intent: $intent, falling back to chat")
                handleChat(userMessage)
            }
        }
    }

    // ----- PASS 1: Intent Classification -----

    /**
     * Build minimal prompt for intent classification only
     */
    private fun buildIntentClassificationPrompt(userMessage: String): String {
        return """
You are V, a personal AI assistant for notification management.

Your job: Identify the user's intention and respond with ONLY a JSON object.

User message: "$userMessage"

Classify into ONE of these intents:
1. notification_query - User asks about their notifications
   - Examples: "what's urgent?", "show me important messages", "what do I need to know?"

2. notification_rerank - User wants to make some notifications more/less important by keywords/senders/domains
   - Examples: "make work emails more important", "ignore spam", "focus on messages from Sarah"

3. app_prioritization - User wants to prioritize or deprioritize specific APPS
   - Examples: "prioritize Discord", "make Instagram less important", "focus on Slack"

4. chat - User is having a conversation, asking follow-up questions, or chatting casually
   - Examples: "hi", "thanks", "what can you do?", "how does this work?"

IMPORTANT:
- If user greets you (hi, hello, hey) → chat
- If user asks about their current priorities ("what are my priorities?") → chat
- If user asks about notifications → notification_query
- If user wants to prioritize/deprioritize specific APPS → app_prioritization
- If user wants to change keywords/senders/domains → notification_rerank

Respond with ONLY this JSON format (no extra text):
{
  "intent": "notification_query OR notification_rerank OR app_prioritization OR chat",
  "confidence": "high OR medium OR low"
}

Examples:

Input: "what's urgent today?"
Output: {"intent": "notification_query", "confidence": "high"}

Input: "prioritize emails from work"
Output: {"intent": "notification_rerank", "confidence": "high"}

Input: "prioritize Discord"
Output: {"intent": "app_prioritization", "confidence": "high"}

Input: "hi there"
Output: {"intent": "chat", "confidence": "high"}

Input: "what are my current priorities?"
Output: {"intent": "chat", "confidence": "high"}

Now classify the user's message:
        """.trimIndent()
    }

    /**
     * Parse intent from Pass 1 JSON response
     */
    private fun parseIntent(json: String): String {
        return try {
            val jsonStart = json.indexOf('{')
            val jsonEnd = json.lastIndexOf('}') + 1

            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val jsonString = json.substring(jsonStart, jsonEnd)
                val jsonObj = this.json.parseToJsonElement(jsonString).jsonObject
                jsonObj["intent"]?.jsonPrimitive?.content ?: "chat"
            } else {
                Log.w(TAG, "No JSON found in intent response, defaulting to chat")
                "chat"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse intent, defaulting to chat", e)
            "chat"
        }
    }

    // ----- PASS 2A: Notification Query -----

    /**
     * Handle notification_query intent
     * Fetches notifications (either by search or priority) and synthesizes summary
     */
    private suspend fun handleNotificationQuery(userMessage: String): String {
        val semanticTool = tools["semantic_retrieval"]
        val notificationTool = tools["notification_filter"]

        if (semanticTool == null && notificationTool == null) {
            return "Notification tools are not registered yet. Please restart the app and ensure notification access is granted."
        }

        val route = router.chooseRoute(estimatePromptTokens(userMessage))
        Log.d(TAG, "HybridRouter selected route=$route for notification query")

        val notificationsResult = if (route == Route.LOCAL && semanticTool != null) {
            val semanticResult = semanticTool.execute(
                mapOf(
                    "action" to "retrieve",
                    "query" to userMessage,
                    "k" to 10
                )
            )
            if (
                (semanticResult.startsWith("Embeddings not ready") ||
                    semanticResult.startsWith("No semantically relevant")) &&
                notificationTool != null
            ) {
                Log.d(TAG, "Semantic retrieval unavailable, falling back to heuristic notification tool")
                notificationTool.execute(mapOf("action" to "get_priority", "limit" to 8))
            } else semanticResult
        } else if (notificationTool != null) {
            notificationTool.execute(mapOf("action" to "get_priority", "limit" to 8))
        } else {
            "I couldn't retrieve notifications right now."
        }

        val context = contextManager.loadContext()
        val notificationSection = if (
            notificationsResult.startsWith("No priority notifications") ||
            notificationsResult.startsWith("No semantically relevant")
        ) {
            val fallbackFromAll = notificationTool?.execute(mapOf("action" to "get_all", "limit" to 8))
            if (
                fallbackFromAll != null &&
                    !fallbackFromAll.startsWith("No priority notifications") &&
                    !fallbackFromAll.startsWith("No recent notifications")
            ) {
                Log.d(TAG, "Notification query fallback: using all recent notifications")
                fallbackFromAll
            } else {
                notificationsResult
            }
        } else {
            notificationsResult
        }
        val calendarSection = buildCalendarContext()

        // Second LLM call to synthesize
        val summaryPrompt = buildNotificationSummaryPrompt(
            userMessage,
            notificationSection,
            calendarSection,
            context
        )

        Log.d(TAG, "Synthesizing notification query with second LLM call")
        return llmEngine.generateContent(summaryPrompt)
    }

    /**
     * Build prompt for notification summary (Pass 2A)
     */
    private fun buildNotificationSummaryPrompt(
        userMessage: String,
        notificationList: String,
        calendarContext: String,
        context: com.verdure.data.UserContext
    ): String {
        val recentTurnsSection = if (recentConversationTurns.isNotEmpty()) {
            "Recent conversation:\n${recentConversationTurns.joinToString("\n")}\n"
        } else {
            ""
        }
        return """
You are V, the user's personal AI assistant.

User's priorities: ${context.priorityRules.keywords.joinToString(", ").ifEmpty { "None set yet" }}
High priority apps: ${context.priorityRules.highPriorityApps.joinToString(", ").ifEmpty { "None set yet" }}
${recentTurnsSection}

Recent urgent notifications (sorted by importance):
$notificationList

Calendar context:
$calendarContext

User asked: "$userMessage"

IMPORTANT: Structure your response as follows:
<thinking>
[Your internal reasoning: which notifications are most relevant, what patterns you notice, what's most urgent]
</thinking>

<response>
[Your helpful, concise summary for the user. Focus on what's most urgent and important based on their priorities.]
</response>
        """.trimIndent()
    }

    // ----- PASS 2B: Notification Rerank -----

    /**
     * Handle notification_rerank intent
     * Extracts priority changes and applies them
     */
    private suspend fun handleNotificationRerank(userMessage: String): String {
        val context = contextManager.loadContext()

        // Second LLM call to extract priority changes
        val updatePrompt = buildPriorityUpdatePrompt(userMessage, context)
        val updateJson = llmEngine.generateContent(updatePrompt)

        Log.d(TAG, "Extracting priority changes with second LLM call")

        val result = parsePriorityUpdateResponse(updateJson)

        if (result != null) {
            // Validate and apply changes
            val validatedChanges = validatePriorityChanges(result.changes)
            contextManager.applyPriorityChanges(validatedChanges)
            return result.message
        } else {
            return "I couldn't understand which notifications to prioritize. Can you be more specific?"
        }
    }

    /**
     * Build prompt for priority update extraction (Pass 2B)
     */
    private fun buildPriorityUpdatePrompt(
        userMessage: String,
        context: com.verdure.data.UserContext
    ): String {
        val recentTurnsSection = if (recentConversationTurns.isNotEmpty()) {
            "Recent conversation:\n${recentConversationTurns.joinToString("\n")}\n"
        } else {
            ""
        }
        return """
You are V, a personal AI assistant.

Current user priorities:
${context.toJson()}
${recentTurnsSection}

User message: "$userMessage"

Extract what the user wants to prioritize or deprioritize. Respond with ONLY valid JSON:

{
  "changes": {
    "add_high_priority_apps": ["App1", "App2"],
    "add_keywords": ["keyword1", "keyword2"],
    "add_senders": ["email@example.com"],
    "add_contacts": ["Person Name"],
    "add_domains": [".edu", ".gov"],
    "remove_high_priority_apps": [],
    "remove_keywords": [],
    "remove_senders": [],
    "remove_contacts": [],
    "remove_domains": []
  },
  "message": "[Your confirmation message to user]"
}

Guidelines:
- App names: Exact app names (Discord, Gmail, Instagram, WhatsApp, etc.)
- Keywords: Important words/phrases user cares about
- Senders: Email addresses (user@example.com)
- Contacts: Person names (John Smith, Sarah)
- Domains: Email domains (.edu, .gov, @company.com)
- If user says "ignore" or "deprioritize", use remove_ fields

Examples:

Input: "prioritize Discord and emails from james deck"
Output: {
  "changes": {
    "add_high_priority_apps": ["Discord"],
    "add_contacts": ["james deck"]
  },
  "message": "Got it! I've prioritized Discord and messages from James Deck."
}

Input: "focus on work emails"
Output: {
  "changes": {
    "add_keywords": ["work"]
  },
  "message": "I'll prioritize work-related emails for you."
}

Input: "stop showing Instagram"
Output: {
  "changes": {
    "remove_high_priority_apps": ["Instagram"]
  },
  "message": "Instagram notifications will be deprioritized."
}

Now extract from the user's message:
        """.trimIndent()
    }

    @Serializable
    private data class PriorityUpdateResponse(
        val changes: PriorityChanges,
        val message: String
    )

    /**
     * Parse priority update response from Pass 2B
     */
    private fun parsePriorityUpdateResponse(json: String): PriorityUpdateResponse? {
        return try {
            val jsonStart = json.indexOf('{')
            val jsonEnd = json.lastIndexOf('}') + 1

            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val jsonString = json.substring(jsonStart, jsonEnd)
                this.json.decodeFromString<PriorityUpdateResponse>(jsonString)
            } else {
                Log.w(TAG, "No JSON found in priority update response")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse priority update response", e)
            null
        }
    }

    /**
     * Validate priority changes to prevent invalid data
     */
    private fun validatePriorityChanges(changes: PriorityChanges): PriorityChanges {
        return changes.copy(
            // Domains must start with "."
            add_domains = changes.add_domains.filter { it.startsWith(".") },
            remove_domains = changes.remove_domains.filter { it.startsWith(".") },

            // Filter out empty strings
            add_keywords = changes.add_keywords.filter { it.isNotBlank() },
            add_high_priority_apps = changes.add_high_priority_apps.filter { it.isNotBlank() },
            add_contacts = changes.add_contacts.filter { it.isNotBlank() },
            add_senders = changes.add_senders.filter { it.isNotBlank() }
        )
    }

    // ----- PASS 2C: App Prioritization -----

    /**
     * Handle app_prioritization intent
     * Delegates to AppPrioritizationTool to update customAppOrder
     */
    private suspend fun handleAppPrioritization(userMessage: String): String {
        val appPrioritizationTool = tools["app_prioritization"]
            ?: return "App prioritization is not available right now."

        // Extract app name and action (prioritize vs deprioritize)
        val appPrioritizationPrompt = buildAppPrioritizationPrompt(userMessage)
        val extractionJson = llmEngine.generateContent(appPrioritizationPrompt)
        
        Log.d(TAG, "Extracting app prioritization with LLM")
        
        val result = parseAppPrioritizationResponse(extractionJson)
        
        if (result != null) {
            val action = if (result.action == "prioritize") "prioritize_app" else "deprioritize_app"
            return appPrioritizationTool.execute(
                mapOf(
                    "action" to action,
                    "app_name" to result.app_name
                )
            )
        } else {
            return "I couldn't understand which app you want to prioritize. Try saying \"prioritize Discord\" or \"make Instagram less important\"."
        }
    }

    /**
     * Build prompt for app prioritization extraction
     */
    private fun buildAppPrioritizationPrompt(userMessage: String): String {
        val recentTurnsSection = if (recentConversationTurns.isNotEmpty()) {
            "Recent conversation:\n${recentConversationTurns.joinToString("\n")}\n"
        } else {
            ""
        }
        return """
You are V, a personal AI assistant.
${recentTurnsSection}

User message: "$userMessage"

Extract the app name and action. Respond with ONLY valid JSON:

{
  "app_name": "[exact app name]",
  "action": "prioritize OR deprioritize"
}

Examples:

Input: "prioritize Discord"
Output: {"app_name": "Discord", "action": "prioritize"}

Input: "make Instagram less important"
Output: {"app_name": "Instagram", "action": "deprioritize"}

Input: "focus on Slack"
Output: {"app_name": "Slack", "action": "prioritize"}

Now extract from the user's message:
        """.trimIndent()
    }

    @Serializable
    private data class AppPrioritizationResponse(
        val app_name: String,
        val action: String
    )

    /**
     * Parse app prioritization response
     */
    private fun parseAppPrioritizationResponse(json: String): AppPrioritizationResponse? {
        return try {
            val jsonStart = json.indexOf('{')
            val jsonEnd = json.lastIndexOf('}') + 1

            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                val jsonString = json.substring(jsonStart, jsonEnd)
                this.json.decodeFromString<AppPrioritizationResponse>(jsonString)
            } else {
                Log.w(TAG, "No JSON found in app prioritization response")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse app prioritization response", e)
            null
        }
    }

    // ----- PASS 2D: Chat -----

    /**
     * Handle chat intent
     * Simple conversational response
     */
    private suspend fun handleChat(userMessage: String): String {
        val context = contextManager.loadContext()
        val chatPrompt = buildChatPrompt(userMessage, context)

        Log.d(TAG, "Generating chat response with LLM")
        return llmEngine.generateContent(chatPrompt)
    }

    /**
     * Build prompt for chat response (Pass 2C)
     */
    private fun buildChatPrompt(
        userMessage: String,
        context: com.verdure.data.UserContext
    ): String {
        val recentTurnsSection = if (recentConversationTurns.isNotEmpty()) {
            "Recent conversation:\n${recentConversationTurns.joinToString("\n")}\n"
        } else {
            ""
        }
        return """
You are V, a personal AI assistant for notification management.

What you can do:
- Summarize urgent/important notifications
- Help users prioritize specific apps, people, or topics
- Answer questions about their priorities

Current user priorities:
- Apps: ${context.priorityRules.highPriorityApps.joinToString(", ").ifEmpty { "None set yet" }}
- Keywords: ${context.priorityRules.keywords.joinToString(", ").ifEmpty { "None set yet" }}
- Important contacts: ${context.priorityRules.contacts.joinToString(", ").ifEmpty { "None set yet" }}
${recentTurnsSection}

User: "$userMessage"

Calendar context:
${buildCalendarContext()}

IMPORTANT: Structure your response as follows:
<thinking>
[Your internal reasoning: what the user is asking, how to best respond, what information to include]
</thinking>

<response>
[Your natural, helpful response to the user]
</response>
        """.trimIndent()
    }

    private fun estimatePromptTokens(text: String): Int {
        // Lightweight approximation for router input.
        return (text.length / 4).coerceAtLeast(1)
    }

    private fun buildCalendarContext(): String {
        return try {
            val events = calendarReader.getUpcomingEvents().take(CALENDAR_EVENT_LIMIT)
            if (events.isEmpty()) {
                "No upcoming calendar events found."
            } else {
                events.joinToString("\n") { event ->
                    "- ${event.title} (${event.getTimeRange()}, ${event.getUrgencyLabel()})"
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed loading calendar context", e)
            "Calendar unavailable."
        }
    }

    // ----- Public API -----

    /**
     * List all available tools (useful for debugging)
     */
    fun getAvailableTools(): List<Tool> = tools.values.toList()

    /**
     * Check if a specific tool is registered
     */
    fun hasTool(toolName: String): Boolean = tools.containsKey(toolName)
}
