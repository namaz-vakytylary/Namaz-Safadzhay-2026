package ru.namaz.safadzhay

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

internal data class PrayerDay(
    val date: String, val fajr: String, val zuhr: String,
    val asr: String, val maghrib: String, val isha: String
)

/** A single verified snapshot in filesDir, never in the disposable Android cache. */
internal class ScheduleRepository(
    context: Context,
    private val download: (String, Int) -> ByteArray = ::downloadScheduleFile,
    private val clock: () -> Long = System::currentTimeMillis,
   private val currentYear: () -> Int = {
    LocalDate.now(java.time.ZoneId.of("Europe/Moscow")).year
}
) {
    private val prefs = context.getSharedPreferences("schedule_updates", Context.MODE_PRIVATE)
    private val file = AtomicFile(File(context.filesDir, "downloaded-schedules.json"))
    @Volatile private var bundles: Map<Int, Bundle> = readSaved()

    data class Result(val changed: Boolean)
    data class Entry(val json: JSONObject, val year: Int, val version: Int, val path: String,
        val cities: Set<String>, val from: LocalDate, val to: LocalDate, val size: Int, val sha: String)
    data class Bundle(val entry: Entry, val raw: String, val cities: Map<String, List<PrayerDay>>)

    fun merged(cityId: String, builtIn: List<PrayerDay>): List<PrayerDay> {
        val result = builtIn.associateBy { it.date }.toMutableMap()
        bundles.toSortedMap().values.forEach { bundle ->
            bundle.cities[cityId]?.forEach { result[it.date] = it }
        }
        return result.values.sortedBy { it.date }
    }
    fun dataForYear(
    cityId: String,
    year: Int,
    builtIn: List<PrayerDay>
): List<PrayerDay> {
    val downloaded = bundles[year]?.cities?.get(cityId)

    if (!downloaded.isNullOrEmpty()) {
        return downloaded.sortedBy { it.date }
    }

    return builtIn
        .filter { LocalDate.parse(it.date).year == year }
        .sortedBy { it.date }
    }

    fun lastSuccess(): Long = prefs.getLong("last_success", 0)
    fun hasDownloads(): Boolean = bundles.isNotEmpty()
    fun revision(): String = bundles.toSortedMap().values.joinToString("|") { it.entry.sha }

    
    @Synchronized fun sync(
    online: Boolean,
    onProgress: (year: Int, progress: Int) -> Unit = { _, _ -> }
): Result {
    fun report(year: Int, progress: Int) {
        runCatching { onProgress(year, progress) }
    }

    if (!online) return Result(false)
    val now = clock()

    return try {
        val entries = parseManifest(download(BASE_URL + "manifest.json", MAX_MANIFEST))
        val currentYear = currentYear()
        val next = bundles.toMutableMap()
        val updatedYears = mutableListOf<Int>()
        var changed = false

        entries.forEach { entry ->
            if (entry.year < currentYear) return@forEach
            
            val old = bundles[entry.year]

            if (old != null && entry.version < old.entry.version) return@forEach

            if (old != null && entry.version == old.entry.version) {
                require(entry.sha == old.entry.sha) {
                    "A changed schedule needs a new version"
                }
                return@forEach
            }

                        report(entry.year, 12)

            require(next.values.filter { it.entry.year != entry.year }.sumOf { it.entry.size.toLong() } + entry.size <= MAX_TOTAL_PAYLOAD)
            val bytes = download(BASE_URL + entry.path, entry.size)
            report(entry.year, 68)

            next[entry.year] = parseBundle(entry, bytes)

            report(entry.year, 88)

            updatedYears.add(entry.year)
            changed = true
        }
        if (next.containsKey(currentYear)) {
    val oldYears = next.keys.filter { it < currentYear }

    if (oldYears.isNotEmpty()) {
        oldYears.forEach { next.remove(it) }
        changed = true
    }
}

        if (changed) {
            val snapshot = JSONObject().put("schemaVersion", 1)
            val saved = org.json.JSONArray()

            next.toSortedMap().values.forEach {
                saved.put(
                    JSONObject()
                        .put("entry", it.entry.json)
                        .put("payload", it.raw)
                )
            }

            snapshot.put("bundles", saved)

            val serialized = snapshot.toString().toByteArray(Charsets.UTF_8)
            require(serialized.size <= MAX_SNAPSHOT)

            val stream = file.startWrite()

            try {
                stream.write(serialized)
                file.finishWrite(stream)
            } catch (error: Exception) {
                file.failWrite(stream)
                throw error
            }

            bundles = next.toMap()

            updatedYears.forEach {
                report(it, 100)
            }
        }

        prefs.edit()
            .putLong("last_success", now)
            .commit()

        Result(changed)

    } catch (error: Exception) {
        android.util.Log.w(
            "ScheduleRepository",
            "Schedule update rejected: ${error.javaClass.simpleName}"
        )
        Result(false)
    }
}

    private fun readSaved(): Map<Int, Bundle> = try {
        val bytes = file.openRead().use { readLimited(it, MAX_SNAPSHOT) }
        val root = RemoteJson.objectFrom(bytes)
        require(root.strictInt("schemaVersion") == 1)
        val array = root.getJSONArray("bundles")
        require(array.length() <= 40)
        val result = mutableMapOf<Int, Bundle>()
        var totalSize = 0L
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val entry = parseEntry(item.getJSONObject("entry"))
            require(!result.containsKey(entry.year))
            totalSize += entry.size
            require(totalSize <= MAX_TOTAL_PAYLOAD)
            result[entry.year] = parseBundle(entry, item.strictString("payload").toByteArray(Charsets.UTF_8))
        }
        result
    } catch (_: Exception) { emptyMap() }

    companion object {
        @Volatile private var instance: ScheduleRepository? = null
        fun get(context: Context): ScheduleRepository = instance ?: synchronized(this) {
            instance ?: ScheduleRepository(context.applicationContext).also { instance = it }
        }
        const val BASE_URL = "https://namaz-vakytylary.github.io/namaz-schedules/"
        private const val MAX_MANIFEST = 128 * 1024
        private const val MAX_BUNDLE = 1024 * 1024
        private const val MAX_SNAPSHOT = 8 * 1024 * 1024
        private const val MAX_TOTAL_PAYLOAD = 2 * 1024 * 1024
        private val supportedCities = setOf("safadzhay", "moscow")
        fun supportedCityIds(): Set<String> = supportedCities.toSet()
        private val prayerKeys = listOf("fajr", "zuhr", "asr", "maghrib", "isha")

        fun parseManifest(bytes: ByteArray): List<Entry> {
            require(bytes.size <= MAX_MANIFEST)
            val root = RemoteJson.objectFrom(bytes)
            require(root.strictInt("schemaVersion") == 1)
            val array = root.getJSONArray("schedules")
            require(array.length() <= 40)
            val entries = (0 until array.length()).map { parseEntry(array.getJSONObject(it)) }
            require(entries.map { it.year }.distinct().size == entries.size)
            return entries
        }

        private fun parseEntry(obj: JSONObject): Entry {
            val year = obj.strictInt("year")
            val version = obj.strictInt("version")
            require(year in 2026..2100 && version > 0)
            val path = obj.strictString("path")
            // Only a simple relative JSON path under our HTTPS host; no arbitrary URLs.
            require(path.matches(Regex("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*\\.json")))
            val fromText = obj.strictString("dateFrom")
            val toText = obj.strictString("dateTo")
            val from = LocalDate.parse(fromText)
            val to = LocalDate.parse(toText)
            require(from.toString() == fromText && to.toString() == toText)
            require(from.year == year && to.year == year && !to.isBefore(from))
            require(obj.strictInt("daysPerCity") == ChronoUnit.DAYS.between(from, to).toInt() + 1)
            require(obj.strictBoolean("completeYear") ==
                (from == LocalDate.of(year, 1, 1) && to == LocalDate.of(year, 12, 31)))
            val ids = obj.getJSONArray("cityIds")
            require(ids.length() in 1..supportedCities.size)
            val cities = (0 until ids.length()).map { (ids.get(it) as? String ?: throw IllegalArgumentException("Expected city id")) }
            require(cities.isNotEmpty() && cities.size == cities.distinct().size && supportedCities.containsAll(cities))
            val size = obj.strictInt("bytes")
            val sha = obj.strictString("sha256")
            require(size in 1..MAX_BUNDLE && sha.matches(Regex("[a-f0-9]{64}")))
            return Entry(obj, year, version, path, cities.toSet(), from, to, size, sha)
        }

        fun parseBundle(entry: Entry, bytes: ByteArray): Bundle {
            require(bytes.size == entry.size)
            val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            require(sha == entry.sha)
            val raw = bytes.toString(Charsets.UTF_8)
            val root = RemoteJson.objectFrom(bytes)
            require(root.strictInt("schemaVersion") == 1 && root.strictInt("year") == entry.year && root.strictInt("version") == entry.version)
            val coverage = root.getJSONObject("coverage")
            require(coverage.strictString("from") == entry.from.toString() && coverage.strictString("to") == entry.to.toString())
            require(coverage.strictBoolean("completeYear") == entry.json.strictBoolean("completeYear"))
            val array = root.getJSONArray("cities")
            val result = mutableMapOf<String, List<PrayerDay>>()
            for (i in 0 until array.length()) {
                val city = array.getJSONObject(i)
                val id = city.strictString("id")
                require(id in entry.cities && !result.containsKey(id))
                require(city.strictString("timeZone") == "Europe/Moscow")
                val days = city.getJSONArray("days")
                require(days.length() == entry.json.strictInt("daysPerCity"))
                result[id] = (0 until days.length()).map { dayIndex ->
                    val day = days.getJSONObject(dayIndex)
                    val date = day.strictString("date")
                    require(date == entry.from.plusDays(dayIndex.toLong()).toString())
                    val times = prayerKeys.map { key ->
                        day.strictString(key).also { require(it.matches(Regex("(?:[01]\\d|2[0-3]):[0-5]\\d"))); LocalTime.parse(it) }
                    }
                    require(times.zipWithNext().all { (a, b) -> a < b })
                    PrayerDay(date, times[0], times[1], times[2], times[3], times[4])
                }
            }
            require(result.keys == entry.cities)
            return Bundle(entry, raw, result)
        }
    }
}

private fun readLimited(input: java.io.InputStream, limit: Int): ByteArray =
    readBoundedBytes(input, limit)

private fun downloadScheduleFile(url: String, limit: Int): ByteArray =
    RemoteTransport.download(url, limit, 15000)
