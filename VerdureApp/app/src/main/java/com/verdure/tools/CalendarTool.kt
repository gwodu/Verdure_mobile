package com.verdure.tools

import com.cactus.models.ToolParameter
import com.verdure.services.CalendarReader

/**
 * Tool wrapper around CalendarReader for LLM function-calling flows.
 */
class CalendarTool(
    private val calendarReader: CalendarReader
) : Tool {

    override val name: String = "calendar_reader"
    override val description: String = "Reads upcoming calendar events from the device"
    override val argumentSchema: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            type = "string",
            description = "Action to perform: upcoming/list"
        ),
        "limit" to ToolParameter(
            type = "number",
            description = "Maximum number of events to return"
        )
    )

    override suspend fun execute(params: Map<String, Any>): String {
        val action = (params["action"] as? String ?: "upcoming").lowercase()
        val limit = (params["limit"] as? Int ?: 5).coerceIn(1, 20)

        return when (action) {
            "upcoming", "list" -> {
                val events = calendarReader.getUpcomingEvents().take(limit)
                if (events.isEmpty()) {
                    "No upcoming calendar events found."
                } else {
                    events.joinToString("\n") { event ->
                        "- ${event.title} (${event.getTimeRange()}, ${event.getUrgencyLabel()})"
                    }
                }
            }
            else -> "Unsupported calendar action: $action"
        }
    }
}
