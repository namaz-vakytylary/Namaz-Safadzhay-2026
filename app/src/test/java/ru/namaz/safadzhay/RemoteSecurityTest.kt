package ru.namaz.safadzhay

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class RemoteSecurityTest {
    @Test fun ambiguousOrPathologicalJsonIsRejected() {
        val inputs = listOf("{\"a\":1,\"a\":2}", "{\"a\":{\"x\":1,\"x\":2}}", "{}{}",
            "{broken}", "{\"a\":", "{'a':1}", "{a:1}", "{\"a\":NaN}",
            "{\"a\":1,}", "/* comment */{}", "{\"a\":" + "[".repeat(30) + "0" + "]".repeat(30) + "}")
        inputs.forEach { raw -> assertThrows(Exception::class.java) { RemoteJson.objectFrom(raw.toByteArray()) } }
        assertThrows(Exception::class.java) { RemoteJson.objectFrom(byteArrayOf(123,34,97,34,58,34,0xc0.toByte(),34,125)) }
    }
    @Test fun integersAndBooleansAreNotCoercedOrWrapped() {
        listOf("\"1\"", "1.5", "4294967297", "1e400", "null", "true").forEach { number ->
            assertThrows(Exception::class.java) { RemoteJson.objectFrom("{\"n\":$number}".toByteArray()).strictInt("n") }
        }
        assertThrows(Exception::class.java) { RemoteJson.objectFrom("{\"b\":\"true\"}".toByteArray()).strictBoolean("b") }
        assertEquals(1, RemoteJson.objectFrom("{\"n\":1}".toByteArray()).strictInt("n"))
    }
    @Test fun fixedOriginRejectsAllUrlAndTraversalVariantsBeforeConnecting() {
        val base = ScheduleRepository.BASE_URL
        val urls = listOf("http://namaz-vakytylary.github.io/namaz-schedules/2026.json",
            "https://evil.invalid/2026.json", "https://namaz-vakytylary.github.io.evil.invalid/namaz-schedules/2026.json",
            base + "../2026.json", base + "%2e%2e/2026.json", base + "https://evil.invalid/x.json",
            base + "//evil.invalid/x.json", base + "holidays\\2026.json", base + "holidays%2f2026.json",
            base + "2026.json?x=1", base + "2026.json#x", base + "\uFF0E\uFF0E/2026.json",
            base + "2026.json\n", base + "holidays/%252e%252e/x.json")
        urls.forEach { url -> assertThrows(IllegalArgumentException::class.java) {
            RemoteTransport.download(url, 1024, 1000) { fail("Network must not be reached"); throw AssertionError() }
        } }
        assertEquals(base + "holidays/2026.json", RemoteTransport.validateUrl(base + "holidays/2026.json").toString())
    }
    private class TrackedStream(body: ByteArray) : ByteArrayInputStream(body) {
        var closed = false
        override fun close() { closed = true; super.close() }
    }
    private class FakeConnection(private val status: Int, body: ByteArray, private val size: Long = -1) :
        HttpURLConnection(URL(ScheduleRepository.BASE_URL + "manifest.json")) {
        val stream = TrackedStream(body)
        var disconnected = false
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getInputStream() = stream
        override fun getHeaderField(name: String): String? = if (name == "Content-Length" && size >= 0) size.toString() else null
    }
    @Test fun redirectsOversizeAndTruncatedResponsesAreRejectedAndDisconnected() {
        val cases = listOf(FakeConnection(302, "{}".toByteArray()),
            FakeConnection(200, ByteArray(20), 20), FakeConnection(200, ByteArray(20)),
            FakeConnection(200, "{}".toByteArray(), 5))
        cases.forEach { connection ->
            assertThrows(IllegalArgumentException::class.java) {
                RemoteTransport.download(ScheduleRepository.BASE_URL + "manifest.json", 10, 1234) { connection }
            }
            assertFalse(connection.instanceFollowRedirects)
            assertTrue(connection.disconnected)
            assertEquals(1234, connection.connectTimeout)
            assertEquals(1234, connection.readTimeout)
        }
        assertTrue(cases[2].stream.closed)
        assertTrue(cases[3].stream.closed)
    }
    @Test fun successfulStreamIsClosedAndResponseIsBounded() {
        val connection = FakeConnection(200, "{}".toByteArray(), 2)
        assertArrayEquals("{}".toByteArray(), RemoteTransport.download(ScheduleRepository.BASE_URL + "manifest.json", 10, 1000) { connection })
        assertTrue(connection.stream.closed && connection.disconnected)
    }
}
