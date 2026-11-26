package com.walkman.tv.source.subscription

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

/** Existing locally-managed subscription entry. Manual scripts must never be passed here. */
data class LocalSubscriptionRecord(
    val localId: String,
    val remoteId: String,
    val name: String,
    val script: String,
    val enabled: Boolean,
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
            val id = item.stringValue("id")?.trim().orEmpty()
            val name = item.stringValue("name")?.trim().orEmpty()
            val rawUrl = item.stringValue("url")?.trim().orEmpty()
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


/** A staged update; the consumer must keep [localId] and [enabled] unchanged. */
data class PlannedSubscriptionUpdate(
    val localId: String,
    val remoteId: String,
    val name: String,
    val script: String,
)

/** Additive/update-only plan. No operation in this model deletes local scripts. */
data class SubscriptionUpdatePlan(
    val additions: List<RemoteSubscriptionScript>,
    val updates: List<PlannedSubscriptionUpdate>,
    val unchangedLocalIds: List<String>,
    val retainedMissingLocalIds: List<String>,
)

/**
 * Builds a complete plan without mutating storage or runtime sources.
 * Existing subscription identity and enabled state are preserved by localId; absent remote
 * entries are retained locally rather than interpreted as deletion instructions.
 * Returns null if the supplied local or remote identity set is ambiguous or invalid.
 */
fun buildUpdatePlan(
    local: List<LocalSubscriptionRecord>,
    remote: List<RemoteSubscriptionScript>,
): SubscriptionUpdatePlan? {
    if (local.any { it.localId.isBlank() || it.remoteId.isBlank() }) return null
    if (local.map { it.localId }.toSet().size != local.size) return null
    if (local.map { it.remoteId }.toSet().size != local.size) return null
    if (local.any { it.localId != localId(it.remoteId) }) return null
    if (remote.any { it.id.isBlank() || it.name.isBlank() || it.script.isBlank() || parseHttpUri(it.url) == null }) return null
    if (remote.map { it.id }.toSet().size != remote.size) return null

    val localByRemoteId = local.associateBy { it.remoteId }
    val remoteIds = remote.mapTo(HashSet()) { it.id }
    val additions = mutableListOf<RemoteSubscriptionScript>()
    val updates = mutableListOf<PlannedSubscriptionUpdate>()
    val unchanged = mutableListOf<String>()

    for (next in remote) {
        val current = localByRemoteId[next.id]
        if (current == null) {
            additions += next
        } else if (current.name != next.name || current.script != next.script) {
            updates += PlannedSubscriptionUpdate(
                localId = current.localId,
                remoteId = next.id,
                name = next.name,
                script = next.script,
            )
        } else {
            unchanged += current.localId
        }
    }

    val retainedMissing = local.filter { it.remoteId !in remoteIds }.map { it.localId }
    return SubscriptionUpdatePlan(additions, updates, unchanged, retainedMissing)
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

    private fun JsonObject.stringValue(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
