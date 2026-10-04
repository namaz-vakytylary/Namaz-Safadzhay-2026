package ru.namaz.safadzhay

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RamadanRemoteTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val responses = mutableMapOf<String, ByteArray>()
    private fun repository() = HolidayRepository(app, { url, limit ->
        readHolidayBytes(ByteArrayInputStream(responses[url.removePrefix(ScheduleRepository.BASE_URL)]
            ?: throw IOException("Offline")), limit)
    }, { 2026 })

    @Before fun reset() {
        app.filesDir.listFiles()?.filter { it.name.startsWith("downloaded-holidays-") }?.forEach { it.delete() }
        clear()
    }
    @After fun clear() {
        for (year in listOf(2026, 2028)) HolidayCalendar.setRemote(year, null)
    }
    private fun publish(start: LocalDate?, end: LocalDate?, year: Int = 2026, version: Int = 1): JSONObject {
        val events = JSONArray()
        for ((date, title) in listOf(start to "Начало Рамадана", end to "Ураза-байрам")) {
            if (date != null) events.put(JSONObject().put("date", date.toString()).put("title", title).put("description", ""))
        }
        val payload = JSONObject().put("year", year).put("source", "Unit-test remote response").put("holidays", events).toString().toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it.toInt() and 255) }
        val entry = JSONObject().put("year", year).put("version", version).put("path", "holidays/$year.json")
            .put("bytes", payload.size).put("sha256", hash)
        responses["manifest.json"] = JSONObject().put("schemaVersion", 1).put("holidays", JSONArray().put(entry)).toString().toByteArray()
        responses["holidays/$year.json"] = payload
        return entry
    }

    @Test fun verifiedRemoteDatesAndOfflineCacheComputeBoundaries() {
        val start = LocalDate.of(2026, 2, 19); val end = LocalDate.of(2026, 3, 20)
        publish(start, end)
        HolidayCalendar.setRemote(2026, repository().download(2026))
        assertNull(HolidayCalendar.ramadanDay(start.minusDays(1)))
        assertEquals(1, HolidayCalendar.ramadanDay(start))
        assertEquals(2, HolidayCalendar.ramadanDay(start.plusDays(1)))
        assertEquals(29, HolidayCalendar.ramadanDay(end.minusDays(1)))
        assertNull(HolidayCalendar.ramadanDay(end))
        assertNull(HolidayCalendar.ramadanDay(end.plusDays(1)))
        responses.clear()
        assertNull(repository().download(2026))
        HolidayCalendar.setRemote(2026, repository().loadSaved(2026))
        assertEquals(1, HolidayCalendar.ramadanDay(start))
    }

    @Test fun eitherMissingBoundaryDisablesRamadanWithoutBuiltInFallback() {
        val start = LocalDate.of(2026, 2, 19); val end = LocalDate.of(2026, 3, 20)
        for ((version, pair) in listOf(start to null, null to end, null to null).withIndex()) {
            publish(pair.first, pair.second, version = version + 1)
            val validated = repository().download(2026)
            assertNotNull(validated)
            HolidayCalendar.setRemote(2026, validated)
            assertNull(HolidayCalendar.ramadanDay(start))
            assertNull(HolidayCalendar.ramadanDay(start.plusDays(5)))
        }
        HolidayCalendar.setRemote(2026, null)
        assertNull(HolidayCalendar.ramadanDay(start))
        assertFalse(HolidayCalendar.items.any { it.title in listOf("Начало Рамадана", "Ураза-байрам") })
        assertEquals("Ночь Мирадж", HolidayCalendar.holidayFor(LocalDate.of(2026, 1, 16))?.title)
    }

    @Test fun changedRemoteDatesAndFutureYearNeedNoAppConstant() {
        val initial = LocalDate.of(2026, 2, 19)
        publish(initial, initial.plusDays(29))
        HolidayCalendar.setRemote(2026, repository().download(2026))
        val updated = initial.plusDays(1)
        publish(updated, updated.plusDays(30), version = 2)
        HolidayCalendar.setRemote(2026, repository().download(2026))
        assertNull(HolidayCalendar.ramadanDay(initial))
        assertEquals(1, HolidayCalendar.ramadanDay(updated))
        val future = LocalDate.of(2028, 3, 5)
        publish(future, future.plusDays(30), year = 2028)
        HolidayCalendar.setRemote(2028, repository().download(2028))
        assertEquals(30, HolidayCalendar.ramadanDay(future.plusDays(29)))
        assertNull(HolidayCalendar.ramadanDay(future.plusDays(30)))
    }

    @Test fun corruptedCachedPayloadCannotActivateRamadan() {
        val start = LocalDate.of(2026, 2, 19)
        publish(start, start.plusDays(29)); assertNotNull(repository().download(2026))
        val file = java.io.File(app.filesDir, "downloaded-holidays-2026.json")
        val snapshot = JSONObject(file.readText())
        snapshot.put("payload", snapshot.getString("payload").replace("Начало Рамадана", "Changed"))
        file.writeText(snapshot.toString())
        HolidayCalendar.setRemote(2026, repository().loadSaved(2026))
        assertNull(HolidayCalendar.ramadanDay(start))
    }
}
