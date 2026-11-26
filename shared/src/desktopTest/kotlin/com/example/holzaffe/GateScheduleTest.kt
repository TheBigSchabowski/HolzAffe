package com.example.holzaffe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GateScheduleTest {
    @Test
    fun codecRoundTripAndMalformedHandling() {
        val slots = listOf(
            GateTimeSlot(
                id = "s1",
                label = "Arbeit",
                kind = GateSlotKind.FREE,
                startMinute = 8 * 60,
                endMinute = 17 * 60,
                daysOfWeek = GateTimeSlot.WEEKDAYS,
                enabled = true,
            ),
            GateTimeSlot(
                id = "s2",
                label = "",
                kind = GateSlotKind.BLOCK,
                startMinute = 22 * 60,
                endMinute = 7 * 60,
                daysOfWeek = setOf(6, 7),
                enabled = false,
            ),
            GateTimeSlot(
                id = "s3",
                label = "Test\u001Emit\u001FTrenner",
                kind = GateSlotKind.BLOCK,
                startMinute = 0,
                endMinute = 0,
                daysOfWeek = GateTimeSlot.ALL_DAYS,
                enabled = true,
            ),
        )
        val encoded = GateTimeSlotCodec.encode(slots)
        val decoded = GateTimeSlotCodec.decode(encoded)
        assertEquals(3, decoded.size)
        assertEquals(slots[0], decoded[0])
        assertEquals(slots[1], decoded[1])
        assertEquals("Test mit Trenner", decoded[2].label)

        // Malformed, duplicate IDs, and empty days are skipped; minutes clamp into 0..1439.
        val raw = listOf(
            "bad\u001Fonly_two",
            "\u001FNoId\u001Fblock\u001F60\u001F120\u001F1,2\u001F1",
            "dup\u001FErstes\u001Ffree\u001F-10\u001F2000\u001F1,9,3\u001F1",
            "dup\u001FZweites\u001Fblock\u001F100\u001F200\u001F2\u001F1",
            "nodays\u001FLeer\u001Fblock\u001F100\u001F200\u001F99\u001F1",
        ).joinToString("\u001E")
        val cleaned = GateTimeSlotCodec.decode(raw)
        assertEquals(
            listOf(
                GateTimeSlot(
                    id = "dup",
                    label = "Erstes",
                    kind = GateSlotKind.FREE,
                    startMinute = 0,
                    endMinute = GateTimeSlot.MINUTES_PER_DAY - 1,
                    daysOfWeek = setOf(1, 3),
                    enabled = true,
                )
            ),
            cleaned,
        )
        assertEquals(emptyList(), GateTimeSlotCodec.decode("   "))
    }

    @Test
    fun slotMatchesBoundariesMidnightAndOvernight() {
        val work = GateTimeSlot(
            id = "work",
            label = "Arbeit",
            kind = GateSlotKind.FREE,
            startMinute = 8 * 60,
            endMinute = 17 * 60,
            daysOfWeek = GateTimeSlot.WEEKDAYS,
        )
        assertFalse(work.matches(1, 7 * 60 + 59))
        assertTrue(work.matches(1, 8 * 60))
        assertTrue(work.matches(3, 16 * 60 + 59))
        assertFalse(work.matches(3, 17 * 60))
        assertFalse(work.matches(6, 10 * 60))

        // 17:00 - 00:00 covers 17:00..23:59 on the selected days only.
        val eveningUntilMidnight = GateTimeSlot(
            id = "eve",
            label = "Abend",
            kind = GateSlotKind.BLOCK,
            startMinute = 17 * 60,
            endMinute = 0,
            daysOfWeek = setOf(3),
        )
        assertFalse(eveningUntilMidnight.wrapsOvernight)
        assertFalse(eveningUntilMidnight.matches(3, 16 * 60 + 59))
        assertTrue(eveningUntilMidnight.matches(3, 17 * 60))
        assertTrue(eveningUntilMidnight.matches(3, 23 * 60 + 59))
        assertFalse(eveningUntilMidnight.matches(4, 0))

        // Overnight 22:00 - 07:00 on Mo..Fr carries Friday night into Saturday 06:59, not Monday 03:00.
        val night = GateTimeSlot(
            id = "night",
            label = "Nacht",
            kind = GateSlotKind.BLOCK,
            startMinute = 22 * 60,
            endMinute = 7 * 60,
            daysOfWeek = GateTimeSlot.WEEKDAYS,
        )
        assertTrue(night.wrapsOvernight)
        assertTrue(night.matches(5, 22 * 60))
        assertTrue(night.matches(6, 6 * 60 + 59))
        assertFalse(night.matches(6, 7 * 60))
        assertFalse(night.matches(1, 3 * 60))
        assertTrue(night.matches(2, 3 * 60))

        // All-day 00:00 - 00:00 covers full selected days.
        val allDay = GateTimeSlot(
            id = "all",
            label = "Sonntag",
            kind = GateSlotKind.FREE,
            startMinute = 0,
            endMinute = 0,
            daysOfWeek = setOf(7),
        )
        assertTrue(allDay.isAllDay)
        assertEquals("Ganztägig", allDay.formatTimeRange())
        assertTrue(allDay.matches(7, 0))
        assertTrue(allDay.matches(7, 23 * 60 + 59))
        assertFalse(allDay.matches(1, 0))

        // Disabled slot never matches.
        assertFalse(work.copy(enabled = false).matches(1, 10 * 60))
    }

    @Test
    fun evaluatorHandlesDefaultDisabledFreeBlockMixedAndPause() {
        // Default empty schedule -> always blocked.
        val emptyStatus = GateScheduleEvaluator.evaluate(
            scheduleEnabled = true,
            slots = emptyList(),
            dayOfWeek = 3,
            minuteOfDay = 10 * 60,
        )
        assertTrue(emptyStatus.enforced)
        assertEquals(GateScheduleReason.ALWAYS_BLOCKED, emptyStatus.reason)
        assertNull(emptyStatus.nextChange)

        val workFree = GateTimeSlot(
            id = "work",
            label = "Arbeit",
            kind = GateSlotKind.FREE,
            startMinute = 8 * 60,
            endMinute = 17 * 60,
            daysOfWeek = GateTimeSlot.WEEKDAYS,
        )
        // Schedule disabled -> always blocked even inside a FREE slot.
        val disabledSchedule = GateScheduleEvaluator.evaluate(
            scheduleEnabled = false,
            slots = listOf(workFree),
            dayOfWeek = 3,
            minuteOfDay = 10 * 60,
        )
        assertTrue(disabledSchedule.enforced)
        assertEquals(GateScheduleReason.ALWAYS_BLOCKED, disabledSchedule.reason)
        assertNull(disabledSchedule.nextChange)

        // Only FREE slot (Mo-Fr 08:00-17:00): Wed 10:00 is free until 17:00.
        val wedWork = GateScheduleEvaluator.evaluate(true, listOf(workFree), 3, 10 * 60)
        assertFalse(wedWork.enforced)
        assertEquals(GateScheduleReason.IN_FREE_SLOT, wedWork.reason)
        assertEquals(workFree, wedWork.matchingSlot)
        assertEquals("17:00", wedWork.nextChange?.formatRelative(3))

        // Wed 17:00 is locked until tomorrow 08:00.
        val wedEvening = GateScheduleEvaluator.evaluate(true, listOf(workFree), 3, 17 * 60)
        assertTrue(wedEvening.enforced)
        assertEquals(GateScheduleReason.OUTSIDE_FREE_SLOTS, wedEvening.reason)
        assertEquals("morgen 08:00", wedEvening.nextChange?.formatRelative(3))

        // Fri 17:00 is locked until Mo 08:00.
        val friEvening = GateScheduleEvaluator.evaluate(true, listOf(workFree), 5, 17 * 60)
        assertTrue(friEvening.enforced)
        assertEquals("Mo 08:00", friEvening.nextChange?.formatRelative(5))

        // Only BLOCK slots: open outside them, blocked inside them.
        val eveningBlock = GateTimeSlot(
            id = "eve",
            label = "Feierabend",
            kind = GateSlotKind.BLOCK,
            startMinute = 17 * 60,
            endMinute = 23 * 60,
            daysOfWeek = GateTimeSlot.WEEKDAYS,
        )
        val weekendBlock = GateTimeSlot(
            id = "wknd",
            label = "Wochenende",
            kind = GateSlotKind.BLOCK,
            startMinute = 9 * 60,
            endMinute = 23 * 60,
            daysOfWeek = GateTimeSlot.WEEKEND,
        )
        val wedMorning = GateScheduleEvaluator.evaluate(true, listOf(eveningBlock, weekendBlock), 3, 10 * 60)
        assertFalse(wedMorning.enforced)
        assertEquals(GateScheduleReason.OUTSIDE_BLOCK_SLOTS, wedMorning.reason)
        assertEquals("17:00", wedMorning.nextChange?.formatRelative(3))

        val wedNightBlock = GateScheduleEvaluator.evaluate(true, listOf(eveningBlock, weekendBlock), 3, 19 * 60)
        assertTrue(wedNightBlock.enforced)
        assertEquals(GateScheduleReason.IN_BLOCK_SLOT, wedNightBlock.reason)
        assertEquals(eveningBlock, wedNightBlock.matchingSlot)
        assertEquals("23:00", wedNightBlock.nextChange?.formatRelative(3))

        val satDayBlock = GateScheduleEvaluator.evaluate(true, listOf(eveningBlock, weekendBlock), 6, 11 * 60)
        assertTrue(satDayBlock.enforced)
        assertEquals(GateScheduleReason.IN_BLOCK_SLOT, satDayBlock.reason)
        assertEquals("23:00", satDayBlock.nextChange?.formatRelative(6))

        // Mixed: daily BLOCK 07:00-23:00 + FREE Mo-Fr 08:30-17:00 -> FREE wins on Wed 12:00, Sat 12:00 stays blocked.
        val dailyBlock = GateTimeSlot(
            id = "daily",
            label = "Tag",
            kind = GateSlotKind.BLOCK,
            startMinute = 7 * 60,
            endMinute = 23 * 60,
            daysOfWeek = GateTimeSlot.ALL_DAYS,
        )
        val mixedWorkFree = GateTimeSlot(
            id = "work2",
            label = "Arbeit",
            kind = GateSlotKind.FREE,
            startMinute = 8 * 60 + 30,
            endMinute = 17 * 60,
            daysOfWeek = GateTimeSlot.WEEKDAYS,
        )
        val wedNoon = GateScheduleEvaluator.evaluate(true, listOf(dailyBlock, mixedWorkFree), 3, 12 * 60)
        assertFalse(wedNoon.enforced)
        assertEquals(GateScheduleReason.IN_FREE_SLOT, wedNoon.reason)
        assertEquals("17:00", wedNoon.nextChange?.formatRelative(3))

        val wedAfterWork = GateScheduleEvaluator.evaluate(true, listOf(dailyBlock, mixedWorkFree), 3, 17 * 60)
        assertTrue(wedAfterWork.enforced)
        assertEquals(GateScheduleReason.IN_BLOCK_SLOT, wedAfterWork.reason)
        assertEquals("23:00", wedAfterWork.nextChange?.formatRelative(3))

        val satNoon = GateScheduleEvaluator.evaluate(true, listOf(dailyBlock, mixedWorkFree), 6, 12 * 60)
        assertTrue(satNoon.enforced)
        assertEquals(GateScheduleReason.IN_BLOCK_SLOT, satNoon.reason)
        assertEquals("23:00", satNoon.nextChange?.formatRelative(6))

        // Quick pause overrides enforced schedule; expired pause falls back to schedule.
        val paused = GateScheduleEvaluator.evaluate(
            scheduleEnabled = true,
            slots = listOf(dailyBlock),
            dayOfWeek = 6,
            minuteOfDay = 12 * 60,
            nowEpochMs = 1_000_000L,
            pauseUntilEpochMs = 1_600_000L,
        )
        assertFalse(paused.enforced)
        assertEquals(GateScheduleReason.PAUSED, paused.reason)
        assertEquals(1_600_000L, paused.pauseUntilEpochMs)

        val expiredPause = GateScheduleEvaluator.evaluate(
            scheduleEnabled = true,
            slots = listOf(dailyBlock),
            dayOfWeek = 6,
            minuteOfDay = 12 * 60,
            nowEpochMs = 1_600_000L,
            pauseUntilEpochMs = 1_600_000L,
        )
        assertTrue(expiredPause.enforced)
        assertEquals(GateScheduleReason.IN_BLOCK_SLOT, expiredPause.reason)

        // Constant 24/7 slot has no transition.
        val fullWeek = GateTimeSlot("always", "Immer", GateSlotKind.BLOCK, 0, 0, GateTimeSlot.ALL_DAYS)
        val constantStatus = GateScheduleEvaluator.evaluate(true, listOf(fullWeek), 2, 14 * 60)
        assertTrue(constantStatus.enforced)
        assertNull(constantStatus.nextChange)
    }

    @Test
    fun formattingHelpersProduceGermanLabels() {
        assertEquals("Täglich", GateTimeSlot.formatDays(GateTimeSlot.ALL_DAYS))
        assertEquals("Mo–Fr", GateTimeSlot.formatDays(GateTimeSlot.WEEKDAYS))
        assertEquals("Sa–So", GateTimeSlot.formatDays(GateTimeSlot.WEEKEND))
        assertEquals("Mo–Do", GateTimeSlot.formatDays(setOf(1, 2, 3, 4)))
        assertEquals("Mo, Mi, Fr", GateTimeSlot.formatDays(setOf(1, 3, 5)))
        assertEquals("08:05", GateTimeSlot.formatMinute(8 * 60 + 5))
        assertEquals("08:00 – 17:00", GateTimeSlot("s", "", GateSlotKind.FREE, 480, 1020).formatTimeRange())
        assertEquals("Ohne Sperre", GateTimeSlot("s", "  ", GateSlotKind.FREE, 480, 1020).displayLabel())
        assertEquals("Sperr-Zeit", GateTimeSlot("s", "", GateSlotKind.BLOCK, 480, 1020).displayLabel())

        assertEquals("1 Std. 24 Min.", GateScheduleEvaluator.formatRemainingMinutes(84 * 60_000L))
        assertEquals("2 Std.", GateScheduleEvaluator.formatRemainingMinutes(120 * 60_000L))
        assertEquals("15 Min.", GateScheduleEvaluator.formatRemainingMinutes(15 * 60_000L))
        assertEquals("1 Min.", GateScheduleEvaluator.formatRemainingMinutes(1_000L))
        assertEquals("0 Min.", GateScheduleEvaluator.formatRemainingMinutes(0L))

        val sameDay = GateScheduleTransition(dayOfWeek = 3, minuteOfDay = 17 * 60, minutesUntil = 60)
        assertEquals("17:00", sameDay.formatRelative(3))
        val nextDay = GateScheduleTransition(dayOfWeek = 4, minuteOfDay = 8 * 60, minutesUntil = 900)
        assertEquals("morgen 08:00", nextDay.formatRelative(3))
        val sameDayNextWeek = GateScheduleTransition(dayOfWeek = 3, minuteOfDay = 8 * 60, minutesUntil = 7 * 1440 - 60)
        assertNotNull(sameDayNextWeek.formatRelative(3))
        assertEquals("Mi 08:00", sameDayNextWeek.formatRelative(3))
    }
}
