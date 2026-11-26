package com.walkman.tv.source.js

import android.util.Log
import com.walkman.tv.data.model.Quality
import com.walkman.tv.data.model.ScriptCapabilities
import com.walkman.tv.data.model.SourceCapability
import com.walkman.tv.data.model.SourceID
import com.walkman.tv.data.model.UserScript
import com.walkman.tv.diagnostics.AppDiagnostics
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Runs a single lx-music v4 user script in a QuickJS context. Ports `userApi/QuickJS.java`
 * (native env + preload contract) and the async request/init contract from iOS `JSRuntime.swift`,
 * but routes results to Kotlin coroutines instead of RN events.
 *
 * QuickJS is single-threaded: every context interaction runs on [jsDispatcher].
 */
class JsScriptRuntime(
    val script: UserScript,
    private val preload: String,
    private val http: ScriptHttpClient,
) {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "lx-js-${script.id.take(8)}") }
    private val jsDispatcher = executor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + jsDispatcher)
    private val key = UUID.randomUUID().toString()

    @Volatile private var ctx: QuickJSContext? = null
    @Volatile var inited = false
        private set
    @Volatile var capabilities = ScriptCapabilities()
        private set

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Any?>>()
    private val activeHttpRequests = ConcurrentHashMap.newKeySet<String>()
    private val musicUrlUserAgents = ConcurrentHashMap<String, String>()
    @Volatile private var activeMusicUrlRequestKey: String? = null
    private val destroyed = AtomicBoolean(false)
    private var initDeferred: CompletableDeferred<ScriptCapabilities>? = null

    open class ScriptException(message: String) : Exception(message)
    /** The script never answered (vs. answered with an error) — retrying it at another quality
     *  tier would just wait out the timeout again. */
    class ScriptTimeoutException(message: String) : ScriptException(message)

    /** Create the context, evaluate preload + script, and wait for the script to report `init`. */
    suspend fun load(timeoutMs: Long = 10_000): ScriptCapabilities {
        if (destroyed.get()) throw ScriptException("指令碼已卸載")
        val deferred = CompletableDeferred<ScriptCapabilities>()
        initDeferred = deferred
        withContext(jsDispatcher) {
            val c = QuickJSContext.create()
            ctx = c
            c.setConsole(object : QuickJSContext.Console {
                override fun log(info: String?) { Log.d(TAG, info ?: "") }
                override fun info(info: String?) { Log.i(TAG, info ?: "") }
                override fun warn(info: String?) { Log.w(TAG, info ?: "") }
                override fun error(info: String?) { Log.e(TAG, info ?: "") }
            })
            installGlobals(c)
            c.evaluate(preload)
            c.globalObject.getJSFunction("lx_setup").call(
                key, script.id, script.name, script.description,
                script.version, script.author, script.homepage, script.rawScript,
            )
            // v4 source is an IIFE — evaluate directly (mirrors QuickJS.java loadScript).
            try {
                c.evaluate(script.rawScript)
            } catch (e: Exception) {
                // A throw after a successful init is non-fatal; otherwise fail init.
                if (!inited) failInit(e.message ?: "指令碼執行錯誤")
            }
            Unit // force lambda return type to Unit; avoids if-without-else type-check error
        }
        return try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            throw ScriptException("指令碼回應逾時")
        }
    }

    /** Ask the script to perform an action (musicUrl / lyric / pic). */
    suspend fun requestAction(source: SourceID, action: String, info: JSONObject, timeoutMs: Long = 15_000): Any? {
        if (destroyed.get()) throw ScriptException("指令碼已卸載")
        if (!inited) throw ScriptException("指令碼尚未初始化")
        val requestKey = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<Any?>()
        pending[requestKey] = deferred
        val data = JSONObject()
            .put("source", source.key)
            .put("action", action)
            .put("info", info)
        val payload = JSONObject().put("requestKey", requestKey).put("data", data)
        var inheritedUserAgent: String? = null
        val result = try {
            withContext(jsDispatcher) {
                if (destroyed.get()) throw ScriptException("指令碼已卸載")
                val previousRequestKey = activeMusicUrlRequestKey
                if (action == "musicUrl") activeMusicUrlRequestKey = requestKey
                try {
                    sendToScript("request", payload.toString())
                } finally {
                    activeMusicUrlRequestKey = previousRequestKey
                }
            }
            val value = try {
                withTimeout(timeoutMs) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                throw ScriptTimeoutException("指令碼呼叫逾時")
            }
            if (action == "musicUrl") inheritedUserAgent = musicUrlUserAgents.remove(requestKey)
            value
        } finally {
            pending.remove(requestKey)
            musicUrlUserAgents.remove(requestKey)
        }
        // Legacy lx-music scripts (including older imported sources) return the play URL
        // directly as a string. Normalize that contract to the object shape consumed by
        // SourceManager, then preserve the resolver request User-Agent for ExoPlayer.
        return if (action == "musicUrl") {
            normalizeMusicUrlResult(result, inheritedUserAgent)
        } else {
            result
        }
    }

    fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        val unloaded = ScriptException("指令碼已卸載")
        pending.values.forEach { it.completeExceptionally(unloaded) }
        pending.clear()
        initDeferred?.completeExceptionally(unloaded)
        initDeferred = null
        activeHttpRequests.toList().forEach(http::cancel)
        activeHttpRequests.clear()
        musicUrlUserAgents.clear()
        activeMusicUrlRequestKey = null
        // Stop timer/callback coroutines before shutting down the JS executor.
        scope.cancel()
        runCatching {
            jsDispatcher.dispatch(EmptyCoroutineContext) {
                runCatching { ctx?.destroy() }
                ctx = null
            }
        }
        executor.shutdown()
    }

    // MARK: - native env

    private fun installGlobals(c: QuickJSContext) {
        val g = c.globalObject
        // Every JSCallFunction body is wrapped in runCatching: these lambdas are invoked from
        // QuickJS via JNI, and a Java exception escaping through that boundary (e.g. a bad
        // script passing a non-string arg into the casts below) can take down the process
        // instead of surfacing as a script error. Crypto helpers fall back to "" — same value
        // CryptoBridge itself returns on failure.
        g.setProperty("__lx_native_call__", JSCallFunction { args ->
            runCatching {
                if (args.isNotEmpty() && args[0] == key) {
                    val action = args.getOrNull(1) as? String ?: return@runCatching
                    val data = args.getOrNull(2) as? String
                    handleScriptCall(action, data)
                }
            }.onFailure { Log.e(TAG, "__lx_native_call__ failed: ${it.message}") }
            null
        })
        g.setProperty("__lx_native_call__utils_str2b64", JSCallFunction { a ->
            runCatching { CryptoBridge.str2b64(a[0] as String) }.getOrDefault("")
        })
        g.setProperty("__lx_native_call__utils_b642buf", JSCallFunction { a ->
            runCatching { CryptoBridge.b642buf(a[0] as String) }.getOrDefault("[]")
        })
        g.setProperty("__lx_native_call__utils_str2md5", JSCallFunction { a ->
            runCatching { CryptoBridge.str2md5(a[0] as String) }.getOrDefault("")
        })
        g.setProperty("__lx_native_call__utils_aes_encrypt", JSCallFunction { a ->
            runCatching {
                CryptoBridge.aesEncrypt(a[0] as String, a[1] as String, a[2] as String, a[3] as String)
            }.getOrDefault("")
        })
        g.setProperty("__lx_native_call__utils_rsa_encrypt", JSCallFunction { a ->
            runCatching {
                CryptoBridge.rsaEncrypt(a[0] as String, a[1] as String, a[2] as String)
            }.getOrDefault("")
        })
        g.setProperty("__lx_native_call__set_timeout", JSCallFunction { a ->
            runCatching {
                val id = (a[0] as Number).toInt()
                val ms = (a[1] as Number).toLong()
                scope.launch {
                    delay(ms)
                    sendToScript("__set_timeout__", id.toString())
                }
            }.onFailure { Log.e(TAG, "set_timeout failed: ${it.message}") }
            null
        })
    }

    /** Call the script's `__lx_native__(key, action[, dataString])`. Must run on [jsDispatcher]. */
    private fun sendToScript(action: String, dataString: String?) {
        if (destroyed.get()) return
        val c = ctx ?: return
        try {
            val fn = c.globalObject.getJSFunction("__lx_native__")
            if (dataString == null) fn.call(key, action) else fn.call(key, action, dataString)
        } catch (e: Exception) {
            Log.e(TAG, "sendToScript($action) failed: ${e.message}")
        }
    }

    // MARK: - script → host

    private fun handleScriptCall(action: String, rawData: String?) {
        if (destroyed.get()) return
        when (action) {
            "init" -> handleInit(rawData)
            "request" -> handleHttpRequest(rawData)
            "cancelRequest" -> rawData?.let {
                val requestKey = stripQuotes(it)
                activeHttpRequests.remove(requestKey)
                http.cancel(requestKey)
            }
            "response" -> handleScriptResponse(rawData)
            "showUpdateAlert" -> Log.i(TAG, "updateAlert: $rawData")
            else -> Log.d(TAG, "unknown script call: $action")
        }
    }

    private fun handleInit(rawData: String?) {
        val obj = parseObj(rawData)
        if (obj == null) {
            failInit("init payload not an object"); return
        }
        if (obj.has("status") && !obj.optBoolean("status", true)) {
            failInit(obj.optString("errorMessage", "unknown")); return
        }
        val sources = mutableMapOf<SourceID, SourceCapability>()
        obj.optJSONObject("info")?.optJSONObject("sources")?.let { srcObj ->
            srcObj.keys().forEach { k ->
                val src = SourceID.fromKey(k) ?: return@forEach
                val v = srcObj.optJSONObject(k) ?: return@forEach
                val actions = v.optJSONArray("actions")?.let { arr ->
                    List(arr.length()) { arr.optString(it) }
                } ?: emptyList()
                val qualities = v.optJSONArray("qualitys")?.let { arr ->
                    (0 until arr.length()).mapNotNull { Quality.fromKey(arr.optString(it)) }
                } ?: emptyList()
                sources[src] = SourceCapability(v.optString("type", "music"), actions, qualities)
            }
        }
        capabilities = ScriptCapabilities(sources)
        inited = true
        initDeferred?.complete(capabilities)
        initDeferred = null
    }

    private fun failInit(message: String) {
        inited = false
        capabilities = ScriptCapabilities()
        initDeferred?.completeExceptionally(ScriptException("載入指令碼失敗： $message"))
        initDeferred = null
    }

    private fun handleHttpRequest(rawData: String?) {
        if (destroyed.get()) return
        val obj = parseObj(rawData) ?: return
        val requestKey = obj.optString("requestKey").ifEmpty { return }
        val url = obj.optString("url").ifEmpty { return }
        val options = obj.optJSONObject("options") ?: JSONObject()
        val requestUri = runCatching { java.net.URI(url) }.getOrNull()
        val requestHost = requestUri?.host ?: "unknown"
        val requestMethod = options.optString("method", "GET").uppercase()
        val shortRequestKey = requestKey.take(8)
        val requestHeaders = options.optJSONObject("headers")
        val headerNames = requestHeaders?.keys()?.asSequence()?.sorted()?.joinToString(",") ?: ""
        val flowerSegments = requestUri?.path?.split('/')?.filter { it.isNotEmpty() }.orEmpty()
        val flowerDiagnostic = if (requestHost == "97.64.37.235" &&
            flowerSegments.size >= 6 && flowerSegments.take(3) == listOf("flower", "v1", "url")
        ) {
            fun headerValue(name: String): String? {
                val key = requestHeaders?.keys()?.asSequence()
                    ?.firstOrNull { it.equals(name, ignoreCase = true) } ?: return null
                return requestHeaders.optString(key).takeIf { it.isNotEmpty() }
            }
            val tag = headerValue("tag")
            val ua = headerValue("User-Agent")
            val ver = headerValue("ver")
            val sourceVer = headerValue("source-ver")
            val identifier = flowerSegments[4]
            val queryParts = requestUri?.rawQuery?.split('&').orEmpty().filter { it.isNotEmpty() }
            val queryKeys = queryParts.map { it.substringBefore('=') }.sorted().joinToString(",")
            val queryValueLengths = queryParts.map { it.substringAfter('=', "").length }.sorted().joinToString(",")
            // Keep each safe key/length pair together so different parameter values can be
            // compared without logging the actual values or changing their privacy boundary.
            val queryShape = queryParts.map {
                "${it.substringBefore('=')}:${it.substringAfter('=', "").length}"
            }.sorted().joinToString(",")
            "; endpoint=flower; route=/flower/v1/url/{source}/{id}/{quality}" +
                "; source=${flowerSegments[3]}; identifierPresent=${identifier.isNotEmpty()}" +
                "; identifierLength=${identifier.length}; quality=${flowerSegments[5]}" +
                "; queryKeys=$queryKeys; queryValueLengths=$queryValueLengths; queryShape=$queryShape" +
                "; headerNames=$headerNames; tagPresent=${tag != null}; tagLength=${tag?.length ?: 0}" +
                "; tagHex=${tag?.matches(Regex("[0-9a-fA-F]+")) == true}; tagFingerprint=${fingerprint(tag)}" +
                "; userAgentPresent=${ua != null}; userAgentLength=${ua?.length ?: 0}" +
                "; userAgentFingerprint=${fingerprint(ua)}; verLength=${ver?.length ?: 0}" +
                "; verFingerprint=${fingerprint(ver)}; sourceVerLength=${sourceVer?.length ?: 0}" +
                "; sourceVerFingerprint=${fingerprint(sourceVer)}"
        } else {
            ""
        }
        AppDiagnostics.record(
            "SCRIPT_HTTP_BEGIN",
            "request=$shortRequestKey; host=$requestHost; method=$requestMethod$flowerDiagnostic"
        )
        // The legacy 野花 musicUrl handler returns only a URL. Capture the User-Agent
        // supplied by that action's synchronous lx.request call and attach it to the result.
        val actionRequestKey = activeMusicUrlRequestKey
        if (actionRequestKey != null) {
            val requestHeaders = options.optJSONObject("headers")
            val uaKey = requestHeaders?.keys()?.asSequence()
                ?.firstOrNull { it.equals("User-Agent", ignoreCase = true) }
            val ua = uaKey?.let { requestHeaders?.optString(it) }
            if (!ua.isNullOrBlank()) musicUrlUserAgents[actionRequestKey] = ua
        }
        activeHttpRequests.add(requestKey)
        http.send(requestKey, url, options) { error, response ->
            activeHttpRequests.remove(requestKey)
            // Completes on an OkHttp thread — hop back to the JS thread to call the script.
            if (!destroyed.get()) scope.launch {
                if (destroyed.get()) return@launch
                val payload = JSONObject().put("requestKey", requestKey)
                if (error != null || response == null) {
                    val requestError = error ?: "no response"
                    AppDiagnostics.record("SCRIPT_HTTP_ERROR", "request=$shortRequestKey; host=$requestHost; method=$requestMethod; error=$requestError")
                    payload.put("error", requestError)
                    payload.put("response", JSONObject.NULL)
                } else {
                    val bodyType = response.body::class.java.simpleName
                    val bodyLength = when (val body = response.body) {
                        is String -> body.length
                        is JSONObject -> body.toString().length
                        is org.json.JSONArray -> body.toString().length
                        else -> -1
                    }
                    // Log only structured error metadata, never the raw response body.
                    // This lets us distinguish API rejection reasons without exposing URLs or tokens.
                    val bodyDiagnostic = (response.body as? JSONObject)?.let { body ->
                        val keys = body.keys().asSequence().sorted().take(12).joinToString(",")
                        val safeFields = listOf("code", "status", "error", "message", "msg", "detail", "reason")
                            .mapNotNull { name ->
                                if (!body.has(name) || body.isNull(name)) return@mapNotNull null
                                val value = body.opt(name)
                                if (value !is String && value !is Number && value !is Boolean) return@mapNotNull null
                                val safeValue = value.toString()
                                    .replace(Regex("https?://\\S+"), "[url]")
                                    .replace(Regex("[A-Fa-f0-9]{32,}"), "[redacted]")
                                    .replace(Regex("[\\r\\n]+"), " ")
                                    .take(120)
                                "$name=$safeValue"
                            }
                            .joinToString(";")
                        "; bodyKeys=$keys" + if (safeFields.isNotEmpty()) "; bodyFields=$safeFields" else ""
                    } ?: ""
                    val responseHeaderSummary = listOf("server", "content-type", "date", "x-request-id")
                        .mapNotNull { name -> response.headers[name]?.take(120)?.let { "$name=$it" } }
                        .joinToString("; ")
                    AppDiagnostics.record(
                        "SCRIPT_HTTP_RESULT",
                        "request=$shortRequestKey; host=$requestHost; method=$requestMethod; statusCode=" +
                            response.statusCode + "; statusMessage=" + response.statusMessage +
                            "; bodyType=" + bodyType + "; bodyLength=" + bodyLength + bodyDiagnostic +
                            if (responseHeaderSummary.isNotEmpty()) "; responseHeaders=$responseHeaderSummary" else ""
                    )
                    payload.put("error", JSONObject.NULL)
                    payload.put(
                        "response",
                        JSONObject()
                            .put("statusCode", response.statusCode)
                            .put("statusMessage", response.statusMessage)
                            .put("headers", JSONObject(response.headers as Map<*, *>))
                            .put("body", response.body),
                    )
                }
                sendToScript("response", payload.toString())
            }
        }
    }

    private fun handleScriptResponse(rawData: String?) {
        val obj = parseObj(rawData) ?: return
        val requestKey = obj.optString("requestKey").ifEmpty { return }
        val deferred = pending.remove(requestKey) ?: return
        if (obj.optBoolean("status", false)) {
            deferred.complete(if (obj.isNull("result")) null else obj.get("result"))
        } else {
            val errorMessage = obj.optString("errorMessage", "unknown error")
            AppDiagnostics.record("SCRIPT_CALLBACK_ERROR", "request=" + requestKey.take(8) + "; error=" + errorMessage)
            deferred.completeExceptionally(ScriptException(errorMessage))
        }
    }

    /** Short one-way fingerprints let diagnostics compare request metadata without logging secrets. */
    private fun fingerprint(value: String?): String {
        if (value.isNullOrEmpty()) return "none"
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun parseObj(raw: String?): JSONObject? =
        try {
            if (raw.isNullOrEmpty()) null else JSONObject(raw)
        } catch (e: Exception) {
            null
        }

    private fun stripQuotes(s: String): String =
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) s.substring(1, s.length - 1) else s

    companion object {
        private const val TAG = "JsScriptRuntime"

        /**
         * Normalize the legacy URL-string result while retaining modern object responses.
         * Kept pure so both compatibility shapes can be regression-tested without QuickJS.
         */
        internal fun normalizeMusicUrlResult(result: Any?, inheritedUserAgent: String?): Any? {
            val normalized = when (result) {
                is String -> JSONObject().put("url", result)
                else -> result
            } ?: return null
            if (normalized is JSONObject && !inheritedUserAgent.isNullOrBlank()) {
                val data = normalized.optJSONObject("data")
                val explicitUserAgent = data?.optString("userAgent")?.takeIf { it.isNotBlank() }
                    ?: normalized.optString("userAgent").takeIf { it.isNotBlank() }
                if (explicitUserAgent == null) {
                    if (data != null) data.put("userAgent", inheritedUserAgent)
                    else normalized.put("userAgent", inheritedUserAgent)
                }
            }
            return normalized
        }
    }
}
