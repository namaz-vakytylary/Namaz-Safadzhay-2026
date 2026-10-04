package ru.namaz.safadzhay

import android.content.Context
import java.time.LocalDate
import java.time.LocalDateTime

/** Release has no fixture date, clock override or preview settings access. */
internal object RamadanUiPreview {
    const val available = false
    val scenes: List<UiPreviewScene> = emptyList()
    fun selected(context: Context) = UiPreviewScene("off", "", null)
    fun select(context: Context, scene: UiPreviewScene) = Unit
    fun now(context: Context, realNow: LocalDateTime): LocalDateTime = realNow
    fun ramadanDay(date: LocalDate): Int? = null
    fun isArtificial(date: LocalDate): Boolean = false
}
