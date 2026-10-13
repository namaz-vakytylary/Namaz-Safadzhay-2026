package ru.namaz.safadzhay

import android.content.SharedPreferences

internal enum class AppUpdateStage { AVAILABLE, WAITING, DOWNLOADING, READY, INSTALLING, FAILED }
internal data class StoreAppUpdate(
    val versionCode: Long,
    val versionName: String?,
    val whatsNew: String?,
    val stage: AppUpdateStage = AppUpdateStage.AVAILABLE,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0
) {
    // Percent describes downloaded bytes, never installation success.
    val percent: Int? get() = if (totalBytes > 0 && downloadedBytes >= 0)
        (downloadedBytes.coerceAtMost(totalBytes).toDouble() / totalBytes * 100).toInt().coerceIn(0, 100) else null
    val versionLabel: String get() = versionName?.takeIf { it.isNotBlank() }?.let { "Версия $it" } ?: "Новая версия"
}

/** Isolated preferences, rolling 24-hour prompt limit and actual installed-version tracking. */
internal class AppUpdateHistory(private val prefs: SharedPreferences) {
    fun canPrompt(now: Long): Boolean = !prefs.contains("last_prompt_at") || now - prefs.getLong("last_prompt_at", now) >= DAY
    fun prompted(now: Long) { prefs.edit().putLong("last_prompt_at", now).apply() }
    fun observeInstalled(code: Long, name: String) {
        val previous = prefs.getLong("observed_version", code)
        val edit = prefs.edit().putLong("observed_version", code)
        if (code > previous) edit.putString("success_version", name)
        edit.apply()
    }
    fun pendingSuccess(): String? = prefs.getString("success_version", null)
    fun successShown() { prefs.edit().remove("success_version").apply() }
    companion object { const val DAY = 86_400_000L }
}
internal interface AppUpdateSource {
    var onState: ((StoreAppUpdate) -> Unit)?
    fun check(success: (StoreAppUpdate?) -> Unit, failure: () -> Unit)
    fun download(failure: () -> Unit)
    fun install(failure: () -> Unit)
    fun close()
}
