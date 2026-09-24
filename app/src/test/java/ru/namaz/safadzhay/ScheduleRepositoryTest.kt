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

   private fun repository(year: Int = 2026) = ScheduleRepository(
    context,
    { url, _ ->
        val path = url.removePrefix(ScheduleRepository.BASE_URL)
        calls.add(path)
        responses[path] ?: throw IOException("offline")
    },
    { now },
    { year }
)

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
        assertTrue(repository().sync(online = true).changed)
        val restarted = repository()
        val days = restarted.merged("safadzhay", listOf(original))
        assertEquals(listOf("2026-08-01", "2027-01-01"), days.map { it.date })
        assertEquals(original, days.first())
        assertEquals("06:00", days.last().fajr)
        assertEquals(1, restarted.merged("moscow", emptyList()).size)
        calls.clear()
        assertFalse(restarted.sync(online = false).changed)
        assertTrue(calls.isEmpty())
        assertTrue(File(context.filesDir, "downloaded-schedules.json").isFile)
    }

    @Test fun everyNewCheckFetchesManifestButUnchangedRevisionDoesNotRedownloadJson() {
        publish(fixture())
        val repo = repository()
        repo.sync(true)
        repeat(3) {
            calls.clear()
            repo.sync(true)
            assertEquals(listOf("manifest.json"), calls)
        }
    }

    @Test fun corruptDownloadCannotReplaceWorkingSnapshot() {
        publish(fixture())
        val repo = repository(); repo.sync(online = true)
        val before = File(context.filesDir, "downloaded-schedules.json").readBytes()
        publish(fixture(version = 2, fajr = "06:05"))
        responses["2027.json"] = "broken".toByteArray()
        assertFalse(repo.sync(online = true).changed)
        assertArrayEquals(before, File(context.filesDir, "downloaded-schedules.json").readBytes())
        assertEquals("06:00", repository().merged("moscow", emptyList()).single().fajr)
    }

    @Test fun interruptedSecondYearDoesNotPartiallyCommitFirstYear() {
        publish(fixture())
        val repo = repository(); repo.sync(online = true)
        publish(fixture(version = 2, fajr = "06:05"), fixture(year = 2028))
        responses.remove("2028.json")
        assertFalse(repo.sync(online = true).changed)
        val days = repository().merged("safadzhay", emptyList())
        assertEquals(1, days.size)
        assertEquals("06:00", days.single().fajr)
    }

    @Test fun newerRevisionUpdatesDataButOlderRevisionCannotRollItBack() {
        publish(fixture())
        val repo = repository(); repo.sync(online = true)
        publish(fixture(version = 2, fajr = "06:05"))
        assertTrue(repo.sync(online = true).changed)
        publish(fixture())
        assertFalse(repo.sync(online = true).changed)
        assertEquals("06:05", repository().merged("safadzhay", emptyList()).single().fajr)
    }

    @Test fun malformedDatesAndTimeZoneRejectedEvenWithMatchingChecksum() {
        fixture()
        val payload = JSONObject(String(responses.getValue("2027.json")))
        payload.getJSONArray("cities").getJSONObject(0).put("timeZone", "UTC")
        val bytes = payload.toString().toByteArray()
        responses["2027.json"] = bytes
        publish(entryFor(2027, 1, bytes))
        assertFalse(repository().sync(online = true).changed)
        assertEquals(listOf(original), repository().merged("safadzhay", listOf(original)))
        fixture()
        val invalid = JSONObject(String(responses.getValue("2027.json")))
        invalid.getJSONArray("cities").getJSONObject(0).getJSONArray("days").getJSONObject(0).put("date", "2027-01-02")
        val invalidBytes = invalid.toString().toByteArray()
        responses["2027.json"] = invalidBytes
        publish(entryFor(2027, 1, invalidBytes))
        assertFalse(repository().sync(online = true).changed)
    }

    @Test fun unsafeUrlDuplicateYearAndUnsupportedSchemaAreRejected() {
        val entry = fixture().put("path", "https://example.com/2027.json")
        publish(entry)
        assertFalse(repository().sync(online = true).changed)
        assertEquals(listOf("manifest.json"), calls)
        calls.clear()
        val valid = fixture(); publish(valid, valid)
        assertFalse(repository().sync(online = true).changed)
        assertEquals(listOf("manifest.json"), calls)
        responses["manifest.json"] = "{\"schemaVersion\":2,\"schedules\":[]}".toByteArray()
        assertFalse(repository().sync(online = true).changed)
    }

    @Test fun offlineFirstLaunchAndInvalidSavedFileFallBackToBuiltIn() {
        val repo = repository()
        assertFalse(repo.sync(online = false).changed)
        assertTrue(calls.isEmpty())
        File(context.filesDir, "downloaded-schedules.json").writeText("invalid")
        assertEquals(listOf(original), repository().merged("safadzhay", listOf(original)))
    }

    @Test fun published2026FilesLoadAll306UnchangedDays() {
        val manifest = javaClass.getResourceAsStream("/schedules/manifest.json")!!.use { it.readBytes() }
        val payload = javaClass.getResourceAsStream("/schedules/2026.json")!!.use { it.readBytes() }
        responses["manifest.json"] = manifest; responses["2026.json"] = payload
        val repo = repository()
        assertTrue(repo.sync(online = true).changed)
        val safadzhay = repo.merged("safadzhay", emptyList())
        val moscow = repo.merged("moscow", emptyList())
        assertEquals(153, safadzhay.size); assertEquals(153, moscow.size)
        assertEquals(original, safadzhay.first())
        assertEquals("2026-12-31", moscow.last().date)
        assertEquals("18:04", moscow.last().isha)
    }

    @Test fun missingNextYearPreservesCurrentYearAndOnlyNewYearIsDownloaded() {
        val manifest = javaClass.getResourceAsStream("/schedules/manifest.json")!!.use { it.readBytes() }
        val payload = javaClass.getResourceAsStream("/schedules/2026.json")!!.use { it.readBytes() }
        responses["manifest.json"] = manifest; responses["2026.json"] = payload
        val repo = repository(); repo.sync(true)
        assertFalse(repo.merged("moscow", emptyList()).any { it.date.startsWith("2027") })
        val old = JSONObject(String(manifest)).getJSONArray("schedules").getJSONObject(0)
        val nextYear = fixture()
        publish(old, nextYear)
        calls.clear(); assertTrue(repo.sync(true).changed)
        assertEquals(listOf("manifest.json", "2027.json"), calls)
        val dates = repository().merged("moscow", emptyList()).map { it.date }
        assertEquals(listOf("2026-12-31", "2027-01-01"), dates.takeLast(2))
        val revised = fixture(version = 2, fajr = "06:02")
        publish(old, revised); calls.clear(); assertTrue(repo.sync(true).changed)
        assertEquals(listOf("manifest.json", "2027.json"), calls)
        publish(old, revised, fixture(2028)); calls.clear(); assertTrue(repo.sync(true).changed)
        assertEquals(listOf("manifest.json", "2028.json"), calls)
    }

    private fun rejectsReplacement(change: (JSONObject, JSONObject) -> Unit) {
        publish(fixture()); val repo = repository(); repo.sync(true)
        val before = File(context.filesDir, "downloaded-schedules.json").readBytes()
        val entry = fixture(version = 2)
        val body = JSONObject(String(responses.getValue("2027.json")))
        change(body, entry)
        val bytes = body.toString().toByteArray()
        responses["2027.json"] = bytes
        entry.put("bytes", bytes.size).put("sha256", entryFor(2027, 2, bytes).getString("sha256"))
        publish(entry)
        assertFalse(repo.sync(true).changed)
        assertArrayEquals(before, File(context.filesDir, "downloaded-schedules.json").readBytes())
        assertEquals("06:00", repository().merged("moscow", emptyList()).single().fajr)
    }
    @Test fun duplicateDateRejected() = rejectsReplacement { body, entry ->
        entry.put("dateTo", "2027-01-02").put("daysPerCity", 2)
        body.getJSONObject("coverage").put("to", "2027-01-02")
        for (i in 0..1) { val days = body.getJSONArray("cities").getJSONObject(i).getJSONArray("days"); days.put(days.getJSONObject(0)) }
    }
    @Test fun missingDateRejected() = rejectsReplacement { body, entry ->
        entry.put("dateTo", "2027-01-02").put("daysPerCity", 2)
        body.getJSONObject("coverage").put("to", "2027-01-02")
    }
    @Test fun invalidTimeRejected() = rejectsReplacement { body, _ ->
        body.getJSONArray("cities").getJSONObject(0).getJSONArray("days").getJSONObject(0).put("fajr", "25:90")
    }
    @Test fun missingPrayerRejected() = rejectsReplacement { body, _ ->
        body.getJSONArray("cities").getJSONObject(0).getJSONArray("days").getJSONObject(0).remove("isha")
    }
    @Test fun unknownCityRejected() = rejectsReplacement { body, entry ->
        entry.put("cityIds", JSONArray().put("unknown").put("moscow"))
        body.getJSONArray("cities").getJSONObject(0).put("id", "unknown")
    }
    @Test fun invalidYearVersionSchemaAndCoverageRejected() {
        listOf("year", "version", "schemaVersion").forEach { key ->
            rejectsReplacement { body, _ -> body.put(key, 0) }
        }
        rejectsReplacement { _, entry -> entry.put("completeYear", true) }
    }
    @Test fun wrongSizeAndWrongShaAndDamagedJsonPreserveSnapshot() {
        publish(fixture()); val repo = repository(); repo.sync(true)
        val before = File(context.filesDir, "downloaded-schedules.json").readBytes()
        val wrongSize = fixture(version = 2); wrongSize.put("bytes", wrongSize.getInt("bytes") + 1); publish(wrongSize)
        assertFalse(repo.sync(true).changed)
        publish(fixture(version = 2).put("sha256", "0".repeat(64)))
        assertFalse(repo.sync(true).changed)
        val invalid = "{broken".toByteArray(); responses["2027.json"] = invalid; publish(entryFor(2027, 2, invalid))
        assertFalse(repo.sync(true).changed)
        assertArrayEquals(before, File(context.filesDir, "downloaded-schedules.json").readBytes())
    }
    @Test fun timeoutDnsAndHttpErrorAreSilentAndNextCheckRetries() {
        publish(fixture()); val repo = repository(); repo.sync(true)
        val before = File(context.filesDir, "downloaded-schedules.json").readBytes()
        listOf(java.net.SocketTimeoutException(), java.net.UnknownHostException(), IOException("HTTP 503")).forEach { error ->
            val failing = ScheduleRepository(context, { _, _ -> throw error })
            assertFalse(failing.sync(true).changed)
            assertArrayEquals(before, File(context.filesDir, "downloaded-schedules.json").readBytes())
            calls.clear(); assertFalse(repository().sync(true).changed)
            assertEquals(listOf("manifest.json"), calls)
        }
    }

   @Test
