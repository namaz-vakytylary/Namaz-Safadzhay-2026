package ru.namaz.safadzhay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ScheduleRepositoryTest {
    private lateinit var context: Context
    private var now = 1_800_000_000_000L
    private val responses = mutableMapOf<String, ByteArray>()
    private val calls = mutableListOf<String>()
    private val original = PrayerDay("2026-08-01", "02:14", "12:20", "16:55", "20:06", "21:56")

    @Before fun reset() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("schedule_updates", Context.MODE_PRIVATE).edit().clear().commit()
        context.filesDir.listFiles()?.filter { it.name.startsWith("downloaded-schedules") }?.forEach { it.delete() }
        responses.clear(); calls.clear()
    }

    private fun repository() = ScheduleRepository(context, { url, _ ->
        val path = url.removePrefix(ScheduleRepository.BASE_URL)
        calls.add(path)
        responses[path] ?: throw IOException("offline")
    }, { now })

    // Synthetic data is confined to these unit tests; never bundled or published.
    private fun fixture(year: Int = 2027, version: Int = 1, fajr: String = "06:00"): JSONObject {
        val date = "$year-01-01"
        val day = JSONObject().put("date", date).put("fajr", fajr).put("zuhr", "12:00")
            .put("asr", "14:00").put("maghrib", "16:00").put("isha", "18:00")
        val cities = JSONArray()
        listOf("safadzhay", "moscow").forEach {
            cities.put(JSONObject().put("id", it).put("timeZone", "Europe/Moscow")
                .put("days", JSONArray().put(day)))
        }
        val payload = JSONObject().put("schemaVersion", 1).put("year", year).put("version", version)
            .put("coverage", JSONObject().put("from", date).put("to", date).put("completeYear", false))
            .put("cities", cities).toString().toByteArray()
        responses["$year.json"] = payload
        return entryFor(year, version, payload)
    }

    private fun entryFor(year: Int, version: Int, payload: ByteArray): JSONObject = JSONObject()
        .put("year", year).put("version", version).put("path", "$year.json")
        .put("cityIds", JSONArray().put("safadzhay").put("moscow"))
        .put("dateFrom", "$year-01-01").put("dateTo", "$year-01-01").put("completeYear", false)
        .put("daysPerCity", 1).put("bytes", payload.size)
        .put("sha256", MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it.toInt() and 255) })

    private fun publish(vararg entries: JSONObject) {
        responses["manifest.json"] = JSONObject().put("schemaVersion", 1)
            .put("schedules", JSONArray(entries.toList())).toString().toByteArray()
    }

    @Test fun downloadPersistsAcrossRestartAndRetainsBuiltInDaysOffline() {
        publish(fixture())
        assertTrue(repository().sync(false, true).changed)
        val restarted = repository()
        val days = restarted.merged("safadzhay", listOf(original))
        assertEquals(listOf("2026-08-01", "2027-01-01"), days.map { it.date })
        assertEquals(original, days.first())
        assertEquals("06:00", days.last().fajr)
        assertEquals(1, restarted.merged("moscow", emptyList()).size)
        calls.clear()
        assertFalse(restarted.sync(true, false).changed)
        assertTrue(calls.isEmpty())
        assertTrue(File(context.filesDir, "downloaded-schedules.json").isFile)
    }

    @Test fun unchangedRevisionDownloadsOnlyManifestAndWeeklyCheckIsThrottled() {
        publish(fixture())
        val repo = repository()
        repo.sync(false, true)
        calls.clear()
        repo.sync(false, true)
        assertTrue(calls.isEmpty())
        now += ScheduleRepository.CHECK_INTERVAL
        repo.sync(false, true)
        assertEquals(listOf("manifest.json"), calls)
        calls.clear()
        repo.sync(true, true)
        assertEquals(listOf("manifest.json"), calls)
    }

    @Test fun corruptDownloadCannotReplaceWorkingSnapshot() {
        publish(fixture())
        val repo = repository(); repo.sync(true, true)
        val before = File(context.filesDir, "downloaded-schedules.json").readBytes()
        publish(fixture(version = 2, fajr = "06:05"))
        responses["2027.json"] = "broken".toByteArray()
        assertFalse(repo.sync(true, true).changed)
        assertArrayEquals(before, File(context.filesDir, "downloaded-schedules.json").readBytes())
        assertEquals("06:00", repository().merged("moscow", emptyList()).single().fajr)
    }

    @Test fun interruptedSecondYearDoesNotPartiallyCommitFirstYear() {
        publish(fixture())
        val repo = repository(); repo.sync(true, true)
        publish(fixture(version = 2, fajr = "06:05"), fixture(year = 2028))
        responses.remove("2028.json")
        assertFalse(repo.sync(true, true).changed)
        val days = repository().merged("safadzhay", emptyList())
        assertEquals(1, days.size)
        assertEquals("06:00", days.single().fajr)
    }

    @Test fun newerRevisionUpdatesDataButOlderRevisionCannotRollItBack() {
        publish(fixture())
        val repo = repository(); repo.sync(true, true)
        publish(fixture(version = 2, fajr = "06:05"))
        assertTrue(repo.sync(true, true).changed)
        publish(fixture())
        assertFalse(repo.sync(true, true).changed)
        assertEquals("06:05", repository().merged("safadzhay", emptyList()).single().fajr)
    }

    @Test fun malformedDatesAndTimeZoneRejectedEvenWithMatchingChecksum() {
        fixture()
        val payload = JSONObject(String(responses.getValue("2027.json")))
        payload.getJSONArray("cities").getJSONObject(0).put("timeZone", "UTC")
        val bytes = payload.toString().toByteArray()
        responses["2027.json"] = bytes
        publish(entryFor(2027, 1, bytes))
        assertFalse(repository().sync(true, true).changed)
        assertEquals(listOf(original), repository().merged("safadzhay", listOf(original)))
        fixture()
        val invalid = JSONObject(String(responses.getValue("2027.json")))
        invalid.getJSONArray("cities").getJSONObject(0).getJSONArray("days").getJSONObject(0).put("date", "2027-01-02")
        val invalidBytes = invalid.toString().toByteArray()
        responses["2027.json"] = invalidBytes
        publish(entryFor(2027, 1, invalidBytes))
        assertFalse(repository().sync(true, true).changed)
    }

    @Test fun unsafeUrlDuplicateYearAndUnsupportedSchemaAreRejected() {
        val entry = fixture().put("path", "https://example.com/2027.json")
        publish(entry)
        assertFalse(repository().sync(true, true).changed)
        assertEquals(listOf("manifest.json"), calls)
        calls.clear()
        val valid = fixture(); publish(valid, valid)
        assertFalse(repository().sync(true, true).changed)
        assertEquals(listOf("manifest.json"), calls)
        responses["manifest.json"] = "{\"schemaVersion\":2,\"schedules\":[]}".toByteArray()
        assertFalse(repository().sync(true, true).changed)
    }

    @Test fun offlineFirstLaunchAndInvalidSavedFileFallBackToBuiltIn() {
        val repo = repository()
        assertFalse(repo.sync(false, false).changed)
        assertTrue(calls.isEmpty())
        File(context.filesDir, "downloaded-schedules.json").writeText("invalid")
        assertEquals(listOf(original), repository().merged("safadzhay", listOf(original)))
    }

    @Test fun published2026FilesLoadAll306UnchangedDays() {
        val manifest = javaClass.getResourceAsStream("/schedules/manifest.json")!!.use { it.readBytes() }
        val payload = javaClass.getResourceAsStream("/schedules/2026.json")!!.use { it.readBytes() }
        responses["manifest.json"] = manifest; responses["2026.json"] = payload
        val repo = repository()
        assertTrue(repo.sync(true, true).changed)
        val safadzhay = repo.merged("safadzhay", emptyList())
        val moscow = repo.merged("moscow", emptyList())
        assertEquals(153, safadzhay.size); assertEquals(153, moscow.size)
        assertEquals(original, safadzhay.first())
        assertEquals("2026-12-31", moscow.last().date)
        assertEquals("18:04", moscow.last().isha)
    }

    @Test fun downloadedNextYearReachesCalendarAndStoredNotificationChain() {
        publish(fixture())
        val repo = repository(); assertTrue(repo.sync(true, true).changed)
        context.getSharedPreferences("schedule_updates", Context.MODE_PRIVATE).edit()
            .putLong("last_attempt", System.currentTimeMillis()).commit()
        val controller = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).create()
        val activity = controller.get()
        try {
            org.robolectric.util.ReflectionHelpers.getField<java.util.concurrent.ExecutorService>(activity, "alarmExecutor")
                .submit {}.get(15, java.util.concurrent.TimeUnit.SECONDS)
            org.robolectric.util.ReflectionHelpers.setField(activity, "scheduleRepository", repo)
            org.robolectric.util.ReflectionHelpers.setField(activity, "calendarMonth", java.time.YearMonth.of(2027, 1))
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(activity, "renderInlineCalendar")
            val title = org.robolectric.util.ReflectionHelpers.getField<android.widget.TextView>(activity, "calendarTitle")
            assertEquals("Январь 2027", title.text.toString())
            val days = repo.merged("safadzhay", listOf(original))
            val method = MainActivity::class.java.getDeclaredMethod("rebuildPrayerNotifications",
                List::class.java, String::class.java, java.lang.Boolean.TYPE, java.lang.Integer.TYPE, Set::class.java)
            method.isAccessible = true
            method.invoke(activity, days, "Сафаджай", true, 5, setOf("fajr", "zuhr", "asr", "maghrib", "isha"))
            val stored = context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("scheduled_prayers", "")!!
            assertTrue(stored.contains("2027-01-01|fajr|Фаджр|Иртәнге намаз|06:00|Сафаджай"))
            assertTrue(stored.contains("2027-01-01|isha|"))
        } finally { controller.destroy() }
    }
}
