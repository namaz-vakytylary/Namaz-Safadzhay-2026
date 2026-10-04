package ru.namaz.safadzhay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

internal enum class ThemeMode(val storedValue: String, val title: String, val subtitle: String) {
    SYSTEM("system", "Системная", "Как в настройках устройства"),
    LIGHT("light", "Светлая", "Всегда светлая тема"),
    DARK("dark", "Тёмная", "Всегда тёмная тема");

    fun usesDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromStored(value: String?): ThemeMode = entries.firstOrNull { it.storedValue == value } ?: SYSTEM
    }
}

/** Shares the existing settings file; writes only the new theme key. */
internal object ThemeSettings {
    const val KEY = "app_theme"
    fun read(context: Context): ThemeMode = ThemeMode.fromStored(
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString(KEY, null)
    )
    fun save(context: Context, mode: ThemeMode) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString(KEY, mode.storedValue).apply()
    }
    fun isDark(context: Context): Boolean = read(context).usesDark(
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    )
    fun colors(context: Context): AppColors = AppColors(isDark(context))
}

/** Dark values preserve the original views. Light uses neutral surfaces and one green accent. */
internal class AppColors(val isDark: Boolean) {
    private fun tone(dark: String, light: String): Int = Color.parseColor(if (isDark) dark else light)
    val background = tone("#021E16", "#F7F9F8")
    val surface = tone("#083427", "#FFFFFF")
    val activePrayer = tone("#0A4E36", "#E7F6EE")
    val outline = tone("#15533C", "#DAE3DE")
    val primary = tone("#46DA91", "#007C4D")
    val onBackground = tone("#EBF7F0", "#172B23")
    val onSurface = tone("#FFFFFF", "#172B23")
    val secondaryText = tone("#ADCBBD", "#56675F")
    val tabSelected = tone("#20C27F", "#007C4D")
    val onPrimary = Color.WHITE
    val tabInactiveText = tone("#BCCCC5", "#56675F")
    val onAction = tone("#012619", "#FFFFFF")
    val onSaveAction = tone("#012D1D", "#FFFFFF")
    val activeTime = tone("#5BE0A4", "#007C4D")
    val tatarText = tone("#ABCABE", "#56675F")
    val notificationText = tone("#AAC6BA", "#56675F")
    val holidaySecondaryText = tone("#BECDC6", "#56675F")
    val calendarWeekday = tone("#96AFA4", "#56675F")
    val calendarOutsideMonth = tone("#586962", "#697A72")
    val fridayToday = tone("#30E4A1", "#007C4D")
    val friday = tone("#64BE96", "#007C4D")
    val gold = tone("#D6B24D", "#86620B")
    val ramadanAccent = tone("#EBCA68", "#86620B")
    val holidayOutline = tone("#F6C453", "#86620B")
    val iftarSurface = tone("#2A2314", "#FFF7DF")
    val progressTrack = tone("#144B38", "#DAE3DE")
    val brightProgress = tone("#43E691", "#007C4D")
    val progressOutline = tone("#124833", "#DAE3DE")
    val goldProgressTrack = tone("#76581F", "#E8DEBE")
    val updateTop = tone("#074932", "#FFFFFF")
    val updateBottom = tone("#033022", "#F7F9F8")
    val noticeSurface = tone("#053D2C", "#E7F6EE")
    val noticeOutline = tone("#207E58", "#DAE3DE")
    val compassOutline = tone("#428267", "#A6B8AE")
    val compassText = tone("#E4F3EA", "#172B23")
    val compassTail = tone("#1F5941", "#175B40")
    val compassHubOutline = tone("#134B34", "#007C4D")
    // Ramadan artwork and its overlay colours belong to both themes.
    val ramadanImageAccent = Color.rgb(235, 202, 104)
    val ramadanHeaderText = Color.rgb(235, 247, 240)
    val onRamadanImage = Color.rgb(244, 241, 232)
    val kaabaBody = Color.rgb(20, 28, 24)
    val kaabaLid = Color.rgb(40, 49, 44)
    val kaabaBand = Color.rgb(178, 232, 204)
}