fun downloadedNextYearIsNotShownBeforeItBecomesCurrentYear() {
    val currentYear = LocalDate.now().year
val nextYear = currentYear + 1

    publish(fixture(year = nextYear))

    val repo = repository(year = currentYear)
    assertTrue(repo.sync(online = true).changed)

    TestNetwork.offline(context)

    val controller =
        org.robolectric.Robolectric.buildActivity(MainActivity::class.java).create()
    val activity = controller.get()

    try {
        org.robolectric.util.ReflectionHelpers
            .getField<java.util.concurrent.ExecutorService>(activity, "alarmExecutor")
            .submit {}
            .get(15, java.util.concurrent.TimeUnit.SECONDS)

        org.robolectric.util.ReflectionHelpers.setField(
            activity,
            "scheduleRepository",
            repo
        )

        org.robolectric.util.ReflectionHelpers.setField(
            activity,
            "calendarMonth",
            java.time.YearMonth.of(nextYear, 1)
        )

        org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(
            activity,
            "renderInlineCalendar"
        )

        val title =
            org.robolectric.util.ReflectionHelpers.getField<android.widget.TextView>(
                activity,
                "calendarTitle"
            )

        assertFalse(
            title.text.toString().contains(nextYear.toString())
        )

        val downloadedNextYear = repo.merged(
            "safadzhay",
            listOf(original)
        )

        assertTrue(
            downloadedNextYear.any {
                it.date == "$nextYear-01-01"
            }
        )
    } finally {
        controller.destroy()
    }
}
@Test
fun newYearRemovesOldCacheDoesNotRedownloadOldYearAndKeepsCurrentSchedule() {
    val oldYear = LocalDate.now().year
val newYear = oldYear + 1

    val oldEntry = fixture(year = oldYear, fajr = "05:30")
    val newEntry = fixture(year = newYear, fajr = "06:10")
    publish(oldEntry, newEntry)

    val oldRepo = repository(year = oldYear)
    assertTrue(oldRepo.sync(online = true).changed)

    calls.clear()

    val newRepo = repository(year = newYear)
    assertTrue(newRepo.sync(online = true).changed)

    val snapshot = File(
        context.filesDir,
        "downloaded-schedules.json"
    ).readText()

    // Старый год удалён из локального кэша.
    assertFalse(snapshot.contains("\"year\":$oldYear"))
    assertTrue(snapshot.contains("\"year\":$newYear"))

    // JSON старого года больше не скачивается.
    assertFalse(calls.contains("$oldYear.json"))

    // Расписание нового года остаётся рабочим.
    val days = newRepo.dataForYear(
        "safadzhay",
        newYear,
        emptyList()
    )

    assertEquals(1, days.size)
    assertEquals("$newYear-01-01", days.first().date)
    assertEquals("06:10", days.first().fajr)
}
}
