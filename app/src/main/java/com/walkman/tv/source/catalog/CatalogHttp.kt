package com.walkman.tv.source.catalog

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val MOBILE_UA =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1"

class CatalogException(message: String) : Exception(message)

/** Thin HTTP helper for the direct platform catalog/search/leaderboard/songlist APIs. */
class CatalogHttp(private val client: OkHttpClient) {

    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String =
        exec(Request.Builder().url(url).applyHeaders(headers).get().build())

    suspend fun postForm(url: String, form: String, headers: Map<String, String> = emptyMap()): String {
        val body = form.toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull())
        return exec(Request.Builder().url(url).applyHeaders(headers).post(body).build())
    }

    suspend fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): String {
        val body = json.toRequestBody("application/json".toMediaTypeOrNull())
        return exec(Request.Builder().url(url).applyHeaders(headers).post(body).build())
    }

    /** Follow redirects for [url] and return the final effective URL (for short-link expansion).
     *  Returns null on failure. Cancellation aborts the underlying network call. */
    suspend fun resolveFinalUrl(url: String, headers: Map<String, String> = emptyMap()): String? =
        runCatching {
            execute(Request.Builder().url(url).applyHeaders(headers).get().build()) { response ->
                response.request.url.toString()
            }
        }.getOrNull()

    /**
     * Use OkHttp's async callback bridge rather than blocking an IO thread in execute().
     * Cancelling the coroutine also cancels the underlying request, freeing network and
     * dispatcher capacity when users change search terms or leave a screen.
     */
    private suspend fun exec(request: Request): String =
        execute(request) { response ->
            if (!response.isSuccessful) throw CatalogException("HTTP ${response.code}")
            response.body?.string() ?: throw CatalogException("空響應")
        }

    private suspend fun <T> execute(request: Request, read: (Response) -> T): T =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val value = read(it)
                            if (continuation.isActive) continuation.resume(value)
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                }
            })
        }

    private fun Request.Builder.applyHeaders(headers: Map<String, String>): Request.Builder {
        if (headers.none { it.key.equals("User-Agent", true) }) header("User-Agent", MOBILE_UA)
        headers.forEach { (k, v) -> header(k, v) }
        return this
    }
}

/** URL-encode a query parameter value. */
fun urlEncode(s: String): String =
    java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
