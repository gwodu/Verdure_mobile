package com.verdure.data

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ChatTurn(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
private data class ChatHistoryPayload(
    val entries: List<ChatTurn> = emptyList()
)

/**
 * Persists chat history so conversation UI survives app restarts.
 */
class ChatHistoryStore(private val context: Context) {
    private val lock = Any()
    private val historyFile = File(context.filesDir, HISTORY_FILENAME)
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun appendUserMessage(message: String) {
        appendInternal(ChatTurn(role = "user", content = message))
    }

    fun appendAssistantMessage(message: String) {
        appendInternal(ChatTurn(role = "assistant", content = message))
    }

    fun appendSystemMessage(message: String) {
        appendInternal(ChatTurn(role = "system", content = message))
    }

    fun getAllForDisplay(): List<ChatTurn> = synchronized(lock) {
        loadInternal()
    }

    fun getRecentForPrompt(limit: Int = 8): List<String> = synchronized(lock) {
        loadInternal()
            .takeLast(limit)
            .map { turn ->
                val compact = turn.content.trim().replace(Regex("\\s+"), " ")
                val clipped = if (compact.length > MAX_PROMPT_CHARS_PER_TURN) {
                    compact.take(MAX_PROMPT_CHARS_PER_TURN) + "..."
                } else {
                    compact
                }
                when (turn.role) {
                    "user" -> "User: $clipped"
                    "assistant" -> "Assistant: $clipped"
                    else -> "System: $clipped"
                }
            }
    }

    private fun appendInternal(turn: ChatTurn) = synchronized(lock) {
        val updated = (loadInternal() + turn).takeLast(MAX_ENTRIES)
        saveInternal(updated)
    }

    private fun loadInternal(): List<ChatTurn> {
        if (!historyFile.exists()) return emptyList()
        return try {
            val payload = json.decodeFromString<ChatHistoryPayload>(historyFile.readText())
            payload.entries
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse chat history; starting fresh", e)
            emptyList()
        }
    }

    private fun saveInternal(entries: List<ChatTurn>) {
        try {
            val payload = ChatHistoryPayload(entries = entries)
            historyFile.writeText(json.encodeToString(ChatHistoryPayload.serializer(), payload))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save chat history", e)
        }
    }

    companion object {
        private const val TAG = "ChatHistoryStore"
        private const val HISTORY_FILENAME = "chat_history.json"
        private const val MAX_ENTRIES = 60
        private const val MAX_PROMPT_CHARS_PER_TURN = 280
    }
}
