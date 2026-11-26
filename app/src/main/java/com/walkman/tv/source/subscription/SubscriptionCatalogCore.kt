package com.walkman.tv.source.subscription

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

/** A validated entry from a user-configured remote source catalog. */
data class SubscriptionCatalogEntry(
    val id: String,
    val name: String,
    val url: String,
)

/** Script content fetched and validated for a catalog entry. */
data class RemoteSubscriptionScript(
    val id: String,
    val name: String,
    val url: String,
    val script: String,
)

/** Minimal local projection used for content-based change detection. */
data class LocalSubscriptionScript(
    val id: String,
    val name: String,
    val script: String,
)

/**
 * Platform-neutral pieces of LX Enhanced subscription recovery.
 *
 * This class intentionally does not fetch, persist, delete, or mutate scripts. Callers must
 * stage every remote script and build a complete update plan before changing local data.
 */
object SubscriptionCatalogCore {
    private const val LOCAL_ID_PREFIX = "user_api_subscription_"
    private val json = Json { ignoreUnknownKeys = true }

    fun localId(remoteId: String): String = LOCAL_ID_PREFIX + remoteId

    fun remoteId(localId: String): String? =
        localId.takeIf { it.startsWith(LOCAL_ID_PREFIX) }
            ?.removePrefix(LOCAL_ID_PREFIX)
            ?.takeIf { it.isNotBlank() }

    /**
     * Accepts either a top-level array or { "sources": [...] }.
     * Any malformed entry invalidates the whole catalog; partial lists are never returned.
     */
    fun parse(text: String, catalogUrl: String): List<SubscriptionCatalogEntry>? {
        if (text.isBlank()) return null
        val base = parseHttpUri(catalogUrl) ?: return null
        val root = runCatching { json.parseToJsonElement(text.trimStart('\uFEFF')) }.getOrNull()
            ?: return null
        val entries = when (root) {
            is JsonArray -> root
            is JsonObject -> root["sources"] as? JsonArray
            else -> null
        } ?: return null
        if (entries.isEmpty()) return null

        val ids = HashSet<String>()
        val result = ArrayList<SubscriptionCatalogEntry>(entries.size)
        for (element in entries) {
            val item = element as? JsonObject ?: return null
            val id = item["id"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
            val name = item["name"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
            val rawUrl = item["url"]?.jsonPrimitive?.contentOrNull()?.trim().orEmpty()
            if (id.isBlank() || name.isBlank() || rawUrl.isBlank() || !ids.add(id)) return null
            val resolved = resolveHttpUrl(rawUrl, base) ?: return null
            result += SubscriptionCatalogEntry(id, name, resolved)
        }
        return result
    }

    /** A URL-only change does not require replacing an identical script body. */
    fun contentChanged(
        local: List<LocalSubscriptionScript>,
        remote: List<RemoteSubscriptionScript>,
    ): Boolean {
        if (local.size != remote.size) return true
        val remoteById = remote.associateBy { it.id }
        if (remoteById.size != remote.size) return true
        return local.any { current ->
            val next = remoteById[current.id]
            next == null || current.name != next.name || current.script != next.script
        }
    }

    private fun parseHttpUri(raw: String): URI? = runCatching {
        URI(raw.trim()).takeIf { uri ->
            uri.isAbsolute && uri.scheme?.lowercase() in setOf("http", "https") &&
                !uri.host.isNullOrBlank() && uri.userInfo == null
        }
    }.getOrNull()

    private fun resolveHttpUrl(raw: String, base: URI): String? = runCatching {
        val candidate = URI(raw)
        val resolved = if (candidate.isAbsolute) candidate else base.resolve(candidate)
        resolved.normalize().takeIf { uri ->
            uri.scheme?.lowercase() in setOf("http", "https") &&
                !uri.host.isNullOrBlank() && uri.userInfo == null
        }?.toString()
    }.getOrNull()

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? =
        if (isString) content else null
}
