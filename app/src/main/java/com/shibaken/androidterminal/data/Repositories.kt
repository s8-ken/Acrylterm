package com.shibaken.androidterminal.data

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

class HostRepository(context: Context) {
    private val store = JsonListStore(context, "hosts.json", HostProfile.serializer())
    val hosts: StateFlow<List<HostProfile>> = store.items

    fun get(id: String): HostProfile? = hosts.value.firstOrNull { it.id == id }

    fun save(profile: HostProfile) = store.update { list ->
        if (list.any { it.id == profile.id }) list.map { if (it.id == profile.id) profile else it } else list + profile
    }

    fun delete(id: String) = store.update { list -> list.filterNot { it.id == id } }

    fun markConnected(id: String) = store.update { list ->
        list.map { if (it.id == id) it.copy(lastConnectedAt = System.currentTimeMillis()) else it }
    }

    fun clearKeyReferences(keyId: String) = store.update { list ->
        list.map { if (it.keyId == keyId) it.copy(keyId = null) else it }
    }
}

class KnownHostsRepository(context: Context) {
    private val store = JsonListStore(context, "known_hosts.json", KnownHost.serializer())
    val hosts: StateFlow<List<KnownHost>> = store.items

    fun find(host: String, keyType: String): KnownHost? =
        hosts.value.firstOrNull { it.host == host && it.keyType == keyType }

    fun typesFor(host: String): List<String> = hosts.value.filter { it.host == host }.map { it.keyType }

    fun add(entry: KnownHost) = store.update { list ->
        list.filterNot { it.host == entry.host && it.keyType == entry.keyType } + entry
    }

    fun remove(host: String, keyType: String) = store.update { list ->
        list.filterNot { it.host == host && it.keyType == keyType }
    }
}
