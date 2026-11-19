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
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.zip.GZIPOutputStream
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
    fun `raw byte array request bodies preserve unsigned bytes`() {
        server.enqueue(MockResponse().setBody("ok"))
        val completion = CountDownLatch(1)
        val raw = org.json.JSONArray(listOf(0, 1, 127, 255))

        ScriptHttpClient(OkHttpClient()).send(
            requestKey = "raw-binary",
            url = server.url("/binary").toString(),
            options = JSONObject()
                .put("method", "POST")
                .put("headers", JSONObject().put("Content-Type", "application/octet-stream"))
                .put("body", raw),
        ) { _, _ -> completion.countDown() }

        assertTrue("request did not finish", completion.await(2, TimeUnit.SECONDS))
        val bytes = server.takeRequest(1, TimeUnit.SECONDS)!!.body.readByteArray()
        assertEquals(byteArrayOf(0, 1, 127, -1).toList(), bytes.toList())
    }

    @Test
    fun `explicit gzip response encoding is decompressed before JSON parsing`() {
        val compressed = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write("""{"ok":true}""".toByteArray(Charsets.UTF_8)) }
        }.toByteArray()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Encoding", "gzip")
                .setBody(okio.Buffer().write(compressed)),
        )
        val completion = CountDownLatch(1)
        val response = AtomicReference<ScriptHttpClient.ScriptResponse?>()
        val error = AtomicReference<String?>()

        ScriptHttpClient(OkHttpClient()).send(
            requestKey = "gzip",
            url = server.url("/gzip").toString(),
            options = JSONObject().put("headers", JSONObject().put("Accept-Encoding", "gzip")),
        ) { message, result ->
            error.set(message)
            response.set(result)
            completion.countDown()
        }

        assertTrue("request did not finish", completion.await(2, TimeUnit.SECONDS))
        assertEquals(null, error.get())
        assertEquals(true, (response.get()?.body as JSONObject).optBoolean("ok"))
    }


    @Test
    fun `cookies persist within one script and are isolated from other scripts`() {
        server.enqueue(MockResponse().addHeader("Set-Cookie", "session=alpha; Path=/; HttpOnly").setBody("ok"))
        server.enqueue(MockResponse().setBody("ok"))
        server.enqueue(MockResponse().setBody("ok"))
        val client = ScriptHttpClient(OkHttpClient())

        fun send(key: String, namespace: String): okhttp3.mockwebserver.RecordedRequest {
            val done = CountDownLatch(1)
            val error = AtomicReference<String?>()
            client.send(
                requestKey = key,
                url = server.url("/").toString(),
                options = JSONObject(),
                cookieNamespace = namespace,
            ) { message, _ ->
                error.set(message)
                done.countDown()
            }
            assertTrue("request did not finish", done.await(2, TimeUnit.SECONDS))
            assertEquals(null, error.get())
            return server.takeRequest(1, TimeUnit.SECONDS)!!
        }

        send("cookie-set", "script-alpha")
        val sameScript = send("cookie-same", "script-alpha")
        val otherScript = send("cookie-other", "script-beta")
        assertEquals("session=alpha", sameScript.getHeader("Cookie"))
        assertEquals(null, otherScript.getHeader("Cookie"))
    }

    @Test
    fun `redirect follows location and forwards cookie to redirected endpoint`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "/final")
                .addHeader("Set-Cookie", "redirected=yes; Path=/; HttpOnly"),
        )
        server.enqueue(MockResponse().setBody("complete"))
        val completion = CountDownLatch(1)
        val response = AtomicReference<ScriptHttpClient.ScriptResponse?>()
        val error = AtomicReference<String?>()

        ScriptHttpClient(OkHttpClient()).send(
            requestKey = "redirect",
            url = server.url("/start").toString(),
            options = JSONObject(),
            cookieNamespace = "redirect-script",
        ) { message, result ->
            error.set(message)
            response.set(result)
            completion.countDown()
        }

        assertTrue("redirect request did not finish", completion.await(2, TimeUnit.SECONDS))
        assertEquals(null, error.get())
        assertEquals(200, response.get()?.statusCode)
        assertEquals("/start", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
        val redirected = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/final", redirected.path)
        assertEquals("redirected=yes", redirected.getHeader("Cookie"))
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
