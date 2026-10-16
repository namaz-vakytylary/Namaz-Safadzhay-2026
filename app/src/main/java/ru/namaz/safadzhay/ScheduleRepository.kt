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
},
    private val loadImmediately: Boolean = true
) {
    private val prefs = context.getSharedPreferences("schedule_updates", Context.MODE_PRIVATE)
    private val file = AtomicFile(File(context.filesDir, "downloaded-schedules.json"))
    @Volatile private var bundles: Map<Int, Bundle> = if (loadImmediately) readSaved() else emptyMap()
    @Volatile var isLoaded: Boolean = loadImmediately
        private set

    init { if (loadImmediately) runCatching { rememberRevisions(bundles) } }

    /** Production calls this only from the common update/alarm workers. */
    fun loadSavedIfNeeded(): Boolean {
        if (isLoaded) return false
        return synchronized(this) {
            if (isLoaded) false else {
                bundles = readSaved()
                runCatching { rememberRevisions(bundles) }
                isLoaded = true
                bundles.isNotEmpty()
            }
        }
    }

    private fun rememberRevisions(values: Map<Int, Bundle>) {
        val edit = prefs.edit()
        values.values.forEach { bundle ->
            val key = "accepted_${bundle.entry.year}"
            if (bundle.entry.version >= prefs.getInt(key, 0)) {
                edit.putInt(key, bundle.entry.version).putString("${key}_sha", bundle.entry.sha)
            }
        }
        check(edit.commit()) { "Cannot persist revision history" }
    }

    data class Result(val changed: Boolean, val successful: Boolean = true)
    data class Entry(val json: JSONObject, val year: Int, val version: Int, val path: String,
        val cities: Set<String>, val from: LocalDate, val to: LocalDate, val size: Int, val sha: String)
    data class Bundle(val entry: Entry, val raw: String, val cities: Map<String, List<PrayerDay>>)

    /** Both UI and reminders use only the same validated downloaded snapshot. */
    fun days(cityId: String): List<PrayerDay> = bundles.toSortedMap().values
        .flatMap { it.cities[cityId].orEmpty() }
    fun dataForYear(cityId: String, year: Int): List<PrayerDay> =
        bundles[year]?.cities?.get(cityId).orEmpty()
    fun day(cityId: String, date: LocalDate): PrayerDay? =
        dataForYear(cityId, date.year).firstOrNull { it.date == date.toString() }

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

    val loadedSaved = loadSavedIfNeeded()
    if (!online) return Result(loadedSaved, successful = false)
    val now = clock()
    val beforeRevision = revision()

    return try {
        report(currentYear(), 0)
        val entries = parseManifest(download(BASE_URL + "manifest.json", MAX_MANIFEST))
        report(currentYear(), 12)
        val currentYear = currentYear()
        val next = bundles.toMutableMap()
        val updatedYears = mutableListOf<Int>()
        var changed = false

        entries.forEach { entry ->
            if (entry.year < currentYear) return@forEach
            
            val old = bundles[entry.year]
            val floor = prefs.getInt("accepted_${entry.year}", 0)
            require(entry.version >= floor) { "Schedule rollback" }
            require(entry.version != floor || entry.sha == prefs.getString("accepted_${entry.year}_sha", null)) {
                "Changed data at accepted revision"
            }

            if (old != null) {
                require(entry.cities.containsAll(old.entry.cities) && !entry.from.isAfter(old.entry.from) && !entry.to.isBefore(old.entry.to)) {
                    "An update must preserve previously downloaded city/date coverage"
                }
            }
            if (old != null && entry.version < old.entry.version) return@forEach

            if (old != null && entry.version == old.entry.version) {
                require(entry.sha == old.entry.sha) {
                    "A changed schedule needs a new version"
                }
                return@forEach
            }

                        report(entry.year, 12)

            require(next.values.filter { it.entry.year != entry.year }.sumOf { it.entry.size.toLong() } + entry.size <= MAX_TOTAL_PAYLOAD)
            val bytes = RemoteTransport.withProgress({ count ->
                report(entry.year, 12 + (count.toLong() * 56 / entry.size).toInt().coerceIn(0, 56))
            }) { download(BASE_URL + entry.path, entry.size) }
            report(entry.year, 68)

            next[entry.year] = parseBundle(entry, bytes)

            report(entry.year, 88)

            updatedYears.add(entry.year)
            changed = true
        }
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Cancelled")
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
            rememberRevisions(bundles)

            updatedYears.forEach {
                report(it, 100)
            }
        }

        check(prefs.edit().putLong("last_success", now).commit()) { "Cannot persist update timestamp" }

        report(currentYear(), 100)
        Result(changed || loadedSaved)

    } catch (error: Exception) {
        android.util.Log.w(
            "ScheduleRepository",
            "Schedule update rejected: ${error.javaClass.simpleName}"
        )
        // If the payload was committed but metadata failed, still refresh UI/alarms.
        Result(loadedSaved || revision() != beforeRevision, successful = false)
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
            val floor = prefs.getInt("accepted_${entry.year}", 0)
            require(entry.version >= floor)
            require(entry.version != floor || entry.sha == prefs.getString("accepted_${entry.year}_sha", null))
            totalSize += entry.size
            require(totalSize <= MAX_TOTAL_PAYLOAD)
            result[entry.year] = parseBundle(entry, item.strictString("payload").toByteArray(Charsets.UTF_8))
        }
        result
    } catch (_: Exception) { emptyMap() }

    companion object {
        @Volatile private var instance: ScheduleRepository? = null
        fun get(context: Context): ScheduleRepository = instance ?: synchronized(this) {
            instance ?: ScheduleRepository(context.applicationContext, loadImmediately = false).also { instance = it }
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
