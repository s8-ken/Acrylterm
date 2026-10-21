package com.shibaken.androidterminal.herdr

import com.shibaken.androidterminal.R
import com.shibaken.androidterminal.App
import com.shibaken.androidterminal.data.AppJson
import com.shibaken.androidterminal.data.HostProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException

@Serializable
data class HerdrSessionInfo(
    val name: String,
    @SerialName("socket_path") val socketPath: String,
    val running: Boolean,
)

@Serializable
private data class HerdrSessionList(val sessions: List<HerdrSessionInfo>)

data class HerdrSessionPickerState(
    val loading: Boolean = false,
    val sessions: List<HerdrSessionInfo> = emptyList(),
    val error: String? = null,
    val switchingTo: String? = null,
)

/** Strict parsing: discovery errors must never turn into an implicit default-session selection. */
fun parseHerdrSessions(json: String): List<HerdrSessionInfo> {
    val sessions = AppJson.decodeFromString(HerdrSessionList.serializer(), json).sessions
    require(sessions.all { it.name.matches(Regex("[A-Za-z0-9._-]{1,64}")) && it.name !in listOf(".", "..") && it.socketPath.startsWith("/") && '\u0000' !in it.socketPath }) {
        App.str(R.string.core_herdr_sessions_bad_format)
    }
    require(sessions.map { it.name }.distinct().size == sessions.size) { App.str(R.string.core_herdr_sessions_duplicate) }
    return sessions
}

/** Configuration changes intentionally invalidate a previous successful picker choice. */
fun herdrSessionPreferenceKey(profile: HostProfile): String =
    java.security.MessageDigest.getInstance("SHA-256").digest(
        listOf(profile.id, profile.host, profile.port.toString(), profile.username, profile.herdrSession)
            .joinToString("\u0000").toByteArray(Charsets.UTF_8)
    ).joinToString("") { "%02x".format(it) }

/** Return only after both discovery and a successful ping; callers commit the switch afterwards. */
suspend fun resolveRunningHerdrSession(name: String, list: suspend () -> List<HerdrSessionInfo>,
    ping: suspend (HerdrSessionInfo) -> Unit): HerdrSessionInfo {
    try {
        val entry = list().firstOrNull { it.name == name && it.running }
            ?: error(App.str(R.string.core_herdr_session_not_running))
        ping(entry)
        return entry
    } catch (timeout: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw IOException(App.str(R.string.core_herdr_session_timeout), timeout)
    }
}
