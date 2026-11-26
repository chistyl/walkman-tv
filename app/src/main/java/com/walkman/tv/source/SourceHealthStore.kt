package com.walkman.tv.source

import android.content.Context
import com.walkman.tv.data.store.JsonStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * Persists per-script resolver health independently from user settings and script definitions.
 * A malformed or older file safely falls back to an empty map through [JsonStore].
 */
class SourceHealthStore(context: Context) {
    private val store = JsonStore(
        File(context.filesDir, "source_health.json"),
        MapSerializer(String.serializer(), SourceHealth.serializer()),
        emptyMap<String, SourceHealth>(),
    )
    private val mutex = Mutex()

    @Volatile
    private var state: Map<String, SourceHealth> = store.load()

    fun snapshot(): Map<String, SourceHealth> = state

    suspend fun recordSuccess(scriptId: String, latencyMs: Long) {
        update(scriptId) { previous ->
            SourceHealthPolicy.recordSuccess(previous, latencyMs, System.currentTimeMillis())
        }
    }

    suspend fun recordFailure(scriptId: String) {
        update(scriptId) { previous ->
            SourceHealthPolicy.recordFailure(previous, System.currentTimeMillis())
        }
    }

    suspend fun remove(scriptId: String) {
        mutex.withLock {
            val next = state - scriptId
            state = next
            withContext(Dispatchers.IO) { store.save(next) }
        }
    }

    private suspend fun update(
        scriptId: String,
        transform: (SourceHealth?) -> SourceHealth,
    ) {
        mutex.withLock {
            val next = state + (scriptId to transform(state[scriptId]))
            state = next
            withContext(Dispatchers.IO) { store.save(next) }
        }
    }
}
