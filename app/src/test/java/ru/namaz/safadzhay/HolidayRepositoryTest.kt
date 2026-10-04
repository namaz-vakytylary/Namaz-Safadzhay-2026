package ru.namaz.safadzhay

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HolidayRepositoryTest {
    private lateinit var app: Application
    private val responses = mutableMapOf<String, ByteArray>()
    private var year = 2026
    private fun repository() = HolidayRepository(app, { url, limit ->
        val bytes = responses[url.removePrefix(ScheduleRepository.BASE_URL)]
            ?: throw IOException("HTTP 503")
        readHolidayBytes(ByteArrayInputStream(bytes), limit)
    }, { year })
    private fun file(year: Int) = File(app.filesDir, "downloaded-holidays-$year.json")
    private fun fixture(year: Int) = JSONObject().put("year", year)
        .put("source", "ДУМ РФ, календарь $year")
        .put("holidays", JSONArray().put(JSONObject().put("date", "$year-02-19")
            .put("title", "Начало Рамадана").put("description", "Начало месяца поста.")))

    private fun publish(year: Int = 2026, body: JSONObject = fixture(year)): JSONObject {
        val bytes = body.toString().toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val entry = JSONObject().put("year", year).put("version", 1).put("path", "holidays/$year.json")
            .put("bytes", bytes.size).put("sha256", sha)
        responses["holidays/$year.json"] = bytes
        manifest(entry)
        return entry
    }
    private fun manifest(entry: JSONObject) {
        responses["manifest.json"] = JSONObject().put("schemaVersion", 1)
            .put("holidays", JSONArray().put(entry)).toString().toByteArray()
    }

    @Before fun reset() {
        app = RuntimeEnvironment.getApplication()
        app.filesDir.listFiles()?.filter { it.name.startsWith("downloaded-holidays-") }
            ?.forEach { it.delete() }
        HolidayCalendar.setRemote(2026, null)
    }

    @Test fun verifiedDownloadPersistsAndRemainsAvailableOffline() {
        publish()
        assertEquals("Начало Рамадана", repository().download(2026)?.single()?.title)
        responses.clear()
        assertNull(repository().download(2026))
        assertEquals(LocalDate.of(2026, 2, 19), repository().loadSaved(2026)?.single()?.date)
    }

    @Test fun wrongShaSizeAndCorruptJsonCannotReplaceWorkingFile() {
        publish(); assertNotNull(repository().download(2026))
        val original = file(2026).readBytes()
        val entry = publish()
        entry.put("sha256", "0".repeat(64)); manifest(entry)
        assertNull(repository().download(2026)); assertArrayEquals(original, file(2026).readBytes())
        val sizeEntry = publish(); sizeEntry.put("bytes", 1); manifest(sizeEntry)
        assertNull(repository().download(2026)); assertArrayEquals(original, file(2026).readBytes())
        val broken = "broken JSON".toByteArray()
        val invalidEntry = publish().put("bytes", broken.size).put("sha256",
            MessageDigest.getInstance("SHA-256").digest(broken)
                .joinToString("") { "%02x".format(it.toInt() and 255) })
        manifest(invalidEntry); responses["holidays/2026.json"] = broken
        assertNull(repository().download(2026)); assertArrayEquals(original, file(2026).readBytes())
    }

    @Test fun unsafePathsAndUnsupportedManifestAreRejected() {
        for (path in listOf("../2026.json", "https://example.invalid/data.json", "holidays/../2026.json")) {
            val entry = publish(); entry.put("path", path); manifest(entry)
            assertNull(repository().download(2026)); assertFalse(file(2026).exists())
        }
        responses["manifest.json"] = "{\"schemaVersion\":2,\"holidays\":[]}".toByteArray()
        assertNull(repository().download(2026))
    }

    @Test fun wrongYearDuplicateRecordsAndTooManyRecordsAreRejectedEvenWithValidHash() {
        publish(body = fixture(2027)); assertNull(repository().download(2026))
        val duplicate = fixture(2026)
        duplicate.getJSONArray("holidays").put(duplicate.getJSONArray("holidays").getJSONObject(0))
        publish(body = duplicate); assertNull(repository().download(2026))
        val many = fixture(2026).put("holidays", JSONArray().apply {
            repeat(101) { put(JSONObject().put("date", "2026-02-19").put("title", "Event $it").put("description", "")) }
        })
        publish(body = many); assertNull(repository().download(2026))
        assertFalse(file(2026).exists())
    }

    @Test fun serverFailureAndInvalidLocalFileAllowBuiltInFallback() {
        assertNull(repository().download(2026))
        file(2026).writeText("{broken}")
        assertNull(repository().loadSaved(2026))
        assertNull(HolidayCalendar.ramadanDay(LocalDate.of(2026, 2, 19)))
        assertNull(HolidayCalendar.holidayFor(LocalDate.of(2026, 2, 19)))
        assertEquals("Ночь Мирадж", HolidayCalendar.holidayFor(LocalDate.of(2026, 1, 16))?.title)
    }

    @Test fun atomicBackupIsRecoveredWhenBaseFileIsMissing() {
        File(file(2026).path + ".bak").writeText(fixture(2026).toString())
        assertNotNull(repository().loadSaved(2026))
        assertTrue(file(2026).exists())
    }

    @Test fun currentYearCleanupRemovesOnlyPastYearsAndPreservesFutureCache() {
        file(2026).writeText(fixture(2026).toString())
        file(2028).writeText(fixture(2028).toString())
        year = 2027
        assertNull(repository().download(2027)); assertTrue(file(2026).exists())
        publish(2027); assertNotNull(repository().download(2027))
        assertFalse(file(2026).exists()); assertTrue(file(2028).exists())
        assertNotNull(repository().loadSaved(2027))
    }

    @Test fun oversizedStreamIsRejectedBeforeItIsFullyConsumed() {
        val input = ByteArrayInputStream(ByteArray(1024 * 1024))
        assertThrows(IllegalArgumentException::class.java) { readHolidayBytes(input, 65536) }
        assertTrue(input.available() > 900000)
        file(2026).writeBytes(ByteArray(65537))
        assertNull(repository().loadSaved(2026))
    }
    @Test fun duplicateManifestYearsAndMissingHashAreRejected() {
        val entry = publish()
        responses["manifest.json"] = JSONObject().put("schemaVersion", 1)
            .put("holidays", JSONArray().put(entry).put(entry)).toString().toByteArray()
        assertNull(repository().download(2026))
        entry.remove("sha256"); manifest(entry)
        assertNull(repository().download(2026))
    }
    @Test fun downgradeAndSameVersionDifferentHashCannotReplaceSnapshot() {
        val entry = publish().put("version", 2); manifest(entry)
        assertNotNull(repository().download(2026))
        val original = file(2026).readBytes()
        manifest(entry.put("version", 1))
        assertNull(repository().download(2026))
        publish(body = fixture(2026).put("source", "changed")).also { manifest(it.put("version", 2)) }
        assertNull(repository().download(2026))
        assertArrayEquals(original, file(2026).readBytes())
        val next = publish(body = fixture(2026).put("source", "changed")); manifest(next.put("version", 3))
        assertNotNull(repository().download(2026))
    }
    @Test fun modifiedPayloadFailsHashOnRestartAndFallsBackToBuiltIn() {
        publish(); assertNotNull(repository().download(2026))
        val snapshot = JSONObject(file(2026).readText())
        snapshot.put("payload", snapshot.getString("payload").replace("Начало Рамадана", "Подмена"))
        file(2026).writeText(snapshot.toString())
        assertNull(repository().loadSaved(2026))
        assertNull(HolidayCalendar.ramadanDay(LocalDate.of(2026, 2, 19)))
        assertNull(HolidayCalendar.holidayFor(LocalDate.of(2026, 2, 19)))
        assertEquals("Ночь Мирадж", HolidayCalendar.holidayFor(LocalDate.of(2026, 1, 16))?.title)
    }
    @Test fun legacyValidatedCacheMigratesWithoutLosingOfflineCalendar() {
        file(2026).writeText(fixture(2026).toString())
        assertNotNull(repository().loadSaved(2026))
        publish(); assertNotNull(repository().download(2026))
        assertEquals(1, JSONObject(file(2026).readText()).getInt("cacheSchemaVersion"))
    }
    @Test fun wrongTypesDatesAndBoundedTextAreRejected() {
        listOf("2026-02-30", "+2026-02-19", "2027-02-19").forEach { date ->
            val body = fixture(2026); body.getJSONArray("holidays").getJSONObject(0).put("date", date)
            publish(body = body); assertNull(repository().download(2026))
        }
        listOf("title" to 123, "night" to "true", "title" to "x".repeat(101), "description" to "x".repeat(1001)).forEach { (key, value) ->
            val body = fixture(2026); body.getJSONArray("holidays").getJSONObject(0).put(key, value)
            publish(body = body); assertNull(repository().download(2026))
        }
    }

}
