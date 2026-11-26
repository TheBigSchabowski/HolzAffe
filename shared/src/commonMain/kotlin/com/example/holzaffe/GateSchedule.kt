package com.example.holzaffe

/** Whether a scheduled time window enforces the app gate or leaves blocked apps open. */
enum class GateSlotKind(val key: String, val label: String) {
    BLOCK("block", "Gesperrt"),
    FREE("free", "Ohne Sperre"),
    ;

    companion object {
        fun fromKey(key: String?): GateSlotKind = entries.firstOrNull { it.key == key } ?: BLOCK
    }
}

/**
 * Recurring weekly time window controlling when the Android app gate is active or paused.
 *
 * - [startMinute] and [endMinute] are minutes since midnight (`0..1439`).
 * - Equal [startMinute] and [endMinute] represents an all-day window on [daysOfWeek].
 * - [startMinute] > [endMinute] represents an overnight window (e.g. `22:00 – 07:00`, or
 *   `17:00 – 00:00` ending at midnight), carrying past midnight into the following day.
 */
data class GateTimeSlot(
    val id: String,
    val label: String,
    val kind: GateSlotKind = GateSlotKind.BLOCK,
    val startMinute: Int,
    val endMinute: Int,
    val daysOfWeek: Set<Int> = ALL_DAYS,
    val enabled: Boolean = true,
) {
    init {
        require(startMinute in 0 until MINUTES_PER_DAY) { "startMinute out of range: $startMinute" }
        require(endMinute in 0 until MINUTES_PER_DAY) { "endMinute out of range: $endMinute" }
        require(daysOfWeek.all { it in 1..7 }) { "daysOfWeek must be in 1..7: $daysOfWeek" }
    }

    val isAllDay: Boolean get() = startMinute == endMinute

    val wrapsOvernight: Boolean get() = startMinute > endMinute && endMinute > 0

    fun displayLabel(): String =
        label.trim().ifEmpty { if (kind == GateSlotKind.FREE) "Ohne Sperre" else "Sperr-Zeit" }

    fun formatTimeRange(): String =
        if (isAllDay) "Ganztägig" else "${formatMinute(startMinute)} – ${formatMinute(endMinute)}"

    /** Returns true if this enabled slot covers ISO weekday [dayOfWeek] (`1=Mo..7=So`) at [minuteOfDay]. */
    fun matches(dayOfWeek: Int, minuteOfDay: Int): Boolean {
        if (!enabled || daysOfWeek.isEmpty() || dayOfWeek !in 1..7) return false
        val m = minuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
        return when {
            startMinute == endMinute -> dayOfWeek in daysOfWeek
            startMinute < endMinute -> dayOfWeek in daysOfWeek && m in startMinute until endMinute
            else -> (dayOfWeek in daysOfWeek && m >= startMinute) ||
                (previousDay(dayOfWeek) in daysOfWeek && m < endMinute)
        }
    }

    companion object {
        const val MINUTES_PER_DAY = 1440
        const val MINUTES_PER_WEEK = 7 * MINUTES_PER_DAY
        val ALL_DAYS: Set<Int> = (1..7).toSet()
        val WEEKDAYS: Set<Int> = (1..5).toSet()
        val WEEKEND: Set<Int> = setOf(6, 7)

        fun previousDay(isoDay: Int): Int = if (isoDay <= 1) 7 else isoDay - 1

        fun nextDay(isoDay: Int): Int = if (isoDay >= 7) 1 else isoDay + 1

        fun dayShortLabel(isoDay: Int): String = when (isoDay) {
            1 -> "Mo"
            2 -> "Di"
            3 -> "Mi"
            4 -> "Do"
            5 -> "Fr"
            6 -> "Sa"
            7 -> "So"
            else -> ""
        }

        fun formatMinute(minute: Int): String {
            val clamped = minute.coerceIn(0, MINUTES_PER_DAY - 1)
            val h = clamped / 60
            val m = clamped % 60
            return "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}"
        }

        fun formatDays(days: Set<Int>): String {
            val sorted = days.filter { it in 1..7 }.distinct().sorted()
            if (sorted.isEmpty()) return ""
            if (sorted.size == 7) return "Täglich"
            if (sorted == listOf(1, 2, 3, 4, 5)) return "Mo–Fr"
            if (sorted == listOf(6, 7)) return "Sa–So"
            if (sorted.size >= 3 && sorted.zipWithNext().all { (a, b) -> b == a + 1 }) {
                return "${dayShortLabel(sorted.first())}–${dayShortLabel(sorted.last())}"
            }
            return sorted.joinToString(", ") { dayShortLabel(it) }
        }
    }
}

