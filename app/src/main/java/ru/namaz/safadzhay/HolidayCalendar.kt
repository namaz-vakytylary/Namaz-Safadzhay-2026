package ru.namaz.safadzhay

import java.time.LocalDate
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class Holiday(
    val date: LocalDate,
    val title: String,
    val description: String,
    val sourceName: String,
    val night: Boolean = false,
    val hijriDate: String = ""
) {
    val displayDate: String get() {
        val fmt = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("ru"))
        return if (night) {
            "Ночь ${date.minusDays(1).format(fmt)} — ${date.format(fmt)}"
        } else {
            date.format(fmt)
        }
    }
}

internal object HolidayCalendar {
    @Volatile
private var remoteByYear: Map<Int, List<Holiday>> = emptyMap()

fun setRemote(year: Int, holidays: List<Holiday>?) {
    remoteByYear = if (holidays == null) {
        remoteByYear - year
    } else {
        remoteByYear + (year to holidays.sortedBy { it.date })
    }
}

private fun holidaysFor(year: Int): List<Holiday> =
    remoteByYear[year] ?: items.filter { it.date.year == year }
    
private fun rf(date: String, title: String, text: String, night: Boolean = false): Holiday {
    val parsedDate = LocalDate.parse(date)
    return Holiday(
        parsedDate,
        title,
        text,
        "ДУМ РФ, календарь ${parsedDate.year}",
        night
    )
}
    val items = listOf(
        rf("2026-01-16", "Ночь Мирадж", "Памятная ночь Исра и Мирадж.", true),
        rf("2026-02-03", "Ночь Бараат", "Памятная ночь середины месяца Шаабан.", true),
        rf("2026-02-19", "Начало Рамадана", "Начало месяца поста."),
        rf("2026-03-20", "Ураза-байрам", "Праздник окончания поста Рамадана."),
        rf("2026-05-26", "День Арафа", "День перед Курбан-байрамом."),
        rf("2026-05-27", "Курбан-байрам", "Праздник жертвоприношения."),
        rf("2026-06-16", "Начало года по Хиджре", "Начало 1448 года по Хиджре."),
        rf("2026-06-25", "День Ашура", "Десятый день месяца Мухаррам."),
        rf("2026-08-24", "Маулид", "Памятная дата рождения Пророка Мухаммада ﷺ.", true),
        ).sortedBy { it.date }

    fun holidayFor(date: LocalDate): Holiday? =
    holidaysFor(date.year).firstOrNull { it.date == date }

    fun labels(date: LocalDate): List<String> = buildList {
    if (date.dayOfWeek == DayOfWeek.FRIDAY) add("Джума-намаз")
    addAll(holidaysFor(date.year).filter { it.date == date }.map { it.title })
}

   fun bannerLabels(date: LocalDate, now: java.time.LocalDateTime, asr: java.time.LocalTime?): List<String> {
    if (date.dayOfWeek != DayOfWeek.FRIDAY) return emptyList()

    if (date == now.toLocalDate() &&
        asr != null &&
        !now.toLocalTime().isBefore(asr)
    ) {
        return emptyList()
    }

    return listOf("Джума-намаз")
}
}
