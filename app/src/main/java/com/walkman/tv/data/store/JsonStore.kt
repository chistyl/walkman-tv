package com.walkman.tv.data.store

import android.util.AtomicFile
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Tiny file-backed JSON store (kotlinx.serialization). Read/write are synchronous; callers
 *  should invoke from an IO context. */
class JsonStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val default: T,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): T = try {
        if (file.exists()) json.decodeFromString(serializer, file.readText()) else default
    } catch (e: Exception) {
        default
    }

    fun save(value: T) {
        try {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(serializer, value))
        } catch (e: Exception) {
            // best-effort persistence
        }
    }

    /**
     * Persist a value atomically and report failures to transactional callers.
     * Existing [save] remains best-effort for legacy stores.
     */
    fun saveStrict(value: T) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        var stream: java.io.FileOutputStream? = null
        try {
            stream = atomic.startWrite()
            stream.write(json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            if (stream != null) runCatching { atomic.failWrite(stream) }
            throw error
        }
    }
}
