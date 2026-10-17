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
import java.time.LocalDate

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

    @Test fun downloadPersistsAcrossRestartWithoutInventingOfflineDays() {
        publish(fixture())
        assertTrue(repository().sync(online = true).changed)
        val restarted = repository()
        val days = restarted.days("safadzhay")
        assertEquals(listOf("2027-01-01"), days.map { it.date })
        assertEquals("06:00", days.last().fajr)
        assertEquals(1, restarted.days("moscow").size)
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
        assertEquals("06:00", repository().days("moscow").single().fajr)
    }

    @Test fun interruptedSecondYearDoesNotPartiallyCommitFirstYear() {
        publish(fixture())
        val repo = repository(); repo.sync(online = true)
        publish(fixture(version = 2, fajr = "06:05"), fixture(year = 2028))
        responses.remove("2028.json")
        assertFalse(repo.sync(online = true).changed)
        val days = repository().days("safadzhay")
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
        assertEquals("06:05", repository().days("safadzhay").single().fajr)
    }

    @Test fun malformedDatesAndTimeZoneRejectedEvenWithMatchingChecksum() {
        fixture()
        val payload = JSONObject(String(responses.getValue("2027.json")))
        payload.getJSONArray("cities").getJSONObject(0).put("timeZone", "UTC")
        val bytes = payload.toString().toByteArray()
        responses["2027.json"] = bytes
        publish(entryFor(2027, 1, bytes))
        assertFalse(repository().sync(online = true).changed)
        assertTrue(repository().days("safadzhay").isEmpty())
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

    @Test fun offlineFirstLaunchAndInvalidSavedFileHaveNoSchedule() {
        val repo = repository()
        assertFalse(repo.sync(online = false).changed)
        assertTrue(calls.isEmpty())
        File(context.filesDir, "downloaded-schedules.json").writeText("invalid")
        assertTrue(repository().days("safadzhay").isEmpty())
    }

    @Test fun published2026FilesLoadAll306UnchangedDays() {
        val manifest = javaClass.getResourceAsStream("/schedules/manifest.json")!!.use { it.readBytes() }
        val payload = javaClass.getResourceAsStream("/schedules/2026.json")!!.use { it.readBytes() }
        responses["manifest.json"] = manifest; responses["2026.json"] = payload
        val repo = repository()
        assertTrue(repo.sync(online = true).changed)
        val safadzhay = repo.days("safadzhay")
        val moscow = repo.days("moscow")
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
        assertFalse(repo.days("moscow").any { it.date.startsWith("2027") })
        val old = JSONObject(String(manifest)).getJSONArray("schedules").getJSONObject(0)
        val nextYear = fixture()
        publish(old, nextYear)
        calls.clear(); assertTrue(repo.sync(true).changed)
        assertEquals(listOf("manifest.json", "2027.json"), calls)
        val dates = repository().days("moscow").map { it.date }
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
        assertEquals("06:00", repository().days("moscow").single().fajr)
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

    @Test fun prefetchIsAvailableByDateWithoutRestartAndCannotFillOtherDates() {
        publish(fixture(year = 2028))
        val repo = repository(year = 2026)
        assertTrue(repo.sync(true).changed)
        assertNull(repo.day("moscow", LocalDate.of(2026, 12, 31)))
        assertEquals("06:00", repo.day("moscow", LocalDate.of(2028, 1, 1))?.fajr)
        assertTrue(repo.dataForYear("moscow", 2026).isEmpty())
    }

    @Test fun migrationReadsExact13SnapshotOfflineWithoutRewritingEitherCity() {
        val entry = fixture(year = 2026)
        val legacy = JSONObject().put("schemaVersion", 1).put("bundles", JSONArray().put(
            JSONObject().put("entry", entry).put("payload", String(responses.getValue("2026.json")))))
        val file = File(context.filesDir, "downloaded-schedules.json")
        file.writeText(legacy.toString())
        val before = file.readBytes()
        val repo = repository()
        assertFalse(repo.sync(false).changed)
        for (city in listOf("moscow", "safadzhay")) {
            assertEquals("06:00", repo.day(city, LocalDate.of(2026, 1, 1))?.fajr)
        }
        assertArrayEquals(before, file.readBytes())
        assertTrue(calls.isEmpty())
        val info = repo.cacheInfo()
        assertEquals(ScheduleRepository.CacheState.VERIFIED, info.state)
        assertEquals(listOf(2026), info.years.map { it.year })
        assertEquals(setOf("moscow", "safadzhay"), info.years.single().cities.map { it.id }.toSet())
        assertEquals(0L, info.lastSuccessMillis) // Never invent a legacy download timestamp.
    }

    @Test fun damagedCacheDoesNotEraseAcceptedRevisionFloor() {
        publish(fixture(version = 2)); assertTrue(repository().sync(true).changed)
        File(context.filesDir, "downloaded-schedules.json").writeText("broken")
        publish(fixture(version = 1))
        val repo = repository()
        assertFalse(repo.sync(true).successful)
        assertTrue(repo.days("moscow").isEmpty())
        publish(fixture(version = 2))
        assertTrue(repo.sync(true).changed)
    }

    @Test fun interruptedAtomicWriteRecovers13BackupWithoutNetwork() {
        val entry = fixture(year = 2026)
        val legacy = JSONObject().put("schemaVersion", 1).put("bundles", JSONArray().put(
            JSONObject().put("entry", entry).put("payload", String(responses.getValue("2026.json")))))
        val file = File(context.filesDir, "downloaded-schedules.json")
        File(file.path + ".bak").writeText(legacy.toString())
        file.writeText("interrupted")
        assertEquals(1, repository().days("moscow").size)
    }

@Test
fun newYearPreservesUserCacheDoesNotRedownloadOldYearAndKeepsCurrentSchedule() {
    val oldYear = LocalDate.now().year
val newYear = oldYear + 1

    val oldEntry = fixture(year = oldYear, fajr = "05:30")
    val newEntry = fixture(year = newYear, fajr = "06:10")
    publish(oldEntry, newEntry)

    val oldRepo = repository(year = oldYear)
    assertTrue(oldRepo.sync(online = true).changed)

    calls.clear()

    val newRepo = repository(year = newYear)
    assertFalse(newRepo.sync(online = true).changed)

    val snapshot = File(
        context.filesDir,
        "downloaded-schedules.json"
    ).readText()

    // Старый год удалён из локального кэша.
    assertTrue(snapshot.contains("\"year\":$oldYear"))
    assertTrue(snapshot.contains("\"year\":$newYear"))

    // JSON старого года больше не скачивается.
    assertFalse(calls.contains("$oldYear.json"))

    // Расписание нового года остаётся рабочим.
    val days = newRepo.dataForYear(
        "safadzhay",
        newYear
    )

    assertEquals(1, days.size)
    assertEquals("$newYear-01-01", days.first().date)
    assertEquals("06:10", days.first().fajr)
}
    @Test fun measureColdVerifiedCacheReadOnBuildHost() {
        responses["manifest.json"] = javaClass.getResourceAsStream("/schedules/manifest.json")!!.use { it.readBytes() }
        responses["2026.json"] = javaClass.getResourceAsStream("/schedules/2026.json")!!.use { it.readBytes() }
        assertTrue(repository().sync(true).changed)
        val durations = (1..5).map {
            val start = System.nanoTime()
            assertEquals(153, repository().days("moscow").size)
            (System.nanoTime() - start) / 1_000_000.0
        }.sorted()
        println("VERIFIED_CACHE_READ_HOST_MEDIAN_MS=${durations[2]}")
    }

    @Test fun deferredStartupRestores13CacheOnWorkerAndReusesItOffline() {
        publish(fixture(year = 2026)); assertTrue(repository().sync(true).changed)
        val repo = ScheduleRepository(context, { _, _ -> throw IOException("offline") }, loadImmediately = false)
        assertFalse(repo.isLoaded)
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        try { assertTrue(worker.submit<Boolean> { repo.loadSavedIfNeeded() }.get()) }
        finally { worker.shutdownNow() }
        assertTrue(repo.isLoaded)
        assertEquals("06:00", repo.day("moscow", LocalDate.of(2026, 1, 1))?.fajr)
        assertFalse(repo.loadSavedIfNeeded())
    }

    @Test fun diskWriteFailureKeepsPreviousVerifiedSnapshot() {
        publish(fixture()); val repo = repository(); assertTrue(repo.sync(true).changed)
        val file = File(context.filesDir, "downloaded-schedules.json")
        val before = file.readBytes()
        val blocked = File(file.path + ".new"); blocked.mkdirs()
        // Non-empty directory prevents AtomicFile replacing its staging file.
        File(blocked, "blocked").writeText("test")
        publish(fixture(version = 2, fajr = "06:05"))
        try {
            assertFalse(repo.sync(true).successful)
            assertArrayEquals(before, file.readBytes())
            assertEquals("06:00", repo.days("moscow").single().fajr)
        } finally { blocked.deleteRecursively() }
    }

    @Test fun partialCityUpdateCannotDiscardOtherCityOfflineCache() = rejectsReplacement { body, entry ->
        body.getJSONArray("cities").remove(1)
        entry.put("cityIds", JSONArray().put("safadzhay"))
    }

    @Test fun multiYearProgressIsMonotonicAndCompletesOnlyAfterDurableSave() {
        publish(fixture(year = 2026), fixture(year = 2028))
        val progress = mutableListOf<Int>()
        val repo = repository()
        var savedYearsAtCompletion = emptyList<Int>()
        assertTrue(repo.sync(true) { _, percent ->
            progress.add(percent)
            if (percent == 100) {
                val restarted = repository()
                savedYearsAtCompletion = listOf(2026, 2028).filter {
                    restarted.day("moscow", LocalDate.of(it, 1, 1)) != null
                }
            }
        }.successful)
        assertEquals(listOf(2026, 2028), savedYearsAtCompletion)
        assertEquals(0, progress.first())
        assertEquals(100, progress.last())
        assertEquals(1, progress.count { it == 100 })
        assertTrue(progress.zipWithNext().all { (before, after) -> before <= after })
        assertTrue(progress.contains(68))
        assertTrue(progress.contains(88))
    }

    @Test fun rejectedSecondYearNeverCompletesOrReplacesPreviousCache() {
        publish(fixture(year = 2026)); assertTrue(repository().sync(true).successful)
        val saved = File(context.filesDir, "downloaded-schedules.json").readBytes()
        publish(fixture(year = 2026, version = 2), fixture(year = 2028))
        responses["2028.json"] = responses.getValue("2028.json").clone().also { it[0] = 32 }
        val progress = mutableListOf<Int>()
        assertFalse(repository().sync(true) { _, percent -> progress.add(percent) }.successful)
        assertFalse(progress.contains(100))
        assertTrue(progress.zipWithNext().all { (before, after) -> before <= after })
        assertArrayEquals(saved, File(context.filesDir, "downloaded-schedules.json").readBytes())
        assertNull(repository().day("moscow", LocalDate.of(2028, 1, 1)))
    }

    @Test fun cacheMetadataDistinguishesUnloadedAbsentAndRejectedFilesWithoutDeletion() {
        val lazy = ScheduleRepository(context, loadImmediately = false)
        assertEquals(ScheduleRepository.CacheState.NOT_LOADED, lazy.cacheInfo().state)
        lazy.loadSavedIfNeeded()
        assertEquals(ScheduleRepository.CacheState.ABSENT, lazy.cacheInfo().state)
        val file = File(context.filesDir, "downloaded-schedules.json")
        file.writeText("damaged")
        val rejected = repository()
        assertEquals(ScheduleRepository.CacheState.INVALID, rejected.cacheInfo().state)
        assertTrue(rejected.cacheInfo().years.isEmpty())
        assertEquals("damaged", file.readText())
        publish(fixture(year = 2026))
        assertTrue(rejected.sync(true).successful)
        val info = rejected.cacheInfo()
        assertEquals(ScheduleRepository.CacheState.VERIFIED, info.state)
        assertEquals(now, info.lastSuccessMillis)
        assertEquals("2026-01-01", info.years.single().cities.first().from)
        responses.clear()
        assertFalse(rejected.sync(true).successful)
        assertEquals(info, rejected.cacheInfo())
    }

}
