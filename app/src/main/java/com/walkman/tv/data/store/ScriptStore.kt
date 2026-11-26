package com.walkman.tv.data.store

import android.content.Context
import com.walkman.tv.data.model.UserScript
import com.walkman.tv.diagnostics.AppDiagnostics
import com.walkman.tv.source.SourceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
        val startedAt = System.currentTimeMillis()
        val persisted = withContext(Dispatchers.IO) { store.load() }
        _scripts.value = persisted
        val enabledScripts = persisted.filter { it.enabled }
        AppDiagnostics.record(
            "SCRIPT_STORE_LOAD_BEGIN",
            "savedCount=${persisted.size}; enabledCount=${enabledScripts.size}; installBundled=$installBundled",
        )
        var loadedCount = 0
        var failedCount = 0
        // Each script owns an isolated QuickJS context. Initialize at most two in parallel:
        // this shortens cold-start time when several sources are enabled without flooding low-RAM
        // Android TV devices. SourceManager retains persisted priority independent of completion.
        val loadResults = coroutineScope {
            val permits = Semaphore(2)
            enabledScripts.mapIndexed { index, script ->
                async {
                    permits.withPermit { sourceManager.load(script, priority = index) }
                }
            }.awaitAll()
        }
        loadResults.forEachIndexed { index, result ->
            val script = enabledScripts[index]
            result.fold(
                onSuccess = {
                    loadedCount++
                    AppDiagnostics.record(
                        "SCRIPT_STORE_LOAD_SUCCESS",
                        "name=${script.name}; sourceCount=${it.sources.size}; sources=${it.sources.keys.joinToString(",") { source -> source.key }}",
                    )
                },
                onFailure = { error ->
                    failedCount++
                    AppDiagnostics.record(
                        "SCRIPT_STORE_LOAD_ERROR",
                        "name=${script.name}; error=${error.message ?: error::class.simpleName}",
                        error,
                    )
                },
            )
        }
        AppDiagnostics.record(
            "SCRIPT_STORE_LOAD_FINISH",
            "savedCount=${persisted.size}; enabledCount=${enabledScripts.size}; loadedCount=$loadedCount; failedCount=$failedCount; availableSources=${sourceManager.supportedSources().joinToString(",") { it.key }}; elapsedMs=${System.currentTimeMillis() - startedAt}",
        )
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
        val old = _scripts.value.firstOrNull { it.name == script.name && it.author == script.author }
        AppDiagnostics.record(
            "SCRIPT_IMPORT_LOAD_BEGIN",
            "name=${script.name}; version=${script.version}; replacing=${old != null}",
        )
        val loadStartedAt = System.currentTimeMillis()
        val result = sourceManager.load(script)
        return result.fold(
            onSuccess = { capabilities ->
                AppDiagnostics.record(
                    "SCRIPT_IMPORT_LOAD_SUCCESS",
                    "name=${script.name}; sourceCount=${capabilities.sources.size}; sources=${capabilities.sources.keys.joinToString(",") { source -> source.key }}; elapsedMs=${System.currentTimeMillis() - loadStartedAt}",
                )
                if (old != null) {
                    sourceManager.unload(old.id)
                    _scripts.value = _scripts.value.map { if (it.id == old.id) script else it }
                } else {
                    _scripts.value = _scripts.value + script
                }
                persist()
                Result.success(Imported(script, old))
            },
            onFailure = { error ->
                AppDiagnostics.record(
                    "SCRIPT_IMPORT_LOAD_ERROR",
                    "name=${script.name}; error=${error.message ?: error::class.simpleName}; elapsedMs=${System.currentTimeMillis() - loadStartedAt}",
                    error,
                )
                Result.failure(error)
            },
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
