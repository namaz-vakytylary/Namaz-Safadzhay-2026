package ru.namaz.safadzhay

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

internal object RemoteTransport {
    private val progress = ThreadLocal<((Int) -> Unit)?>()
    fun <T> withProgress(observer: (Int) -> Unit, block: () -> T): T {
        val previous = progress.get(); progress.set(observer)
        return try { block() } finally { progress.set(previous) }
    }
    private val deadlines = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "schedule-deadlines").apply { isDaemon = true }
    }
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
        val deadline = deadlines.schedule({ connection.disconnect() }, 25, java.util.concurrent.TimeUnit.SECONDS)
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
            val bytes = connection.inputStream.use { readBoundedBytes(it, limit) { count -> progress.get()?.invoke(count) } }
            require(declared < 0 || declared == bytes.size.toLong())
            return bytes
        } finally { deadline.cancel(false); connection.disconnect() }
    }
}

internal fun readBoundedBytes(input: InputStream, limit: Int, onRead: (Int) -> Unit = {}): ByteArray {
    require(limit > 0)
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Cancelled")
        val count = input.read(buffer)
        if (count < 0) break
        require(count <= limit - output.size()) { "Response size limit" }
        output.write(buffer, 0, count)
        onRead(output.size())
    }
    require(output.size() > 0)
    return output.toByteArray()
}
