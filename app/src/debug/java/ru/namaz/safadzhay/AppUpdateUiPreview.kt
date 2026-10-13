package ru.namaz.safadzhay

import android.app.Activity
import android.app.AlertDialog
import android.content.Context

/** Manual, clearly labelled TEST fixtures; never invokes an SDK or modifies update history. */
internal object AppUpdateUiPreview {
    fun available(context: Context) = context.packageName == "ru.namaz.safadzhay.test"
    fun show(activity: Activity) {
        if (!available(activity)) return
        val labels = arrayOf("Доступно обновление", "Загрузка · пример 42%", "Загрузка без процентов", "Готово к установке",
            "Установка через RuStore", "Повтор после ошибки", "Успешное обновление · пример")
        val stages = listOf(AppUpdateStage.AVAILABLE, AppUpdateStage.DOWNLOADING, AppUpdateStage.DOWNLOADING,
            AppUpdateStage.READY, AppUpdateStage.INSTALLING, AppUpdateStage.FAILED)
        AlertDialog.Builder(activity, if (ThemeSettings.isDark(activity)) R.style.AppDialogTheme else R.style.AppDialogThemeLight)
            .setTitle("TEST · состояния окна")
            .setItems(labels) { _, index ->
                if (index == 6) AppUpdateSuccessBanner(activity, "TEST · пример: Обновлено до версии 1.4")
                else {
                    lateinit var dialog: AppUpdateDialog
                    dialog = AppUpdateDialog(activity, { dialog.dismiss(); show(activity) }, {}, preview = true)
                    dialog.submit(StoreAppUpdate(100, "1.4 · пример", "• Пример описания изменений\n• Текст реального обновления придёт из RuStore",
                        stages[index], if (index == 1) 42 else 0, if (index == 1) 100 else 0))
                    dialog.show()
                }
            }.setNegativeButton("Закрыть", null).show()
    }
}
