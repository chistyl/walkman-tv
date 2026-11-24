package com.walkman.tv.data.store

import android.content.Context
import com.walkman.tv.data.model.UserScript
import com.walkman.tv.source.SourceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _scripts = MutableStateFlow<List<UserScript>>(emptyList())
    val scripts: StateFlow<List<UserScript>> = _scripts.asStateFlow()

    /**
     * Load saved scripts first, then install the bundled 野花 v1 source only when no matching
     * source is already present. Existing user scripts are never replaced or removed.
     *
     * The bundled script performs network requests during initialization. Keep that operation
     * bounded and best-effort so an unavailable third-party service cannot prevent app startup.
     */
    suspend fun loadAll() {
        val persisted = withContext(Dispatchers.IO) { store.load() }
        _scripts.value = persisted
        persisted.filter { it.enabled }.forEach { sourceManager.load(it) }
        installBundledYehuaIfMissing()
    }

    private suspend fun installBundledYehuaIfMissing() {
        // Treat either historical display name as an existing installation. This also protects
        // a user's manually imported copy, even if its author metadata differs.
        if (_scripts.value.any { it.name.trim() in BUNDLED_YEHUA_NAMES }) return

        val raw = runCatching {
            withContext(Dispatchers.IO) {
                context.assets.open(BUNDLED_YEHUA_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        }.getOrNull() ?: return

        // Do not block startup indefinitely on a third-party source's init/network requests.
        runCatching { withTimeout(BUNDLED_IMPORT_TIMEOUT_MS) { import(raw) } }
            .onFailure { android.util.Log.w(TAG, "Bundled 野花 v1 source was not installed: ${it.message}") }
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
    suspend fun import(raw: String): Result<Imported> {
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

    suspend fun setEnabled(id: String, enabled: Boolean) {
        val script = _scripts.value.firstOrNull { it.id == id } ?: return
        _scripts.value = _scripts.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        if (enabled) sourceManager.load(script.copy(enabled = true)) else sourceManager.unload(id)
        persist()
    }

    suspend fun remove(id: String) {
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
