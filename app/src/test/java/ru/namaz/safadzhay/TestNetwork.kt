package ru.namaz.safadzhay

import android.content.Context
import android.net.ConnectivityManager
import org.robolectric.Shadows.shadowOf

internal object TestNetwork {
    fun published(context: Context) {
        val repo = ScheduleRepository(context, { url, _ ->
            val path = url.removePrefix(ScheduleRepository.BASE_URL)
            javaClass.getResourceAsStream("/schedules/$path")!!.use { it.readBytes() }
        })
        check(repo.sync(true).successful)
        org.robolectric.util.ReflectionHelpers.setStaticField(ScheduleRepository::class.java, "instance", repo)
    }
    /** Unit-test inputs only: never packaged as schedules or a production clock override. */
    fun installDay(context: Context, day: PrayerDay) {
        val date = java.time.LocalDate.parse(day.date)
        val wrapper = object : android.content.ContextWrapper(context) {
            private val directory = java.io.File(context.filesDir, "ui-fixture-" + java.util.UUID.randomUUID()).apply { mkdirs() }
            override fun getFilesDir(): java.io.File = directory
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
                context.getSharedPreferences(directory.name + name, mode)
        }
        val cities = org.json.JSONArray()
        for (city in listOf("safadzhay", "moscow")) {
            val row = org.json.JSONObject().put("date", day.date).put("fajr", day.fajr).put("zuhr", day.zuhr)
                .put("asr", day.asr).put("maghrib", day.maghrib).put("isha", day.isha)
            cities.put(org.json.JSONObject().put("id", city).put("timeZone", "Europe/Moscow").put("days", org.json.JSONArray().put(row)))
        }
        val body = org.json.JSONObject().put("schemaVersion", 1).put("year", date.year).put("version", 1)
            .put("coverage", org.json.JSONObject().put("from", day.date).put("to", day.date).put("completeYear", false))
            .put("cities", cities).toString().toByteArray()
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it.toInt() and 255) }
        val entry = org.json.JSONObject().put("year", date.year).put("version", 1).put("path", "ui.json")
            .put("cityIds", org.json.JSONArray().put("safadzhay").put("moscow")).put("dateFrom", day.date).put("dateTo", day.date)
            .put("completeYear", false).put("daysPerCity", 1).put("bytes", body.size).put("sha256", hash)
        val manifest = org.json.JSONObject().put("schemaVersion", 1).put("schedules", org.json.JSONArray().put(entry)).toString().toByteArray()
        val repo = ScheduleRepository(wrapper, { url, _ -> if (url.endsWith("manifest.json")) manifest else body }, currentYear = { date.year })
        check(repo.sync(true).successful)
        org.robolectric.util.ReflectionHelpers.setStaticField(ScheduleRepository::class.java, "instance", repo)
    }
    fun offline(context: Context) {
        shadowOf(context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).setActiveNetworkInfo(null)
    }
}
