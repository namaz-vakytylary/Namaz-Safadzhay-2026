package ru.namaz.safadzhay

import android.content.Context
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** Artificial UI fixture for debug and signed TEST APKs only.
 * Production release keeps its fixture-free source set. Does not modify HolidayCalendar. */
internal object RamadanUiPreview {
    const val available = true
    const val TEST_RAMADAN_START_DATE = "2026-08-10"
    private const val KEY = "debug_ramadan_ui_scene"
    private val start = LocalDate.parse(TEST_RAMADAN_START_DATE)
    val scenes = listOf(
        UiPreviewScene("off", "Текущее время устройства", null),
        UiPreviewScene("before", "09.08.2026 · обычный режим", start.minusDays(1).atTime(12, 0)),
        UiPreviewScene("suhoor", "10.08.2026 · до сухура", start.atTime(0, 30)),
        UiPreviewScene("fast", "10.08.2026 · дневной пост", start.atTime(12, 0)),
        UiPreviewScene("iftar", "10.08.2026 · до ифтара", start.atTime(18, 0)),
        UiPreviewScene("after", "10.08.2026 · ифтар наступил", start.atTime(20, 25))
    )
    fun selected(context: Context): UiPreviewScene {
        val key = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(KEY, "off")
        return scenes.firstOrNull { it.key == key } ?: scenes.first()
    }
    fun select(context: Context, scene: UiPreviewScene) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString(KEY, scene.key).apply()
    }
    fun now(context: Context, realNow: LocalDateTime): LocalDateTime = selected(context).time ?: realNow
    fun ramadanDay(date: LocalDate): Int? {
        val day = ChronoUnit.DAYS.between(start, date)
        return if (day in 0..29) day.toInt() + 1 else null
    }
    fun isArtificial(date: LocalDate): Boolean = ramadanDay(date) != null
}
