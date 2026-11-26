package com.walkman.tv.data.store

import android.content.Context
import com.walkman.tv.data.model.UserScript
import com.walkman.tv.source.SourceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/** Stores imported custom-source scripts and (re)loads enabled ones into [SourceManager]. */
class ScriptStore(private val context: Context, private val sourceManager: SourceManager) {
    private val store = JsonStore(
        File(context.filesDir, "scripts.json"),
        ListSerializer(UserScript.serializer()),
        emptyList(),
    )

    private val mutationMutex = Mutex()

    private val _scripts = MutableStateFlow<List<UserScript>>(emptyList())
    val scripts: StateFlow<List<UserScript>> = _scripts.asStateFlow()

    /**
     * Load saved scripts first, then install the bundled 野花 v1 source only when no matching
     * source is already present. Existing user scripts are never replaced or removed.
     *
     * The bundled script performs network requests during initialization. Keep that operation
     * bounded and best-effort so an unavailable third-party service cannot prevent app startup.
     */
    suspend fun loadAll(installBundled: Boolean = true) {
        val persisted = withContext(Dispatchers.IO) { store.load() }
        _scripts.value = persisted
        persisted.filter { it.enabled }.forEach { sourceManager.load(it) }
        if (installBundled) installBundledYehuaIfMissing()
    }

    /** Optional first-run source setup; callers may defer this network work until after startup. */
    suspend fun installBundledIfMissing() {
        installBundledYehuaIfMissing()
    }

