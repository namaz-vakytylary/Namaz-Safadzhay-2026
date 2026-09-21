package ru.namaz.safadzhay

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CalendarTest {
    @Test fun fridayBannerStartsAtMidnightAndEndsExactlyAtSelectedAsr() {
        val date = LocalDate.of(2026, 9, 18)
        val asr = java.time.LocalTime.of(15, 30)
        assertTrue(HolidayCalendar.bannerLabels(date, date.atStartOfDay(), asr).contains("Джума-намаз"))
        assertTrue(HolidayCalendar.bannerLabels(date, date.atTime(asr).minusNanos(1), asr).contains("Джума-намаз"))
        assertFalse(HolidayCalendar.bannerLabels(date, date.atTime(asr), asr).contains("Джума-намаз"))
        assertFalse(HolidayCalendar.bannerLabels(date, date.atTime(23, 59), asr).contains("Джума-намаз"))
        assertTrue(HolidayCalendar.bannerLabels(date, date.atTime(asr), asr.plusHours(1)).contains("Джума-намаз"))
    }
    @Test fun bannerCutoffPreservesHolidaysAndOtherCalendarDates() {
        val date = LocalDate.of(2026, 3, 20)
        val asr = java.time.LocalTime.of(15, 30)
        assertEquals(listOf("Ураза-байрам"), HolidayCalendar.bannerLabels(date, date.atTime(asr), asr))
        assertEquals(listOf("Джума-намаз", "Ураза-байрам"), HolidayCalendar.bannerLabels(date, date.atStartOfDay(), null))
        assertEquals(listOf("Джума-намаз", "Ураза-байрам"), HolidayCalendar.bannerLabels(date, date.minusDays(1).atTime(23, 59), asr))
    }
    @Test fun fridayWithoutDownloadedAsrDoesNotDisappearAtMidnight() {
        val date = LocalDate.of(2027, 1, 1)
        assertEquals(listOf("Джума-намаз"), HolidayCalendar.bannerLabels(date, date.atStartOfDay(), null))
        assertEquals(listOf("Джума-намаз"), HolidayCalendar.bannerLabels(date, date.atTime(9, 0), null))
    }
    @Test fun eventsUseOnlyDumRf2026() {
        assertTrue(HolidayCalendar.items.all { it.date.year == 2026 && it.sourceName == "ДУМ РФ, календарь 2026" })
        assertEquals(LocalDate.of(2026, 8, 24), HolidayCalendar.items.single { it.title == "Маулид" }.date)
        assertFalse(HolidayCalendar.labels(LocalDate.of(2026, 8, 25)).contains("Маулид"))
    }
    @Test fun fridayAndHolidayAreBothPresentInOneDay() {
        assertEquals(listOf("Джума-намаз", "Ураза-байрам"), HolidayCalendar.labels(LocalDate.of(2026, 3, 20)))
    }
    @Test fun ordinaryDayHasNoBannerContent() {
        assertTrue(HolidayCalendar.labels(LocalDate.of(2026, 9, 8)).isEmpty())
    }
    @Test fun regularFridayHasOnlyFridayLabel() {
        assertEquals(listOf("Джума-намаз"), HolidayCalendar.labels(LocalDate.of(2026, 9, 4)))
    }
    @Test fun eventDatesAreSortedAndTitlesNotDuplicatedPerDay() {
        assertEquals(HolidayCalendar.items.sortedBy { it.date }, HolidayCalendar.items)
        HolidayCalendar.items.groupBy { it.date }.forEach { (_, events) -> assertEquals(events.size, events.map { it.title }.toSet().size) }
    }
    @Test fun nightDisplayIncludesPreviousCivilDate() {
        val event = HolidayCalendar.items.first { it.title == "Ночь Мирадж" }
        assertTrue(event.displayDate.contains("15 января 2026"))
        assertTrue(event.displayDate.contains("16 января 2026"))
    }
}
