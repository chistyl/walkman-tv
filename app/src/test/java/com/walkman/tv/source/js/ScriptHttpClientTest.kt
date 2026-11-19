package com.walkman.tv.source.js

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ScriptHttpClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `formData is encoded as multipart with a matching boundary and binary file bytes`() {
        server.enqueue(MockResponse().setBody("ok"))
        val completion = CountDownLatch(1)
        val error = AtomicReference<String?>()
        val formData = JSONObject()
            .put("title", "測試歌曲")
            .put(
                "upload",
                JSONObject()
                    .put("value", org.json.JSONArray(listOf(0, 1, 127, 255)))
                    .put("options", JSONObject().put("filename", "cover.bin").put("contentType", "application/octet-stream")),
            )

        ScriptHttpClient(OkHttpClient()).send(
            requestKey = "multipart",
            url = server.url("/upload").toString(),
            options = JSONObject().put("method", "POST").put("formData", formData),
        ) { message, _ ->
            error.set(message)
            completion.countDown()
        }

        assertTrue("request did not finish", completion.await(2, TimeUnit.SECONDS))
        assertEquals(null, error.get())
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        val contentType = request.getHeader("Content-Type")!!
        val boundary = Regex("""boundary=([^;]+)""").find(contentType)!!.groupValues[1]
        val payloadBytes = request.body.readByteArray()
        val payload = String(payloadBytes, Charsets.UTF_8)
        assertTrue(contentType.startsWith("multipart/form-data"))
        assertTrue(payload.startsWith("--$boundary\r\n"))
        assertTrue(payload.contains("""name="title"""" ))
        assertTrue(payload.contains("測試歌曲"))
        assertTrue(payload.contains("""filename="cover.bin""""))
        assertTrue(payload.contains("Content-Type: application/octet-stream"))
        assertTrue(payload.contains("--$boundary--"))
        // Binary bytes are written as raw octets, not their JSON decimal representation.
        val fileBytes = byteArrayOf(0, 1, 127, -1).toList()
        assertTrue(payloadBytes.toList().windowed(4).any { it == fileBytes })
    }

    @Test
    fun `form requests are url encoded and custom headers are preserved`() {
        server.enqueue(MockResponse().setBody("ok"))
        val completion = CountDownLatch(1)
        val form = JSONObject().put("keyword", "a b").put("page", 2)

        ScriptHttpClient(OkHttpClient()).send(
            requestKey = "form",
            url = server.url("/search").toString(),
            options = JSONObject()
                .put("method", "POST")
                .put("form", form)
                .put("headers", JSONObject().put("X-Source-Token", "abc123")),
        ) { _, _ -> completion.countDown() }

        assertTrue("request did not finish", completion.await(2, TimeUnit.SECONDS))
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("keyword=a%20b&page=2", request.body.readUtf8())
        assertEquals("application/x-www-form-urlencoded", request.getHeader("Content-Type"))
        assertEquals("abc123", request.getHeader("X-Source-Token"))
    }

    @Test
    fun `binary response is returned as unsigned byte values`() {
        val decoded = ScriptHttpClient.decodeBody(byteArrayOf(0, 127, -1), binary = true)
        assertEquals(org.json.JSONArray(listOf(0, 127, 255)).toString(), decoded.toString())
    }

    @Test
    fun `request honors the script timeout option`() {
        server.enqueue(
            MockResponse()
                .setHeadersDelay(500, TimeUnit.MILLISECONDS)
                .setBody("ok"),
        )
        val completion = CountDownLatch(1)
        val error = AtomicReference<String?>()

        ScriptHttpClient(OkHttpClient()).send(
            requestKey = "slow-request",
            url = server.url("/slow").toString(),
            options = JSONObject().put("timeout", 50),
        ) { message, _ ->
            error.set(message)
            completion.countDown()
        }

        assertTrue("request did not finish", completion.await(2, TimeUnit.SECONDS))
        assertNotNull("request ignored options.timeout", error.get())
    }
}
