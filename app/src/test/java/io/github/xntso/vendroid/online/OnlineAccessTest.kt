package io.github.xntso.vendroid.online

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.Calendar
import java.util.GregorianCalendar

class OnlineAccessTest {
    private val today: Calendar = GregorianCalendar(2026, Calendar.OCTOBER, 13)

    @Test
    fun `unknown sessions deny online access until a valid date is supplied`() {
        val session = OnlineAccessSession()
        assertEquals(OnlineEligibility.UNKNOWN, session.eligibility)
        assertNull(session.submitBirthDate("", "", "", today))
        assertEquals(OnlineEligibility.UNKNOWN, session.eligibility)
    }

    @Test
    fun `GitHub eligibility changes on the thirteenth birthday rather than adulthood`() {
        assertEquals(OnlineEligibility.RESTRICTED, eligibilityForBirthDate("14", "10", "2013", today))
        assertEquals(OnlineEligibility.ELIGIBLE, eligibilityForBirthDate("13", "10", "2013", today))
        assertEquals(OnlineEligibility.ELIGIBLE, eligibilityForBirthDate("14", "10", "2012", today))
        assertEquals(OnlineEligibility.ELIGIBLE, eligibilityForBirthDate("13", "10", "2010", today))
        assertEquals(OnlineEligibility.RESTRICTED, eligibilityForBirthDate("1", "1", "2018", today))
    }

    @Test
    fun `invalid and future dates leave the session denied`() {
        val session = OnlineAccessSession()
        val invalidDates = listOf(
            Triple("29", "2", "2021"), Triple("31", "4", "2000"),
            Triple("0", "10", "2000"), Triple("1", "13", "2000"),
            Triple("1", "1", "0"), Triple("1", "1", "2027"),
            Triple("14", "10", "2026"), Triple("x", "1", "2000"),
        )
        for ((day, month, year) in invalidDates) {
            assertNull(session.submitBirthDate(day, month, year, today))
            assertEquals(OnlineEligibility.UNKNOWN, session.eligibility)
        }
        assertEquals(OnlineEligibility.RESTRICTED, session.submitBirthDate("29", "2", "2020", today))
    }

    @Test
    fun `a new process session never inherits eligibility or a birth date`() {
        val previous = OnlineAccessSession()
        assertEquals(OnlineEligibility.ELIGIBLE, previous.submitBirthDate("1", "1", "2000", today))
        assertEquals(OnlineEligibility.ELIGIBLE, previous.eligibility)
        assertEquals(OnlineEligibility.UNKNOWN, OnlineAccessSession().eligibility)
    }
}
