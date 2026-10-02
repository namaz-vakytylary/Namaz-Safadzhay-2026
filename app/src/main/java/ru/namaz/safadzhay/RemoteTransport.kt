package ru.namaz.safadzhay

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

internal object RemoteTransport {
    fun validateUrl(url: String): URL {
        val suffix = url.removePrefix(ScheduleRepository.BASE_URL)
        require(url.startsWith(ScheduleRepository.BASE_URL) &&
            suffix.matches(Regex("[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)*\\.json")))
        return URL(url)
    }

    fun download(url: String, limit: Int, timeout: Int,
        connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }
    ): ByteArray {
        require(limit in 1..1024 * 1024)
        val connection = connect(validateUrl(url))
        try {
            connection.connectTimeout = timeout
            connection.readTimeout = timeout
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            require(connection.responseCode == HttpURLConnection.HTTP_OK)
            val declared = connection.getHeaderField("Content-Length")?.let { header ->
                require(header.matches(Regex("[0-9]{1,19}")))
                header.toLong()
            } ?: -1L
            require(declared <= limit)
            val bytes = connection.inputStream.use { readBoundedBytes(it, limit) }
            require(declared < 0 || declared == bytes.size.toLong())
            return bytes
        } finally { connection.disconnect() }
    }
}

internal fun readBoundedBytes(input: InputStream, limit: Int): ByteArray {
    require(limit > 0)
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(count <= limit - output.size()) { "Response size limit" }
        output.write(buffer, 0, count)
    }
    require(output.size() > 0)
    return output.toByteArray()
}
