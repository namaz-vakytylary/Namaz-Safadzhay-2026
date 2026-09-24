package ru.namaz.safadzhay

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * Удалённые исламские праздники.
 *
 * Если загрузка или проверка не удалась, приложение продолжает
 * использовать встроенный HolidayCalendar.
 */
internal class HolidayRepository(context: Context) {

    private val filesDir = context.filesDir

private fun fileFor(year: Int): AtomicFile =
    AtomicFile(File(filesDir, "downloaded-holidays-$year.json"))

    fun loadSaved(year: Int): List<Holiday>? {
    return try {
        val file = fileFor(year)

        if (!file.baseFile.exists()) return null

        val raw = file.openRead().bufferedReader().use { it.readText() }
        parse(raw, year)
    } catch (_: Exception) {
        null
    }
}

    fun download(year: Int): List<Holiday>? {
        return try {
            require(year in 2026..2100)

            val url = URL("$BASE_URL$year.json")
            val connection = url.openConnection() as HttpURLConnection

            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"

            try {
                require(connection.responseCode == HttpURLConnection.HTTP_OK)

                val bytes = connection.inputStream.use {
                    it.readBytes()
                }

                require(bytes.isNotEmpty())
                require(bytes.size <= MAX_FILE_SIZE)

                val raw = bytes.toString(Charsets.UTF_8)
                val holidays = parse(raw, year)

                save(raw)

                holidays
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun save(year: Int, raw: String) {
    val bytes = raw.toByteArray(Charsets.UTF_8)
    require(bytes.size <= MAX_FILE_SIZE)

    val file = fileFor(year)
    val stream = file.startWrite()

    try {
        stream.write(bytes)
        file.finishWrite(stream)
    } catch (error: Exception) {
        file.failWrite(stream)
        throw error
    }
}

    internal fun parse(raw: String, expectedYear: Int): List<Holiday> {
        val root = JSONObject(raw)

        require(root.getInt("year") == expectedYear)

        val source = root.getString("source")
        require(source.length <= 200)

        val array = root.getJSONArray("holidays")
        require(array.length() <= MAX_HOLIDAYS)

        val result = mutableListOf<Holiday>()

        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)

            val date = LocalDate.parse(item.getString("date"))
            require(date.year == expectedYear)

            val title = item.getString("title")
            val description = item.getString("description")
            val night = item.optBoolean("night", false)

            require(title.isNotBlank() && title.length <= 100)
            require(description.length <= 1000)

            result += Holiday(
                date = date,
                title = title,
                description = description,
                sourceName = source,
                night = night
            )
        }

        require(
            result.map { it.date to it.title }.distinct().size == result.size
        )

        return result.sortedBy { it.date }
    }

    companion object {
        private const val BASE_URL =
            "https://namaz-vakytylary.github.io/namaz-schedules/holidays/"

        private const val MAX_FILE_SIZE = 64 * 1024
        private const val MAX_HOLIDAYS = 100
    }
}