/** Encodes/decodes [GateTimeSlot] lists safely using ASCII control separators. */
object GateTimeSlotCodec {
    private const val RECORD_SEP = '\u001E'
    private const val FIELD_SEP = '\u001F'

    fun encode(slots: List<GateTimeSlot>): String = slots.joinToString(RECORD_SEP.toString()) { s ->
        fun clean(value: String) = value.replace(RECORD_SEP, ' ').replace(FIELD_SEP, ' ')
        val days = s.daysOfWeek.filter { it in 1..7 }.distinct().sorted().joinToString(",")
        listOf(
            clean(s.id),
            clean(s.label),
            s.kind.key,
            s.startMinute.coerceIn(0, GateTimeSlot.MINUTES_PER_DAY - 1).toString(),
            s.endMinute.coerceIn(0, GateTimeSlot.MINUTES_PER_DAY - 1).toString(),
            days,
            if (s.enabled) "1" else "0",
        ).joinToString(FIELD_SEP.toString())
    }

    fun decode(raw: String): List<GateTimeSlot> {
        if (raw.isBlank()) return emptyList()
        val seen = HashSet<String>()
        val result = mutableListOf<GateTimeSlot>()
        for (record in raw.split(RECORD_SEP)) {
            if (record.isBlank()) continue
            val parts = record.split(FIELD_SEP)
            if (parts.size != 7) continue
            val id = parts[0].trim()
            if (id.isEmpty() || !seen.add(id)) continue
            val label = parts[1].trim()
            val kind = GateSlotKind.fromKey(parts[2].trim())
            val start = parts[3].toIntOrNull()?.coerceIn(0, GateTimeSlot.MINUTES_PER_DAY - 1) ?: continue
            val end = parts[4].toIntOrNull()?.coerceIn(0, GateTimeSlot.MINUTES_PER_DAY - 1) ?: continue
            val days = parts[5]
                .split(',')
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it in 1..7 }
                .toSet()
            if (days.isEmpty()) continue
            val enabled = parts[6].trim() != "0"
            result.add(
                GateTimeSlot(
                    id = id,
                    label = label,
                    kind = kind,
                    startMinute = start,
                    endMinute = end,
                    daysOfWeek = days,
                    enabled = enabled,
                )
            )
        }
        return result
    }
}

/** Next minute in the weekly schedule where the gate transitions between enforced and open. */
data class GateScheduleTransition(
    val dayOfWeek: Int,
    val minuteOfDay: Int,
    val minutesUntil: Int,
) {
    fun formatRelative(currentDayOfWeek: Int): String {
        val time = GateTimeSlot.formatMinute(minuteOfDay)
        return when {
            dayOfWeek == currentDayOfWeek && minutesUntil < GateTimeSlot.MINUTES_PER_DAY -> time
            dayOfWeek == GateTimeSlot.nextDay(currentDayOfWeek) && minutesUntil < 2 * GateTimeSlot.MINUTES_PER_DAY ->
                "morgen $time"
            else -> "${GateTimeSlot.dayShortLabel(dayOfWeek)} $time"
        }
    }
}

enum class GateScheduleReason {
    PAUSED,
    ALWAYS_BLOCKED,
    IN_FREE_SLOT,
    IN_BLOCK_SLOT,
    OUTSIDE_FREE_SLOTS,
    OUTSIDE_BLOCK_SLOTS,
}

data class GateScheduleStatus(
    val enforced: Boolean,
    val reason: GateScheduleReason,
    val matchingSlot: GateTimeSlot? = null,
    val pauseUntilEpochMs: Long = 0L,
    val nextChange: GateScheduleTransition? = null,
)

/**
 * Evaluates whether the app gate should intercept apps right now, combining an optional
 * quick-pause timer with recurring weekly [GateTimeSlot]s:
 * 1. An active pause timer ([pauseUntilEpochMs] > [nowEpochMs]) always leaves apps open.
 * 2. If [scheduleEnabled] is false or no enabled slots exist, the gate is always enforced.
 * 3. Any matching [GateSlotKind.FREE] slot overrides [GateSlotKind.BLOCK] slots and leaves apps open.
 * 4. If at least one [GateSlotKind.BLOCK] slot is enabled, apps are locked only inside those slots.
 * 5. If only [GateSlotKind.FREE] slots are enabled, apps are locked outside of them.
 */
