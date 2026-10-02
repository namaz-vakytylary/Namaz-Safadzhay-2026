package ru.namaz.safadzhay

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.time.LocalDate

/** Verified private snapshot; legacy raw caches remain readable during migration. */
internal class HolidayRepository(
    context: Context,
    private val downloadBytes: (String, Int) -> ByteArray = ::downloadHolidayFile,
    private val currentYear: () -> Int = { LocalDate.now(java.time.ZoneId.of("Europe/Moscow")).year }
) {
    private val filesDir = context.filesDir
    private data class Entry(val json: JSONObject, val year: Int, val version: Int,
        val path: String, val size: Int, val sha: String)
    private data class Saved(val version: Int, val sha: String, val holidays: List<Holiday>)

    private fun fileFor(year: Int): AtomicFile {
        require(year in 2026..2100)
        return AtomicFile(File(filesDir, "downloaded-holidays-$year.json"))
    }

    @Synchronized fun loadSaved(year: Int): List<Holiday>? = readSaved(year)?.holidays

    private fun readSaved(year: Int): Saved? = try {
        val bytes = fileFor(year).openRead().use { readBoundedBytes(it, MAX_SNAPSHOT_SIZE) }
        val root = RemoteJson.objectFrom(bytes)
        if (root.has("cacheSchemaVersion")) {
            require(root.strictInt("cacheSchemaVersion") == 1)
            val entry = parseEntry(root.getJSONObject("entry"))
            require(entry.year == year)
            val payload = root.strictString("payload").toByteArray(Charsets.UTF_8)
            Saved(entry.version, entry.sha, verified(entry, payload))
        } else {
            // Old releases saved no hash/version. Validate, retain offline, then upgrade
            // on the first verified download. This cannot retroactively authenticate it.
            Saved(0, sha256(bytes), parse(bytes.toString(Charsets.UTF_8), year))
        }
    } catch (_: Exception) { null }

    @Synchronized fun download(year: Int): List<Holiday>? = try {
        require(year in 2026..2100)
        val manifestBytes = downloadBytes(ScheduleRepository.BASE_URL + "manifest.json", MAX_MANIFEST_SIZE)
        require(manifestBytes.size <= MAX_MANIFEST_SIZE)
        val manifest = RemoteJson.objectFrom(manifestBytes)
        require(manifest.strictInt("schemaVersion") == 1)
        val array = manifest.getJSONArray("holidays")
        require(array.length() <= 100)
        val entries = (0 until array.length()).map { parseEntry(array.getJSONObject(it)) }
        require(entries.map { it.year }.distinct().size == entries.size)
        val entry = entries.singleOrNull { it.year == year }
        if (entry == null) null else {
            val old = readSaved(year)
            require(old == null || entry.version >= old.version)
            require(old == null || entry.version != old.version || entry.sha == old.sha)
            val bytes = downloadBytes(ScheduleRepository.BASE_URL + entry.path, entry.size)
            val holidays = verified(entry, bytes)
            val snapshot = JSONObject().put("cacheSchemaVersion", 1).put("entry", entry.json)
                .put("payload", bytes.toString(Charsets.UTF_8)).toString().toByteArray(Charsets.UTF_8)
            require(snapshot.size <= MAX_SNAPSHOT_SIZE)
            val file = fileFor(year)
            val stream = file.startWrite()
            try { stream.write(snapshot); file.finishWrite(stream) }
            catch (error: Exception) { file.failWrite(stream); throw error }
            val nowYear = currentYear()
            if (year == nowYear) filesDir.listFiles()?.filter {
                val savedYear = Regex("downloaded-holidays-(\\d{4})\\.json")
                    .matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull()
                savedYear != null && savedYear < nowYear
            }?.forEach { AtomicFile(it).delete() }
            holidays
        }
    } catch (_: Exception) { null }

    private fun parseEntry(obj: JSONObject): Entry {
        val year = obj.strictInt("year")
        val version = obj.strictInt("version")
        val path = obj.strictString("path")
        val size = obj.strictInt("bytes")
        val sha = obj.strictString("sha256")
        require(year in 2026..2100 && version > 0)
        require(path.matches(Regex("holidays/[A-Za-z0-9_-]+\\.json")))
        require(size in 1..MAX_FILE_SIZE && sha.matches(Regex("[a-f0-9]{64}")))
        return Entry(obj, year, version, path, size, sha)
    }

    private fun verified(entry: Entry, bytes: ByteArray): List<Holiday> {
        require(bytes.size == entry.size && sha256(bytes) == entry.sha)
        RemoteJson.objectFrom(bytes)
        return parse(bytes.toString(Charsets.UTF_8), entry.year)
    }

    internal fun parse(raw: String, expectedYear: Int): List<Holiday> {
        require(expectedYear in 2026..2100)
        val bytes = raw.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FILE_SIZE)
        val root = RemoteJson.objectFrom(bytes)
        require(root.strictInt("year") == expectedYear)
        val source = root.strictString("source")
        require(source.length <= 200)
        val array = root.getJSONArray("holidays")
        require(array.length() <= MAX_HOLIDAYS)
        val result = (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            val rawDate = item.strictString("date")
            val date = LocalDate.parse(rawDate)
            require(date.year == expectedYear && date.toString() == rawDate)
            val title = item.strictString("title")
            val description = item.strictString("description")
            val night = if (item.has("night")) item.strictBoolean("night") else false
            require(title.isNotBlank() && title.length <= 100 && description.length <= 1000)
            Holiday(date, title, description, source, night)
        }
        require(result.map { it.date to it.title }.distinct().size == result.size)
        return result.sortedBy { it.date }
    }

    companion object {
        private const val MAX_FILE_SIZE = 64 * 1024
        private const val MAX_SNAPSHOT_SIZE = 160 * 1024
        private const val MAX_MANIFEST_SIZE = 128 * 1024
        private const val MAX_HOLIDAYS = 100
        private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

internal fun readHolidayBytes(input: InputStream, maxSize: Int): ByteArray = readBoundedBytes(input, maxSize)
private fun downloadHolidayFile(url: String, maxSize: Int): ByteArray = RemoteTransport.download(url, maxSize, 8000)
