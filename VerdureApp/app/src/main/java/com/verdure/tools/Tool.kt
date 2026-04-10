package com.verdure.tools

import com.cactus.models.CactusTool
import com.cactus.models.ToolParameter
import com.cactus.models.createTool

/**
 * Base interface for all Verdure tools
 * Tools are specialized AI capabilities that can be invoked by VerdureAI
 * 
 * Example tools:
 * - NotificationTool: Filters and prioritizes notifications
 * - DayPlannerTool: Plans your day based on calendar/tasks
 * - EmailTool: Drafts or summarizes emails
 */
interface Tool {
    /** Unique identifier for this tool */
    val name: String
    
    /** Description of what this tool does (helps AI decide when to use it) */
    val description: String
    
    /**
     * Execute this tool with given parameters
     * @param params Map of parameter name to value
     * @return Result as a string (can be JSON, plain text, etc.)
     */
    suspend fun execute(params: Map<String, Any>): String

    /**
     * Optional argument schema for native Cactus tool calling.
     * Keys are argument names; values describe type/description/required.
     *
     * Default empty map keeps compatibility with existing tools.
     */
    val argumentSchema: Map<String, ToolParameter>
        get() = emptyMap()
}

/**
 * Adapter from Verdure Tool interface to Cactus tool schema.
 */
fun Tool.toCactusTool(): CactusTool = createTool(
    name = name,
    description = description,
    parameters = argumentSchema
)
