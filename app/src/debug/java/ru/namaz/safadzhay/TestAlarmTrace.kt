package ru.namaz.safadzhay

import android.content.Context
import android.content.Intent

/** Process-local facts, written only AFTER successful AlarmManager calls.
 * Not an alarm source, delivery history or a claim of OS/OEM delivery guarantees. */
internal object TestAlarmTrace {
    data class Entry(val date: String, val key: String, val city: String, val prayerTime: String,
        val triggerMillis: Long, val exact: Boolean)
    data class Snapshot(val status: String, val updatedMillis: Long, val entries: List<Entry>)
    private var value = Snapshot("unknown", 0, emptyList())
    @Synchronized fun begin(context: Context, now: Long) {
        if (context.packageName != "ru.namaz.safadzhay.test") return
        value = Snapshot("rebuilding", now, emptyList())
    }
    @Synchronized fun scheduled(context: Context, intent: Intent, trigger: Long, exact: Boolean) {
        if (context.packageName != "ru.namaz.safadzhay.test") return
        val entry = Entry(intent.getStringExtra("date")!!, intent.getStringExtra("key")!!,
            intent.getStringExtra("city")!!, intent.getStringExtra("time")!!, trigger, exact)
        value = value.copy(entries = value.entries + entry)
    }
    @Synchronized fun finish(context: Context, success: Boolean) {
        if (context.packageName == "ru.namaz.safadzhay.test")
            value = value.copy(status = if (success) "complete" else "incomplete")
    }
    @Synchronized fun snapshot(): Snapshot = value.copy(entries = value.entries.toList())
}
