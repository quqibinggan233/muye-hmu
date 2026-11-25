package com.fengkao.timetable.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import com.fengkao.timetable.data.local.AppSettings
import com.fengkao.timetable.data.model.CourseBlock

class TermCalculatorTest {
    @Test fun autumnRolloverDoesNotReusePreviousSpringTerm() {
        val spring = TermSpec(2025, 2)
        val autumn = TermCalculator.autoTerm(LocalDate.of(2026, 10, 7))
        assertEquals(TermSpec(2026, 1), autumn)
        assertFalse(TermCalculator.shouldReplaceLatest(autumn, spring))
        assertTrue(TermCalculator.shouldReplaceLatest(spring, autumn))
    }

    @Test fun sameTermRefreshCanReplaceItsOwnCache() {
        assertTrue(TermCalculator.shouldReplaceLatest(TermSpec(2026, 1), TermSpec(2026, 1)))
        assertTrue(TermCalculator.shouldReplaceLatest(null, TermSpec(2026, 1)))
    }

    @Test fun earlySemesterAlreadyCachedBeatsCalendarEstimate() {
        assertEquals(TermSpec(2026, 1),
            TermCalculator.automaticTarget(TermSpec(2025, 2), TermSpec(2026, 1)))
        assertEquals(TermSpec(2026, 1),
            TermCalculator.automaticTarget(TermSpec(2026, 1), TermSpec(2025, 2)))
    }

    @Test fun previousSemesterStartDateCannotShiftCurrentWeek() {
        val previousStart = LocalDate.of(2026, 3, 2).toEpochDay()
        val current = TermSpec(2026, 1)
        val settings = AppSettings(termStartEpochDay = previousStart, termStartXnm = 2025, termStartTerm = 2)
        assertEquals(TermCalculator.defaultTermStart(2026, 1), settings.termStartFor(current))
        val manual = LocalDate.of(2026, 9, 7)
        assertEquals(manual, settings.copy(termStartEpochDay = manual.toEpochDay(),
            termStartXnm = 2026, termStartTerm = 1).termStartFor(current))
    }

    private fun course(vararg weeks: Int) = CourseBlock(
        name = "课程",
        place = "A101",
        teacher = "教师",
        campus = "龙子湖",
        dayOfWeek = 1,
        startPeriod = 1,
        endPeriod = 2,
        weeks = weeks.toList(),
        weeksText = "",
    )

    @Test fun inferTermWeeksPrefersActualCourseMaxOverTemplateFallback() {
        val courses = listOf(course(1, 2, 16), course(1, 3, 5, 15))
        assertEquals(16, TermCalculator.inferTermWeeks(courses, fallbackMaxWeek = 20))
    }

    @Test fun inferTermWeeksFindsMaxAcrossOddEvenAndLateCourses() {
        val courses = listOf(
            course(1, 2, 3, 4, 5, 6, 7, 8),
            course(2, 4, 6, 8, 10, 12, 14),
            course(9, 11, 13, 15, 17, 18),
        )
        assertEquals(18, TermCalculator.inferTermWeeks(courses, fallbackMaxWeek = 16))
    }

    @Test fun inferTermWeeksFallsBackWhenNoValidWeeks() {
        assertEquals(20, TermCalculator.inferTermWeeks(emptyList(), fallbackMaxWeek = 20))
        assertEquals(19, TermCalculator.inferTermWeeks(listOf(course()), fallbackMaxWeek = 19))
        assertNull(TermCalculator.inferTermWeeks(emptyList(), fallbackMaxWeek = null))
    }

    @Test fun inferTermWeeksFiltersOutOfRangeAndClampsMinimum() {
        val courses = listOf(course(-1, 0, 2, 35))
        assertEquals(4, TermCalculator.inferTermWeeks(courses, fallbackMaxWeek = 20))
    }
}
