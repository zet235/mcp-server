package net.portswigger.mcp.tools

import burp.api.montoya.core.ByteArray as BurpByteArray
import burp.api.montoya.http.message.requests.HttpRequest
import burp.api.montoya.http.message.responses.HttpResponse
import burp.api.montoya.proxy.ProxyHttpRequestResponse
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZonedDateTime
import java.util.Base64
import java.util.regex.Pattern

class HistoryExportTest {

    @TempDir
    lateinit var home: Path

    private fun bytes(value: kotlin.ByteArray) = mockk<BurpByteArray>().also { every { it.bytes } returns value }

    private fun historyItem(
        id: Int,
        host: String,
        method: String,
        path: String,
        requestBytes: kotlin.ByteArray,
        responseBytes: kotlin.ByteArray?
    ): ProxyHttpRequestResponse {
        val request = mockk<HttpRequest>().also { every { it.toByteArray() } returns bytes(requestBytes) }
        val response = responseBytes?.let { body ->
            mockk<HttpResponse>().also {
                every { it.toByteArray() } returns bytes(body)
                every { it.statusCode() } returns 200.toShort()
            }
        }
        return mockk<ProxyHttpRequestResponse>(relaxed = true).also {
            every { it.request() } returns request
            every { it.hasResponse() } returns (response != null)
            every { it.response() } returns response
            every { it.id() } returns id
            every { it.time() } returns ZonedDateTime.parse("2026-10-20T08:00:00Z")
            every { it.host() } returns host
            every { it.port() } returns 443
            every { it.secure() } returns true
            every { it.requestHttpVersion() } returns "HTTP/2"
            every { it.method() } returns method
            every { it.path() } returns path
            every { it.url() } returns "https://$host$path"
            every { it.edited() } returns false
            every { it.contains(any<Pattern>()) } answers {
                firstArg<Pattern>().matcher(String(requestBytes, Charsets.UTF_8)).find()
            }
        }
    }

    @Test
    fun `relative, blank and outside-home directories are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { validateExportDirectory("relative/dir", home) }
        assertThrows(IllegalArgumentException::class.java) { validateExportDirectory("", home) }
        assertThrows(IllegalArgumentException::class.java) { validateExportDirectory("/etc", home) }
        assertThrows(IllegalArgumentException::class.java) {
            validateExportDirectory(home.resolve("a/../../escape").toString(), home)
        }
        assertEquals(home.resolve("out"), validateExportDirectory(home.resolve("x/../out").toString(), home))
    }

    @Test
    fun `missing directory is created`() {
        val target = home.resolve("exports/nested")

        val prepared = prepareExportDirectory(target, home)

        assertTrue(Files.isDirectory(prepared))
        assertEquals(target.toRealPath(), prepared)
    }

    @Test
    fun `symlink escaping home is rejected before creating directories`(@TempDir outside: Path) {
        val link = Files.createSymbolicLink(home.resolve("link"), outside)

        assertThrows(IllegalArgumentException::class.java) {
            prepareExportDirectory(link.resolve("new"), home)
        }
        assertFalse(Files.exists(outside.resolve("new")))
    }

    @Test
    fun `items are written untruncated with base64 and text`() {
        val body = "x".repeat(50_000) + "é中"
        val requestBytes = "POST /api?q=1 HTTP/2\r\nHost: example.com\r\n\r\n$body".toByteArray()
        val responseBytes = byteArrayOf(0x48, 0x54, 0x00, 0xFF.toByte(), 0x0A)
        val items = listOf(
            historyItem(7, "example.com", "POST", "/api?q=1", requestBytes, responseBytes),
            historyItem(8, "[::1]", "GET", "/", "GET / HTTP/1.1\r\n\r\n".toByteArray(), null)
        )
        val dir = prepareExportDirectory(home.resolve("out"), home)

        val summaries = saveProxyHistoryItems(items, { true }, 0, 10, dir)

        assertEquals(2, summaries.size)
        assertTrue(summaries.all { it.error == null })
        assertEquals(dir.resolve("7-example.com-POST.json").toString(), summaries[0].file)
        assertEquals(dir.resolve("8-1-GET.json").toString(), summaries[1].file)
        assertEquals(200, summaries[0].status)
        assertNull(summaries[1].status)

        val first = Json.parseToJsonElement(Files.readString(Path.of(summaries[0].file!!))).jsonObject
        assertArrayEquals(requestBytes, Base64.getDecoder().decode(first.getValue("requestBase64").jsonPrimitive.content))
        assertArrayEquals(responseBytes, Base64.getDecoder().decode(first.getValue("responseBase64").jsonPrimitive.content))
        assertTrue(first.getValue("requestText").jsonPrimitive.content.endsWith(body))
        assertEquals(0, first.getValue("historyIndex").jsonPrimitive.int)
        assertEquals(7, first.getValue("id").jsonPrimitive.int)
        assertEquals("2026-10-20T08:00Z", first.getValue("time").jsonPrimitive.content)
        assertEquals("HTTP/2", first.getValue("httpVersion").jsonPrimitive.content)

        val second = Json.parseToJsonElement(Files.readString(Path.of(summaries[1].file!!))).jsonObject
        assertEquals(JsonNull, second.getValue("responseBase64"))
        assertEquals(JsonNull, second.getValue("responseText"))

        assertEquals(2, Files.list(dir).use { it.count() }, "no temp files should be left behind")
    }

    @Test
    fun `filter and pagination apply to matching items and keep full-history index`() {
        val items = (0 until 6).map { i ->
            val method = if (i % 2 == 0) "GET" else "POST"
            historyItem(100 + i, "h$i.test", method, "/$i", "$method /$i HTTP/1.1\r\n\r\n".toByteArray(), null)
        }
        val dir = prepareExportDirectory(home.resolve("filtered"), home)
        val pattern = Pattern.compile("^POST")

        val summaries = saveProxyHistoryItems(items, { it.contains(pattern) }, 1, 5, dir)

        assertEquals(listOf(3, 5), summaries.map { it.historyIndex })
        assertEquals(listOf("/3", "/5"), summaries.map { it.path })
    }

    @Test
    fun `file name components are sanitised`() {
        assertEquals("evil.com_.._x", sanitizeFileNameComponent("evil.com/../x"))
        assertEquals("unknown", sanitizeFileNameComponent("../"))
        assertEquals("unknown", sanitizeFileNameComponent(null))
    }
}
