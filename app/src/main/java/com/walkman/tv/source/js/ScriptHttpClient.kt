package com.walkman.tv.source.js

import okhttp3.Call
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * HTTP requests issued by JS user scripts via `lx.request`, ported from iOS `ScriptHTTPClient`.
 * Mirrors lx-music-mobile request.js: same default UA, JSON-parse-on-response, Content-Type rules.
 */
class ScriptHttpClient(private val client: OkHttpClient) {

    /** [body] is a JSON value (JSONObject/JSONArray/String) or a List<Int> for binary. */
    data class ScriptResponse(
        val statusCode: Int,
        val statusMessage: String,
        val headers: Map<String, String>,
        val body: Any,
    )

    private val calls = ConcurrentHashMap<String, Call>()
    private val cookieJars = ConcurrentHashMap<String, CookieJar>()

    /** Cookie state is isolated per imported script, but shared across that script's requests. */
    private class ScriptCookieJar : CookieJar {
        private val cookies = ConcurrentHashMap<String, Cookie>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val now = System.currentTimeMillis()
            cookies.forEach { cookie ->
                val key = "${cookie.domain}|${cookie.path}|${cookie.name}"
                if (cookie.expiresAt <= now) this.cookies.remove(key)
                else this.cookies[key] = cookie
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            val matched = mutableListOf<Cookie>()
            cookies.entries.removeIf { it.value.expiresAt <= now }
            cookies.values.forEach { cookie ->
                if (cookie.matches(url)) matched += cookie
            }
            return matched
        }
    }

    fun send(
        requestKey: String,
        url: String,
        options: JSONObject,
        cookieNamespace: String = "default",
        completion: (error: String?, response: ScriptResponse?) -> Unit,
    ) {
        val req = try {
            buildRequest(url, options)
        } catch (e: Exception) {
            completion(e.message ?: "Invalid request", null); return
        }
        val binary = options.optBoolean("binary", false)
        val cookieJar = cookieJars.getOrPut(cookieNamespace) { ScriptCookieJar() }
        val call = client.newBuilder().cookieJar(cookieJar).build().newCall(req)
        // lx-music's Needle-based request API defaults to 60 seconds and caps
        // caller-provided timeouts at the same limit.
        val requestedTimeoutMs = options.optLong("timeout", 60_000L)
        val timeoutMs = if (requestedTimeoutMs > 0L) minOf(requestedTimeoutMs, 60_000L) else 60_000L
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        calls[requestKey] = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val wasActive = calls.remove(requestKey) != null
                // Explicit cancellation removes the call before cancel(); a call timeout also
                // marks it canceled, but must still be reported to the script callback.
                if (!wasActive && call.isCanceled()) return
                completion(e.message ?: "request failed", null)
            }