object GateScheduleEvaluator {
    fun evaluate(
        scheduleEnabled: Boolean,
        slots: List<GateTimeSlot>,
        dayOfWeek: Int,
        minuteOfDay: Int,
        nowEpochMs: Long = 0L,
        pauseUntilEpochMs: Long = 0L,
    ): GateScheduleStatus {
        if (pauseUntilEpochMs > nowEpochMs) {
            return GateScheduleStatus(
                enforced = false,
                reason = GateScheduleReason.PAUSED,
                pauseUntilEpochMs = pauseUntilEpochMs,
            )
        }
        val safeDay = dayOfWeek.coerceIn(1, 7)
        val safeMinute = minuteOfDay.coerceIn(0, GateTimeSlot.MINUTES_PER_DAY - 1)
        val activeSlots = if (scheduleEnabled) {
            slots.filter { it.enabled && it.daysOfWeek.isNotEmpty() }
        } else {
            emptyList()
        }
        if (activeSlots.isEmpty()) {
            return GateScheduleStatus(
                enforced = true,
                reason = GateScheduleReason.ALWAYS_BLOCKED,
            )
        }

        val freeSlots = activeSlots.filter { it.kind == GateSlotKind.FREE }
        val blockSlots = activeSlots.filter { it.kind == GateSlotKind.BLOCK }

        val (enforced, reason, matching) = evaluateSlots(freeSlots, blockSlots, safeDay, safeMinute)
        val nextChange = findNextTransition(freeSlots, blockSlots, enforced, safeDay, safeMinute)
        return GateScheduleStatus(
            enforced = enforced,
            reason = reason,
            matchingSlot = matching,
            nextChange = nextChange,
        )
    }

    private fun evaluateSlots(
        freeSlots: List<GateTimeSlot>,
        blockSlots: List<GateTimeSlot>,
        dayOfWeek: Int,
        minuteOfDay: Int,
    ): Triple<Boolean, GateScheduleReason, GateTimeSlot?> {
        freeSlots.firstOrNull { it.matches(dayOfWeek, minuteOfDay) }?.let { freeMatch ->
            return Triple(false, GateScheduleReason.IN_FREE_SLOT, freeMatch)
        }
        if (blockSlots.isNotEmpty()) {
            val blockMatch = blockSlots.firstOrNull { it.matches(dayOfWeek, minuteOfDay) }
            return if (blockMatch != null) {
                Triple(true, GateScheduleReason.IN_BLOCK_SLOT, blockMatch)
            } else {
                Triple(false, GateScheduleReason.OUTSIDE_BLOCK_SLOTS, null)
            }
        }
        return Triple(true, GateScheduleReason.OUTSIDE_FREE_SLOTS, null)
    }

    private fun findNextTransition(
        freeSlots: List<GateTimeSlot>,
        blockSlots: List<GateTimeSlot>,
        currentEnforced: Boolean,
        dayOfWeek: Int,
        minuteOfDay: Int,
    ): GateScheduleTransition? {
        val startTotal = (dayOfWeek - 1) * GateTimeSlot.MINUTES_PER_DAY + minuteOfDay
        for (offset in 1..GateTimeSlot.MINUTES_PER_WEEK) {
            val wrapped = (startTotal + offset) % GateTimeSlot.MINUTES_PER_WEEK
            val candidateDay = (wrapped / GateTimeSlot.MINUTES_PER_DAY) + 1
            val candidateMinute = wrapped % GateTimeSlot.MINUTES_PER_DAY
            val candidateEnforced = evaluateSlots(freeSlots, blockSlots, candidateDay, candidateMinute).first
            if (candidateEnforced != currentEnforced) {
                return GateScheduleTransition(
                    dayOfWeek = candidateDay,
                    minuteOfDay = candidateMinute,
                    minutesUntil = offset,
                )
            }
        }
        return null
    }

    fun formatRemainingMinutes(remainingMs: Long): String {
        if (remainingMs <= 0L) return "0 Min."
        val totalMinutes = ((remainingMs + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
        val hours = totalMinutes / 60
        val mins = totalMinutes % 60
        return when {
            hours > 0 && mins > 0 -> "$hours Std. $mins Min."
            hours > 0 -> "$hours Std."
            else -> "$mins Min."
        }
    }
}
