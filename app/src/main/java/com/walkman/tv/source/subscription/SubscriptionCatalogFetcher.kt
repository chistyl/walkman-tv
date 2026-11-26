package com.walkman.tv.source.subscription

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A fully downloaded catalog snapshot. It is staged only; callers decide when to apply it. */
data class StagedSubscriptionCatalog(
    val catalogUrl: String,
    val scripts: List<RemoteSubscriptionScript>,
    val totalBytes: Long,
)

/**
 * Bounded, cancellation-aware subscription fetcher.
 *
 * It downloads the catalog and every referenced script before returning. Any HTTP, parsing,
 * size, or cancellation failure aborts the whole stage; no local script store is touched.
 */
class SubscriptionCatalogFetcher(private val client: OkHttpClient) {
    suspend fun stage(catalogUrl: String): StagedSubscriptionCatalog = withContext(Dispatchers.IO) {
        require(isSafeHttpUrl(catalogUrl)) { "訂閱目錄網址必須是有效的 HTTP(S) 網址" }

        val catalogBytes = fetchBytes(catalogUrl, MAX_CATALOG_BYTES)
        val catalogText = catalogBytes.toString(Charsets.UTF_8)
        val entries = SubscriptionCatalogCore.parse(catalogText, catalogUrl)
            ?: throw IOException("訂閱目錄格式錯誤或包含無效項目")
        require(entries.size <= MAX_SCRIPT_COUNT) { "訂閱目錄最多允許 $MAX_SCRIPT_COUNT 個音源" }

        var totalBytes = catalogBytes.size.toLong()
        val staged = ArrayList<RemoteSubscriptionScript>(entries.size)
        for (entry in entries) {
            val bytes = fetchBytes(entry.url, MAX_SCRIPT_BYTES)
            totalBytes += bytes.size
            require(totalBytes <= MAX_TOTAL_BYTES) { "訂閱內容總大小超過限制" }
            val script = bytes.toString(Charsets.UTF_8)
            require(script.isNotBlank()) { "音源「${entry.name}」內容是空的" }
            staged += RemoteSubscriptionScript(entry.id, entry.name, entry.url, script)
        }
        StagedSubscriptionCatalog(catalogUrl, staged, totalBytes)
    }

    private suspend fun fetchBytes(url: String, maxBytes: Int): ByteArray {
        require(isSafeHttpUrl(url)) { "不允許的音源網址" }
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/javascript, application/javascript, */*")
            .build()
        val response = execute(request)
        response.use { resp ->
            if (!resp.isSuccessful) throw IOException("下載失敗：HTTP ${resp.code}")
            if (!isSafeHttpUrl(resp.request.url.toString())) {
                throw IOException("重新導向到不安全的網址，已中止更新")
            }
            val body = resp.body ?: throw IOException("伺服器回傳空內容")
            val declared = body.contentLength()
            if (declared > maxBytes) throw IOException("下載內容超過大小限制")
            return body.byteStream().use { input ->
                val output = ByteArrayOutputStream(minOf(maxBytes, 16 * 1024))
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) throw IOException("下載內容超過大小限制")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
    }

    private suspend fun execute(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) {
                        continuation.resume(response)
                    } else {
                        response.close()
                    }
                }
            })
        }

    private fun isSafeHttpUrl(raw: String): Boolean = runCatching {
        val uri = URI(raw.trim())
        uri.isAbsolute &&
            uri.scheme?.lowercase() in setOf("http", "https") &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo == null
    }.getOrDefault(false)

    companion object {
        const val MAX_CATALOG_BYTES = 512 * 1024
        const val MAX_SCRIPT_BYTES = 2 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 12 * 1024 * 1024
        const val MAX_SCRIPT_COUNT = 30
    }
}
