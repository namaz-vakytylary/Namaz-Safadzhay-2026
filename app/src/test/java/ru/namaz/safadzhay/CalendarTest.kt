package ru.namaz.safadzhay

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CalendarTest {
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
