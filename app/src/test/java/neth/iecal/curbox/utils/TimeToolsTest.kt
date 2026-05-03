package neth.iecal.curbox.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeToolsTest {

    // --- convertToMinutesFromMidnight / convertMinutesTo24Hour ---

    @Test
    fun convertToMinutesFromMidnight_midnight() {
        assertEquals(0, TimeTools.convertToMinutesFromMidnight(0, 0))
    }

    @Test
    fun convertToMinutesFromMidnight_morning() {
        assertEquals(570, TimeTools.convertToMinutesFromMidnight(9, 30))
    }

    @Test
    fun convertToMinutesFromMidnight_endOfDay() {
        assertEquals(1439, TimeTools.convertToMinutesFromMidnight(23, 59))
    }

    @Test
    fun convertMinutesTo24Hour_roundTripsWithConvertToMinutes() {
        for (h in 0..23) for (m in 0..59 step 7) {
            val total = TimeTools.convertToMinutesFromMidnight(h, m)
            assertEquals(Pair(h, m), TimeTools.convertMinutesTo24Hour(total))
        }
    }

    // --- shortenDate ---

    @Test
    fun shortenDate_fullDate_truncatesMonthToThreeChars() {
        assertEquals("12 Jan", TimeTools.shortenDate("12 January 2026"))
    }

    @Test
    fun shortenDate_shortMonth_remainsUnchanged() {
        assertEquals("3 May", TimeTools.shortenDate("3 May 2026"))
    }

    @Test
    fun shortenDate_singleToken_returnedAsIs() {
        assertEquals("oops", TimeTools.shortenDate("oops"))
    }

    @Test
    fun shortenDate_empty_returnedAsIs() {
        assertEquals("", TimeTools.shortenDate(""))
    }

    // --- formatTime ---

    @Test
    fun formatTime_zero_isEmpty() {
        assertEquals("", TimeTools.formatTime(0L))
    }

    @Test
    fun formatTime_secondsOnly() {
        assertEquals("5 secs", TimeTools.formatTime(5_000L))
    }

    @Test
    fun formatTime_minutesAndSeconds() {
        assertEquals("1 mins 5 secs", TimeTools.formatTime(65_000L))
    }

    @Test
    fun formatTime_hoursMinutesSeconds() {
        assertEquals("1 hr 1 mins 1 secs", TimeTools.formatTime(3_661_000L))
    }

    @Test
    fun formatTime_hoursOnly() {
        assertEquals("2 hr", TimeTools.formatTime(2 * 60 * 60 * 1000L))
    }

    @Test
    fun formatTime_showSecondsFalse_omitsSeconds() {
        assertEquals("1 mins", TimeTools.formatTime(65_000L, showSeconds = false))
    }

    @Test
    fun formatTime_subSecond_isEmpty() {
        // Anything under 1 second has no hr/min/sec component to print and trims to "".
        assertEquals("", TimeTools.formatTime(500L))
    }

    // --- formatTimeInHHMM ---

    @Test
    fun formatTimeInHHMM_zero_isMmSs() {
        assertEquals("00:00", TimeTools.formatTimeInHHMM(0L))
    }

    @Test
    fun formatTimeInHHMM_underAnHour_isMmSs() {
        assertEquals("01:05", TimeTools.formatTimeInHHMM(65_000L))
    }

    @Test
    fun formatTimeInHHMM_atOneHour_includesHours() {
        assertEquals("01:00:00", TimeTools.formatTimeInHHMM(60 * 60 * 1000L))
    }

    @Test
    fun formatTimeInHHMM_overOneHour_includesHours() {
        assertEquals("01:01:01", TimeTools.formatTimeInHHMM(3_661_000L))
    }

    @Test
    fun formatTimeInHHMM_padsSingleDigits() {
        assertEquals("00:09", TimeTools.formatTimeInHHMM(9_000L))
    }

    // --- formatTimeForWidget ---

    @Test
    fun formatTimeForWidget_zero_returnsLessThanAMinute() {
        assertEquals("<1m", TimeTools.formatTimeForWidget(0L))
    }

    @Test
    fun formatTimeForWidget_under1Minute_returnsLessThanAMinute() {
        assertEquals("<1m", TimeTools.formatTimeForWidget(45_000L))
    }

    @Test
    fun formatTimeForWidget_minutesOnly() {
        assertEquals("5m", TimeTools.formatTimeForWidget(5 * 60 * 1000L))
    }

    @Test
    fun formatTimeForWidget_hoursOnly() {
        assertEquals("2h", TimeTools.formatTimeForWidget(2 * 60 * 60 * 1000L))
    }

    @Test
    fun formatTimeForWidget_hoursAndMinutes() {
        assertEquals("1h1m", TimeTools.formatTimeForWidget(3_660_000L))
    }
}
