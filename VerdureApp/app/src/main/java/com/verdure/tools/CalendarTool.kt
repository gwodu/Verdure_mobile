package com.verdure.tools

import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import android.text.format.DateFormat
import android.util.Log
import com.cactus.models.ToolParameter
import com.verdure.services.CalendarReader
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Calendar capability for LLM tool calling: read upcoming events and add
 * new ones.
 *
 * Auto-formalization split: the LLM only *extracts* loose fields from the
 * user's words ("dentist", "tomorrow", "3pm") under constrained decoding;
 * this tool *normalizes* them deterministically in Kotlin (relative dates,
 * 12/24h times) so the same request always produces the same event.
 */
class CalendarTool(
    private val context: Context,
    private val calendarReader: CalendarReader
) : Tool {

    companion object {
        private const val TAG = "CalendarTool"
        private const val DEFAULT_DURATION_MINUTES = 60L
    }

    override val name: String = "calendar"
    override val description: String =
        "Reads the user's upcoming calendar events or adds a new event to their calendar"

    override val argumentSchema: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            type = "string",
            description = "What to do: 'add' to create an event, 'upcoming' to list events",
            required = true
        ),
        "title" to ToolParameter(
            type = "string",
            description = "Event title, e.g. 'Dentist appointment' (required for add)"
        ),
        "date" to ToolParameter(
            type = "string",
            description = "Event date: 'today', 'tomorrow', a weekday like 'friday', or YYYY-MM-DD"
        ),
        "time" to ToolParameter(
            type = "string",
            description = "Start time like '15:00', '3pm' or '9:30am'. Omit for all-day"
        ),
        "duration_minutes" to ToolParameter(
            type = "number",
            description = "Event length in minutes (default 60)"
        ),
        "location" to ToolParameter(
            type = "string",
            description = "Where the event happens (optional)"
        )
    )

    override suspend fun execute(params: Map<String, Any>): String {
        return when ((params["action"] as? String ?: "upcoming").lowercase(Locale.US).trim()) {
            "add", "create", "add_event" -> addEvent(params)
            else -> listUpcoming(params)
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────

    private fun listUpcoming(params: Map<String, Any>): String {
        val limit = (params["limit"] as? String)?.toIntOrNull()
            ?: (params["limit"] as? Int)
            ?: 5
        val events = calendarReader.getUpcomingEvents().take(limit.coerceIn(1, 20))
        return if (events.isEmpty()) {
            "No upcoming calendar events in the next two days."
        } else {
            events.joinToString("\n") { event ->
                "- ${event.title} (${event.getTimeRange()}, ${event.getUrgencyLabel()})"
            }
        }
    }

    // ── Add ───────────────────────────────────────────────────────────────

    private fun addEvent(params: Map<String, Any>): String {
        val title = (params["title"] as? String)?.trim().orEmpty()
        if (title.isEmpty()) {
            return "I need a title for the event — what should I call it?"
        }

        val dateSpec = (params["date"] as? String).orEmpty()
        val timeSpec = (params["time"] as? String).orEmpty()
        val day = parseDate(dateSpec)
            ?: return "I couldn't understand the date \"$dateSpec\" — try 'tomorrow', a weekday, or YYYY-MM-DD."

        val timeOfDay = parseTime(timeSpec)
        val allDay = timeOfDay == null
        val start = (day.clone() as Calendar).apply {
            if (timeOfDay != null) {
                set(Calendar.HOUR_OF_DAY, timeOfDay.first)
                set(Calendar.MINUTE, timeOfDay.second)
            } else {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
            }
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val durationMinutes = (params["duration_minutes"] as? String)?.toLongOrNull()
            ?: (params["duration_minutes"] as? Number)?.toLong()
            ?: DEFAULT_DURATION_MINUTES
        val endMillis = if (allDay) {
            start.timeInMillis + 24 * 60 * 60 * 1000
        } else {
            start.timeInMillis + durationMinutes.coerceIn(5, 24 * 60) * 60 * 1000
        }

        val calendarId = findWritableCalendarId()
            ?: return "I couldn't find a writable calendar on this device. " +
                "Check that calendar access is granted in Verdure's settings."

        return try {
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, start.timeInMillis)
                put(CalendarContract.Events.DTEND, endMillis)
                put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                (params["location"] as? String)?.takeIf { it.isNotBlank() }?.let {
                    put(CalendarContract.Events.EVENT_LOCATION, it)
                }
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri != null) {
                val whenText = if (allDay) {
                    DateFormat.format("EEE, MMM d", start).toString() + " (all day)"
                } else {
                    DateFormat.format("EEE, MMM d 'at' h:mm a", start).toString()
                }
                Log.i(TAG, "Event created: '$title' at ${start.timeInMillis} (uri=$uri)")
                "Added \"$title\" on $whenText to your calendar."
            } else {
                "The calendar refused the event — no error given. Try again?"
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing WRITE_CALENDAR permission", e)
            "I don't have permission to write to your calendar yet — " +
                "grant calendar access in Verdure's settings and try again."
        } catch (e: Exception) {
            Log.e(TAG, "Event insert failed", e)
            "Something went wrong adding the event: ${e.message}"
        }
    }

    /** Prefer the primary calendar, else the first user-writable one. */
    private fun findWritableCalendarId(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )
        return try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ?",
                arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
                null
            )?.use { cursor ->
                var firstId: Long? = null
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    if (firstId == null) firstId = id
                    if (cursor.getInt(1) == 1) return id // primary wins
                }
                firstId
            }
        } catch (e: Exception) {
            Log.e(TAG, "Calendar lookup failed", e)
            null
        }
    }

    // ── Deterministic normalization of LLM-extracted fields ──────────────

    /** "today" / "tomorrow" / weekday / YYYY-MM-DD → midnight Calendar. */
    private fun parseDate(spec: String): Calendar? {
        val s = spec.trim().lowercase(Locale.US)
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (s.isEmpty() || s == "today" || s == "tonight") return today
        if (s == "tomorrow") return today.apply { add(Calendar.DAY_OF_YEAR, 1) }

        val weekdays = mapOf(
            "monday" to Calendar.MONDAY, "tuesday" to Calendar.TUESDAY,
            "wednesday" to Calendar.WEDNESDAY, "thursday" to Calendar.THURSDAY,
            "friday" to Calendar.FRIDAY, "saturday" to Calendar.SATURDAY,
            "sunday" to Calendar.SUNDAY
        )
        weekdays[s.removePrefix("next ").trim()]?.let { target ->
            return today.apply {
                do add(Calendar.DAY_OF_YEAR, 1) while (get(Calendar.DAY_OF_WEEK) != target)
            }
        }

        Regex("""(\d{4})-(\d{1,2})-(\d{1,2})""").find(s)?.let { m ->
            val (y, mo, d) = m.destructured
            return today.apply {
                set(y.toInt(), mo.toInt() - 1, d.toInt())
            }
        }
        return null
    }

    /** "15:00" / "3pm" / "9:30 am" → hour/minute pair, or null (all-day). */
    private fun parseTime(spec: String): Pair<Int, Int>? {
        val s = spec.trim().lowercase(Locale.US)
        if (s.isEmpty() || s == "all day" || s == "all-day") return null

        val m = Regex("""(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""").find(s) ?: return null
        var hour = m.groupValues[1].toIntOrNull() ?: return null
        val minute = m.groupValues[2].toIntOrNull() ?: 0
        when (m.groupValues[3]) {
            "pm" -> if (hour < 12) hour += 12
            "am" -> if (hour == 12) hour = 0
        }
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour to minute
    }
}
