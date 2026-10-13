package ru.namaz.safadzhay

import android.app.Activity
import android.content.Context

/** Production release has no simulated versions, progress or preview UI. */
internal object AppUpdateUiPreview {
    fun available(context: Context) = false
    fun show(activity: Activity) = Unit
}
