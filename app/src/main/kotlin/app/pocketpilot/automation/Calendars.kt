package app.pocketpilot.automation

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import app.pocketpilot.core.automation.AutomationFailure
import app.pocketpilot.core.automation.TriggerEvent
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.TimeZone

/** One occurrence of a calendar event. */
data class CalendarInstance(
    val title: String,
    val location: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
)

/**
 * The phone's calendars through the calendar provider: what is on now and next, adding events, and the
 * "Calendar event" trigger, which sets one alarm for the next start or end among the coming events.
 */
class Calendars(
    private val context: Context,
) {
    private var emit: ((TriggerEvent) -> Unit)? = null
    private var observer: ContentObserver? = null

    @Volatile private var lastCheck = 0L

    val readable: Boolean get() = granted(Manifest.permission.READ_CALENDAR)

    /** Occurrences overlapping [from, to), from visible calendars, earliest first. */
    fun instances(
        from: Long,
        to: Long,
    ): List<CalendarInstance> {
        if (!readable) throw AutomationFailure(NO_ACCESS)
        val uri =
            CalendarContract.Instances.CONTENT_URI
                .buildUpon()
                .also {
                    ContentUris.appendId(it, from)
                    ContentUris.appendId(it, to)
                }.build()
        val columns =
            arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
            )
        return context.contentResolver
            .query(uri, columns, "${CalendarContract.Instances.VISIBLE} = 1", null, "${CalendarContract.Instances.BEGIN} ASC")
            ?.use { cursor ->
                buildList {
                    while (cursor.moveToNext() && size < MAX_INSTANCES) {
                        add(
                            CalendarInstance(
                                title = cursor.getString(0).orEmpty(),
                                location = cursor.getString(1).orEmpty(),
                                begin = cursor.getLong(2),
                                end = cursor.getLong(3),
                                allDay = cursor.getInt(4) == 1,
                            ),
                        )
                    }
                }
            }.orEmpty()
    }

    /** Titles of the timed events happening now, one per line; null without calendar access. */
    fun now(): String? {
        if (!readable) return null
        val now = System.currentTimeMillis()
        return instances(now - DAY_MS, now + 1)
            .filter { !it.allDay && it.begin <= now && it.end > now }
            .joinToString("\n") { it.title }
    }

    /** The next [hours] of events whose title contains [title], one per line as "Mon 14:30, title". */
    fun upcoming(
        hours: Int,
        title: String,
    ): String {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        return instances(now, now + hours.coerceIn(1, MAX_HOURS) * HOUR_MS)
            .filter { it.end > now && (title.isBlank() || it.title.contains(title, ignoreCase = true)) }
            .joinToString("\n") { event ->
                val start =
                    if (event.allDay) {
                        Instant.ofEpochMilli(event.begin).atZone(ZoneId.of("UTC")).format(DAY) + ", all day"
                    } else {
                        Instant.ofEpochMilli(event.begin).atZone(zone).format(DAY_TIME)
                    }
                val place = if (event.location.isBlank()) "" else " (${event.location})"
                "$start, ${event.title}$place"
            }
    }

    /** Adds an event to [calendar] by name, or to the main writable calendar; returns its start as text. */
    fun add(
        title: String,
        start: String,
        minutes: Int,
        location: String,
        calendar: String,
    ): String {
        if (!granted(Manifest.permission.WRITE_CALENDAR)) throw AutomationFailure(NO_ACCESS)
        val begin = parseStart(start) ?: throw AutomationFailure("Give the start as 2026-10-20 14:30, or 14:30 for today")
        val calendarId = writableCalendar(calendar) ?: throw AutomationFailure("No calendar on this phone takes new events")
        val values =
            ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, begin)
                put(CalendarContract.Events.DTEND, begin + minutes.coerceIn(1, MAX_MINUTES) * MINUTE_MS)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                if (location.isNotBlank()) put(CalendarContract.Events.EVENT_LOCATION, location)
            }
        context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            ?: throw AutomationFailure("The calendar did not take the event")
        return Instant.ofEpochMilli(begin).atZone(ZoneId.systemDefault()).format(DAY_TIME)
    }

    /** Starts or stops the "Calendar event" trigger. */
    @Synchronized
    fun watch(emit: ((TriggerEvent) -> Unit)?) {
        this.emit = emit
        if (emit == null) {
            observer?.let { context.contentResolver.unregisterContentObserver(it) }
            observer = null
            cancelAlarm()
            return
        }
        if (observer == null && readable) {
            lastCheck = System.currentTimeMillis()
            observer =
                object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean) = schedule()
                }.also { context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, it) }
        }
        schedule()
    }

    /** The calendar alarm went off: fire every start and end since the last check. */
    fun onAlarm() {
        val emit = emit ?: return
        val now = System.currentTimeMillis()
        val since = lastCheck.takeIf { it > 0 } ?: (now - MINUTE_MS)
        runCatching {
            instances(since - DAY_MS, now + 1).forEach { event ->
                if (event.begin in (since + 1)..now) emit(event("start", event))
                if (event.end in (since + 1)..now) emit(event("end", event))
            }
        }
        lastCheck = now
        schedule()
    }

    private fun event(
        kind: String,
        event: CalendarInstance,
    ): TriggerEvent {
        val zone = ZoneId.systemDefault()
        return TriggerEvent(
            "calendar.event",
            mapOf(
                "event" to kind,
                "title" to event.title,
                "location" to event.location,
                "start" to Instant.ofEpochMilli(event.begin).atZone(zone).format(DAY_TIME),
                "end" to Instant.ofEpochMilli(event.end).atZone(zone).format(DAY_TIME),
                "all_day" to if (event.allDay) "yes" else "no",
            ),
        )
    }

    private fun schedule() {
        if (emit == null || !readable) return
        val now = System.currentTimeMillis()
        val next =
            runCatching { instances(now, now + WEEK_MS) }
                .getOrDefault(emptyList())
                .flatMap { listOf(it.begin, it.end) }
                .filter { it > now }
                .minOrNull()
                // With nothing coming up, look again in a day.
                ?: (now + DAY_MS)
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarm())
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarm())
        }
    }

    private fun cancelAlarm() = context.getSystemService(AlarmManager::class.java).cancel(alarm())

    private fun alarm(): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST,
            Intent(context, CalendarAlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun writableCalendar(name: String): Long? {
        val columns =
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.IS_PRIMARY,
            )
        val calendars =
            context.contentResolver
                .query(
                    CalendarContract.Calendars.CONTENT_URI,
                    columns,
                    "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR} AND " +
                        "${CalendarContract.Calendars.VISIBLE} = 1",
                    null,
                    null,
                )?.use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(Triple(cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getInt(2) == 1))
                    }
                }.orEmpty()
        if (name.isNotBlank()) {
            return calendars.firstOrNull { it.second.equals(name.trim(), ignoreCase = true) }?.first
                ?: throw AutomationFailure("No writable calendar named $name")
        }
        return (calendars.firstOrNull { it.third } ?: calendars.firstOrNull())?.first
    }

    private fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val NO_ACCESS = "PocketPilot has no calendar access; connect Shizuku or allow Calendar in the app's permissions"
        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 3_600_000L
        private const val DAY_MS = 86_400_000L
        private const val WEEK_MS = 7 * DAY_MS
        private const val MAX_HOURS = 24 * 31
        private const val MAX_MINUTES = 24 * 60 * 14
        private const val MAX_INSTANCES = 200
        private const val ALARM_REQUEST = 0x0CA1
        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd")
        private val DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm")

        /** "2026-10-20 14:30", "2026-10-20T14:30" or "14:30" for today, in the phone's time zone. */
        fun parseStart(
            text: String,
            zone: ZoneId = ZoneId.systemDefault(),
        ): Long? {
            val t = text.trim().replace('T', ' ')
            val local =
                runCatching { LocalDateTime.parse(t, DateTimeFormatter.ofPattern("yyyy-MM-dd H:mm")) }.getOrNull()
                    ?: runCatching { LocalDate.now(zone).atTime(LocalTime.parse(t.padStart(5, '0'))) }.getOrNull()
                    ?: return null
            return local.atZone(zone).toInstant().toEpochMilli()
        }
    }
}
