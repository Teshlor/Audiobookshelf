package com.teshlor.abstv

import java.util.Calendar
import java.util.GregorianCalendar
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeRulesTest {
    private fun on(y: Int, m: Int, d: Int): AbsTheme = themeFor(GregorianCalendar(y, m - 1, d))

    @Test fun handoffAcceptanceDates() {
        assertEquals(AbsTheme.AUTUMN, on(2026, 10, 24))
        assertEquals(AbsTheme.HALLOWEEN, on(2026, 10, 25))
        assertEquals(AbsTheme.HALLOWEEN, on(2026, 10, 31))
        assertEquals(AbsTheme.AUTUMN, on(2026, 11, 1))
        assertEquals(AbsTheme.AUTUMN, on(2026, 11, 22))
        assertEquals(AbsTheme.THANKSGIVING, on(2026, 11, 23))
        assertEquals(AbsTheme.THANKSGIVING, on(2026, 11, 29))
        assertEquals(AbsTheme.CHRISTMAS, on(2026, 11, 30))
        assertEquals(AbsTheme.CHRISTMAS, on(2026, 12, 26))
        assertEquals(AbsTheme.WINTER, on(2026, 12, 27))
        assertEquals(AbsTheme.SPRING, on(2027, 3, 1))
        assertEquals(AbsTheme.SUMMER, on(2027, 6, 1))
        assertEquals(AbsTheme.AUTUMN, on(2027, 9, 1))
        assertEquals(AbsTheme.WINTER, on(2028, 2, 29))
        assertEquals(AbsTheme.CHRISTMAS, on(2029, 11, 26)) // T = Nov 22
        assertEquals(AbsTheme.THANKSGIVING, on(2030, 12, 1)) // T = Nov 28
        assertEquals(AbsTheme.CHRISTMAS, on(2030, 12, 2))
    }

    @Test fun thanksgivingWindowEdges() {
        // 2029: T = Nov 22, window Nov 19..25
        assertEquals(AbsTheme.AUTUMN, on(2029, 11, 18))
        assertEquals(AbsTheme.THANKSGIVING, on(2029, 11, 19))
        assertEquals(AbsTheme.THANKSGIVING, on(2029, 11, 25))
    }

    @Test fun extraMoments() {
        assertEquals(AbsTheme.WINTER, on(2026, 12, 30))
        assertEquals(AbsTheme.NEW_YEAR, on(2026, 12, 31))
        assertEquals(AbsTheme.NEW_YEAR, on(2027, 1, 1))
        assertEquals(AbsTheme.WINTER, on(2027, 1, 2))
        assertEquals(AbsTheme.WINTER, on(2027, 1, 14))
        assertEquals(AbsTheme.AURORA, on(2027, 1, 15))
        assertEquals(AbsTheme.AURORA, on(2027, 1, 31))
        assertEquals(AbsTheme.WINTER, on(2027, 2, 1))
        assertEquals(AbsTheme.WINTER, on(2027, 2, 11))
        assertEquals(AbsTheme.VALENTINE, on(2027, 2, 12))
        assertEquals(AbsTheme.VALENTINE, on(2027, 2, 14))
        assertEquals(AbsTheme.WINTER, on(2027, 2, 15))
        assertEquals(AbsTheme.SUMMER, on(2027, 6, 20))
        assertEquals(AbsTheme.FIREFLIES, on(2027, 6, 21))
        assertEquals(AbsTheme.FIREFLIES, on(2027, 6, 30))
        assertEquals(AbsTheme.JULY_4, on(2027, 7, 1))
        assertEquals(AbsTheme.JULY_4, on(2027, 7, 4))
        assertEquals(AbsTheme.SUMMER, on(2027, 7, 5))
    }

    @Test fun seasonBoundaries() {
        assertEquals(AbsTheme.WINTER, on(2027, 2, 28))
        assertEquals(AbsTheme.SPRING, on(2027, 5, 31))
        assertEquals(AbsTheme.SUMMER, on(2027, 8, 31))
        assertEquals(AbsTheme.AUTUMN, on(2027, 9, 30))
    }

    @Test fun everyDayOfAYearResolves() {
        // Guards against exceptions and gaps in leap and non-leap years.
        for (y in 2024..2031) {
            val c = GregorianCalendar(y, Calendar.JANUARY, 1)
            while (c.get(Calendar.YEAR) == y) {
                themeFor(c)
                c.add(Calendar.DAY_OF_YEAR, 1)
            }
        }
    }

    @Test fun debugKeysRoundTrip() {
        AbsTheme.entries.forEach { assertEquals(it, AbsTheme.fromKey(it.key)) }
        assertEquals(null, AbsTheme.fromKey("nope"))
        assertEquals(null, AbsTheme.fromKey(null))
    }
}
