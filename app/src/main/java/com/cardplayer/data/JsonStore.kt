package com.cardplayer.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Tiny persistent store: one JSON file, one in-memory StateFlow.
 * Writes are atomic (temp file + rename) and happen off the main thread.
 */
class JsonStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val default: T
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    /** False on a fresh install (no file yet), so callers can seed first-run defaults. */
    val existedAtLoad: Boolean = file.exists()

    private val _state = MutableStateFlow(load())
    val state: StateFlow<T> = _state

    val value: T get() = _state.value

    /** Re-reads the file: a backup restore has just replaced it on disk. */
    fun reload() {
        _state.value = load()
    }

    private fun load(): T = try {
        if (file.exists()) json.decodeFromString(serializer, file.readText()) else default
    } catch (e: Exception) {
        default
    }

    fun update(transform: (T) -> T) {
        _state.update(transform)
        val snapshot = _state.value
        scope.launch {
            writeLock.withLock {
                try {
                    file.parentFile?.mkdirs()
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    tmp.writeText(json.encodeToString(serializer, snapshot))
                    if (!tmp.renameTo(file)) {
                        file.writeText(json.encodeToString(serializer, snapshot))
                        tmp.delete()
                    }
                } catch (_: Exception) {
                }
            }
        }
    }
}
