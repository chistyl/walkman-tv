package com.walkman.tv.source.js

import com.walkman.tv.diagnostics.AppDiagnostics
import okhttp3.Call
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

    fun send(
        requestKey: String,
        url: String,
        options: JSONObject,
        completion: (error: String?, response: ScriptResponse?) -> Unit,
    ) {
        val req = try {
            buildRequest(url, options)
        } catch (e: Exception) {
            completion(e.message ?: "Invalid request", null); return
        }
        val binary = options.optBoolean("binary", false)
        recordTransportBegin(req)
        val call = client.newCall(req)
        val timeoutMs = options.optLong("timeout", 0L)
        if (timeoutMs > 0L) call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        calls[requestKey] = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                val wasActive = calls.remove(requestKey) != null
                // Explicit cancellation removes the call before cancel(); a call timeout also
                // marks it canceled, but must still be reported to the script callback.
                if (!wasActive && call.isCanceled()) return
                AppDiagnostics.record(
                    "SCRIPT_HTTP_TRANSPORT_ERROR",
                    "host=${call.request().url.host}; method=${call.request().method}; " +
                        "path=${safePathShape(call.request().url)}; errorType=${e.javaClass.simpleName}; " +
                        "canceled=${call.isCanceled()}"
                )
                completion(e.message ?: "request failed", null)
            }

            override fun onResponse(call: Call, response: Response) {
                calls.remove(requestKey)
                response.use { resp ->
                    recordTransportResult(resp)
                    try {
                        val headers = mutableMapOf<String, String>()
                        resp.headers.forEach { (k, v) -> headers[k.lowercase()] = v }
                        val bytes = resp.body?.bytes()
                        val bodyValue = decodeBody(bytes, binary)
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

    /**
     * Records the request after OkHttp has parsed/canonicalized its URL and assembled headers.
     * Values that may identify a song or source session are represented only by fingerprints.
     */
    private fun recordTransportBegin(request: Request) {
        val headers = request.headers
        AppDiagnostics.record(
            "SCRIPT_HTTP_TRANSPORT_BEGIN",
            "host=${request.url.host}; method=${request.method}; " +
                "path=${safePathShape(request.url)}; encodedPathLength=${request.url.encodedPath.length}; " +
                "headerNames=${headers.names().sorted().joinToString(",")}; " +
                "tagCount=${headers.values("tag").size}; tagFingerprints=${headerFingerprints(headers, "tag")}; " +
                "uaFingerprint=${headerFingerprints(headers, "User-Agent")}; " +
                "verFingerprint=${headerFingerprints(headers, "ver")}; " +
                "sourceVerFingerprint=${headerFingerprints(headers, "source-ver")}"
        )
    }

    /** Records the final request actually associated with the response and any redirect chain. */
    private fun recordTransportResult(response: Response) {
        val request = response.request
        val redirects = mutableListOf<String>()
        var previous = response.priorResponse
        while (previous != null && redirects.size < 5) {
            redirects += "${previous.code}@${previous.request.url.host}@" +
                safePathShape(previous.request.url)
            previous = previous.priorResponse
        }
        val headers = request.headers
        AppDiagnostics.record(
            "SCRIPT_HTTP_TRANSPORT_RESULT",
            "status=${response.code}; host=${request.url.host}; " +
                "path=${safePathShape(request.url)}; redirects=${redirects.joinToString(">")}; " +
                "headerNames=${headers.names().sorted().joinToString(",")}; " +
                "tagCount=${headers.values("tag").size}; tagFingerprints=${headerFingerprints(headers, "tag")}; " +
                "uaFingerprint=${headerFingerprints(headers, "User-Agent")}; " +
                "verFingerprint=${headerFingerprints(headers, "ver")}; " +
                "sourceVerFingerprint=${headerFingerprints(headers, "source-ver")}"
        )
    }

    /** Never logs the identifier itself or query string. */
    private fun safePathShape(url: okhttp3.HttpUrl): String {
        if (url.host != "97.64.37.235") return "/[non-flower-path]"
        val segments = url.encodedPathSegments
        if (segments.size < 6 || segments.take(3) != listOf("flower", "v1", "url")) {
            return "/[unrecognized-flower-path]"
        }
        val source = segments[3].takeIf { it.matches(Regex("[a-z0-9_-]{1,16}")) } ?: "[other]"
        val quality = segments[5].takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,24}")) } ?: "[other]"
        return "/flower/v1/url/$source/{id:${segments[4].length}}/$quality"
    }

    private fun headerFingerprints(headers: Headers, name: String): String =
        headers.values(name).joinToString(",") { fingerprint(it) }

    private fun fingerprint(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
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
            body = options.getJSONObject("formData").toString()
                .toRequestBody((contentType ?: "multipart/form-data").toMediaTypeOrNull())
        } else if (options.has("body") && !options.isNull("body")) {
            val raw = options.get("body")
            val effectiveCt = contentType ?: if (method == "POST") "application/json" else null
            val ctLower = (effectiveCt ?: "").lowercase()
            val text: String = when {
                ctLower.contains("application/json") && raw is JSONObject -> raw.toString()
                raw is JSONObject -> raw.toString()
                raw is JSONArray -> bytesFromIntArray(raw).let { String(it, Charsets.ISO_8859_1) }
                else -> raw.toString()
            }
            body = text.toRequestBody(effectiveCt?.toMediaTypeOrNull())
        }

        // Method + body
        if (method == "GET" || method == "HEAD") {
            builder.method(method, null)
        } else {
            builder.method(method, body ?: ByteArray(0).toRequestBody(null))
        }

        // Preserve caller-supplied headers. The JS bridge used by LE Music forwards the
        // lx.request options without synthesizing an Accept header; adding application/json
        // here changes the actual request sent to source APIs (including the flower endpoint).
        val hb = Headers.Builder()
        callerHeaders.forEach { (k, v) -> hb.add(k, v) }
        if (headerCI(callerHeaders, "User-Agent") == null) hb.add("User-Agent", DEFAULT_UA)
        builder.headers(hb.build())
        return builder.build()
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