    private suspend fun installBundledYehuaIfMissing() {
        mutationMutex.withLock {
        // Treat either historical display name as an existing installation. This also protects
        // a user's manually imported copy, even if its author metadata differs.
        if (_scripts.value.any { it.name.trim() in BUNDLED_YEHUA_NAMES }) return

        val raw = try {
            withContext(Dispatchers.IO) {
                context.assets.open(BUNDLED_YEHUA_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }

        // Do not block startup indefinitely on a third-party source's init/network requests.
        val imported = try {
            withTimeoutOrNull(BUNDLED_IMPORT_TIMEOUT_MS) { importUnlocked(raw) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            android.util.Log.w(TAG, "Bundled 野花 v1 source import failed: ${error.message}")
            return
        } ?: run {
            android.util.Log.w(TAG, "Bundled 野花 v1 source import timed out")
            return
        }
        imported.onFailure {
            android.util.Log.w(TAG, "Bundled 野花 v1 source was rejected: ${it.message}")
        }
        }
    }

    /** Result of [import]: [replaced] is the older copy of the same script (same name + author)
     *  that this import updated in place, or null when it was added as a new source. */
    data class Imported(val script: UserScript, val replaced: UserScript?) {
        val message: String
            get() = if (replaced == null) "已新增音源：${script.name}"
            else "已更新音源：${script.name}（替换了同名旧版本${replaced.version.takeIf { it.isNotBlank() }?.let { " v$it" } ?: ""}）"
    }

    /**
     * Import a raw script: parse its header, load it, persist. Several sources can coexist; only
     * a script with the same name AND author counts as a new version of an existing one and
     * replaces it (keeping its place in the list). The old runtime is unloaded — previously it
     * stayed loaded in [SourceManager] invisibly — and same-named scripts by different authors
     * are kept side by side instead of silently overwriting each other.
     */
    suspend fun import(raw: String): Result<Imported> = mutationMutex.withLock {
        importUnlocked(raw)
    }

    /** Caller must hold [mutationMutex] so bundled and user imports cannot race. */
    private suspend fun importUnlocked(raw: String): Result<Imported> {
        val meta = parseHeader(raw)
        val script = UserScript(
            name = meta["name"] ?: "未命名脚本",
            description = meta["description"] ?: "",
            version = meta["version"] ?: "",
            author = meta["author"] ?: "",
            homepage = meta["homepage"] ?: "",
            rawScript = raw,
            enabled = true,
        )
        // Subscription-managed scripts have a separate identity and lifecycle. A manual import
        // with the same display name/author must never replace a catalog-managed entry.
        val subscriptionPrefix = com.walkman.tv.source.subscription.SubscriptionCatalogCore.localId("")
        val old = _scripts.value.firstOrNull {
            !it.id.startsWith(subscriptionPrefix) && it.name == script.name && it.author == script.author
        }
        val result = sourceManager.load(script)
        return result.fold(
            onSuccess = {
                if (old != null) {
                    sourceManager.unload(old.id)
                    _scripts.value = _scripts.value.map { if (it.id == old.id) script else it }
                } else {
                    _scripts.value = _scripts.value + script
                }
                persist()
                Result.success(Imported(script, old))
            },
            onFailure = { Result.failure(it) },
        )
    }

    /** Summary returned after a complete subscription catalog has been applied. */
    data class SubscriptionApplySummary(
        val added: Int,
        val updated: Int,
        val unchanged: Int,
        val retainedMissing: Int,
    )

    /**
     * Apply a fully staged catalog as one transaction:
     * 1) build a complete additive-only plan,
     * 2) initialize candidate runtimes without publishing them,
     * 3) atomically persist the complete candidate list,
     * 4) publish the runtime snapshot and StateFlow list.
     *
     * New subscription entries are deliberately disabled until the user enables them.
     * Manual scripts, their IDs, enabled flags, and relative order are preserved.
     */
    suspend fun applySubscriptionCatalog(
        staged: com.walkman.tv.source.subscription.StagedSubscriptionCatalog,
    ): Result<SubscriptionApplySummary> = mutationMutex.withLock {
        val current = _scripts.value
        val subscriptionPrefix = com.walkman.tv.source.subscription.SubscriptionCatalogCore.localId("")
        if (current.any { it.id.startsWith(subscriptionPrefix) &&
                com.walkman.tv.source.subscription.SubscriptionCatalogCore.remoteId(it.id) == null }) {
            return@withLock Result.failure(IllegalStateException("本機訂閱音源識別碼無效"))
        }
        val subscriptionScripts = current.filter {
            com.walkman.tv.source.subscription.SubscriptionCatalogCore.remoteId(it.id) != null
        }
        val localRecords = subscriptionScripts.map { script ->
            val remoteId = com.walkman.tv.source.subscription.SubscriptionCatalogCore.remoteId(script.id)
                ?: return@withLock Result.failure(IllegalStateException("訂閱音源識別碼無效"))
            com.walkman.tv.source.subscription.LocalSubscriptionRecord(
                localId = script.id,
                remoteId = remoteId,
                name = script.name,
                script = script.rawScript,
                enabled = script.enabled,
            )
        }
        val plan = com.walkman.tv.source.subscription.SubscriptionCatalogCore.buildUpdatePlan(localRecords, staged.scripts)
            ?: return@withLock Result.failure(IllegalArgumentException("訂閱更新計畫驗證失敗，未修改任何音源"))

        val remoteById = staged.scripts.associateBy { it.id }
        val updatesByLocalId = plan.updates.associateBy { it.localId }
        fun updatedScript(old: UserScript, remote: com.walkman.tv.source.subscription.RemoteSubscriptionScript): UserScript {
            val meta = parseHeader(remote.script)
            return UserScript(
                id = old.id,
                name = meta["name"]?.takeIf { it.isNotBlank() } ?: remote.name,
                description = meta["description"] ?: "",
                version = meta["version"] ?: "",
                author = meta["author"] ?: "",
                homepage = meta["homepage"] ?: "",
                rawScript = remote.script,
                importedAt = old.importedAt,
                enabled = old.enabled,
            )
        }

        val candidate = current.map { old ->
            val update = updatesByLocalId[old.id]
            if (update == null) old
            else {
                val remote = remoteById[update.remoteId]
                    ?: return@withLock Result.failure(IllegalStateException("訂閱更新項目遺失"))
                updatedScript(old, remote)
            }
        }.toMutableList()

        // Append additions in remote catalog order. Never auto-enable a newly introduced script.
        plan.additions.forEach { remote ->
            val meta = parseHeader(remote.script)
            candidate += UserScript(
                id = com.walkman.tv.source.subscription.SubscriptionCatalogCore.localId(remote.id),
                name = meta["name"]?.takeIf { it.isNotBlank() } ?: remote.name,
                description = meta["description"] ?: "",
                version = meta["version"] ?: "",
                author = meta["author"] ?: "",
                homepage = meta["homepage"] ?: "",
                rawScript = remote.script,
                enabled = false,
            )
        }

        val candidateSubscriptions = candidate.filter {
            com.walkman.tv.source.subscription.SubscriptionCatalogCore.remoteId(it.id) != null
        }
        val prepared = sourceManager.prepareSubscriptionScripts(candidateSubscriptions).getOrElse {
            return@withLock Result.failure(it)
        }

        try {
            withContext(NonCancellable + Dispatchers.IO) {
                store.saveStrict(candidate)
            }
        } catch (error: Exception) {
            sourceManager.discardSubscriptionScripts(prepared)
            return@withLock Result.failure(error)
        }

        // No suspension between durable persistence and the runtime/list publication.
        sourceManager.commitSubscriptionScripts(prepared)
        _scripts.value = candidate
        Result.success(
            SubscriptionApplySummary(
                added = plan.additions.size,
                updated = plan.updates.size,
                unchanged = plan.unchangedLocalIds.size,
                retainedMissing = plan.retainedMissingLocalIds.size,
            ),
        )
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        mutationMutex.withLock {
        val script = _scripts.value.firstOrNull { it.id == id } ?: return@withLock
        _scripts.value = _scripts.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        if (enabled) sourceManager.load(script.copy(enabled = true)) else sourceManager.unload(id)
        persist()
        }
    }

    suspend fun remove(id: String) = mutationMutex.withLock {
        sourceManager.unload(id)
        _scripts.value = _scripts.value.filter { it.id != id }
        persist()
    }

    private suspend fun persist() = withContext(Dispatchers.IO) { store.save(_scripts.value) }

    companion object {
        private const val TAG = "ScriptStore"
        private const val BUNDLED_YEHUA_ASSET = "script/yehua-v1.js"
        private const val BUNDLED_IMPORT_TIMEOUT_MS = 12_000L
        private val BUNDLED_YEHUA_NAMES = setOf("野花🌷", "野花")

        private val headerBlock = Regex("""/\*[\s\S]*?\*/""")
        private val tagLine = Regex("""@(\w+)\s+(.+)""")

        /** Parse the leading KDoc-style header of an lx v4 script (@name / @version / etc). */
        fun parseHeader(raw: String): Map<String, String> {
            val block = headerBlock.find(raw)?.value ?: return emptyMap()
            val out = mutableMapOf<String, String>()
            tagLine.findAll(block).forEach { m ->
                out[m.groupValues[1].lowercase()] = m.groupValues[2].trim().removeSuffix("*").trim()
            }
            return out
        }
    }
}
