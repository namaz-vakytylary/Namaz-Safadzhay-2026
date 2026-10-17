package ru.namaz.safadzhay

import android.content.Context
import android.content.Intent

/** Production records no TEST alarm journal. */
internal object TestAlarmTrace {
    fun begin(context: Context, now: Long) = Unit
    fun scheduled(context: Context, intent: Intent, trigger: Long, exact: Boolean) = Unit
    fun finish(context: Context, success: Boolean) = Unit
}