            override fun onResponse(call: Call, response: Response) {
                calls.remove(requestKey)
                response.use { resp ->
                    try {
                        val headers = mutableMapOf<String, String>()
                        resp.headers.forEach { (k, v) -> headers[k.lowercase()] = v }
                        val bytes = resp.body?.bytes()
                        val bodyValue = decodeBody(decodeContentEncoding(bytes, resp.header("Content-Encoding")), binary)
                        completion(
                            null,
                            ScriptResponse(
                                statusCode = resp.code,
                                statusMessage = resp.message.ifEmpty { statusText(resp.code) },
                                headers = headers,
                                body = bodyValue,
                            ),
                        )
                    } catch (e: IOException) {
                        completion(e.message ?: "request failed", null)
                    }
                }
            }
        })
    }

    fun cancel(requestKey: String) {
        calls.remove(requestKey)?.cancel()
    }

    private fun buildRequest(url: String, options: JSONObject): Request {
        val method = (options.optString("method", "GET")).uppercase()
        val builder = Request.Builder().url(url)

        // Caller headers
        val callerHeaders = mutableMapOf<String, String>()
        options.optJSONObject("headers")?.let { h ->
            h.keys().forEach { k ->
                val v = h.get(k).toString()
                callerHeaders[k] = v
            }
        }

        val contentType: String? = headerCI(callerHeaders, "Content-Type")
        var body: RequestBody? = null

        if (method == "POST" && options.has("form") && options.opt("form") is JSONObject) {
            body = encodeForm(options.getJSONObject("form"))
                .toRequestBody((contentType ?: "application/x-www-form-urlencoded").toMediaTypeOrNull())
        } else if (method == "POST" && options.has("formData") && options.opt("formData") is JSONObject) {
            // Needle/form-data semantics used by lx-music scripts: formData is a
            // multipart field map. A boundary must be present in both body and header.
            val boundary = multipartBoundary(contentType) ?: "----WalkmanTV${java.util.UUID.randomUUID().toString().replace("-", "")}"
            body = encodeMultipart(options.getJSONObject("formData"), boundary)
                .toRequestBody("multipart/form-data; boundary=$boundary".toMediaTypeOrNull())
            putHeaderCI(callerHeaders, "Content-Type", "multipart/form-data; boundary=$boundary")
        } else if (options.has("body") && !options.isNull("body")) {
            val raw = options.get("body")
            val effectiveCt = contentType ?: if (method == "POST") "application/json" else null
            val ctLower = (effectiveCt ?: "").lowercase()
            body = if (raw is JSONArray) {
                // lx.request accepts byte arrays for raw binary request bodies.
                bytesFromIntArray(raw).toRequestBody(effectiveCt?.toMediaTypeOrNull())
            } else {
                val text = when {
                    ctLower.contains("application/json") && raw is JSONObject -> raw.toString()
                    raw is JSONObject -> raw.toString()
                    else -> raw.toString()
                }
                text.toRequestBody(effectiveCt?.toMediaTypeOrNull())
            }
        }

        // Method + body
        if (method == "GET" || method == "HEAD") {
            builder.method(method, null)
        } else {
            builder.method(method, body ?: ByteArray(0).toRequestBody(null))
        }

        // Headers (caller first)
        val hb = Headers.Builder()
        callerHeaders.forEach { (k, v) -> hb.add(k, v) }
        if (headerCI(callerHeaders, "User-Agent") == null) hb.add("User-Agent", DEFAULT_UA)
        if (headerCI(callerHeaders, "Accept") == null) hb.add("Accept", "application/json")
        builder.headers(hb.build())
        return builder.build()
    }

    private fun encodeMultipart(formData: JSONObject, boundary: String): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        formData.keys().forEach { fieldName ->
            val raw = formData.get(fieldName)
            val wrapper = raw as? JSONObject
            val value = if (wrapper != null && wrapper.has("value")) wrapper.opt("value") else raw
            val partOptions = wrapper?.optJSONObject("options")
            val filename = partOptions?.optString("filename")?.takeIf { it.isNotBlank() }
            val partContentType = partOptions?.optString("contentType")?.takeIf { it.isNotBlank() }
            val safeName = fieldName.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "").replace("\n", "")
            output.write("--$boundary\r\n".toByteArray(Charsets.UTF_8))
            val disposition = buildString {
                append("Content-Disposition: form-data; name=\"").append(safeName).append('"')
                if (filename != null) {
                    val safeFilename = filename.replace("\\", "\\\\").replace("\"", "\\\"")
                        .replace("\r", "").replace("\n", "")
                    append("; filename=\"").append(safeFilename).append('"')
                }
                append("\r\n")
            }
            output.write(disposition.toByteArray(Charsets.UTF_8))
            if (partContentType != null) {
                output.write("Content-Type: $partContentType\r\n".toByteArray(Charsets.UTF_8))
            } else if (filename != null) {
                output.write("Content-Type: application/octet-stream\r\n".toByteArray(Charsets.UTF_8))
            }
            output.write("\r\n".toByteArray(Charsets.UTF_8))
            val bytes = multipartValueBytes(value)
            output.write(bytes)
            output.write("\r\n".toByteArray(Charsets.UTF_8))
        }
        output.write("--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        return output.toByteArray()
    }

    private fun multipartValueBytes(value: Any?): ByteArray = when (value) {
        is JSONArray -> bytesFromIntArray(value)
        is JSONObject -> {
            // Buffer JSON ({ type: "Buffer", data: [...] }) and numeric-keyed
            // Uint8Array JSON are common after crossing the JS/native bridge.
            val bufferData = if (value.optString("type") == "Buffer") value.opt("data") else null
            when {
                bufferData is JSONArray -> bytesFromIntArray(bufferData)
                value.length() > 0 && value.keys().asSequence().all { it.toIntOrNull() != null } -> {
                    val indexed = value.keys().asSequence().mapNotNull { it.toIntOrNull() }.sorted()
                    if (indexed.toList() == (0 until value.length()).toList()) {
                        ByteArray(value.length()) { index -> (value.optInt(index.toString()) and 0xFF).toByte() }
                    } else value.toString().toByteArray(Charsets.UTF_8)
                }
                else -> value.toString().toByteArray(Charsets.UTF_8)
            }
        }
        null, JSONObject.NULL -> ByteArray(0)
        else -> value.toString().toByteArray(Charsets.UTF_8)
    }

    private fun multipartBoundary(contentType: String?): String? =
        contentType?.let { Regex("""(?:^|;)\s*boundary=(?:\"([^\"]+)\"|([^;\s]+))""", RegexOption.IGNORE_CASE)
            .find(it)?.let { match -> match.groupValues[1].ifEmpty { match.groupValues[2] } } }

    private fun putHeaderCI(headers: MutableMap<String, String>, name: String, value: String) {
        val existing = headers.keys.firstOrNull { it.equals(name, ignoreCase = true) }
        if (existing != null) headers.remove(existing)
        headers[name] = value
    }

    private fun encodeForm(form: JSONObject): String =
        form.keys().asSequence().map { k ->
            val v = form.get(k).toString()
            "${enc(k)}=${enc(v)}"
        }.joinToString("&")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun bytesFromIntArray(arr: JSONArray): ByteArray =
        ByteArray(arr.length()) { (arr.optInt(it) and 0xFF).toByte() }

    private fun headerCI(headers: Map<String, String>, name: String): String? {
        val target = name.lowercase()
        return headers.entries.firstOrNull { it.key.lowercase() == target }?.value
    }

    private fun decodeContentEncoding(data: ByteArray?, encoding: String?): ByteArray? {
        if (data == null || data.isEmpty() || encoding.isNullOrBlank()) return data
        var decoded = data
        try {
            encoding.split(",").map { it.trim().lowercase() }.asReversed().forEach { item ->
                decoded = when (item) {
                    "gzip", "x-gzip" -> GZIPInputStream(ByteArrayInputStream(decoded)).use { it.readBytes() }
                    "deflate" -> InflaterInputStream(ByteArrayInputStream(decoded)).use { it.readBytes() }
                    else -> decoded
                }
            }
        } catch (_: IOException) {
            // Preserve the original payload if a server sends a malformed or unsupported
            // content-encoding; script-level parsing can still report the response.
            return data
        }
        return decoded
    }

    companion object {
        const val DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/69.0.3497.100 Safari/537.36"

        /** request.js contract: JSON.parse → object/array; else raw text; binary → int array. */
        fun decodeBody(data: ByteArray?, binary: Boolean): Any {
            if (data == null || data.isEmpty()) return ""
            if (binary) return JSONArray().also { a -> for (b in data) a.put(b.toInt() and 0xFF) }
            val text = String(data, Charsets.UTF_8)
            return try {
                JSONTokener(text).nextValue() ?: text
            } catch (e: Exception) {
                text
            }
        }

        private fun statusText(code: Int): String = when (code) {
            200 -> "OK"; 201 -> "Created"; 204 -> "No Content"
            301 -> "Moved Permanently"; 302 -> "Found"; 304 -> "Not Modified"
            400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
            500 -> "Internal Server Error"; 502 -> "Bad Gateway"; 503 -> "Service Unavailable"
            else -> ""
        }
    }
}
