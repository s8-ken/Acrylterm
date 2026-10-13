package com.shibaken.androidterminal.ssh

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * mosh-server pids started by this app and not yet killed, per host profile id (persisted, so leftovers of a
 * dead app process are killed on the next SSH connect). [live] = pids currently owned by a running session.
 */
class MoshPids(context: Context) {
    private val prefs = context.getSharedPreferences("mosh_pids", Context.MODE_PRIVATE)

    @Synchronized fun add(hostId: String, pid: Int) {
        prefs.edit().putStringSet(hostId, (get(hostId) + pid).map { it.toString() }.toSet()).apply()
    }

    @Synchronized fun remove(hostId: String, pid: Int) {
        prefs.edit().putStringSet(hostId, (get(hostId) - pid).map { it.toString() }.toSet()).apply()
    }

    @Synchronized fun get(hostId: String): Set<Int> =
        prefs.getStringSet(hostId, emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()

    fun markLive(hostId: String, pid: Int) { live.add("$hostId:$pid") }
    fun markDead(hostId: String, pid: Int) { live.remove("$hostId:$pid") }
    fun isLive(hostId: String, pid: Int) = live.contains("$hostId:$pid")

    private companion object {
        val live: MutableSet<String> = ConcurrentHashMap.newKeySet()
    }
}
