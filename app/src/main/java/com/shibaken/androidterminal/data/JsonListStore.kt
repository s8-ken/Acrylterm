package com.shibaken.androidterminal.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    coerceInputValues = true
}

/** A tiny file-backed list (app-private storage), exposed as a StateFlow. */
class JsonListStore<T>(context: Context, fileName: String, serializer: KSerializer<T>) {
    private val file = File(context.filesDir, fileName)
    private val listSerializer = ListSerializer(serializer)
    private val lock = Any()
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<T>> = _items.asStateFlow()

    private fun load(): List<T> = try {
        if (file.exists()) AppJson.decodeFromString(listSerializer, file.readText()) else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    fun update(transform: (List<T>) -> List<T>) {
        synchronized(lock) {
            val next = transform(_items.value)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(AppJson.encodeToString(listSerializer, next))
            tmp.renameTo(file)
            _items.value = next
        }
    }
}
